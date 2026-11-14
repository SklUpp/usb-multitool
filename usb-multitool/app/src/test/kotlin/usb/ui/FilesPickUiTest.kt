package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.service.OperationTokenGenerator
import usb.fs.FilesystemId

/**
 * The simplified Files picker is one pinned session over the existing Files listing.
 * These checks lock the safe-navigation rules, the attachment/geometry pin, and the
 * "auto-select only after a successful copy" promise.
 */
class FilesPickUiTest {
    private val device =
        DeviceIdentity(
            label = "Ventoy Stick",
            capacityBytes = 32_000_000_000uL,
            serialNumber = "PICK-1",
            attachGeneration = 4L,
        )
    private val data =
        PartitionTab(
            id = "1",
            name = "Ventoy",
            readOnly = false,
            startLba = 2048uL,
            sectorCount = 4_000_000uL,
            filesystemId = FilesystemId.FAT,
        )
    private val efi =
        PartitionTab(
            id = "2",
            name = "VTOYEFI",
            readOnly = true,
            isEfiType = true,
            startLba = 4_002_048uL,
            sectorCount = 65_536uL,
            filesystemId = FilesystemId.FAT,
        )

    private fun ready(
        path: String = "/ISO",
        files: List<FileEntry> =
            listOf(
                FileEntry("win11.iso", isDirectory = false, sizeBytes = 5_000_000_000uL),
                FileEntry("ventoy.json", isDirectory = false, sizeBytes = 2_048uL),
                FileEntry("images", isDirectory = true, sizeBytes = 0uL),
            ),
        partitions: List<PartitionTab> = listOf(data, efi),
        tab: ShellTab = ShellTab.Ventoy,
    ): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = partitions,
            selectedPartitionId = "1",
            currentPath = path,
            files = files,
            tab = tab,
        )

    private fun begun(
        purpose: FilesPickPurpose = FilesPickPurpose.WindowsImage,
        returnTab: ShellTab = ShellTab.Ventoy,
        state: AppUiState = ready(),
        path: String = "/ISO",
    ): AppUiState {
        val opened =
            UiReducer.reduce(
                state,
                UiIntent.BeginFilesPick(
                    purpose = purpose,
                    returnTab = returnTab,
                    initialPath = path,
                ),
            ).state
        return opened.copy(
            files = ready().files,
            filesListingState = FilesListingState.Loaded,
            filesError = null,
        )
    }

    @Test
    fun beginPinsAttachmentPartitionAndGeometry() {
        val result =
            UiReducer.reduce(
                ready(path = "/old"),
                UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.WindowsImage,
                    returnTab = ShellTab.Ventoy,
                ),
            )

        val mode = requireNotNull(result.state.filesPickMode)
        assertEquals(ShellTab.Files, result.state.tab)
        assertEquals("1", result.state.selectedPartitionId)
        assertEquals("/", result.state.currentPath)
        assertEquals(device.identity, mode.identity)
        assertEquals(device.attachGeneration, mode.attachGeneration)
        assertEquals("1", mode.partitionId)
        assertEquals(2048uL, mode.startLba)
        assertEquals(4_000_000uL, mode.sectorCount)
        assertEquals(ShellTab.Ventoy, mode.returnTab)
        assertEquals(UiEffect.LoadFiles(path = "/"), result.effects.single())
    }
    @Test
    fun phonePickerFailureClosesPhoneLaunchButFilesFailureKeepsFilesPicker() {
        val phoneOpened =
            UiReducer.reduce(
                ready(),
                UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.WindowsImage,
                    returnTab = ShellTab.Ventoy,
                    launchSource = FilesPickLaunchSource.Phone,
                ),
            ).state
        val phoneSession = requireNotNull(phoneOpened.filesPickMode).sessionId
        val phoneFailed =
            UiReducer.reduce(
                phoneOpened,
                UiIntent.WindowsImportPickerFinished(
                    pickSessionId = phoneSession,
                    cancelled = false,
                    failure = UiMessageKey.ImportPickerUnavailable,
                    launchSource = FilesPickLaunchSource.Phone,
                ),
            )
        assertNull(phoneFailed.state.filesPickMode)
        assertEquals(ShellTab.Ventoy, phoneFailed.state.tab)
        assertEquals(
            UiMessage.Key(UiMessageKey.ImportPickerUnavailable),
            phoneFailed.state.statusMessage,
        )

        val filesOpened =
            UiReducer.reduce(
                ready(),
                UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.WindowsImage,
                    returnTab = ShellTab.Ventoy,
                ),
            ).state
        val filesSession = requireNotNull(filesOpened.filesPickMode).sessionId
        val filesFailed =
            UiReducer.reduce(
                filesOpened,
                UiIntent.WindowsImportPickerFinished(
                    pickSessionId = filesSession,
                    cancelled = false,
                    failure = UiMessageKey.ImportPickerUnavailable,
                    launchSource = FilesPickLaunchSource.Files,
                ),
            )
        assertNotNull(filesFailed.state.filesPickMode)
        assertEquals(ShellTab.Files, filesFailed.state.tab)
        assertEquals(
            UiMessage.Key(UiMessageKey.ImportPickerUnavailable),
            filesFailed.state.statusMessage,
        )
    }


    @Test
    fun beginFailsClosedWithoutPinnedPartition() {
        val empty = ready(partitions = emptyList())
        val result =
            UiReducer.reduce(
                empty,
                UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.VentoyConfig,
                    returnTab = ShellTab.Ventoy,
                ),
            )

        assertNull(result.state.filesPickMode)
        assertEquals(
            UiMessage.Key(UiMessageKey.VentoyDataPartitionMissing),
            result.state.statusMessage,
        )

        val missingId =
            UiReducer.reduce(
                ready(),
                UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.RawSource,
                    returnTab = ShellTab.Partitions,
                    partitionId = "9",
                ),
            )
        assertNull(missingId.state.filesPickMode)
        assertEquals(
            UiMessage.Key(UiMessageKey.SelectPartitionFirst),
            missingId.state.statusMessage,
        )

        val unselected =
            UiReducer.reduce(
                ready().copy(selectedPartitionId = null),
                UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.RawSource,
                    returnTab = ShellTab.Partitions,
                ),
            )
        assertNull(unselected.state.filesPickMode)
        assertEquals(
            UiMessage.Key(UiMessageKey.SelectPartitionFirst),
            unselected.state.statusMessage,
        )
    }

    @Test
    fun beginRefusesReadOnlyImageTarget() {
        val result =
            UiReducer.reduce(
                ready(),
                UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.WindowsImage,
                    returnTab = ShellTab.Ventoy,
                    partitionId = "2",
                ),
            )

        assertNull(result.state.filesPickMode)
        assertTrue(result.state.statusMessage is UiMessage.Key)
        assertEquals(
            UiMessageKey.ReadOnlyPartition,
            (result.state.statusMessage as UiMessage.Key).key,
        )
    }

    @Test
    fun navigationCannotTraverseOutsideOrSwitchPartition() {
        val state = begun()
        val traversal = UiReducer.reduce(state, UiIntent.OpenDirectory("/../EFI"))

        assertEquals("/ISO", traversal.state.currentPath)
        assertTrue(traversal.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            traversal.state.statusMessage,
        )

        val partition = UiReducer.reduce(state, UiIntent.SelectPartition("2"))
        assertEquals("1", partition.state.selectedPartitionId)
        assertNull(partition.state.filesPickMode?.takeIf { it.partitionId != "1" })
    }

    @Test
    fun pickingExistingImageReturnsToVentoyWithPinnedSelection() {
        val result = UiReducer.reduce(begun(), UiIntent.ConfirmFilesPickFile("win11.iso"))

        assertNull(result.state.filesPickMode)
        assertEquals(ShellTab.Ventoy, result.state.tab)
        assertTrue(result.effects.isEmpty())
        val selection = requireNotNull(result.state.filesPickSelection(FilesPickPurpose.WindowsImage))
        assertEquals("/ISO/win11.iso", selection.path)
        assertEquals("win11.iso", selection.name)
        assertEquals(5_000_000_000uL, selection.sizeBytes)
        assertFalse(selection.isDirectory)
        assertEquals("1", selection.partitionId)
        assertEquals(2048uL, selection.startLba)
        assertEquals(4_000_000uL, selection.sectorCount)
        assertEquals(device.attachGeneration, selection.attachGeneration)
    }

    @Test
    fun missingOrDirectoryEntryIsNeverSelected() {
        val state = begun()
        val missing = UiReducer.reduce(state, UiIntent.ConfirmFilesPickFile("missing.iso"))
        assertNotNull(missing.state.filesPickMode)
        assertNull(missing.state.filesPickSelection(FilesPickPurpose.WindowsImage))

        val directory = UiReducer.reduce(state, UiIntent.ConfirmFilesPickFile("images"))
        assertNotNull(directory.state.filesPickMode)
        assertNull(directory.state.filesPickSelection(FilesPickPurpose.WindowsImage))
    }

    @Test
    fun confirmationIsRejectedWhileListingIsPendingOrFailed() {
        val loading =
            UiReducer.reduce(
                begun().copy(filesListingState = FilesListingState.Loading),
                UiIntent.ConfirmFilesPickFile("win11.iso"),
            )
        assertNotNull(loading.state.filesPickMode)
        assertNull(loading.state.filesPickSelection(FilesPickPurpose.WindowsImage))
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            loading.state.statusMessage,
        )

        val failed =
            UiReducer.reduce(
                begun().copy(filesListingState = FilesListingState.Failed, filesError = "read failed"),
                UiIntent.ConfirmFilesPickFile("win11.iso"),
            )
        assertNotNull(failed.state.filesPickMode)
        assertNull(failed.state.filesPickSelection(FilesPickPurpose.WindowsImage))
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            failed.state.statusMessage,
        )
    }

    @Test
    fun rawSourcePickReturnsPathAndStagesThroughExistingEffect() {
        val state = begun(purpose = FilesPickPurpose.RawSource, returnTab = ShellTab.Partitions)
        val result = UiReducer.reduce(state, UiIntent.ConfirmFilesPickFile("win11.iso"))

        assertEquals(ShellTab.Partitions, result.state.tab)
        val selection = requireNotNull(result.state.filesPickSelection(FilesPickPurpose.RawSource))
        assertEquals("/ISO/win11.iso", selection.path)
        val effect = result.effects.single() as UiEffect.StageUsbImageForRawWrite
        assertEquals("/ISO", effect.path)
        assertEquals("win11.iso", effect.name)
        assertEquals("1", effect.binding?.partitionId)
        assertEquals(2048uL, effect.binding?.startLba)
        assertEquals(4_000_000uL, effect.binding?.sectorCount)
        assertEquals(
            UiMessage.Key(UiMessageKey.StagingFile, listOf("win11.iso")),
            result.state.statusMessage,
        )
    }

    @Test
    fun cancelledPickWritesNothingAndReturns() {
        val state = begun().copy(isoImportSafInFlight = 7L)
        val session = requireNotNull(state.filesPickMode).sessionId
        val result = UiReducer.reduce(state, UiIntent.CancelFilesPick)

        assertNull(result.state.filesPickMode)
        assertEquals(ShellTab.Ventoy, result.state.tab)
        assertNull(result.state.isoImportSafInFlight)
        assertEquals(
            listOf(
                UiEffect.CancelIsoImportInspection,
                UiEffect.CancelPendingWindowsImport(session),
            ),
            result.effects,
        )
        assertTrue(result.state.filesPickSession != state.filesPickSession)
        assertEquals(state.isoImportGeneration + 1, result.state.isoImportGeneration)
        assertTrue(result.state.filesPickSelections.isEmpty())
    }

    @Test
    fun cancelledPickerDisownsALateSafResult() {
        val cancelled = UiReducer.reduce(begun(), UiIntent.CancelFilesPick).state
        val late =
            UiReducer.reduce(
                cancelled,
                UiIntent.RequestImportWindowsIso(
                    uri = "content://late/win11.iso",
                    kind = WindowsImportKind.Image,
                    directory = "/ISO",
                ),
            )

        assertTrue(late.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            late.state.statusMessage,
        )
    }

    @Test
    fun anyOtherTabCancelsThePickButRunningCopyKeepsIt() {
        val cancelled = UiReducer.reduce(begun(), UiIntent.SelectTab(ShellTab.Partitions))
        assertNull(cancelled.state.filesPickMode)
        assertEquals(ShellTab.Partitions, cancelled.state.tab)

        val runningToken = OperationTokenGenerator.next()
        val running =
            UiReducer.reduce(
                begun(),
                UiIntent.OperationBegan(
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.ImportTarget("win11.iso"),
                        token = runningToken,
                        bytesDone = 1L,
                        bytesTotal = 10L,
                    ),
                ),
            )
        val blocked = UiReducer.reduce(running.state, UiIntent.SelectTab(ShellTab.Partitions))
        assertNotNull(blocked.state.filesPickMode)
        assertEquals(ShellTab.Files, blocked.state.tab)
        assertEquals(
            UiMessage.Key(UiMessageKey.WaitForOperation),
            blocked.state.statusMessage,
        )
    }

    @Test
    fun successfulCopySelectsAndPartialNeverDoes() {
        val copyState = begun()
        val copy =
            UiReducer.reduce(
                copyState,
                UiIntent.FilesPickCopySucceeded(
                    path = "/ISO/win11.iso",
                    sizeBytes = 1234L,
                    pickSessionId = copyState.filesPickMode?.sessionId,
                ),
            )
        assertEquals(ShellTab.Ventoy, copy.state.tab)
        assertNull(copy.state.filesPickMode)
        val selected = requireNotNull(copy.state.filesPickSelection(FilesPickPurpose.WindowsImage))
        assertEquals("/ISO/win11.iso", selected.path)
        assertEquals(1234uL, selected.sizeBytes)

        val partialToken = OperationTokenGenerator.next()
        val partialState =
            UiReducer.reduce(
                begun(),
                UiIntent.OperationBegan(
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.ImportTarget("win11.iso"),
                        token = partialToken,
                    ),
                ),
            ).state
        val partial = reduceTerminalOperation(
            partialState,
            OperationProgress(
                state = OperationState.Partial,
                title = OperationTitle.ImportTarget("win11.iso"),
                token = partialToken,
                bytesDone = 10L,
                bytesTotal = 100L,
            ),
        )
        assertNotNull(partial.state.filesPickMode)
        assertNull(partial.state.filesPickSelection(FilesPickPurpose.WindowsImage))
    }

    @Test
    fun copyOutsideThePinnedFolderIsRejected() {
        val state = begun()
        val result =
            UiReducer.reduce(
                state,
                UiIntent.FilesPickCopySucceeded(
                    path = "/other/win11.iso",
                    sizeBytes = 10L,
                    pickSessionId = state.filesPickMode?.sessionId,
                ),
            )

        assertNotNull(result.state.filesPickMode)
        assertNull(result.state.filesPickSelection(FilesPickPurpose.WindowsImage))
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun anotherAttachmentOrChangedGeometryDropsPinAndSelections() {
        val selected = UiReducer.reduce(begun(), UiIntent.ConfirmFilesPickFile("win11.iso")).state
        val other = device.copy(label = "Other Stick", attachGeneration = 5L)
        val replaced =
            UiReducer.reduce(
                selected,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = other,
                    partitions = listOf(data, efi),
                ),
            )
        assertNull(replaced.state.filesPickMode)
        assertTrue(replaced.state.filesPickSelections.isEmpty())

        val moved =
            UiReducer.reduce(
                UiReducer.reduce(begun(), UiIntent.OpenDirectory("/")).state,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions =
                        listOf(
                            data.copy(sectorCount = data.sectorCount!! + 1uL),
                            efi,
                        ),
                ),
            )
        assertNull(moved.state.filesPickMode)
    }

    @Test
    fun sameStickProbeKeepsTheSession() {
        val result =
            UiReducer.reduce(
                begun(),
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device,
                    partitions = emptyList(),
                ),
            )

        assertNotNull(result.state.filesPickMode)
        assertEquals("1", result.state.filesPickMode?.partitionId)
    }

    @Test
    fun tabExitDisownsSafAndInvalidatesSession() {
        val state = begun().copy(isoImportSafInFlight = 7L)
        val result = UiReducer.reduce(state, UiIntent.SelectTab(ShellTab.Partitions))

        assertEquals(ShellTab.Partitions, result.state.tab)
        assertNull(result.state.filesPickMode)
        assertNull(result.state.isoImportSafInFlight)
        assertTrue(result.state.filesPickSession != state.filesPickSession)
        assertTrue(result.effects.contains(UiEffect.CancelIsoImportInspection))
    }

    @Test
    fun oldPickerSessionCannotCopyIntoTheNextSession() {
        val first = begun()
        val staleToken = requireNotNull(first.filesPickMode).sessionId
        val cancelled = UiReducer.reduce(first, UiIntent.CancelFilesPick).state
        val second = begun(state = cancelled)
        val currentToken = requireNotNull(second.filesPickMode).sessionId
        assertTrue(currentToken != staleToken)

        val late =
            UiReducer.reduce(
                second,
                UiIntent.RequestImportWindowsIso(
                    uri = "content://old/win11.iso",
                    kind = WindowsImportKind.Image,
                    directory = "/ISO",
                    pickSessionId = staleToken,
                ),
            )
        assertTrue(late.effects.isEmpty())
        assertEquals(currentToken, late.state.filesPickMode?.sessionId)
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            late.state.statusMessage,
        )

        val accepted =
            UiReducer.reduce(
                second,
                UiIntent.RequestImportWindowsIso(
                    uri = "content://new/win11.iso",
                    kind = WindowsImportKind.Image,
                    directory = "/ISO",
                    pickSessionId = currentToken,
                ),
            )
        val inspect = accepted.effects.single() as UiEffect.InspectIsoImport
        assertEquals(currentToken, inspect.pickSessionId)
        assertEquals("/ISO", inspect.importDirectory)
    }

    @Test
    fun lateInspectedFromPreviousSessionIsDropped() {
        val first = begun()
        val oldToken = requireNotNull(first.filesPickMode).sessionId
        val cancelled = UiReducer.reduce(first, UiIntent.CancelFilesPick).state
        val second = begun(state = cancelled)
        val newToken = requireNotNull(second.filesPickMode).sessionId
        val requested =
            UiReducer.reduce(
                second,
                UiIntent.RequestImportWindowsIso(
                    uri = "content://new/win11.iso",
                    kind = WindowsImportKind.Image,
                    directory = "/ISO",
                    pickSessionId = newToken,
                ),
            ).state

        val stale =
            UiReducer.reduce(
                requested,
                UiIntent.WindowsIsoImportInspected(
                    uri = "content://old/win11.iso",
                    name = "old.iso",
                    sizeBytes = 10L,
                    targetName = "old.iso",
                    exists = false,
                    generation = requested.isoImportGeneration,
                    directory = "/ISO",
                    pickSessionId = oldToken,
                ),
            )

        assertNull(stale.state.pendingIsoImport)
        assertTrue(stale.state.isoImportSafInFlight == null)
        assertEquals(newToken, stale.state.filesPickMode?.sessionId)
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            stale.state.statusMessage,
        )
        assertTrue(stale.effects.contains(UiEffect.CancelIsoImportInspection))
    }

    @Test
    fun geometryChangeInFlightInvalidatesPinButKeepsUsbGuardUntilClose() {
        val state =
            begun().copy(
                isoImportSafInFlight = 7L,
                isoImportUsbInFlight = 7L,
            )
        val result =
            UiReducer.reduce(
                state,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions =
                        listOf(
                            data.copy(sectorCount = data.sectorCount!! + 1uL),
                            efi,
                        ),
                ),
            )

        assertNull(result.state.filesPickMode)
        assertTrue(result.state.filesPickSession != state.filesPickSession)
        assertNull(result.state.isoImportSafInFlight)
        assertEquals(7L, result.state.isoImportUsbInFlight)
        assertEquals(state.isoImportGeneration + 1, result.state.isoImportGeneration)
        assertTrue(result.effects.contains(UiEffect.CancelIsoImportInspection))
    }

    @Test
    fun staleCopySuccessCannotSelectIntoSamePathInNewSession() {
        val first = begun()
        val staleToken = requireNotNull(first.filesPickMode).sessionId
        val cancelled = UiReducer.reduce(first, UiIntent.CancelFilesPick).state
        val second = begun(state = cancelled)
        val result =
            UiReducer.reduce(
                second,
                UiIntent.FilesPickCopySucceeded(
                    path = "/ISO/win11.iso",
                    sizeBytes = 10L,
                    pickSessionId = staleToken,
                ),
            )

        assertNotNull(result.state.filesPickMode)
        assertNull(result.state.filesPickSelection(FilesPickPurpose.WindowsImage))
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun secondLaunchIsBlockedAndDuplicateResultCannotStealNewContext() {
        val queue = WindowsImportLaunchQueue()
        val first = queue.imageLaunch("/ISO", "session-a")
        assertTrue(
            queue.begin(
                WindowsImportKind.Image,
                "/other",
                "session-b",
                identity = "stick-a",
                attachGeneration = 4L,
            ) is WindowsImportLaunch.Busy,
        )
        assertEquals(first, queue.pending)
        assertEquals("session-a", queue.pending?.pickSessionId)

        assertEquals(first, queue.take(first.requestCode))
        // A duplicate/late result for the old code must not consume the next launch.
        val second = queue.imageLaunch("/other", "session-b")
        assertTrue(second.requestCode != first.requestCode)
        assertNull(queue.take(first.requestCode))
        assertEquals(second, queue.pending)
        assertEquals(second, queue.take(second.requestCode))
        assertNull(queue.take(second.requestCode))

        // Rotation restores the context and still blocks a second launch.
        queue.restore(second)
        assertTrue(
            queue.begin(WindowsImportKind.XmlTemplate, "/ventoy", null, "stick-a", 4L)
                is WindowsImportLaunch.Busy,
        )
        assertEquals(second, queue.take(second.requestCode))
    }

    @Test
    fun newInstanceAfterRestoreNeverReusesTheRestoredCode() {
        val codes = WindowsImportRequestCodes(InMemoryRequestCodeStorage())
        val first = WindowsImportLaunchQueue(codes)
        val restored = first.imageLaunch("/ISO", "session-a")
        assertNull(first.take(restored.requestCode + 1))
        first.resetByRequestCode(restored.requestCode)

        // Rotation/process death: the durable allocator is shared, not rebuilt from a
        // per-instance snapshot.
        val second = WindowsImportLaunchQueue(codes)
        assertTrue(second.restore(restored))
        assertEquals(restored, second.take(restored.requestCode))

        val next = second.imageLaunch("/ISO", "session-a")
        assertTrue(next.requestCode > restored.requestCode)
        assertNull(second.take(restored.requestCode))
    }

    @Test
    fun exhaustedRangeRefusesLaunchInsteadOfWrapping() {
        val codes =
            WindowsImportRequestCodes(
                InMemoryRequestCodeStorage(WindowsImportRequestCodes.LAST_REQUEST_CODE),
            )
        val last = WindowsImportLaunchQueue(codes)
        val final = last.imageLaunch("/ISO", "session-a")
        assertEquals(WindowsImportRequestCodes.LAST_REQUEST_CODE, final.requestCode)
        last.resetByRequestCode(final.requestCode)
        assertTrue(
            last.begin(WindowsImportKind.Image, "/ISO", "session-a", "stick-a", 4L)
                is WindowsImportLaunch.Exhausted,
        )

        // A restored last-code context also stays exhausted once its pending context
        // is retired; while one is pending, begin reports Busy first.
        val restored = WindowsImportLaunchQueue(codes)
        assertTrue(restored.restore(final))
        assertTrue(
            restored.begin(WindowsImportKind.Image, "/ISO", "session-a", "stick-a", 4L)
                is WindowsImportLaunch.Busy,
        )
        assertTrue(restored.resetByRequestCode(final.requestCode))
        assertTrue(
            restored.begin(WindowsImportKind.Image, "/ISO", "session-a", "stick-a", 4L)
                is WindowsImportLaunch.Exhausted,
        )
    }

    @Test
    fun storageFailureDoesNotLaunchOrReuseTheUnwrittenCode() {
        val codes = WindowsImportRequestCodes(InMemoryRequestCodeStorage(failWrites = true))
        val queue = WindowsImportLaunchQueue(codes)

        assertTrue(
            queue.begin(WindowsImportKind.Image, "/ISO", "session-a", "stick-a", 4L)
                is WindowsImportLaunch.StorageFailed,
        )
        assertNull(queue.pending)
    }

    @Test
    fun restoreFailsClosedWhenTheMarkCannotBeRaised() {
        val storage = InMemoryRequestCodeStorage()
        val queue = WindowsImportLaunchQueue(WindowsImportRequestCodes(storage))
        val context =
            PendingWindowsImport(
                kind = WindowsImportKind.XmlTemplate,
                directory = "/ventoy",
                pickSessionId = null,
                identity = "stick-a",
                attachGeneration = 4L,
                requestCode = WindowsImportRequestCodes.FIRST_REQUEST_CODE + 2,
            )
        storage.failWrites = true

        assertFalse(queue.restore(context))
        assertNull(queue.pending)
    }

    @Test
    fun twoActivityQueuesSharingTheAllocatorNeverGetTheSameCode() {
        val codes = WindowsImportRequestCodes(InMemoryRequestCodeStorage())
        val first = WindowsImportLaunchQueue(codes)
        val second = WindowsImportLaunchQueue(codes)
        val firstPool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val secondPool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val futures =
                (1..40).map { index ->
                    val queue = if (index % 2 == 0) first else second
                    val pool = if (index % 2 == 0) firstPool else secondPool
                    pool.submit(
                        java.util.concurrent.Callable {
                            val start =
                                queue.begin(
                                    WindowsImportKind.Image,
                                    "/ISO",
                                    "session-$index",
                                    "stick-a",
                                    4L,
                                )
                            val code =
                                (start as WindowsImportLaunch.Starting).context.requestCode
                            queue.resetByRequestCode(code)
                            code
                        },
                    )
                }
            val codesIssued = futures.map { it.get() }
            assertEquals(codesIssued.size, codesIssued.toSet().size)
        } finally {
            firstPool.shutdown()
            secondPool.shutdown()
        }
    }

    @Test
    fun pickerTimeoutResetRetiresOnlyItsOwnLaunch() {
        val queue = WindowsImportLaunchQueue()
        val stale = queue.xmlLaunch()
        // A reset for another code must not free this launch.
        queue.resetByRequestCode(stale.requestCode + 1)
        assertEquals(stale, queue.pending)
        queue.resetByRequestCode(stale.requestCode)
        assertNull(queue.pending)
        val next = queue.xmlLaunch()
        assertTrue(next.requestCode != stale.requestCode)
        assertNull(queue.take(stale.requestCode))
        assertEquals(next, queue.take(next.requestCode))
    }

    @Test
    fun lateSessionCancelDoesNotClearANewerPendingLaunch() {
        val queue = WindowsImportLaunchQueue()
        val old = queue.imageLaunch("/ISO", "session-old")
        assertFalse(queue.resetByPickSession("session-new"))
        assertEquals(old, queue.pending)

        queue.resetByPickSession("session-old")
        assertNull(queue.pending)
        val newer = queue.imageLaunch("/other", "session-new")
        assertFalse(queue.resetByPickSession("session-old"))
        assertEquals(newer, queue.pending)
        assertEquals(newer, queue.take(newer.requestCode))
    }

    private fun WindowsImportLaunchQueue.imageLaunch(
        directory: String,
        session: String,
    ): PendingWindowsImport =
        (begin(WindowsImportKind.Image, directory, session, "stick-a", 4L)
            as WindowsImportLaunch.Starting).context

    private fun WindowsImportLaunchQueue.xmlLaunch(): PendingWindowsImport =
        (begin(WindowsImportKind.XmlTemplate, "/ventoy", null, "stick-a", 4L)
            as WindowsImportLaunch.Starting).context

    @Test
    fun localFinishCancelsLiveInspectionAndKeepsUsbGuard() {
        val state =
            begun().copy(
                isoImportSafInFlight = 7L,
                isoImportUsbInFlight = 7L,
            )
        val result = UiReducer.reduce(state, UiIntent.ConfirmFilesPickFile("win11.iso"))

        assertNull(result.state.filesPickMode)
        assertNotNull(result.state.filesPickSelection(FilesPickPurpose.WindowsImage))
        assertNull(result.state.isoImportSafInFlight)
        assertEquals(7L, result.state.isoImportUsbInFlight)
        assertEquals(state.isoImportGeneration + 1, result.state.isoImportGeneration)
        assertTrue(result.effects.contains(UiEffect.CancelIsoImportInspection))
    }

    @Test
    fun selectionsAreKeptPerPurpose() {
        val image =
            UiReducer.reduce(begun(), UiIntent.ConfirmFilesPickFile("win11.iso")).state
        val openedConfig =
            UiReducer.reduce(
                image,
                UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.VentoyConfig,
                    returnTab = ShellTab.Ventoy,
                    initialPath = "/ISO",
                ),
            ).state
        val configState =
            openedConfig.copy(files = ready().files, filesListingState = FilesListingState.Loaded)
        val config =
            UiReducer.reduce(configState, UiIntent.ConfirmFilesPickFile("ventoy.json")).state

        assertEquals(
            "/ISO/win11.iso",
            config.filesPickSelection(FilesPickPurpose.WindowsImage)?.path,
        )
        assertEquals(
            "/ISO/ventoy.json",
            config.filesPickSelection(FilesPickPurpose.VentoyConfig)?.path,
        )
        val cleared =
            UiReducer.reduce(
                config,
                UiIntent.ClearFilesPickSelection(FilesPickPurpose.VentoyConfig),
            ).state
        assertNull(cleared.filesPickSelection(FilesPickPurpose.VentoyConfig))
        assertNotNull(cleared.filesPickSelection(FilesPickPurpose.WindowsImage))
    }
}
