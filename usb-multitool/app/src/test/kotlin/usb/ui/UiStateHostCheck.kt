package usb.ui

/**
 * Host-runnable assert self-check (no Android/Compose).
 * Compile with kotlinc against UiModels.kt + UiReducer.kt.
 */
object UiStateHostCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val device =
            DeviceIdentity(
                label = "SanDisk Ultra USB 3.0 (USB 1234:5678)",
                capacityBytes = 31_457_280_000uL,
            )

        val wipe = DestructiveConfirmation.request(device, DestructiveAction.WIPE)
        require(wipe.message.contains(device.identity)) { "identity missing: ${wipe.message}" }
        require(wipe.message.contains(device.capacityLabel)) {
            "capacity missing: ${wipe.message}"
        }

        DestructiveAction.entries.forEach { action ->
            val msg = DestructiveConfirmation.request(device, action).message
            require(msg.contains(device.identity)) { "$action missing identity" }
            require(msg.contains(device.capacityLabel)) { "$action missing capacity" }
        }

        val absent =
            UiReducer.reduce(
                AppUiState(presence = DevicePresence.Absent),
                UiIntent.RequestDestructive(DestructiveAction.WIPE),
            )
        require(absent.state.pendingConfirmation == null)
        require(absent.state.statusMessage == "No USB mass-storage device")

        val ready =
            AppUiState(
                presence = DevicePresence.Ready,
                device = device,
                partitions =
                    listOf(
                        PartitionTab("1", "VENTOY", readOnly = false),
                        PartitionTab("2", "VTOYEFI", readOnly = true, isVtoyEfi = true),
                    ),
                selectedPartitionId = "1",
                files =
                    listOf(
                        FileEntry("win11.iso", isDirectory = false, sizeBytes = 5_000_000_000uL),
                    ),
            )
        require(ready.actionsEnabled)

        val permission =
            ready.copy(presence = DevicePresence.PermissionRequired)
        require(!permission.actionsEnabled)

        val busy =
            ready.copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        label = "raw write",
                        bytesDone = 10,
                        bytesTotal = 100,
                    ),
            )
        require(busy.isRunning)
        require(!busy.actionsEnabled)

        val runningBlocked =
            UiReducer.reduce(busy, UiIntent.RequestDestructive(DestructiveAction.WIPE))
        require(runningBlocked.state.pendingConfirmation == null)

        fun statusOf(state: OperationState, err: String? = null): String? =
            UiReducer.reduce(
                ready,
                UiIntent.OperationUpdated(OperationProgress(state, "op", errorMessage = err)),
            ).state.statusMessage

        require(statusOf(OperationState.Completed) == "Success")
        require(statusOf(OperationState.Cancelled) == "Cancelled")
        require(statusOf(OperationState.Failed, "boom") == "boom")
        require(statusOf(OperationState.Partial, "kept map") == "kept map")

        val pending =
            UiReducer.reduce(ready, UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE))
        require(pending.state.pendingConfirmation != null)
        val disconnected =
            UiReducer.reduce(
                pending.state,
                UiIntent.DeviceChanged(presence = DevicePresence.Disconnected),
            )
        require(disconnected.state.pendingConfirmation == null)
        require(disconnected.state.statusMessage == "Device disconnected")

        val confirmed = UiReducer.reduce(pending.state, UiIntent.ConfirmDestructive)
        require(confirmed.effects.single().kind == UiEffect.Kind.SubmitDestructive)
        require(confirmed.effects.single().action == DestructiveAction.RAW_WRITE)

        val link =
            UiReducer.reduce(
                ready,
                UiIntent.IsoActionRequested("win11.iso", IsoAction.LinkXml),
            )
        require(link.effects.single().kind == UiEffect.Kind.LinkXml)

        val send =
            UiReducer.reduce(
                ready,
                UiIntent.IsoActionRequested("win11.iso", IsoAction.SendToRawWriter),
            )
        require(send.effects.single().kind == UiEffect.Kind.SendToRawWriter)
        require(send.state.tab == ShellTab.Partitions)

        println("UiStateHostCheck PASS")
    }
}
