package usb.ui

import usb.fs.FilesystemId
import usb.fs.FormatCatalog
import usb.partition.TableScheme

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
        require(wipe.identity == device) { "identity missing: ${wipe.identity}" }
        require(wipe.identity.capacityLabel.isNotEmpty()) { "capacity missing" }

        DestructiveAction.entries.forEach { action ->
            val req = DestructiveConfirmation.request(device, action)
            require(req.action == action) { "$action missing action" }
            require(req.identity == device) { "$action missing identity" }
        }

        val absent =
            UiReducer.reduce(
                AppUiState(presence = DevicePresence.Absent),
                UiIntent.RequestDestructive(DestructiveAction.WIPE),
            )
        require(absent.state.pendingConfirmation == null)
        require(absent.state.statusMessage == UiMessage.Key(UiMessageKey.NoDevice))

        val ready =
            AppUiState(
                presence = DevicePresence.Ready,
                device = device,
                partitions =
                    listOf(
                        PartitionTab("1", "VENTOY", readOnly = false, startLba = 0uL, sectorCount = 2048uL),
                        PartitionTab(
                            "2",
                            "VTOYEFI",
                            readOnly = true,
                            isEfiType = true,
                            startLba = 2048uL,
                            sectorCount = 4096uL,
                        ),
                    ),
                selectedPartitionId = "1",
                files =
                    listOf(
                        FileEntry("win11.iso", isDirectory = false, sizeBytes = 5_000_000_000uL),
                    ),
                imageUri = "content://test/win11.iso",
            )
        require(ready.actionsEnabled)

        val listFailed = UiReducer.reduce(ready, UiIntent.FilesListFailed("/", "Read failed"))
        require(listFailed.state.filesError == "Read failed")
        val listRecovered =
            UiReducer.reduce(listFailed.state, UiIntent.FilesListed("/", emptyList()))
        require(listRecovered.state.filesError == null) { "successful listing must clear filesError" }
        require(
            UiReducer.reduce(
                listFailed.state,
                UiIntent.DeviceChanged(DevicePresence.Ready, device, partitions = ready.partitions),
            ).state.filesError == null,
        ) { "device change must clear filesError" }

        val maxMib = PartitionRangeMath.availableMib(16_777_219UL * 2048UL, 2048UL)
        require(maxMib == 16_777_219UL)
        require(PartitionRangeMath.snapToMib(16_777_220f, maxMib) == maxMib) {
            "Float rounding must not cross the exact MiB bound"
        }
        require(PartitionRangeMath.snapToMib(-5f, maxMib) == 0UL)
        require(PartitionRangeMath.snapToMib(Float.NaN, maxMib) == 0UL)
        require(
            PartitionRangeMath.sectorsFor(
                firstAligned = 0UL,
                regionEnd = maxMib * 2048UL,
                startMib = 0UL,
                endMib = maxMib,
                sectorsPerMib = 2048UL,
            )?.second == maxMib * 2048UL,
        )
        require(
            PartitionRangeMath.sectorsFor(
                firstAligned = 0UL,
                regionEnd = 2048UL,
                startMib = 0UL,
                endMib = 2UL,
                sectorsPerMib = 2048UL,
            ) == null,
        )

        val withFree = ready.copy(freeRegions = listOf(FreeRegionUi(2048UL, 4096UL), FreeRegionUi(16384UL, 4096UL)))
        val create = UiReducer.reduce(withFree, UiIntent.RequestCreatePartition(16384UL, 1024UL)).state.pendingConfirmation
        require(create?.createStartLba == 16384UL && create.createSectorCount == 1024UL)
        require(UiReducer.reduce(withFree, UiIntent.RequestCreatePartition(16385UL, 1024UL)).state.pendingConfirmation == null)
        require(UiReducer.reduce(withFree, UiIntent.RequestCreatePartition(16384UL, 4097UL)).state.pendingConfirmation == null)
        require(create != null) { "create confirmation missing" }
        require(create.createName == null && create.createFormatId == null && create.volumeLabel == "")

        val gptFree = withFree.copy(partitionScheme = TableScheme.GPT)
        val named =
            requireNotNull(
                UiReducer.reduce(
                    gptFree,
                    UiIntent.RequestCreatePartition(
                        16384UL,
                        1024UL,
                        name = "  Data  ",
                        formatId = FilesystemId.EXT,
                        volumeLabel = "MEDIA",
                    ),
                ).state.pendingConfirmation,
            ) { "trimmed GPT name must be pinned in the confirmation" }
        require(named.createName == "Data")
        require(named.createFormatId == FilesystemId.EXT)
        require(named.volumeLabel == "MEDIA")
        require(
            UiReducer.reduce(
                withFree,
                UiIntent.RequestCreatePartition(16384UL, 1024UL, name = "Data"),
            ).state.pendingConfirmation == null,
        ) { "a name without a GPT scheme must be refused, not dropped" }
        require(
            UiReducer.reduce(
                gptFree,
                UiIntent.RequestCreatePartition(16384UL, 1024UL, name = "x".repeat(37)),
            ).state.pendingConfirmation == null,
        )
        require(
            UiReducer.reduce(
                gptFree,
                UiIntent.RequestCreatePartition(16384UL, 1024UL, formatId = FilesystemId.EXT, volumeLabel = "x".repeat(16)),
            ).state.pendingConfirmation == null,
        ) { "EXT labels are limited to 15 UTF-8 bytes" }

        val gptFormatted =
            ready.copy(
                partitionScheme = TableScheme.GPT,
                partitions = ready.partitions.map {
                    if (it.id == "1") it.copy(filesystemId = FilesystemId.NTFS) else it
                },
            )
        val rename = requireNotNull(
            UiReducer.reduce(gptFormatted, UiIntent.RequestRenamePartition(" Media "))
                .state.pendingConfirmation,
        )
        require(rename.action == DestructiveAction.RENAME_PARTITION && rename.partitionName == "Media")
        require(rename.partitionStartLba == 0UL && rename.partitionSectorCount == 2048UL)
        require(UiReducer.reduce(gptFormatted, UiIntent.RequestRenamePartition("VENTOY")).state.pendingConfirmation == null)
        require(
            UiReducer.reduce(
                gptFormatted.copy(selectedPartitionId = "2"),
                UiIntent.RequestRenamePartition("NEW"),
            ).state.pendingConfirmation == null,
        ) { "protected VTOYEFI name must not be renamed" }
        require(UiReducer.reduce(ready, UiIntent.RequestRenamePartition("Media")).state.pendingConfirmation == null) {
            "unknown scheme must not accept a GPT name"
        }
        val label =
            requireNotNull(
                UiReducer.reduce(gptFormatted, UiIntent.RequestRenameVolumeLabel(" MEDIA "))
                    .state.pendingConfirmation,
            )
        require(label.action == DestructiveAction.RENAME_VOLUME_LABEL && label.volumeLabel == "MEDIA")
        require(
            UiReducer.reduce(
                gptFormatted.copy(
                    partitions = gptFormatted.partitions.map {
                        if (it.id == "1") it.copy(filesystemId = FilesystemId.EXT) else it
                    },
                ),
                UiIntent.RequestRenameVolumeLabel("MEDIA"),
            ).state.pendingConfirmation == null,
        ) { "EXT label rename has no native setter" }
        require(
            UiReducer.reduce(
                gptFormatted.copy(
                    partitions = gptFormatted.partitions.map {
                        if (it.id == "1") it.copy(filesystemId = FilesystemId.FAT) else it
                    },
                ),
                UiIntent.RequestRenameVolumeLabel("bad/name"),
            ).state.pendingConfirmation == null,
        ) { "FAT-invalid characters must be refused before writing" }

        val initGpt = requireNotNull(
            UiReducer.reduce(
                gptFormatted,
                UiIntent.RequestInitializePartitionTable(PartitionScheme.Mbr),
            ).state.pendingConfirmation,
        )
        require(initGpt.initialScheme == PartitionScheme.Gpt && initGpt.targetScheme == PartitionScheme.Mbr)
        require(initGpt.initialPartitions.size == gptFormatted.partitions.size)
        require(
            UiReducer.reduce(
                gptFormatted,
                UiIntent.RequestInitializePartitionTable(PartitionScheme.Gpt),
            ).state.pendingConfirmation == null,
        ) { "same scheme must be refused" }
        require(
            UiReducer.reduce(
                gptFormatted.copy(partitionTableAmbiguous = true),
                UiIntent.RequestInitializePartitionTable(PartitionScheme.Mbr),
            ).state.pendingConfirmation == null,
        ) { "ambiguous layout must be refused fail-closed" }

        val permission =
            ready.copy(presence = DevicePresence.PermissionRequired)
        require(!permission.actionsEnabled)

        val busy =
            ready.copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.Destructive(DestructiveAction.RAW_WRITE),
                        bytesDone = 10,
                        bytesTotal = 100,
                    ),
            )
        require(busy.isRunning)
        require(!busy.actionsEnabled)

        val runningBlocked =
            UiReducer.reduce(busy, UiIntent.RequestDestructive(DestructiveAction.WIPE))
        require(runningBlocked.state.pendingConfirmation == null)

        fun statusOf(state: OperationState, err: String? = null): UiMessage? =
            UiReducer.reduce(
                ready,
                UiIntent.OperationUpdated(OperationProgress(state, OperationTitle.Upload, errorMessage = err)),
            ).state.statusMessage

        require(statusOf(OperationState.Completed) == UiMessage.Key(UiMessageKey.Success))
        require(statusOf(OperationState.Cancelled) == UiMessage.Key(UiMessageKey.CancelledStatus))
        require(statusOf(OperationState.Failed, "boom") == UiMessage.Raw("boom"))
        require(statusOf(OperationState.Partial, "kept map") == UiMessage.Raw("kept map"))
        require(
            UiReducer.reduce(
                ready,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        OperationState.Completed,
                        OperationTitle.RescueBackup,
                        resultMessage = "saved /a.bin",
                    ),
                ),
            ).state.statusMessage == UiMessage.Raw("saved /a.bin"),
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
        require(noBackup.state.statusMessage == UiMessage.Key(UiMessageKey.ChooseGptBackup))

        val picked = UiReducer.reduce(ready, UiIntent.SetBackupFile("content://backup/gpt.bin"))
        require(picked.state.backupUri == "content://backup/gpt.bin")
        require(picked.state.statusMessage == UiMessage.Key(UiMessageKey.BackupReady, listOf("gpt.bin")))
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
        require(disconnected.state.statusMessage == UiMessage.Key(UiMessageKey.DeviceDisconnected))

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
        require(linkReq.state.pendingConfirmation!!.action == DestructiveAction.VENTOY_LINK_XML)
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
            require(
                blocked.state.statusMessage ==
                    UiMessage.Key(UiMessageKey.ReadOnlyPartition, listOf("VTOYEFI")),
            ) {
                "no explicit read-only status for $intent: ${blocked.state.statusMessage}"
            }
        }
        val efiEnabled = UiReducer.reduce(efi, UiIntent.SetVtoyEfiWritable("2", true)).state
        require(efiEnabled.vtoyEfiWriteEnabled)
        val sameAttach = UiReducer.reduce(
            efiEnabled,
            UiIntent.DeviceChanged(
                DevicePresence.Ready,
                efiEnabled.device,
                partitions = efiEnabled.partitions,
            ),
        ).state
        require(sameAttach.vtoyEfiWriteEnabled) {
            "partition refresh on the same attach must keep the EFI override"
        }
        val reattached = UiReducer.reduce(
            efiEnabled,
            UiIntent.DeviceChanged(
                DevicePresence.Ready,
                efiEnabled.device?.copy(
                    attachGeneration = (efiEnabled.device?.attachGeneration ?: 0L) + 1L,
                ),
            ),
        ).state
        require(!reattached.vtoyEfiWriteEnabled) {
            "EFI write override must reset when the device is re-attached"
        }
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
            operation = OperationProgress(OperationState.Running, OperationTitle.Destructive(DestructiveAction.RAW_WRITE), bytesTotal = 100),
        )
        val switched = UiReducer.reduce(running, UiIntent.SelectPartition("2"))
        require(switched.effects.isEmpty()) { "partition switch during a write must not list" }
        require(switched.state.selectedPartitionId == "1") { "partition switched mid-write" }
        require(switched.state.statusMessage == UiMessage.Key(UiMessageKey.DeviceBusy)) {
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
        require(selfTest.state.statusMessage == UiMessage.Key(UiMessageKey.SelfTestStarting))
        val blockedSelfTest = UiReducer.reduce(running, UiIntent.RunSelfTest)
        require(blockedSelfTest.effects.isEmpty()) { "self-test while running must be blocked" }
        require(blockedSelfTest.state.statusMessage == UiMessage.Key(UiMessageKey.OperationInProgress))

        val noPart = ready.copy(selectedPartitionId = null)
        val renameBinding = PartitionBinding(
            device = ready.device!!,
            partitionId = "1",
            startLba = 0uL,
            sectorCount = 2048uL,
        )
        require(
            UiReducer.reduce(
                ready,
                UiIntent.RenameEntry("win11.iso", "a/b", renameBinding),
            ).effects.isEmpty(),
        )
        val renameEffect =
            UiReducer.reduce(
                ready,
                UiIntent.RenameEntry("win11.iso", "win.iso", renameBinding),
            ).effects.single()
        require(renameEffect.kind == UiEffect.Kind.RenameEntry)
        require(renameEffect.newName == "win.iso")
        require(renameEffect.binding == renameBinding)
        val staleDevice = ready.copy(device = ready.device!!.copy(attachGeneration = 1L))
        val staleRename =
            UiReducer.reduce(
                staleDevice,
                UiIntent.RenameEntry("win11.iso", "win.iso", renameBinding),
            )
        require(staleRename.effects.isEmpty())
        require(staleRename.state.statusMessage == UiMessage.Key(UiMessageKey.TargetChanged))
        val roleRequest =
            UiReducer.reduce(ready, UiIntent.RequestSetPartitionRole(PartitionRole.Efi))
        require(roleRequest.state.pendingConfirmation?.partitionRole == PartitionRole.Efi)
        require(roleRequest.state.pendingConfirmation?.action == DestructiveAction.SET_PARTITION_ROLE)
        require(roleRequest.state.pendingConfirmation?.partitionStartLba == 0uL)
        val bootRequest =
            UiReducer.reduce(ready, UiIntent.RequestSetPartitionBootable(true))
        require(bootRequest.state.pendingConfirmation?.partitionBootable == true)

        val damaged = ready.copy(partitionTableDamaged = true)
        val damagedDelete =
            UiReducer.reduce(damaged, UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
        require(damagedDelete.state.pendingConfirmation == null)
        require(damagedDelete.state.statusMessage == UiMessage.Key(UiMessageKey.GptPrimaryDamaged))
        val damagedFormat =
            UiReducer.reduce(damaged, UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
        require(damagedFormat.state.pendingConfirmation?.action == DestructiveAction.FORMAT_PARTITION)

        val deleteBlocked = UiReducer.reduce(noPart, UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
        require(deleteBlocked.state.pendingConfirmation == null)
        require(deleteBlocked.state.statusMessage == UiMessage.Key(UiMessageKey.SelectPartitionFirst))
        val formatBlocked = UiReducer.reduce(noPart, UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
        require(formatBlocked.state.pendingConfirmation == null)
        require(formatBlocked.state.statusMessage == UiMessage.Key(UiMessageKey.SelectPartitionFirst))

        require(ready.vtoyBypassChecks == null) { "default must preserve existing ventoy.json" }
        val bypassToggle = UiReducer.reduce(ready, UiIntent.SetVtoyBypassChecks(true)).state
        require(bypassToggle.vtoyBypassChecks == true)
        val bypassOff = UiReducer.reduce(bypassToggle, UiIntent.SetVtoyBypassChecks(false)).state
        require(bypassOff.vtoyBypassChecks == false)
        val bypassKeep = UiReducer.reduce(bypassOff, UiIntent.SetVtoyBypassChecks(null)).state
        require(bypassKeep.vtoyBypassChecks == null)
        val localToggle = UiReducer.reduce(bypassKeep, UiIntent.SetVtoyLocalAccount(true)).state
        require(localToggle.vtoyLocalAccount == true)
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
        val stagedBusy = staged.copy(operation = OperationProgress(OperationState.Running, OperationTitle.Destructive(DestructiveAction.RAW_WRITE), bytesTotal = 1))
        require(UiReducer.reduce(stagedBusy, UiIntent.ClearStagedRawImage()).state.stagedRawImagePath == "/staging/old.img")
        require(UiReducer.reduce(stagedBusy, UiIntent.SetStagedRawImage("/staging/new.img", "new")).state.stagedRawImagePath == "/staging/old.img")
        val stagedConfirmed = staged.copy(pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.RAW_WRITE))
        require(UiReducer.reduce(stagedConfirmed, UiIntent.ClearStagedRawImage()).state.stagedRawImagePath == "/staging/old.img")
        require(cancelEffect.effects.single().kind == UiEffect.Kind.CancelOperation)

        val corrupt = ready.copy(tableValid = false)
        val createBlocked = UiReducer.reduce(corrupt, UiIntent.RequestDestructive(DestructiveAction.CREATE_PARTITION))
        require(createBlocked.state.pendingConfirmation == null)
        require(createBlocked.state.statusMessage == UiMessage.Key(UiMessageKey.CorruptTableCreate))

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
        require(
            FormatCatalog.supportedFormatIds() ==
                listOf(FilesystemId.FAT, FilesystemId.EXFAT, FilesystemId.EXT, FilesystemId.NTFS),
        )
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
        require(fmtReq.state.pendingConfirmation!!.formatId == FilesystemId.EXFAT)
        val fmtGo = UiReducer.reduce(fmtReq.state, UiIntent.ConfirmDestructive)
        require(fmtGo.effects.single().formatId == FilesystemId.EXFAT)
        require(fmtGo.effects.single().confirmation?.partitionId == "1")
        require(fmtGo.effects.single().confirmation?.partitionStartLba == null) { "missing geometry must remain explicit" }
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
                operation = OperationProgress(state = OperationState.Running, title = OperationTitle.Destructive(DestructiveAction.RAW_WRITE)),
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
            require(
                ok.state.statusMessage ==
                    UiMessage.Key(UiMessageKey.ImageReady, listOf("content://new.iso")),
            )
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
