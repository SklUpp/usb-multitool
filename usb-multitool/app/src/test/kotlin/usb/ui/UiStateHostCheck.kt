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
                imageUri = "content://test/win11.iso",
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
        require(
            UiReducer.reduce(
                ready,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        OperationState.Completed,
                        "backup",
                        resultMessage = "saved /a.bin",
                    ),
                ),
            ).state.statusMessage == "saved /a.bin",
        ) { "resultMessage must replace the bare Success label" }

        require(
            UiReducer.reduce(ready, UiIntent.StartReadScan).effects.single().kind ==
                UiEffect.Kind.ReadScan,
        ) { "read scan must submit real disk work" }
        require(
            UiReducer.reduce(ready, UiIntent.StartRescueBackup).effects.single().kind ==
                UiEffect.Kind.RescueBackup,
        ) { "rescue backup must submit real disk work" }
        require(
            UiReducer.reduce(ready, UiIntent.CaptureGptBackup).effects.single().kind ==
                UiEffect.Kind.CaptureGptBackup,
        ) { "GPT capture must submit real disk work" }

        val noBackup =
            UiReducer.reduce(ready, UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
        require(noBackup.state.pendingConfirmation == null)
        require(noBackup.state.statusMessage == "Choose a GPT backup file first")

        val picked = UiReducer.reduce(ready, UiIntent.SetBackupFile("content://backup/gpt.bin"))
        require(picked.state.backupUri == "content://backup/gpt.bin")
        require(picked.state.statusMessage!!.contains("gpt.bin"))
        val restorePending =
            UiReducer.reduce(
                picked.state,
                UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE),
            )
        require(restorePending.state.pendingConfirmation != null)
        require(
            UiReducer.reduce(restorePending.state, UiIntent.ConfirmDestructive)
                .effects.single().action == DestructiveAction.GPT_RESTORE,
        )
        require(DestructiveAction.entries.none { it.name.contains("FAKE") }) {
            "fake-capacity action must stay removed"
        }

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

        val efi = ready.copy(selectedPartitionId = "2")
        listOf(
            UiIntent.CreateFolder("reports"),
            UiIntent.DeleteEntry("win11.iso"),
            UiIntent.UploadFile("content://test/new.iso"),
        ).forEach { intent ->
            val blocked = UiReducer.reduce(efi, intent)
            require(blocked.effects.isEmpty()) { "$intent wrote to a read-only partition" }
            require(blocked.state.statusMessage?.contains("read-only") == true) {
                "no explicit read-only status for $intent: ${blocked.state.statusMessage}"
            }
        }
        val efiEnabled = UiReducer.reduce(efi, UiIntent.SetVtoyEfiWritable(true)).state
        require(efiEnabled.vtoyEfiWriteEnabled)
        require(
            UiReducer.reduce(efiEnabled, UiIntent.UploadFile("content://test/new.iso"))
                .effects.single().kind == UiEffect.Kind.UploadFile,
        ) { "enabled VTOYEFI writes must be allowed" }
        require(
            UiReducer.reduce(efi, UiIntent.DownloadFile("win11.iso", "content://test/out"))
                .effects.single().kind == UiEffect.Kind.DownloadFile,
        ) { "download is a read path and must stay allowed" }

        val running = ready.copy(
            presence = DevicePresence.Busy,
            operation = OperationProgress(OperationState.Running, "raw write", bytesTotal = 100),
        )
        val switched = UiReducer.reduce(running, UiIntent.SelectPartition("2"))
        require(switched.effects.isEmpty()) { "partition switch during a write must not list" }
        require(switched.state.selectedPartitionId == "1") { "partition switched mid-write" }
        require(switched.state.statusMessage == "Device busy") {
            "unexpected status: ${switched.state.statusMessage}"
        }

        // Theme toggle: null -> true -> false -> null
        val autoTheme = AppUiState()
        val darkTheme = UiReducer.reduce(autoTheme, UiIntent.ToggleTheme).state
        require(darkTheme.darkTheme == true) { "expected dark theme true" }
        val lightTheme = UiReducer.reduce(darkTheme, UiIntent.ToggleTheme).state
        require(lightTheme.darkTheme == false) { "expected light theme false" }
        val backToAuto = UiReducer.reduce(lightTheme, UiIntent.ToggleTheme).state
        require(backToAuto.darkTheme == null) { "expected auto theme null" }

        // RunSelfTest produces effect and updates status
        val selfTest = UiReducer.reduce(ready, UiIntent.RunSelfTest)
        require(selfTest.effects.single().kind == UiEffect.Kind.RunSelfTest)
        require(selfTest.state.statusMessage?.contains("self-test") == true)
        val blockedSelfTest = UiReducer.reduce(running, UiIntent.RunSelfTest)
        require(blockedSelfTest.effects.isEmpty()) { "self-test while running must be blocked" }
        require(blockedSelfTest.state.statusMessage?.contains("progress") == true)

        println("UiStateHostCheck PASS")
    }
}
