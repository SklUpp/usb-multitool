package usb.ui

import usb.fs.FilesystemId
import usb.fs.FormatCatalog

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

        val linkReq =
            UiReducer.reduce(
                ready,
                UiIntent.IsoActionRequested("win11.iso", IsoAction.LinkXml),
            )
        require(linkReq.effects.isEmpty()) { "LinkXml must confirm before write" }
        require(linkReq.state.pendingConfirmation != null)
        require(linkReq.state.pendingConfirmation!!.action.contains("link Ventoy"))
        require(!linkReq.state.pendingConfirmation!!.linkOverwrite)
        val link =
            UiReducer.reduce(linkReq.state, UiIntent.ConfirmDestructive)
        require(link.effects.single().kind == UiEffect.Kind.LinkXml)
        require(link.effects.single().isoName == "win11.iso")
        require(!link.effects.single().linkOverwrite)

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

        val noPart = ready.copy(selectedPartitionId = null)
        val deleteBlocked = UiReducer.reduce(noPart, UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
        require(deleteBlocked.state.pendingConfirmation == null)
        require(deleteBlocked.state.statusMessage == "Select a partition first")
        val formatBlocked = UiReducer.reduce(noPart, UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
        require(formatBlocked.state.pendingConfirmation == null)
        require(formatBlocked.state.statusMessage == "Select a partition first")

        val tpmToggle = UiReducer.reduce(ready, UiIntent.SetVtoyBypassTpm(false)).state
        require(!tpmToggle.vtoyBypassTpm)
        val ramToggle = UiReducer.reduce(ready, UiIntent.SetVtoyBypassRam(true)).state
        require(ramToggle.vtoyBypassRam)
        val localToggle = UiReducer.reduce(ramToggle, UiIntent.SetVtoyLocalAccount(true)).state
        require(localToggle.vtoyLocalAccount)
        val candidate = UiReducer.reduce(ready, UiIntent.LinkOverwriteAvailable("win.iso")).state
        require(candidate.linkOverwriteCandidate == "win.iso")
        require(candidate.linkOverwriteCandidateIdentity == device.identity)
        val sameDiskOverwrite = UiReducer.reduce(candidate, UiIntent.RequestLinkOverwrite("win.iso")).state
        require(sameDiskOverwrite.pendingConfirmation?.linkOverwrite == true)
        val detached = UiReducer.reduce(candidate, UiIntent.DeviceChanged(DevicePresence.Disconnected)).state
        require(detached.linkOverwriteCandidate == null)
        require(detached.linkOverwriteCandidateIdentity == null)
        require(UiReducer.reduce(detached, UiIntent.RequestLinkOverwrite("win.iso")).state.pendingConfirmation == null)
        val otherDevice = device.copy(label = "Other USB", serialNumber = "other")
        val deviceSwitched = UiReducer.reduce(candidate, UiIntent.DeviceChanged(DevicePresence.Ready, otherDevice)).state
        require(deviceSwitched.linkOverwriteCandidate == null)
        require(UiReducer.reduce(deviceSwitched, UiIntent.RequestLinkOverwrite("win.iso")).state.pendingConfirmation == null)

        val cancelEffect = UiReducer.reduce(ready, UiIntent.CancelOperation)
        val staged = ready.copy(stagedRawImagePath = "/staging/old.img", stagedRawImageLabel = "old")
        val stagedBusy = staged.copy(operation = OperationProgress(OperationState.Running, "raw write", bytesTotal = 1))
        require(UiReducer.reduce(stagedBusy, UiIntent.ClearStagedRawImage()).state.stagedRawImagePath == "/staging/old.img")
        require(UiReducer.reduce(stagedBusy, UiIntent.SetStagedRawImage("/staging/new.img", "new")).state.stagedRawImagePath == "/staging/old.img")
        val stagedConfirmed = staged.copy(pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.RAW_WRITE))
        require(UiReducer.reduce(stagedConfirmed, UiIntent.ClearStagedRawImage()).state.stagedRawImagePath == "/staging/old.img")
        require(cancelEffect.effects.single().kind == UiEffect.Kind.CancelOperation)

        val corrupt = ready.copy(tableValid = false)
        val createBlocked = UiReducer.reduce(corrupt, UiIntent.RequestDestructive(DestructiveAction.CREATE_PARTITION))
        require(createBlocked.state.pendingConfirmation == null)
        require(createBlocked.state.statusMessage?.contains("Cannot create partition on corrupt table") == true)

        val busyReset = UiReducer.reduce(
            ready.copy(pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.WIPE)),
            UiIntent.DeviceChanged(presence = DevicePresence.Busy),
        )
        require(busyReset.state.pendingConfirmation == null) { "DeviceChanged(Busy) must clear pendingConfirmation" }

        val confirmCorrupt = UiReducer.reduce(
            ready.copy(
                tableValid = false,
                pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.CREATE_PARTITION),
            ),
            UiIntent.ConfirmDestructive,
        )
        require(confirmCorrupt.state.pendingConfirmation == null)
        require(confirmCorrupt.effects.isEmpty()) { "ConfirmDestructive on corrupt table must not emit SubmitDestructive" }

        val confirmNoSelection = UiReducer.reduce(
            ready.copy(
                selectedPartitionId = null,
                pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.DELETE_PARTITION, "1"),
            ),
            UiIntent.ConfirmDestructive,
        )
        require(confirmNoSelection.state.pendingConfirmation == null)
        require(confirmNoSelection.effects.isEmpty()) { "ConfirmDestructive without selection must not emit SubmitDestructive" }

        println("UiStateHostCheck PASS")

        // Format picker host invariants (FormatCatalog — no Formatter/JNI).
        require(FormatCatalog.supportedFormatIds() == listOf(FilesystemId.FAT, FilesystemId.EXFAT, FilesystemId.EXT))
        val fmtReady =
            AppUiState(
                presence = DevicePresence.Ready,
                device = device,
                partitions = listOf(PartitionTab("1", "DATA", readOnly = false)),
                selectedPartitionId = "1",
                selectedFormatId = FilesystemId.EXFAT,
            )
        val fmtReq =
            UiReducer.reduce(fmtReady, UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
        require(fmtReq.state.pendingConfirmation != null)
        require(fmtReq.state.pendingConfirmation!!.formatId == FilesystemId.EXFAT)
        require(fmtReq.state.pendingConfirmation!!.message.contains("exFAT"))
        val fmtGo = UiReducer.reduce(fmtReq.state, UiIntent.ConfirmDestructive)
        require(fmtGo.effects.single().formatId == FilesystemId.EXFAT)
        val blockedSel =
            UiReducer.reduce(fmtReq.state, UiIntent.SelectFormatFilesystem(FilesystemId.EXT))
        require(blockedSel.state.selectedFormatId == FilesystemId.EXFAT)
        require(blockedSel.state.pendingConfirmation != null)

        // Choose Image / SetImage must not clear staged source while busy.
        run {
            val device = DeviceIdentity(
                label = "SanDisk Ultra USB 3.0 (USB 1234:5678)",
                capacityBytes = 31_457_280uL,
                serialNumber = "SN-TEST",
            )
            val busy = AppUiState(
                presence = DevicePresence.Ready,
                device = device,
                stagedRawImagePath = "/data/staging/raw-1-img.bin",
                stagedRawImageLabel = "img.bin",
                operation = OperationProgress(state = OperationState.Running, label = "raw image write"),
            )
            require(!busy.actionsEnabled)
            val blocked = UiReducer.reduce(busy, UiIntent.SetImage("content://new.iso"))
            require(blocked.state.stagedRawImagePath == "/data/staging/raw-1-img.bin")
            require(blocked.state.imageUri == null)
            require(blocked.effects.isEmpty())

            val idle = busy.copy(operation = null)
            require(idle.actionsEnabled)
            val ok = UiReducer.reduce(idle, UiIntent.SetImage("content://new.iso"))
            require(ok.state.imageUri == "content://new.iso")
            require(ok.state.stagedRawImagePath == null)

            val absent = AppUiState(
                presence = DevicePresence.Absent,
                stagedRawImagePath = "/data/staging/raw-1-img.bin",
                stagedRawImageLabel = "img.bin",
            )
            val absentSet = UiReducer.reduce(absent, UiIntent.SetImage("content://new.iso"))
            require(absentSet.state.stagedRawImagePath == "/data/staging/raw-1-img.bin")
            require(absentSet.state.imageUri == null)
            val cleared = UiReducer.reduce(absent, UiIntent.ClearStagedRawImage(deleteFile = true))
            require(cleared.state.stagedRawImagePath == null)
            require(cleared.state.stagedRawImageLabel == null)
        }

    }
}
