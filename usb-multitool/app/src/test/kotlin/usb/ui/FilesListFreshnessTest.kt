package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.service.OperationTokenGenerator

/**
 * F5/F15: directory list answers stay bound to path + partition + attachment +
 * request generation, and a partition refresh must reload instead of showing a fake empty folder.
 */
class FilesListFreshnessTest {
    private val device =
        DeviceIdentity(
            label = "SanDisk",
            capacityBytes = 8_000_000_000uL,
            serialNumber = "SN-1",
            attachGeneration = 3L,
        )

    private val data =
        PartitionTab("1", "DATA", readOnly = false, startLba = 2048uL, sectorCount = 1_000_000uL)
    private val other =
        PartitionTab("2", "OTHER", readOnly = false, startLba = 2_000_000uL, sectorCount = 500_000uL)

    private fun ready(
        path: String = "/",
        files: List<FileEntry> = listOf(FileEntry("keep.iso", isDirectory = false, sizeBytes = 1uL)),
        generation: Long = 7L,
        pending: Boolean = false,
    ): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = listOf(data, other),
            selectedPartitionId = data.id,
            currentPath = path,
            files = files,
            filesListGeneration = generation,
            filesListingState =
                if (pending) FilesListingState.Loading else FilesListingState.Loaded,
        )

    private fun AppUiState.listed(
        path: String = currentPath,
        files: List<FileEntry> = emptyList(),
        generation: Long = filesListGeneration,
        identity: String? = device?.identity,
        partitionId: String = selectedPartitionId ?: "",
        attachGeneration: Long = device?.attachGeneration ?: 0L,
    ): UiIntent.FilesListed =
        UiIntent.FilesListed(
            path = path,
            files = files,
            generation = generation,
            identity = identity,
            partitionId = partitionId,
            attachGeneration = attachGeneration,
        )

    private fun AppUiState.listFailed(
        path: String = currentPath,
        message: String,
        generation: Long = filesListGeneration,
        identity: String? = device?.identity,
        partitionId: String = selectedPartitionId ?: "",
        attachGeneration: Long = device?.attachGeneration ?: 0L,
    ): UiIntent.FilesListFailed =
        UiIntent.FilesListFailed(
            path = path,
            message = message,
            generation = generation,
            identity = identity,
            partitionId = partitionId,
            attachGeneration = attachGeneration,
        )

    @Test
    fun deviceChangedReadyWithPartitionReloadsAndMarksPending() {
        val base = ready(path = "/ISO", files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 2uL)))
        val refreshed =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(data, other),
                    files = emptyList(),
                ),
            )

        assertEquals("/ISO", refreshed.state.currentPath)
        assertTrue(refreshed.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, refreshed.state.filesListingState)
        assertNull(refreshed.state.filesError)
        assertEquals(base.filesListGeneration + 1L, refreshed.state.filesListGeneration)
        val refreshedFiles = refreshed.effects.single() as UiEffect.LoadFiles
        assertEquals("/ISO", refreshedFiles.path)
    }

    @Test
    fun deviceChangedDropsPathWhenSelectedPartitionGone() {
        val base = ready(path = "/deep")
        val onlyOther =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(other),
                ),
            )

        // Missing selected partition must stay unselected; never retarget to another entry.
        assertNull(onlyOther.state.selectedPartitionId)
        assertEquals("/", onlyOther.state.currentPath)
        assertEquals(FilesListingState.Stale, onlyOther.state.filesListingState)
        assertTrue(onlyOther.effects.none { it is UiEffect.LoadFiles })
    }
    @Test
    fun deviceChangedDropsSelectionWhenGeometryChanged() {
        val base = ready()
        val changed = UiReducer.reduce(
            base,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device,
                partitions = listOf(data.copy(startLba = 4096uL), other),
            ),
        )
        assertNull(changed.state.selectedPartitionId)
        assertEquals(FilesListingState.Stale, changed.state.filesListingState)
        assertTrue(changed.effects.none { it is UiEffect.LoadFiles })
    }

    @Test
    fun deviceChangedWithoutPartitionsClearsPendingWithoutLoad() {
        val base = ready()
        val empty =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = emptyList(),
                    files = emptyList(),
                ),
            )

        assertNull(empty.state.selectedPartitionId)
        assertEquals(FilesListingState.Stale, empty.state.filesListingState)
        assertTrue(empty.effects.none { it is UiEffect.LoadFiles })
    }

    @Test
    fun detachClearsPendingAndDoesNotLoad() {
        val base = ready(pending = true)
        val detached = UiReducer.reduce(base, UiIntent.DeviceChanged(DevicePresence.Disconnected))
        assertEquals(FilesListingState.Stale, detached.state.filesListingState)
        assertTrue(detached.state.files.isEmpty())
        assertTrue(detached.effects.isEmpty())
    }

    @Test
    fun selectPartitionBumpsGenerationAndRequestsRootList() {
        val base = ready(path = "/ISO", generation = 4L)
        val switched = UiReducer.reduce(base, UiIntent.SelectPartition(other.id))
        assertEquals(other.id, switched.state.selectedPartitionId)
        assertEquals("/", switched.state.currentPath)
        assertTrue(switched.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, switched.state.filesListingState)
        assertEquals(5L, switched.state.filesListGeneration)
        val switchedFiles = switched.effects.single() as UiEffect.LoadFiles
        assertEquals("/", switchedFiles.path)
    }

    @Test
    fun matchingListClearsPendingAndAppliesEntries() {
        val pending = ready(files = emptyList(), pending = true, generation = 9L)
        val entries = listOf(FileEntry("a.txt", isDirectory = false, sizeBytes = 3uL))
        val applied = UiReducer.reduce(pending, pending.listed(files = entries))
        assertEquals(entries, applied.state.files)
        assertEquals(FilesListingState.Loaded, applied.state.filesListingState)
        assertNull(applied.state.filesError)
    }

    @Test
    fun emptyMatchingListIsRealEmptyNotPending() {
        val pending = ready(files = emptyList(), pending = true)
        val applied = UiReducer.reduce(pending, pending.listed(files = emptyList()))
        assertTrue(applied.state.files.isEmpty())
        assertEquals(FilesListingState.Loaded, applied.state.filesListingState)
        assertNull(applied.state.filesError)
    }

    @Test
    fun staleListAfterPartitionSwitchIsIgnored() {
        val onData = ready(files = emptyList(), pending = true, generation = 2L)
        val switched = UiReducer.reduce(onData, UiIntent.SelectPartition(other.id)).state
        assertEquals(3L, switched.filesListGeneration)
        assertEquals(FilesListingState.Loading, switched.filesListingState)

        val foreign =
            listOf(FileEntry("from-data.bin", isDirectory = false, sizeBytes = 9uL))
        // Quiet list that started on DATA finishes after the user moved to OTHER.
        val stale =
            UiReducer.reduce(
                switched,
                onData.listed(path = "/", files = foreign, generation = 2L, partitionId = data.id),
            )
        assertTrue(stale.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, stale.state.filesListingState)
        assertNull(stale.state.filesError)

        val live =
            listOf(FileEntry("from-other.bin", isDirectory = false, sizeBytes = 4uL))
        val fresh = UiReducer.reduce(stale.state, stale.state.listed(files = live))
        assertEquals(live, fresh.state.files)
        assertEquals(FilesListingState.Loaded, fresh.state.filesListingState)
    }

    @Test
    fun staleListAfterReattachIsIgnored() {
        val before = ready(files = emptyList(), pending = true, generation = 5L)
        val reattached =
            UiReducer.reduce(
                before,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device.copy(attachGeneration = 99L),
                    partitions = listOf(data, other),
                ),
            ).state
        assertEquals(6L, reattached.filesListGeneration)
        assertEquals(99L, reattached.device?.attachGeneration)

        val staleEntries =
            listOf(FileEntry("pre-reattach.bin", isDirectory = false, sizeBytes = 1uL))
        val stale =
            UiReducer.reduce(
                reattached,
                before.listed(
                    files = staleEntries,
                    generation = 5L,
                    attachGeneration = 3L,
                ),
            )
        assertTrue(stale.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, stale.state.filesListingState)

        val live = listOf(FileEntry("post-reattach.bin", isDirectory = false, sizeBytes = 2uL))
        val applied = UiReducer.reduce(stale.state, stale.state.listed(files = live))
        assertEquals(live, applied.state.files)
        assertEquals(FilesListingState.Loaded, applied.state.filesListingState)
    }

    @Test
    fun staleListAfterIdentityChangeIsIgnored() {
        val before = ready(files = emptyList(), pending = true, generation = 1L)
        val otherStick =
            device.copy(label = "Other", serialNumber = "SN-2", attachGeneration = 3L)
        val swapped =
            UiReducer.reduce(
                before,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = otherStick,
                    partitions = listOf(data),
                ),
            ).state

        val stale =
            UiReducer.reduce(
                swapped,
                before.listed(
                    files = listOf(FileEntry("old-stick.bin", isDirectory = false, sizeBytes = 1uL)),
                    identity = device.identity,
                ),
            )
        assertTrue(stale.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, stale.state.filesListingState)
    }

    @Test
    fun matchingFailureClearsPendingAndSetsError() {
        val pending = ready(files = emptyList(), pending = true, generation = 8L)
        val failed = UiReducer.reduce(pending, pending.listFailed(message = "I/O error"))
        assertEquals("I/O error", failed.state.filesError)
        assertEquals(FilesListingState.Failed, failed.state.filesListingState)
        assertTrue(failed.state.files.isEmpty())
    }

    @Test
    fun staleFailureAfterPartitionSwitchIsIgnored() {
        val onData = ready(files = emptyList(), pending = true, generation = 2L)
        val switched = UiReducer.reduce(onData, UiIntent.SelectPartition(other.id)).state
        val stale =
            UiReducer.reduce(
                switched,
                onData.listFailed(message = "late fail", generation = 2L, partitionId = data.id),
            )
        assertNull(stale.state.filesError)
        assertEquals(FilesListingState.Loading, stale.state.filesListingState)
    }

    @Test
    fun openDirectoryMarksPendingAndBumpsGeneration() {
        val base = ready(generation = 10L)
        val opened = UiReducer.reduce(base, UiIntent.OpenDirectory("/docs"))
        assertEquals("/docs", opened.state.currentPath)
        assertTrue(opened.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, opened.state.filesListingState)
        assertEquals(11L, opened.state.filesListGeneration)
        assertTrue(opened.effects.single() is UiEffect.LoadFiles)
    }

    @Test
    fun busyProbeKeepsPathThenReadyReloadsSameFolder() {
        val base = ready(path = "/ISO", generation = 4L)
        val busy =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device,
                    partitions = emptyList(),
                    files = emptyList(),
                ),
            )
        assertEquals("/ISO", busy.state.currentPath)
        assertEquals(data.id, busy.state.selectedPartitionId)
        assertEquals(base.files, busy.state.files)
        assertEquals(FilesListingState.Loaded, busy.state.filesListingState)
        assertEquals(4L, busy.state.filesListGeneration)
        assertTrue(busy.effects.none { it is UiEffect.LoadFiles })

        val readyAgain =
            UiReducer.reduce(
                busy.state,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(data, other),
                ),
            )
        assertEquals("/ISO", readyAgain.state.currentPath)
        assertTrue(readyAgain.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, readyAgain.state.filesListingState)
        assertEquals(5L, readyAgain.state.filesListGeneration)
        val readyAgainFiles = readyAgain.effects.single() as UiEffect.LoadFiles
        assertEquals("/ISO", readyAgainFiles.path)
    }

    @Test
    fun busyWithZeroGenerationDoesNotKeepForeignPath() {
        // Host used to emit Busy with attachGeneration=0; that must not keep path/selection.
        val base = ready(path = "/ISO", generation = 4L)
        val zeroGen = device.copy(attachGeneration = 0L)
        val busy =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = zeroGen,
                    partitions = emptyList(),
                ),
            )
        assertEquals("/", busy.state.currentPath)
        assertNull(busy.state.selectedPartitionId)
        assertTrue(busy.state.files.isEmpty())
    }

    @Test
    fun differentIdentityBusyDoesNotKeepPath() {
        val base = ready(path = "/ISO")
        val otherStick = device.copy(label = "Other", serialNumber = "SN-9")
        val busy =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = otherStick,
                    partitions = emptyList(),
                ),
            )
        assertEquals("/", busy.state.currentPath)
        assertNull(busy.state.selectedPartitionId)
    }

    @Test
    fun deepPathMissingFallsBackToRoot() {
        val pending =
            ready(path = "/gone/after/format", files = emptyList(), pending = true, generation = 6L)
        val failed = UiReducer.reduce(pending, pending.listFailed(message = "Path not found: /gone/after/format"))
        assertEquals("/", failed.state.currentPath)
        assertEquals(FilesListingState.Loading, failed.state.filesListingState)
        assertNull(failed.state.filesError)
        assertEquals(7L, failed.state.filesListGeneration)
        val failedFiles = failed.effects.single() as UiEffect.LoadFiles
        assertEquals("/", failedFiles.path)

        // Root failure sticks as a real error.
        val rootFail =
            UiReducer.reduce(
                failed.state,
                failed.state.listFailed(message = "I/O error"),
            )
        assertEquals("I/O error", rootFail.state.filesError)
        assertEquals(FilesListingState.Failed, rootFail.state.filesListingState)
        assertTrue(rootFail.effects.isEmpty())
    }

    @Test
    fun deepPathUnknownIoErrorStaysVisible() {
        val pending =
            ready(path = "/ISO", files = emptyList(), pending = true, generation = 6L)
        val failed = UiReducer.reduce(pending, pending.listFailed(message = "disk read failed"))
        assertEquals("/ISO", failed.state.currentPath)
        assertEquals("disk read failed", failed.state.filesError)
        assertEquals(FilesListingState.Failed, failed.state.filesListingState)
        assertTrue(failed.effects.isEmpty())
    }

    @Test
    fun staleDeepFailAfterPathChangeIsIgnored() {
        val deep = ready(path = "/ISO", files = emptyList(), pending = true, generation = 2L)
        val atRoot = UiReducer.reduce(deep, UiIntent.NavigateUp).state
        assertEquals("/", atRoot.currentPath)
        assertEquals(FilesListingState.Loading, atRoot.filesListingState)
        val stale =
            UiReducer.reduce(
                atRoot,
                deep.listFailed(path = "/ISO", message = "Path not found: /ISO", generation = 2L),
            )
        assertEquals("/", stale.state.currentPath)
        assertNull(stale.state.filesError)
        assertEquals(FilesListingState.Loading, stale.state.filesListingState)
    }

    @Test
    fun sameGenerationDifferentPartitionIsIgnored() {
        val pending = ready(files = emptyList(), pending = true, generation = 3L)
        val foreign =
            UiReducer.reduce(
                pending,
                pending.listed(
                    files = listOf(FileEntry("x.bin", isDirectory = false, sizeBytes = 1uL)),
                    generation = 3L,
                    partitionId = other.id,
                ),
            )
        assertTrue(foreign.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, foreign.state.filesListingState)
    }

    @Test
    fun permissionRequiredSameStickKeepsPathAndCapacity() {
        val base = ready(path = "/ISO", generation = 4L)
        val permission =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.PermissionRequired,
                    device = device.copy(capacityBytes = 0uL),
                    partitions = emptyList(),
                ),
            )
        assertEquals("/ISO", permission.state.currentPath)
        assertEquals(data.id, permission.state.selectedPartitionId)
        assertEquals(device.capacityBytes, permission.state.device?.capacityBytes)
        assertEquals(FilesListingState.Loaded, permission.state.filesListingState)
        assertTrue(permission.effects.none { it is UiEffect.LoadFiles })
    }

    @Test
    fun efiOverrideKeptOnTrustedBusyProbe() {
        val efi =
            PartitionTab(
                "efi",
                "VTOYEFI",
                readOnly = true,
                startLba = 100uL,
                sectorCount = 200uL,
            )
        val base =
            ready(path = "/ISO").copy(
                partitions = listOf(data, efi),
                vtoyEfiWritePartitionId = efi.id,
                vtoyEfiWriteStartLba = efi.startLba,
                vtoyEfiWriteSectorCount = efi.sectorCount,
            )
        val busy =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device.copy(capacityBytes = 0uL),
                    partitions = emptyList(),
                ),
            )
        assertEquals(efi.id, busy.state.vtoyEfiWritePartitionId)
        assertEquals(efi.startLba, busy.state.vtoyEfiWriteStartLba)
        assertEquals(device.capacityBytes, busy.state.device?.capacityBytes)
    }

    @Test
    fun efiOverrideDroppedWithoutStableSerialOnLabelOnly() {
        val labelOnly =
            DeviceIdentity(
                label = "Generic",
                capacityBytes = 8_000_000_000uL,
                serialNumber = null,
                attachGeneration = 3L,
            )
        val efi =
            PartitionTab(
                "efi",
                "VTOYEFI",
                readOnly = true,
                startLba = 100uL,
                sectorCount = 200uL,
            )
        val base =
            AppUiState(
                presence = DevicePresence.Ready,
                device = labelOnly,
                partitions = listOf(data, efi),
                selectedPartitionId = data.id,
                currentPath = "/ISO",
                files = listOf(FileEntry("keep.iso", isDirectory = false, sizeBytes = 1uL)),
                filesListGeneration = 4L,
                vtoyEfiWritePartitionId = efi.id,
                vtoyEfiWriteStartLba = efi.startLba,
                vtoyEfiWriteSectorCount = efi.sectorCount,
                pendingConfirmation =
                    DestructiveConfirmation(
                        action = DestructiveAction.FORMAT_PARTITION,
                        identity = labelOnly,
                        partitionId = data.id,
                    ),
            )
        val busy =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    // capacity 0 on Busy: not trusted without serial
                    device = labelOnly.copy(capacityBytes = 0uL),
                    partitions = emptyList(),
                ),
            )
        // Path still retained via sameStick+gen; privileged EFI/confirmation must drop.
        assertEquals("/ISO", busy.state.currentPath)
        assertNull(busy.state.vtoyEfiWritePartitionId)
        assertNull(busy.state.pendingConfirmation)
    }

    @Test
    fun efiOverrideDroppedWhenOnlyCapacityMatchesNoSerial() {
        // Two sticks can share capacity; that must never keep EFI/confirmation.
        val noSerial =
            DeviceIdentity(
                label = "SanDisk",
                capacityBytes = 8_000_000_000uL,
                serialNumber = null,
                attachGeneration = 3L,
            )
        val efi =
            PartitionTab(
                "efi",
                "VTOYEFI",
                readOnly = true,
                startLba = 100uL,
                sectorCount = 200uL,
            )
        val base =
            AppUiState(
                presence = DevicePresence.Ready,
                device = noSerial,
                partitions = listOf(data, efi),
                selectedPartitionId = data.id,
                currentPath = "/",
                vtoyEfiWritePartitionId = efi.id,
                vtoyEfiWriteStartLba = efi.startLba,
                vtoyEfiWriteSectorCount = efi.sectorCount,
                pendingConfirmation =
                    DestructiveConfirmation(
                        action = DestructiveAction.FORMAT_PARTITION,
                        identity = noSerial,
                        partitionId = data.id,
                    ),
            )
        val readyAgain =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = noSerial, // same capacity, still no serial
                    partitions = listOf(data, efi),
                ),
            )
        assertNull(readyAgain.state.vtoyEfiWritePartitionId)
        assertNull(readyAgain.state.pendingConfirmation)
    }

    @Test
    fun missingDirectoryHelperRecognizesKnownMessages() {
        // FatFs nativeList exact shapes (FR_NO_FILE=4, FR_NO_PATH=5)
        assertTrue(UiReducer.isMissingDirectoryListError("list failed: 4"))
        assertTrue(UiReducer.isMissingDirectoryListError("list failed: 5"))
        // EXT/NTFS list ENOENT (ext_jni / ntfs_jni)
        assertTrue(UiReducer.isMissingDirectoryListError("Path not found"))
        assertTrue(UiReducer.isMissingDirectoryListError("Path not found: /x"))
        // Must not treat arbitrary I/O that happens to end with ": 5" as path-absent.
        assertFalse(UiReducer.isMissingDirectoryListError("scsi sense: 5"))
        assertFalse(UiReducer.isMissingDirectoryListError("error: 5"))
        assertFalse(UiReducer.isMissingDirectoryListError("disk I/O error"))
        assertFalse(UiReducer.isMissingDirectoryListError("Cancelled"))
        assertFalse(UiReducer.isMissingDirectoryListError("list failed: 1")) // FR_DISK_ERR
        // Pre-fix EXT generic listing failed must NOT fallback (D-A regression).
        assertFalse(UiReducer.isMissingDirectoryListError("directory listing failed"))
        assertFalse(
            UiReducer.isMissingDirectoryListError("directory listing failed: unreadable directory"),
        )
        assertFalse(
            UiReducer.isMissingDirectoryListError("directory listing exceeds the supported entry count"),
        )
    }

    @Test
    fun missingDirectoryHelperRejectsNonexactAbsenceTexts() {
        // No engine emits these for a listing fault; the old contains() matches hid EIO.
        assertFalse(UiReducer.isMissingDirectoryListError("No such file or directory"))
        assertFalse(UiReducer.isMissingDirectoryListError("java.io.IOException: ENOENT"))
        assertFalse(UiReducer.isMissingDirectoryListError("stat failed: ENOENT"))
        assertFalse(UiReducer.isMissingDirectoryListError("path not found"))
        // Exact producer text only: no prefix/suffix drift.
        assertFalse(UiReducer.isMissingDirectoryListError("list failed: 4 "))
        assertFalse(UiReducer.isMissingDirectoryListError("prefix list failed: 4"))
        assertFalse(UiReducer.isMissingDirectoryListError("Path not found:"))
        assertFalse(UiReducer.isMissingDirectoryListError("Path not found /x"))
    }

    @Test
    fun extPathNotFoundDeepListFallsBackToRoot() {
        val pending =
            ready(path = "/gone/on/ext", files = emptyList(), pending = true, generation = 6L)
        // Message from fixed ext_jni ENOENT branch (same as NTFS list).
        val failed =
            UiReducer.reduce(pending, pending.listFailed(message = "Path not found"))
        assertEquals("/", failed.state.currentPath)
        assertEquals(FilesListingState.Loading, failed.state.filesListingState)
        assertNull(failed.state.filesError)
        assertTrue(failed.effects.single() is UiEffect.LoadFiles)
    }

    @Test
    fun extGenericListingFailedDoesNotFallback() {
        val pending =
            ready(path = "/ISO", files = emptyList(), pending = true, generation = 6L)
        val failed =
            UiReducer.reduce(
                pending,
                pending.listFailed(message = "directory listing failed"),
            )
        assertEquals("/ISO", failed.state.currentPath)
        assertEquals("directory listing failed", failed.state.filesError)
        assertEquals(FilesListingState.Failed, failed.state.filesListingState)
        assertTrue(failed.effects.isEmpty())
    }

    @Test
    fun enteringFilesTabReloadsTheOpenFolder() {
        val base =
            ready(
                path = "/ISO",
                files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 1uL)),
            ).copy(tab = ShellTab.Ventoy)

        val entered = UiReducer.reduce(base, UiIntent.SelectTab(ShellTab.Files))

        assertEquals(ShellTab.Files, entered.state.tab)
        // The folder is kept; only its listing is re-read.
        assertEquals("/ISO", entered.state.currentPath)
        assertTrue(entered.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, entered.state.filesListingState)
        assertEquals(base.filesListGeneration + 1L, entered.state.filesListGeneration)
        assertEquals("/ISO", (entered.effects.single() as UiEffect.LoadFiles).path)
    }

    @Test
    fun selectingTheAlreadyOpenFilesTabDoesNotReload() {
        val pending = ready(path = "/ISO", files = emptyList(), pending = true)

        val again = UiReducer.reduce(pending, UiIntent.SelectTab(ShellTab.Files))

        assertTrue(again.effects.isEmpty())
        assertEquals(pending.filesListGeneration, again.state.filesListGeneration)
        assertEquals(FilesListingState.Loading, again.state.filesListingState)
    }

    @Test
    fun manualRefreshDropsTheOlderInFlightAnswer() {
        val staleEntry = FileEntry("stale.bin", isDirectory = false, sizeBytes = 1uL)
        val freshEntry = FileEntry("fresh.bin", isDirectory = false, sizeBytes = 2uL)
        val pending = ready(path = "/ISO", files = listOf(staleEntry), pending = true)
        val staleGeneration = pending.filesListGeneration

        val refreshed = UiReducer.reduce(pending, UiIntent.RefreshFiles)

        assertTrue(refreshed.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, refreshed.state.filesListingState)
        assertEquals(staleGeneration + 1L, refreshed.state.filesListGeneration)
        assertEquals("/ISO", (refreshed.effects.single() as UiEffect.LoadFiles).path)

        val late =
            UiReducer.reduce(
                refreshed.state,
                refreshed.state.listed(files = listOf(staleEntry), generation = staleGeneration),
            )
        assertTrue(late.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, late.state.filesListingState)

        val applied =
            UiReducer.reduce(
                late.state,
                late.state.listed(files = listOf(freshEntry)),
            )
        assertEquals(listOf(freshEntry), applied.state.files)
        assertEquals(FilesListingState.Loaded, applied.state.filesListingState)
    }

    @Test
    fun refreshWhileBusyKeepsTheCurrentListing() {
        val keep = FileEntry("keep.iso", isDirectory = false, sizeBytes = 1uL)
        val busy =
            ready(path = "/ISO", files = listOf(keep), pending = true).copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        token = OperationTokenGenerator.next(),
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
            )

        val refused = UiReducer.reduce(busy, UiIntent.RefreshFiles)

        assertEquals(listOf(keep), refused.state.files)
        assertEquals(FilesListingState.Loading, refused.state.filesListingState)
        assertTrue(refused.effects.isEmpty())
        assertEquals(UiMessage.Key(UiMessageKey.DeviceBusy), refused.state.statusMessage)
    }

    @Test
    fun deviceChangedReadyOnAnotherTabDefersTheListUntilEntry() {
        val base =
            ready(
                path = "/ISO",
                files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 1uL)),
            ).copy(tab = ShellTab.Ventoy)

        val changed =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(data, other),
                    files = emptyList(),
                ),
            )

        // No off-tab mount, but the listing is already invalid.
        assertTrue(changed.effects.isEmpty())
        assertTrue(changed.state.files.isEmpty())
        assertEquals(FilesListingState.Stale, changed.state.filesListingState)
        assertEquals(base.filesListGeneration + 1L, changed.state.filesListGeneration)

        val entered = UiReducer.reduce(changed.state, UiIntent.SelectTab(ShellTab.Files))
        assertEquals("/ISO", (entered.effects.single() as UiEffect.LoadFiles).path)
        assertEquals(FilesListingState.Loading, entered.state.filesListingState)
    }

    @Test
    fun completedMutationReloadsTheVisibleFolderOnce() {
        val token = OperationTokenGenerator.next()
        val running =
            ready(
                path = "/ISO",
                files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 1uL)),
            ).copy(
                operation =
                    OperationProgress(
                        token = token,
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
            )

        val progressed =
            UiReducer.reduce(
                running,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Completed,
                        token = token,
                        title = OperationTitle.Upload,
                    ),
                ),
            )
        assertEquals(FilesListingState.Loaded, progressed.state.filesListingState)
        val done = UiReducer.reduce(
            progressed.state,
            UiIntent.OperationCompletionEffects(
                token = token,
                attachmentGeneration = device.attachGeneration,
                title = OperationTitle.Upload,
                state = OperationState.Completed,
            ),
        )
        assertTrue(done.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, done.state.filesListingState)
        assertEquals(running.filesListGeneration + 1L, done.state.filesListGeneration)
        assertEquals("/ISO", (done.effects.single() as UiEffect.LoadFiles).path)
    }

    @Test
    fun ventoyWriteReloadsVisibleFolder() {
        val token = OperationTokenGenerator.next()
        val running =
            ready(path = "/ISO", files = listOf(FileEntry("old.iso", isDirectory = false, sizeBytes = 1uL)))
                .copy(
                    operation = OperationProgress(
                        state = OperationState.Running,
                        token = token,
                        title = OperationTitle.GenerateUnattendXml,
                    ),
                )

        val progressed = UiReducer.reduce(
            running,
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Completed,
                    token = token,
                    title = OperationTitle.GenerateUnattendXml,
                ),
            ),
        )
        val done = UiReducer.reduce(
            progressed.state,
            UiIntent.OperationCompletionEffects(
                token = token,
                attachmentGeneration = device.attachGeneration,
                title = OperationTitle.GenerateUnattendXml,
                state = OperationState.Completed,
            ),
        )
        assertTrue(done.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, done.state.filesListingState)
        assertEquals("/ISO", (done.effects.single() as UiEffect.LoadFiles).path)
    }

    @Test
    fun staleMutationCompletionKeepsReloadPendingUntilTakeoverFinishes() {
        val mutation = OperationTokenGenerator.next()
        val takeover = OperationTokenGenerator.next()
        val base = ready(
            path = "/ISO",
            files = listOf(FileEntry("old.iso", isDirectory = false, sizeBytes = 1uL)),
        )
        val runningTakeover = UiReducer.reduce(
            UiReducer.reduce(
                base,
                UiIntent.OperationBegan(
                    OperationProgress(OperationState.Running, OperationTitle.Upload, mutation),
                ),
            ).state,
            UiIntent.OperationBegan(
                OperationProgress(OperationState.Running, OperationTitle.ReadScan, takeover),
            ),
        ).state

        val staleCompletion = UiReducer.reduce(
            runningTakeover,
            UiIntent.OperationCompletionEffects(
                token = mutation,
                attachmentGeneration = device.attachGeneration,
                title = OperationTitle.Upload,
                state = OperationState.Completed,
            ),
        )
        assertTrue(staleCompletion.effects.isEmpty())
        assertTrue(staleCompletion.state.files.isEmpty())
        assertEquals(FilesListingState.Stale, staleCompletion.state.filesListingState)
        assertEquals(takeover, staleCompletion.state.operation?.token)

        val takeoverProgressed = UiReducer.reduce(
            staleCompletion.state,
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Completed,
                    token = takeover,
                    title = OperationTitle.ReadScan,
                ),
            ),
        )
        val takeoverDone = UiReducer.reduce(
            takeoverProgressed.state,
            UiIntent.OperationCompletionEffects(
                token = takeover,
                attachmentGeneration = device.attachGeneration,
                title = OperationTitle.ReadScan,
                state = OperationState.Completed,
            ),
        )
        assertEquals(FilesListingState.Loading, takeoverDone.state.filesListingState)
        assertEquals("/ISO", (takeoverDone.effects.single() as UiEffect.LoadFiles).path)
    }

    @Test
    fun failedMutationStillInvalidatesTheListing() {
        val token = OperationTokenGenerator.next()
        val running =
            ready(path = "/ISO", files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 1uL)))
                .copy(
                    operation =
                        OperationProgress(
                            state = OperationState.Running,
                            token = token,
                            title = OperationTitle.DeleteEntry("old.bin"),
                        ),
                )

        val progressed = UiReducer.reduce(
            running,
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Failed,
                    token = token,
                    title = OperationTitle.DeleteEntry("old.bin"),
                    errorMessage = "delete failed",
                ),
            ),
        )
        val failed = UiReducer.reduce(
            progressed.state,
            UiIntent.OperationCompletionEffects(
                token = token,
                attachmentGeneration = device.attachGeneration,
                title = OperationTitle.DeleteEntry("old.bin"),
                state = OperationState.Failed,
            ),
        )
        assertTrue(failed.state.files.isEmpty())
        assertEquals(FilesListingState.Loading, failed.state.filesListingState)
        assertEquals(running.filesListGeneration + 1L, failed.state.filesListGeneration)
        assertEquals("/ISO", (failed.effects.single() as UiEffect.LoadFiles).path)
    }

    @Test
    fun mutationOnAnotherTabDefersReloadUntilEntry() {
        val token = OperationTokenGenerator.next()
        val running =
            ready(path = "/ISO", files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 1uL)))
                .copy(
                    tab = ShellTab.Ventoy,
                    operation =
                        OperationProgress(
                            state = OperationState.Running,
                            token = token,
                            title = OperationTitle.DeleteEntry("old.bin"),
                        ),
                )

        val progressed =
            UiReducer.reduce(
                running,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Partial,
                        token = token,
                        title = OperationTitle.DeleteEntry("old.bin"),
                        errorMessage = "partial",
                    ),
                ),
            )
        val failed = UiReducer.reduce(
            progressed.state,
            UiIntent.OperationCompletionEffects(
                token = token,
                attachmentGeneration = device.attachGeneration,
                title = OperationTitle.DeleteEntry("old.bin"),
                state = OperationState.Partial,
            ),
        )
        assertTrue(failed.effects.isEmpty())
        assertTrue(failed.state.files.isEmpty())
        assertEquals(FilesListingState.Stale, failed.state.filesListingState)
        assertEquals(running.filesListGeneration + 1L, failed.state.filesListGeneration)
    }

    @Test
    fun probeStatesToReadyLoadFilesOnTheDefaultTab() {
        for (probePresence in listOf(DevicePresence.Busy, DevicePresence.PermissionRequired)) {
            val probe =
                ready(
                    path = "/ISO",
                    files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 1uL)),
                ).copy(presence = probePresence)

            val readyState =
                UiReducer.reduce(
                    probe,
                    UiIntent.DeviceChanged(
                        presence = DevicePresence.Ready,
                        device = device,
                        partitions = listOf(data, other),
                        files = emptyList(),
                    ),
                )

            // The old state could not act; the state produced by this intent can, so the
            // list must be requested instead of showing "empty folder" after attach.
        assertEquals(FilesListingState.Loading, readyState.state.filesListingState)
            assertEquals("/ISO", (readyState.effects.single() as UiEffect.LoadFiles).path)
            assertEquals(probe.filesListGeneration + 1L, readyState.state.filesListGeneration)
        }
    }

    @Test
    fun runningOperationStillBlocksTheReadyLoad() {
        val probe =
            ready(path = "/ISO", files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 1uL)))
                .copy(
                    presence = DevicePresence.Busy,
                    operation =
                        OperationProgress(
                            token = OperationTokenGenerator.next(),
                            state = OperationState.Running,
                            title = OperationTitle.Upload,
                        ),
                )

        val readyState =
            UiReducer.reduce(
                probe,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(data, other),
                    files = emptyList(),
                ),
            )

        assertTrue(readyState.effects.isEmpty())
        assertEquals(FilesListingState.Stale, readyState.state.filesListingState)
    }
}
