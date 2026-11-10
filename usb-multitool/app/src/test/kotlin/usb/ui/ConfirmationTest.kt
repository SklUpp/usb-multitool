package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationTest {
    private val device =
        DeviceIdentity(
            label = "SanDisk Ultra USB 3.0 (USB\u00A01234:5678)",
            capacityBytes = 31_457_280_000uL,
        )

    @Test
    fun destructiveActionIncludesIdentityAndCapacity() {
        val request = DestructiveConfirmation.request(device, DestructiveAction.WIPE)
        assertEquals(DestructiveAction.WIPE, request.action)
        assertEquals(device, request.identity)
        assertTrue(request.identity.identity.contains(device.identity))
        assertTrue(request.identity.capacityLabel.isNotEmpty())
    }

    @Test
    fun everyDestructiveActionEmbedsDeviceFacts() {
        DestructiveAction.entries.forEach { action ->
            val request = DestructiveConfirmation.request(device, action)
            assertEquals(action, request.action)
            assertEquals(device, request.identity)
        }
    }
}

class UiStateMachineTest {
    private val device =
        DeviceIdentity(label = "Kingston DataTraveler", capacityBytes = 15_728_640_000uL)

    private val partitions =
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
        )

    private fun ready(): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = partitions,
            selectedPartitionId = "1",
            files =
                listOf(
                    FileEntry("ubuntu.iso", isDirectory = false, sizeBytes = 2_000_000_000uL),
                    FileEntry("docs", isDirectory = true, sizeBytes = 0uL),
                ),
            vtoyBypassChecks = true,
        )

    @Test
    fun absentDeviceBlocksDestructiveConfirm() {
        val result =
            UiReducer.reduce(
                AppUiState(presence = DevicePresence.Absent),
                UiIntent.RequestDestructive(DestructiveAction.WIPE),
            )
        assertNull(result.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.NoDevice), result.state.statusMessage)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun permissionRequiredBlocksActions() {
        val state =
            AppUiState(
                presence = DevicePresence.PermissionRequired,
                device = device,
            )
        assertFalse(state.actionsEnabled)
        val result =
            UiReducer.reduce(state, UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE))
        assertNull(result.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.PermissionRequired), result.state.statusMessage)
    }

    @Test
    fun busyAndRunningDisableActions() {
        val running =
            ready().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        label = "raw write",
                        bytesDone = 1000,
                        bytesTotal = 5000,
                        bytesPerSecond = 100,
                    ),
            )
        assertFalse(running.actionsEnabled)
        assertTrue(running.isRunning)

        val blocked =
            UiReducer.reduce(running, UiIntent.RequestDestructive(DestructiveAction.WIPE))
        assertNull(blocked.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.WaitForOperation), blocked.state.statusMessage)
    }

    @Test
    fun successCancellationFailureAndPartialSurfaceTruthfully() {
        val base = ready()
        val completed =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(OperationState.Completed, "wipe"),
                ),
            )
        assertEquals(UiMessage.Key(UiMessageKey.Success), completed.state.statusMessage)

        val cancelled =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(OperationState.Cancelled, "wipe"),
                ),
            )
        assertEquals(UiMessage.Key(UiMessageKey.CancelledStatus), cancelled.state.statusMessage)

        val failed =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        OperationState.Failed,
                        "wipe",
                        errorMessage = "I/O error",
                    ),
                ),
            )
        assertEquals(UiMessage.Raw("I/O error"), failed.state.statusMessage)

        val partial =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        OperationState.Partial,
                        "backup",
                        errorMessage = "Partial rescue map retained",
                    ),
                ),
            )
        assertEquals(UiMessage.Raw("Partial rescue map retained"), partial.state.statusMessage)
    }

    @Test
    fun disconnectClearsPendingConfirmation() {
        val pending =
            ready().copy(
                pendingConfirmation =
                    DestructiveConfirmation.request(device, DestructiveAction.WIPE),
            )
        val result =
            UiReducer.reduce(
                pending,
                UiIntent.DeviceChanged(presence = DevicePresence.Disconnected),
            )
        assertEquals(DevicePresence.Disconnected, result.state.presence)
        assertNull(result.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.DeviceDisconnected), result.state.statusMessage)
    }

    @Test
    fun confirmEmitsSubmitEffectWithIdentityBoundAction() {
        val withConfirm =
            UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.WIPE))
        assertNotNull(withConfirm.state.pendingConfirmation)
        assertEquals(device, withConfirm.state.pendingConfirmation!!.identity)

        val confirmed = UiReducer.reduce(withConfirm.state, UiIntent.ConfirmDestructive)
        assertNull(confirmed.state.pendingConfirmation)
        assertEquals(1, confirmed.effects.size)
        assertEquals(UiEffect.Kind.SubmitDestructive, confirmed.effects.single().kind)
        assertEquals(DestructiveAction.WIPE, confirmed.effects.single().action)
    }

    @Test
    fun isoActionsExposeLinkXmlConfirmation() {
        val link = UiReducer.reduce(ready(), UiIntent.IsoActionRequested("ubuntu.iso", IsoAction.LinkXml))
        assertTrue(link.effects.isEmpty())
        assertEquals("ubuntu.iso", link.state.pendingConfirmation?.isoName)
        val confirmed = UiReducer.reduce(link.state, UiIntent.ConfirmDestructive)
        assertEquals(UiEffect.Kind.LinkXml, confirmed.effects.single().kind)
        assertEquals("ubuntu.iso", confirmed.effects.single().isoName)
    }

    @Test
    fun isoActionsBlockedWhenDisconnected() {
        val state = ready().copy(presence = DevicePresence.Disconnected)
        val result =
            UiReducer.reduce(
                state,
                UiIntent.IsoActionRequested("ubuntu.iso", IsoAction.LinkXml),
            )
        assertTrue(result.effects.isEmpty())
        assertEquals(UiMessage.Key(UiMessageKey.DeviceDisconnected), result.state.statusMessage)
    }

    @Test
    fun selectPartitionSwitchesActivePartition() {
        val state = ready()
        val switched = UiReducer.reduce(state, UiIntent.SelectPartition("2"))
        assertEquals("2", switched.state.selectedPartitionId)
        assertEquals("2", switched.state.selectedPartition()?.id)
    }

    @Test
    fun statusMessageIsUpdatedByStatusIntent() {
        val state = ready()
        val updated = UiReducer.reduce(state, UiIntent.Status(UiMessage.Raw("Uploaded myfile.iso to /")))
        assertEquals(UiMessage.Raw("Uploaded myfile.iso to /"), updated.state.statusMessage)
    }

    @Test
    fun openDirectoryClearsStaleEntriesAndRequestsListing() {
        val state = ready()
        val result = UiReducer.reduce(state, UiIntent.OpenDirectory("/docs"))
        assertEquals("/docs", result.state.currentPath)
        assertTrue(result.state.files.isEmpty())
        assertEquals(1, result.effects.size)
        assertEquals(UiEffect.Kind.LoadFiles, result.effects.single().kind)
        assertEquals("/docs", result.effects.single().path)
    }

    @Test
    fun navigateUpMovesToParentAndRequestsListing() {
        val nested = ready().copy(currentPath = "/docs/manuals", files = emptyList())
        val result = UiReducer.reduce(nested, UiIntent.NavigateUp)
        assertEquals("/docs", result.state.currentPath)
        assertEquals(UiEffect.Kind.LoadFiles, result.effects.single().kind)

        val root = UiReducer.reduce(result.state.copy(currentPath = "/"), UiIntent.NavigateUp)
        assertEquals("/", root.state.currentPath)
        assertTrue(root.effects.isEmpty())
    }

    @Test
    fun listedFilesApplyOnlyToTheDisplayedPath() {
        val state = ready().copy(currentPath = "/docs", files = emptyList())
        val entries = listOf(FileEntry("manual.pdf", isDirectory = false, sizeBytes = 10uL))
        val applied = UiReducer.reduce(state, UiIntent.FilesListed("/docs", entries))
        assertEquals(entries, applied.state.files)

        val stale = UiReducer.reduce(state, UiIntent.FilesListed("/", entries))
        assertTrue(stale.state.files.isEmpty())
    }

    @Test
    fun createFolderAndDeleteEmitRealDiskEffects() {
        val state = ready().copy(currentPath = "/docs", files = emptyList())
        val mkdir = UiReducer.reduce(state, UiIntent.CreateFolder("reports"))
        assertEquals(UiEffect.Kind.CreateFolder, mkdir.effects.single().kind)
        assertEquals("/docs", mkdir.effects.single().path)
        assertEquals("reports", mkdir.effects.single().name)

        val delete = UiReducer.reduce(state, UiIntent.DeleteEntry("manual.pdf"))
        assertEquals(UiEffect.Kind.DeleteEntry, delete.effects.single().kind)
        assertEquals("manual.pdf", delete.effects.single().name)

        val rejected = UiReducer.reduce(state, UiIntent.CreateFolder("a/b"))
        assertTrue(rejected.effects.isEmpty())
        assertNotNull(rejected.state.statusMessage)
    }

    @Test
    fun uploadAndDownloadCarrySafUris() {
        val state = ready().copy(currentPath = "/docs", files = emptyList())
        val upload = UiReducer.reduce(state, UiIntent.UploadFile("content://docs/42"))
        assertEquals(UiEffect.Kind.UploadFile, upload.effects.single().kind)
        assertEquals("content://docs/42", upload.effects.single().uri)
        assertEquals("/docs", upload.effects.single().path)

        val download =
            UiReducer.reduce(state, UiIntent.DownloadFile("manual.pdf", "content://out/7"))
        val effect = download.effects.single()
        assertEquals(UiEffect.Kind.DownloadFile, effect.kind)
        assertEquals("manual.pdf", effect.name)
        assertEquals("content://out/7", effect.uri)
    }

    @Test
    fun fileEffectsAreSuppressedWhileBusy() {
        val busy = ready().copy(presence = DevicePresence.Busy)
        assertTrue(UiReducer.reduce(busy, UiIntent.UploadFile("content://docs/42")).effects.isEmpty())
        assertTrue(UiReducer.reduce(busy, UiIntent.CreateFolder("x")).effects.isEmpty())
        assertTrue(UiReducer.reduce(busy, UiIntent.DeleteEntry("x")).effects.isEmpty())
    }

    @Test
    fun readOnlyPartitionRefusesWritesUntilEnabled() {
        val efi = ready().copy(selectedPartitionId = "2")
        listOf(
            UiIntent.CreateFolder("reports"),
            UiIntent.DeleteEntry("ubuntu.iso"),
            UiIntent.UploadFile("content://docs/42"),
        ).forEach { intent ->
            val result = UiReducer.reduce(efi, intent)
            assertTrue("$intent must not write to VTOYEFI", result.effects.isEmpty())
            assertEquals(
                "explicit reason expected for $intent",
                UiMessage.Key(UiMessageKey.ReadOnlyPartition, listOf("VTOYEFI")),
                result.state.statusMessage,
            )
        }

        val enabled = UiReducer.reduce(efi, UiIntent.SetVtoyEfiWritable("2", true)).state
        assertEquals(
            UiEffect.Kind.UploadFile,
            UiReducer.reduce(enabled, UiIntent.UploadFile("content://docs/42")).effects.single().kind,
        )
    }

    @Test
    fun readOnlyPartitionStillAllowsReads() {
        val efi = ready().copy(selectedPartitionId = "2")
        val download = UiReducer.reduce(efi, UiIntent.DownloadFile("ubuntu.iso", "content://out/1"))
        assertEquals(UiEffect.Kind.DownloadFile, download.effects.single().kind)
        val listed = UiReducer.reduce(efi, UiIntent.OpenDirectory("/docs"))
        assertEquals(UiEffect.Kind.LoadFiles, listed.effects.single().kind)
    }

    @Test
    fun readOnlyActionsSubmitRealDiskWork() {
        assertEquals(
            UiEffect.Kind.ReadScan,
            UiReducer.reduce(ready(), UiIntent.StartReadScan).effects.single().kind,
        )
        assertEquals(
            UiEffect.Kind.RescueBackup,
            UiReducer.reduce(ready(), UiIntent.StartRescueBackup).effects.single().kind,
        )
        assertEquals(
            UiEffect.Kind.CaptureGptBackup,
            UiReducer.reduce(ready(), UiIntent.CaptureGptBackup).effects.single().kind,
        )

        val disconnected = ready().copy(presence = DevicePresence.Disconnected)
        assertTrue(UiReducer.reduce(disconnected, UiIntent.StartReadScan).effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.DeviceDisconnected),
            UiReducer.reduce(disconnected, UiIntent.StartRescueBackup).state.statusMessage,
        )
    }

    @Test
    fun gptRestoreNeedsAChosenBackupFile() {
        val refused =
            UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
        assertNull(refused.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.ChooseGptBackup), refused.state.statusMessage)

        val picked = UiReducer.reduce(ready(), UiIntent.SetBackupFile("content://b/gpt.bin"))
        assertEquals("content://b/gpt.bin", picked.state.backupUri)
        assertEquals(
            UiMessage.Key(UiMessageKey.BackupReady, listOf("gpt.bin")),
            picked.state.statusMessage,
        )

        val confirmed =
            UiReducer.reduce(
                picked.state,
                UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE),
            )
        assertNotNull(confirmed.state.pendingConfirmation)

        val submitted = UiReducer.reduce(confirmed.state, UiIntent.ConfirmDestructive)
        assertEquals(DestructiveAction.GPT_RESTORE, submitted.effects.single().action)
    }

    @Test
    fun completedOperationShowsItsResultMessage() {
        val detail = "Rescue backup: image /x.img, map /x.map: 10 copied, 2 unreadable"
        val completed =
            UiReducer.reduce(
                ready(),
                UiIntent.OperationUpdated(
                    OperationProgress(
                        OperationState.Completed,
                        "rescue backup",
                        resultMessage = detail,
                    ),
                ),
            )
        assertEquals(UiMessage.Raw(detail), completed.state.statusMessage)

        val bare =
            UiReducer.reduce(
                ready(),
                UiIntent.OperationUpdated(OperationProgress(OperationState.Completed, "wipe")),
            )
        assertEquals(UiMessage.Key(UiMessageKey.Success), bare.state.statusMessage)
    }

    @Test
    fun fakeCapacityActionIsNoLongerOffered() {
        assertFalse(DestructiveAction.entries.any { it.name.contains("FAKE") })
        assertEquals("restore GPT backup", DestructiveAction.GPT_RESTORE.displayName())
    }

    @Test
    fun selectPartitionIsRefusedWhileAnOperationRuns() {
        val running =
            ready().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        label = "raw write",
                        bytesTotal = 100,
                    ),
            )
        val result = UiReducer.reduce(running, UiIntent.SelectPartition("2"))
        assertTrue(result.effects.isEmpty())
        assertEquals("1", result.state.selectedPartitionId)
        assertEquals(UiMessage.Key(UiMessageKey.DeviceBusy), result.state.statusMessage)
    }
    @Test
    fun deleteAndFormatPartitionRefusedWithoutSelectedPartition() {
        val noPart = ready().copy(selectedPartitionId = null)
        val deleteResult = UiReducer.reduce(noPart, UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
        assertNull(deleteResult.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.SelectPartitionFirst), deleteResult.state.statusMessage)

        val formatResult = UiReducer.reduce(noPart, UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
        assertNull(formatResult.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.SelectPartitionFirst), formatResult.state.statusMessage)
    }

    @Test
    fun toggleVtoyBypassChecksUpdatesState() {
        val initial = ready().copy(vtoyBypassChecks = null)
        assertNull(initial.vtoyBypassChecks)
        val disabled = UiReducer.reduce(initial, UiIntent.SetVtoyBypassChecks(false))
        assertEquals(false, disabled.state.vtoyBypassChecks)
        val reEnabled = UiReducer.reduce(disabled.state, UiIntent.SetVtoyBypassChecks(true))
        assertEquals(true, reEnabled.state.vtoyBypassChecks)
    }

    @Test
    fun confirmationIncludesPartitionNameForPartitionActions() {
        val deleteReq = UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
        assertNotNull(deleteReq.state.pendingConfirmation)
        assertEquals("VENTOY", deleteReq.state.pendingConfirmation!!.targetPartitionName)
    }

    private fun renameBinding(state: AppUiState): PartitionBinding {
        val partition = state.selectedPartition()!!
        return PartitionBinding(
            device = state.device!!,
            partitionId = partition.id,
            startLba = partition.startLba,
            sectorCount = partition.sectorCount,
        )
    }

    @Test
    fun ventoyOverwriteCandidateRequiresSameAttachGeneration() {
        val available =
            UiReducer.reduce(ready(), UiIntent.LinkOverwriteAvailable("win11.iso")).state
        val reattached = available.copy(
            device = available.device!!.copy(attachGeneration = available.device!!.attachGeneration + 1L),
        )
        val refused = UiReducer.reduce(
            reattached,
            UiIntent.RequestLinkOverwrite("win11.iso"),
        )
        assertNull(refused.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.LinkMismatch), refused.state.statusMessage)

        val accepted = UiReducer.reduce(
            available,
            UiIntent.RequestLinkOverwrite("win11.iso"),
        )
        assertEquals(
            DestructiveAction.VENTOY_LINK_XML,
            accepted.state.pendingConfirmation?.action,
        )
    }

    @Test
    fun damagedPrimaryGptBlocksTableEditsButKeepsRecovery() {
        val damaged = ready().copy(partitionTableDamaged = true, tableValid = true)

        val damagedWithFreeSpace = damaged.copy(
            diskSectorSize = 512,
            freeRegions = listOf(FreeRegionUi(0uL, 8192uL)),
        )
        val create = UiReducer.reduce(
            damagedWithFreeSpace,
            UiIntent.RequestCreatePartition(0uL, 2048uL),
        )
        assertNull(create.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.GptPrimaryDamaged), create.state.statusMessage)

        val delete = UiReducer.reduce(
            damaged,
            UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION),
        )
        assertNull(delete.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.GptPrimaryDamaged), delete.state.statusMessage)

        val repair = UiReducer.reduce(
            damaged,
            UiIntent.RequestDestructive(DestructiveAction.GPT_REPAIR),
        )
        assertNull(repair.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.GptPrimaryDamaged), repair.state.statusMessage)

        val role = UiReducer.reduce(
            damaged,
            UiIntent.RequestSetPartitionRole(PartitionRole.Efi),
        )
        assertNull(role.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.GptPrimaryDamaged), role.state.statusMessage)

        val boot = UiReducer.reduce(
            damaged,
            UiIntent.RequestSetPartitionBootable(true),
        )
        assertNull(boot.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.GptPrimaryDamaged), boot.state.statusMessage)

        val format = UiReducer.reduce(
            damaged,
            UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
        )
        assertEquals(DestructiveAction.FORMAT_PARTITION, format.state.pendingConfirmation?.action)
    }

    @Test
    fun renameRequiresValidNewNameAndEmitsEffect() {
        val state = ready()
        val binding = renameBinding(state)
        val invalid = UiReducer.reduce(state, UiIntent.RenameEntry("ubuntu.iso", "a/b", binding))
        assertTrue(invalid.effects.isEmpty())
        assertEquals(UiMessage.Key(UiMessageKey.InvalidEntryName), invalid.state.statusMessage)

        val same = UiReducer.reduce(state, UiIntent.RenameEntry("ubuntu.iso", "ubuntu.iso", binding))
        assertTrue(same.effects.isEmpty())
        assertEquals(UiMessage.Key(UiMessageKey.RenameSameName), same.state.statusMessage)

        val renamed = UiReducer.reduce(state, UiIntent.RenameEntry("ubuntu.iso", "linux.iso", binding))
        val effect = renamed.effects.single()
        assertEquals(UiEffect.Kind.RenameEntry, effect.kind)
        assertEquals("ubuntu.iso", effect.name)
        assertEquals("linux.iso", effect.newName)
        assertEquals(binding, effect.binding)
    }

    @Test
    fun renameRejectsStaleDeviceOrGeometry() {
        val state = ready()
        val binding = renameBinding(state)

        val newDevice = state.copy(device = device.copy(attachGeneration = 1L))
        val staleDevice = UiReducer.reduce(newDevice, UiIntent.RenameEntry("ubuntu.iso", "linux.iso", binding))
        assertTrue(staleDevice.effects.isEmpty())
        assertEquals(UiMessage.Key(UiMessageKey.TargetChanged), staleDevice.state.statusMessage)

        val changedGeometry = state.copy(
            partitions = listOf(
                PartitionTab("1", "VENTOY", readOnly = false, startLba = 0uL, sectorCount = 4096uL),
            ),
        )
        val staleGeometry = UiReducer.reduce(
            changedGeometry,
            UiIntent.RenameEntry("ubuntu.iso", "linux.iso", binding),
        )
        assertTrue(staleGeometry.effects.isEmpty())
        assertEquals(UiMessage.Key(UiMessageKey.TargetChanged), staleGeometry.state.statusMessage)

        val otherPartition = state.copy(selectedPartitionId = "2")
        val staleSelection = UiReducer.reduce(
            otherPartition,
            UiIntent.RenameEntry("ubuntu.iso", "linux.iso", binding),
        )
        assertTrue(staleSelection.effects.isEmpty())
        assertEquals(UiMessage.Key(UiMessageKey.TargetChanged), staleSelection.state.statusMessage)
    }

    @Test
    fun partitionRoleChangeNeedsConfirmationWithPinnedGeometry() {
        val requested =
            UiReducer.reduce(ready(), UiIntent.RequestSetPartitionRole(PartitionRole.Efi))
        val pending = requested.state.pendingConfirmation
        assertNotNull(pending)
        assertEquals(DestructiveAction.SET_PARTITION_ROLE, pending!!.action)
        assertEquals(PartitionRole.Efi, pending.partitionRole)
        assertEquals("1", pending.partitionId)
        assertTrue(pending.partitionStartLba == 0uL)
        assertTrue(pending.partitionSectorCount == 2048uL)

        val confirmed = UiReducer.reduce(requested.state, UiIntent.ConfirmDestructive)
        assertEquals(DestructiveAction.SET_PARTITION_ROLE, confirmed.effects.single().action)
    }

    @Test
    fun partitionRoleChangeOnProtectedEfiNeedsOverride() {
        val efi = ready().copy(selectedPartitionId = "2")
        val blocked = UiReducer.reduce(efi, UiIntent.RequestSetPartitionRole(PartitionRole.Data))
        assertNull(blocked.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.ReadOnlyPartition, listOf("VTOYEFI")),
            blocked.state.statusMessage,
        )
    }

    @Test
    fun bootableChangeNeedsConfirmation() {
        val requested =
            UiReducer.reduce(ready(), UiIntent.RequestSetPartitionBootable(true))
        val pending = requested.state.pendingConfirmation
        assertNotNull(pending)
        assertEquals(DestructiveAction.SET_PARTITION_BOOTABLE, pending!!.action)
        assertEquals(true, pending.partitionBootable)
        assertEquals("1", pending.partitionId)
    }

    @Test
    fun createPartitionRefusedOnCorruptTable() {
        val corrupt = ready().copy(tableValid = false)
        val createResult = UiReducer.reduce(corrupt, UiIntent.RequestDestructive(DestructiveAction.CREATE_PARTITION))
        assertNull(createResult.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.CorruptTableCreate), createResult.state.statusMessage)
    }

    @Test
    fun deviceChangedClearsPendingConfirmationAcrossAllStates() {
        for (presence in DevicePresence.entries) {
            val pending = ready().copy(
                pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.WIPE),
            )
            val result = UiReducer.reduce(pending, UiIntent.DeviceChanged(presence = presence))
            assertNull("pendingConfirmation must be cleared on DeviceChanged with presence $presence", result.state.pendingConfirmation)
        }
    }

    @Test
    fun confirmDestructiveRevalidatesTableAndSelection() {
        val corruptState = ready().copy(
            tableValid = false,
            pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.CREATE_PARTITION),
        )
        val confirmCorrupt = UiReducer.reduce(corruptState, UiIntent.ConfirmDestructive)
        assertNull(confirmCorrupt.state.pendingConfirmation)
        assertTrue(confirmCorrupt.effects.isEmpty())

        val noSelection = ready().copy(
            selectedPartitionId = null,
            pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.DELETE_PARTITION, "1"),
        )
        val confirmNoSel = UiReducer.reduce(noSelection, UiIntent.ConfirmDestructive)
        assertNull(confirmNoSel.state.pendingConfirmation)
        assertTrue(confirmNoSel.effects.isEmpty())
    }
}
