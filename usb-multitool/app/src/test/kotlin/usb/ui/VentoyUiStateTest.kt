package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendState
import usb.ventoy.BindProblem
import usb.ventoy.FolderReadProblem
import usb.ventoy.UnreadableProblem

/**
 * Ventoy UI state: same-stick retain (identity+attachGeneration), Probing clear, SelectPartition
 * stick-scope, ToggleWindowsImage against rendered autounattend list.
 *
 * Host attach must stamp attachGeneration on Busy/Permission; Ventoy tab alone mounts catalogs.
 * These tests cover reducer contracts only — not MainActivity mount sequencing.
 */
class VentoyUiStateTest {
    private val pathA = "/ISO/win11.iso"
    private val pathB = "/ISO/win10.iso"
    private val pathBound = "/ISO/bound.iso"
    private val pathGone = "/ISO/gone.iso"
    private val template = "/ventoy/autounattend.xml"

    private fun partition(id: String = "1", readOnly: Boolean = false) = PartitionTab(
        id = id,
        name = if (readOnly) "VTOYEFI" else "Ventoy",
        readOnly = readOnly,
        startLba = if (readOnly) 100uL else 2048uL,
        sectorCount = if (readOnly) 64uL else 4096uL,
        filesystemId = FilesystemId.FAT,
    )

    private fun device(gen: Long = 1L, capacity: ULong = 8_000_000_000uL) =
        DeviceIdentity(label = "Test Stick", capacityBytes = capacity, attachGeneration = gen)

    private fun ready(
        gen: Long = 1L,
        partitions: List<PartitionTab> = listOf(partition()),
    ) = AppUiState(
        presence = DevicePresence.Ready,
        device = device(gen),
        partitions = partitions,
        selectedPartitionId = partitions.firstOrNull()?.id,
        diskSectorSize = 512,
    )

    private fun candidates(vararg images: AutounattendBinding) = AutounattendImageCandidates(
        images = images.toList(),
        searchScope = listOf("/"),
        scopeLimitedBy = null,
    )

    private fun withVentoy(state: AppUiState = ready()) = state.copy(
        windowsTemplatePath = template,
        windowsSelectedImages = listOf(pathA),
        autounattendState = AutounattendState.Missing,
        autounattendCandidates = candidates(
            AutounattendBinding(imagePath = pathA, bound = false),
            AutounattendBinding(imagePath = pathB, bound = false),
            AutounattendBinding(imagePath = pathBound, bound = true),
            AutounattendBinding(
                imagePath = "/ISO/bad.iso",
                bound = false,
                problem = BindProblem.OutsideScope,
            ),
        ),
        windowsIsoCandidates = listOf(
            RawSourceCandidate(path = pathA, name = "win11.iso", sizeBytes = 1L, partitionId = "1"),
        ),
        windowsIsoCandidatesGeneration = 3L,
        windowsIsoCandidatesIdentity = state.device?.identity,
        ventoyGlobalSettings = usb.ventoy.VentoyGlobalSettings.Empty,
        ventoyFolderPath = "/",
        ventoyFolders = listOf("/ISO"),
        statusMessage = UiMessage.Key(UiMessageKey.Probing),
    )

    // --- same-stick retain / different attach wipe ---

    @Test
    fun readySameStickRetainsTemplateAndCatalogs() {
        val loaded = withVentoy()
        val next = UiReducer.reduce(
            loaded,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device(gen = 1L, capacity = 9_000_000_000uL),
                partitions = listOf(partition()),
                sectorSize = 512,
            ),
        ).state
        assertEquals(template, next.windowsTemplatePath)
        assertEquals(listOf(pathA), next.windowsSelectedImages)
        assertNotNull(next.autounattendCandidates)
        assertNotNull(next.windowsIsoCandidates)
        assertNotNull(next.ventoyGlobalSettings)
        assertEquals(listOf("/ISO"), next.ventoyFolders)
        assertEquals(3L, next.windowsIsoCandidatesGeneration)
        assertNull(next.statusMessage) // Probing cleared
    }

    @Test
    fun busySameStickRetainsTemplate() {
        val loaded = withVentoy()
        val next = UiReducer.reduce(
            loaded,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Busy,
                device = device(gen = 1L),
                partitions = emptyList(),
                files = emptyList(),
            ),
        ).state
        assertEquals(DevicePresence.Busy, next.presence)
        assertEquals(template, next.windowsTemplatePath)
        assertEquals(listOf(pathA), next.windowsSelectedImages)
        assertNotNull(next.ventoyFolders)
        assertEquals(UiMessage.Key(UiMessageKey.DeviceBusy), next.statusMessage)
    }

    @Test
    fun differentAttachGenerationWipesVentoy() {
        val loaded = withVentoy(ready(gen = 1L))
        val next = UiReducer.reduce(
            loaded,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device(gen = 2L),
                partitions = listOf(partition()),
            ),
        ).state
        assertNull(next.windowsTemplatePath)
        assertTrue(next.windowsSelectedImages.isEmpty())
        assertNull(next.autounattendCandidates)
        assertNull(next.windowsIsoCandidates)
        assertNull(next.ventoyGlobalSettings)
        assertNull(next.ventoyFolders)
        assertEquals(4L, next.windowsIsoCandidatesGeneration)
    }

    @Test
    fun disconnectedWipesVentoyEvenSameIdentity() {
        val loaded = withVentoy()
        val next = UiReducer.reduce(
            loaded,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Disconnected,
                device = device(gen = 1L),
            ),
        ).state
        assertNull(next.windowsTemplatePath)
        assertNull(next.ventoyFolders)
        assertTrue(next.windowsSelectedImages.isEmpty())
    }

    @Test
    fun readyClearsOnlyProbingKeepsOtherStatus() {
        val err = ready().copy(statusMessage = UiMessage.Key(UiMessageKey.NoPartitionsFound))
        val kept = UiReducer.reduce(
            err,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device(),
                partitions = err.partitions,
            ),
        ).state
        assertEquals(UiMessage.Key(UiMessageKey.NoPartitionsFound), kept.statusMessage)
    }

    // --- SelectPartition stick-scope ---

    @Test
    fun selectPartitionDoesNotWipeVentoyOrTemplate() {
        val loaded = withVentoy(
            ready(partitions = listOf(partition("1"), partition("2"))),
        ).copy(selectedPartitionId = "1")
        val next = UiReducer.reduce(loaded, UiIntent.SelectPartition("2")).state
        assertEquals("2", next.selectedPartitionId)
        assertEquals(template, next.windowsTemplatePath)
        assertNotNull(next.autounattendCandidates)
        assertNotNull(next.ventoyFolders)
        assertNotNull(next.windowsIsoCandidates)
        assertEquals(listOf(pathA), next.windowsSelectedImages)
        // Partition-scoped file browser still resets.
        assertTrue(next.files.isEmpty())
        assertNull(next.rawSourceCandidates)
    }

    // --- ToggleWindowsImage ---

    @Test
    fun toggleAcceptsCurrentCandidateAndDeselects() {
        val base = withVentoy()
        val on = UiReducer.reduce(base, UiIntent.ToggleWindowsImage(pathB)).state
        assertEquals(listOf(pathA, pathB), on.windowsSelectedImages)
        val off = UiReducer.reduce(on, UiIntent.ToggleWindowsImage(pathA)).state
        assertEquals(listOf(pathB), off.windowsSelectedImages)
    }

    @Test
    fun toggleRejectsMissingBoundProblemAndIsoOnlyList() {
        val base = withVentoy()
        val missing = UiReducer.reduce(base, UiIntent.ToggleWindowsImage(pathGone))
        assertEquals(listOf(pathA), missing.state.windowsSelectedImages)
        assertTrue(missing.state.statusMessage is UiMessage.Raw)

        val bound = UiReducer.reduce(base, UiIntent.ToggleWindowsImage(pathBound))
        assertEquals(listOf(pathA), bound.state.windowsSelectedImages)

        val onlyIso = ready().copy(
            windowsIsoCandidates = listOf(
                RawSourceCandidate(path = pathA, name = "win11.iso", sizeBytes = 1L, partitionId = "1"),
            ),
        )
        val rejected = UiReducer.reduce(onlyIso, UiIntent.ToggleWindowsImage(pathA))
        assertTrue(rejected.state.windowsSelectedImages.isEmpty())
        assertTrue(rejected.state.statusMessage is UiMessage.Raw)
    }

    @Test
    fun toggleBlockedWhenBusy() {
        val busy = withVentoy().copy(
            operation = OperationProgress(
                state = OperationState.Running,
                title = OperationTitle.BindImagesToUnattendXml,
            ),
        )
        assertFalse(busy.actionsEnabled)
        val result = UiReducer.reduce(busy, UiIntent.ToggleWindowsImage(pathB))
        assertEquals(listOf(pathA), result.state.windowsSelectedImages)
    }

    @Test
    fun candidatesReloadDropsRemovedAndBoundSelection() {
        val selected = withVentoy().copy(windowsSelectedImages = listOf(pathA, pathB, pathGone))
        val reloaded = UiReducer.reduce(
            selected,
            UiIntent.AutounattendCandidatesLoaded(
                xmlName = template,
                candidates = candidates(
                    AutounattendBinding(imagePath = pathA, bound = true),
                    AutounattendBinding(imagePath = pathB, bound = false),
                ),
                generation = selected.autounattendRequestGeneration,
            ),
        ).state
        assertEquals(listOf(pathB), reloaded.windowsSelectedImages)
    }

    @Test
    fun requestBindUsesSelectedPaths() {
        val selected = withVentoy().copy(windowsSelectedImages = listOf(pathA, pathB))
        val result = UiReducer.reduce(
            selected,
            UiIntent.RequestBindAutounattend(
                xmlName = template,
                imagePaths = selected.windowsSelectedImages,
            ),
        )
        val effect = result.effects.single() as UiEffect.BindAutounattend
        assertEquals(listOf(pathA, pathB), effect.imagePaths)
    }

    @Test
    fun loadWindowsIsoCandidatesReadyEmitsBusyWaits() {
        val readyResult = UiReducer.reduce(ready(), UiIntent.LoadWindowsIsoCandidates)
        assertTrue(readyResult.effects.single() is UiEffect.LoadWindowsIsoCandidates)

        val busy = ready().copy(
            presence = DevicePresence.Busy,
            operation = OperationProgress(
                state = OperationState.Running,
                title = OperationTitle.ProbeDevice,
            ),
        )
        val busyResult = UiReducer.reduce(busy, UiIntent.LoadWindowsIsoCandidates)
        assertTrue(busyResult.effects.isEmpty())
        assertEquals(UiMessage.Key(UiMessageKey.WaitForOperation), busyResult.state.statusMessage)
    }

    // --- S1 invalidation after dangerous mutations ---

    @Test
    fun ventoyInstallCompletedDropsCatalogsKeepsTemplate() {
        val loaded = withVentoy().copy(ventoyCacheEpoch = 4L)
        val next = UiReducer.reduce(
            loaded,
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Completed,
                    title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                ),
            ),
        ).state
        assertEquals(template, next.windowsTemplatePath)
        assertNull(next.ventoyGlobalSettings)
        assertNull(next.ventoyFolders)
        assertNull(next.windowsIsoCandidates)
        assertNull(next.autounattendCandidates)
        assertEquals(AutounattendState.Missing, next.autounattendState)
        assertTrue(next.windowsSelectedImages.isEmpty())
        assertEquals(5L, next.ventoyCacheEpoch)
        assertEquals(
            loaded.windowsIsoCandidatesGeneration + 1,
            next.windowsIsoCandidatesGeneration,
        )
        assertEquals(
            loaded.autounattendRequestGeneration + 1,
            next.autounattendRequestGeneration,
        )
    }

    @Test
    fun rawWritePartialAlsoDropsCatalogs() {
        val next = UiReducer.reduce(
            withVentoy(),
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Partial,
                    title = OperationTitle.Destructive(DestructiveAction.RAW_WRITE),
                    errorMessage = "transport cut",
                ),
            ),
        ).state
        assertNull(next.ventoyFolders)
        assertNull(next.windowsIsoCandidates)
        assertEquals(template, next.windowsTemplatePath)
        assertEquals(AutounattendState.Missing, next.autounattendState)
    }

    @Test
    fun formatAndRestoreInvalidateLikeInstall() {
        for (action in listOf(
            DestructiveAction.FORMAT_PARTITION,
            DestructiveAction.GPT_RESTORE,
            DestructiveAction.RESCUE_RESTORE,
            DestructiveAction.VENTOY_UPDATE,
            DestructiveAction.WIPE,
        )) {
            val next = UiReducer.reduce(
                withVentoy(),
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Completed,
                        title = OperationTitle.Destructive(action),
                    ),
                ),
            ).state
            assertNull(next.ventoyGlobalSettings)
            assertNull(next.windowsIsoCandidates)
            assertEquals(AutounattendState.Missing, next.autounattendState)
        }
    }

    @Test
    fun probeCompletedDoesNotDropVentoyCaches() {
        val loaded = withVentoy()
        val next = UiReducer.reduce(
            loaded,
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Completed,
                    title = OperationTitle.ProbeDevice,
                ),
            ),
        ).state
        assertNotNull(next.ventoyFolders)
        assertNotNull(next.windowsIsoCandidates)
        assertEquals(loaded.ventoyCacheEpoch, next.ventoyCacheEpoch)
        assertEquals(template, next.windowsTemplatePath)
    }

    @Test
    fun staleIsoCandidatesLoadedAfterInvalidationDropped() {
        val loaded = withVentoy()
        val genBefore = loaded.windowsIsoCandidatesGeneration
        val afterDrop = UiReducer.reduce(
            loaded,
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Completed,
                    title = OperationTitle.Destructive(DestructiveAction.VENTOY_UPDATE),
                ),
            ),
        ).state
        assertEquals(genBefore + 1, afterDrop.windowsIsoCandidatesGeneration)
        assertNull(afterDrop.windowsIsoCandidates)

        // Late host post still carrying the pre-mutation generation must not repopulate.
        val stale = UiReducer.reduce(
            afterDrop,
            UiIntent.WindowsIsoCandidatesLoaded(
                generation = genBefore,
                identity = afterDrop.device?.identity,
                candidates = listOf(
                    RawSourceCandidate(path = pathA, name = "win11.iso", sizeBytes = 1L, partitionId = "1"),
                ),
            ),
        ).state
        assertNull(stale.windowsIsoCandidates)
    }

    @Test
    fun staleAutounattendLoadedAfterInvalidationDropped() {
        val loaded = withVentoy()
        val genBefore = loaded.autounattendRequestGeneration
        val afterDrop = UiReducer.reduce(
            loaded,
            UiIntent.InvalidateVentoyStickCaches,
        ).state
        assertEquals(genBefore + 1, afterDrop.autounattendRequestGeneration)
        assertEquals(AutounattendState.Missing, afterDrop.autounattendState)
        assertEquals(template, afterDrop.windowsTemplatePath)

        val stale = UiReducer.reduce(
            afterDrop,
            UiIntent.AutounattendLoaded(
                xmlName = template,
                state = AutounattendState.Missing,
                bindings = emptyList(),
                generation = genBefore,
            ),
        ).state
        assertEquals(AutounattendState.Missing, stale.autounattendState)
        // Matching generation still applies.
        val fresh = UiReducer.reduce(
            afterDrop,
            UiIntent.AutounattendLoaded(
                xmlName = template,
                state = AutounattendState.Missing,
                bindings = emptyList(),
                generation = afterDrop.autounattendRequestGeneration,
            ),
        ).state
        assertEquals(AutounattendState.Missing, fresh.autounattendState)
    }

    @Test
    fun afterDropLoadIsoEmitsNewGeneration() {
        val afterDrop = UiReducer.reduce(withVentoy(), UiIntent.InvalidateVentoyStickCaches).state
        val result = UiReducer.reduce(afterDrop, UiIntent.LoadWindowsIsoCandidates)
        assertTrue(result.effects.single() is UiEffect.LoadWindowsIsoCandidates)
        assertEquals(
            afterDrop.windowsIsoCandidatesGeneration + 1,
            result.state.windowsIsoCandidatesGeneration,
        )
        assertEquals(
            result.state.windowsIsoCandidatesGeneration,
            (result.effects.single() as UiEffect.LoadWindowsIsoCandidates).requestGeneration,
        )
    }

    @Test
    fun uploadCompletedInvalidatesCatalogs() {
        val next = UiReducer.reduce(
            withVentoy(),
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Completed,
                    title = OperationTitle.Upload,
                ),
            ),
        ).state
        assertNull(next.windowsIsoCandidates)
        assertNull(next.ventoyFolders)
        assertEquals(AutounattendState.Missing, next.autounattendState)
        assertEquals(template, next.windowsTemplatePath)
    }

    @Test
    fun failedAndCancelledDestructiveStillInvalidate() {
        for (terminal in listOf(OperationState.Failed, OperationState.Cancelled)) {
            val next = UiReducer.reduce(
                withVentoy(),
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = terminal,
                        title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                        errorMessage = "x",
                    ),
                ),
            ).state
            assertNull(next.ventoyGlobalSettings)
            assertNull(next.windowsIsoCandidates)
            assertEquals(AutounattendState.Missing, next.autounattendState)
        }
    }

    @Test
    fun dropClearsPendingIsoImportAndBumpsGeneration() {
        val proposal = IsoImportProposal(
            uri = "content://iso",
            name = "win.iso",
            targetName = "win.iso",
            sizeBytes = 1L,
            exists = true,
            identity = device().identity,
            attachGeneration = 1L,
            requestGeneration = 9L,
        )
        val loaded = withVentoy().copy(
            pendingIsoImport = proposal,
            isoImportGeneration = 9L,
        )
        val next = UiReducer.reduce(loaded, UiIntent.InvalidateVentoyStickCaches).state
        assertNull(next.pendingIsoImport)
        assertNull(next.isoImportError)
        assertEquals(10L, next.isoImportGeneration)
    }

    @Test
    fun staleFoldersAndSettingsLoadedDroppedByEpoch() {
        val loaded = withVentoy().copy(ventoyCacheEpoch = 2L)
        val after = UiReducer.reduce(loaded, UiIntent.InvalidateVentoyStickCaches).state
        assertEquals(3L, after.ventoyCacheEpoch)
        assertNull(after.ventoyFolders)
        assertNull(after.ventoyGlobalSettings)

        val staleFolders = UiReducer.reduce(
            after,
            UiIntent.VentoyFoldersLoaded(listOf("/old"), epoch = 2L),
        ).state
        assertNull(staleFolders.ventoyFolders)

        val staleSettings = UiReducer.reduce(
            after,
            UiIntent.VentoyGlobalSettingsLoaded(
                usb.ventoy.VentoyGlobalSettings.Empty,
                epoch = 2L,
            ),
        ).state
        assertNull(staleSettings.ventoyGlobalSettings)

        val freshFolders = UiReducer.reduce(
            after,
            UiIntent.VentoyFoldersLoaded(listOf("/ISO"), epoch = 3L),
        ).state
        assertEquals(listOf("/ISO"), freshFolders.ventoyFolders)

        val freshSettings = UiReducer.reduce(
            after,
            UiIntent.VentoyGlobalSettingsLoaded(
                usb.ventoy.VentoyGlobalSettings.Empty,
                epoch = 3L,
            ),
        ).state
        assertNotNull(freshSettings.ventoyGlobalSettings)
    }

    @Test
    fun settingsErrorStillSurfacesOnMatchingEpoch() {
        val base = ready().copy(ventoyCacheEpoch = 1L, statusMessage = null)
        val next = UiReducer.reduce(
            base,
            UiIntent.VentoyGlobalSettingsLoaded(
                usb.ventoy.VentoyGlobalSettings.ReadError("mount failed"),
                epoch = 1L,
            ),
        ).state
        assertNotNull(next.ventoyGlobalSettings)
        assertEquals(UiMessage.Raw("mount failed"), next.statusMessage)
    }

    @Test
    fun loadAutounattendSetsInFlightClearedOnLoaded() {
        val base = withVentoy().copy(
            autounattendState = AutounattendState.Missing,
            autounattendLoadInFlight = false,
        )
        val loading = UiReducer.reduce(base, UiIntent.LoadAutounattend(template))
        assertTrue(loading.state.autounattendLoadInFlight)
        assertEquals(1, loading.effects.count { it is UiEffect.LoadAutounattend })

        val done = UiReducer.reduce(
            loading.state,
            UiIntent.AutounattendLoaded(
                xmlName = template,
                state = AutounattendState.Missing,
                bindings = emptyList(),
                generation = loading.state.autounattendRequestGeneration,
            ),
        ).state
        assertFalse(done.autounattendLoadInFlight)
    }

    @Test
    fun invalidateClearsInFlightSoTabCanReload() {
        val loading = withVentoy().copy(
            autounattendState = AutounattendState.Missing,
            autounattendLoadInFlight = true,
        )
        val after = UiReducer.reduce(loading, UiIntent.InvalidateVentoyStickCaches).state
        assertFalse(after.autounattendLoadInFlight)
        assertEquals(AutounattendState.Missing, after.autounattendState)
        assertEquals(template, after.windowsTemplatePath)
    }

    @Test
    fun folderRefreshKeepsOnlyTheNewestAnswer() {
        val base = withVentoy().copy(ventoyCacheEpoch = 1L)
        val first = UiReducer.reduce(base, UiIntent.LoadVentoyFolders)
        val firstToken = first.state.ventoyFoldersRequest
        assertEquals(1L, firstToken)
        assertEquals(
            firstToken,
            (first.effects.single() as UiEffect.LoadVentoyFolders).requestToken,
        )

        val second = UiReducer.reduce(first.state, UiIntent.LoadVentoyFolders)
        val secondToken = second.state.ventoyFoldersRequest
        assertTrue(secondToken > firstToken)

        // The older manual refresh lands last: it must not replace the newer list.
        val late =
            UiReducer.reduce(
                second.state,
                UiIntent.VentoyFoldersLoaded(
                    listOf("/late"),
                    epoch = 1L,
                    requestToken = firstToken,
                ),
            )
        assertEquals(listOf("/ISO"), late.state.ventoyFolders)
        assertNull(late.state.ventoyFoldersError)

        val applied =
            UiReducer.reduce(
                late.state,
                UiIntent.VentoyFoldersLoaded(
                    listOf("/NEW"),
                    epoch = 1L,
                    requestToken = secondToken,
                ),
            )
        assertEquals(listOf("/NEW"), applied.state.ventoyFolders)
    }

    @Test
    fun settingsRefreshDropsLateErrorAndKeepsNewSuccess() {
        val base = withVentoy().copy(ventoyCacheEpoch = 1L, statusMessage = null)
        val first = UiReducer.reduce(base, UiIntent.LoadVentoyGlobalSettings)
        val firstToken = first.state.ventoyGlobalSettingsRequest
        val second = UiReducer.reduce(first.state, UiIntent.LoadVentoyGlobalSettings)
        val secondToken = second.state.ventoyGlobalSettingsRequest
        assertTrue(secondToken > firstToken)

        val late =
            UiReducer.reduce(
                second.state,
                UiIntent.VentoyGlobalSettingsLoaded(
                    usb.ventoy.VentoyGlobalSettings.ReadError("late mount failure"),
                    epoch = 1L,
                    requestToken = firstToken,
                ),
            )
        assertNotNull(late.state.ventoyGlobalSettings)
        assertNull(late.state.statusMessage)

        val applied =
            UiReducer.reduce(
                late.state,
                UiIntent.VentoyGlobalSettingsLoaded(
                    usb.ventoy.VentoyGlobalSettings.ReadError("fresh mount failure"),
                    epoch = 1L,
                    requestToken = secondToken,
                ),
            )
        assertEquals(UiMessage.Raw("fresh mount failure"), applied.state.statusMessage)
    }

    @Test
    fun reopenImportFolderPickDropsFoldersAndAsksAgain() {
        val base =
            withVentoy().copy(
                ventoyFolderPath = "/ISO/Windows",
                ventoyFolders = listOf("/ISO/Windows/11"),
            )
        val device = requireNotNull(base.device)

        val next =
            UiReducer.reduce(
                base,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            )

        assertEquals(WindowsImportKind.Image, next.state.pendingImportFolderPick)
        assertEquals("/", next.state.ventoyFolderPath)
        assertNull(next.state.ventoyFolders)
        assertNull(next.state.ventoyFoldersError)
        assertTrue(next.state.ventoyFoldersLoading)
        assertEquals(base.ventoyFoldersRequest + 1L, next.state.ventoyFoldersRequest)
        val effect = next.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(next.state.ventoyFoldersRequest, effect.requestToken)
        assertEquals("/", effect.path)
    }

    @Test
    fun reopenImportFolderPickIgnoresAnotherAttachment() {
        val base = withVentoy()
        val device = requireNotNull(base.device)

        val next =
            UiReducer.reduce(
                base,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.XmlTemplate,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration + 1L,
                    requestGeneration = 0L,
                ),
            )

        assertNull(next.state.pendingImportFolderPick)
        assertEquals(listOf("/ISO"), next.state.ventoyFolders)
        assertEquals(base.ventoyFoldersRequest, next.state.ventoyFoldersRequest)
        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun importFolderPickOpenedConsumesTheSignal() {
        val base = withVentoy().copy(pendingImportFolderPick = WindowsImportKind.XmlTemplate)

        val next = UiReducer.reduce(base, UiIntent.ImportFolderPickOpened).state

        assertNull(next.pendingImportFolderPick)
        assertNotNull(next.ventoyFolders)
    }

    @Test
    fun pickerOpenAfterDeferralReleasesTheDeferredLoad() {
        val deferred =
            withVentoy().copy(
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
            )

        val next = UiReducer.reduce(deferred, UiIntent.ImportFolderPickOpened)

        // The demand comes back for the UI (the picker was not really open yet) and the
        // deferred read starts now that mounting is allowed.
        assertEquals(WindowsImportKind.Image, next.state.pendingImportFolderPick)
        assertNull(next.state.deferredImportFolderPick)
        assertFalse(next.state.pendingImportFolderPickLoad)
        assertEquals(deferred.ventoyFoldersRequest + 1L, next.state.ventoyFoldersRequest)
        val effect = next.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(next.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun pickerOpenWhileBusyKeepsTheDeferredLoad() {
        val deferred =
            withVentoy().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
            )

        val next = UiReducer.reduce(deferred, UiIntent.ImportFolderPickOpened)

        assertNull(next.state.pendingImportFolderPick)
        assertEquals(WindowsImportKind.Image, next.state.deferredImportFolderPick)
        assertTrue(next.state.pendingImportFolderPickLoad)
        assertEquals(deferred.ventoyFoldersRequest, next.state.ventoyFoldersRequest)
        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun attachChangeClearsPendingImportFolderPick() {
        val base = withVentoy().copy(pendingImportFolderPick = WindowsImportKind.Image)

        val next =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device(gen = 2L),
                    partitions = listOf(partition()),
                ),
            ).state

        assertNull(next.pendingImportFolderPick)
    }

    @Test
    fun reopenImportFolderPickDefersTheLoadWhileTheDiskIsBusy() {
        val base =
            withVentoy().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
            )
        val device = requireNotNull(base.device)

        val next =
            UiReducer.reduce(
                base,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            )

        // No USB mount beside the operation and no picker over the busy screen; the demand
        // and the kind survive for the release step.
        assertNull(next.state.pendingImportFolderPick)
        assertEquals(WindowsImportKind.Image, next.state.deferredImportFolderPick)
        assertTrue(next.state.pendingImportFolderPickLoad)
        assertNull(next.state.ventoyFolders)
        assertEquals(base.ventoyFoldersRequest + 1L, next.state.ventoyFoldersRequest)
        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun reopenWhileRunningConsumeThenTerminalLoadsAndReopens() {
        val base =
            withVentoy().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
            )
        val device = requireNotNull(base.device)
        val deferred =
            UiReducer.reduce(
                base,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            ).state

        // The UI consumes its (absent) picker signal while the read is still deferred.
        val consumed = UiReducer.reduce(deferred, UiIntent.ImportFolderPickOpened)
        assertNull(consumed.state.pendingImportFolderPick)
        assertTrue(consumed.state.pendingImportFolderPickLoad)
        assertTrue(consumed.effects.isEmpty())

        val released =
            UiReducer.reduce(
                consumed.state,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Completed,
                        title = OperationTitle.ProbeDevice,
                    ),
                ),
            )

        assertFalse(released.state.pendingImportFolderPickLoad)
        assertNull(released.state.deferredImportFolderPick)
        assertEquals(WindowsImportKind.Image, released.state.pendingImportFolderPick)
        assertEquals(
            consumed.state.ventoyFoldersRequest + 1L,
            released.state.ventoyFoldersRequest,
        )
        val effect = released.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(released.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun confirmationDismissReleasesDeferredReopen() {
        val prompt =
            UiReducer.reduce(
                withVentoy(),
                UiIntent.RequestDestructive(DestructiveAction.WIPE),
            ).state
        assertNotNull(prompt.pendingConfirmation)
        val device = requireNotNull(prompt.device)

        val deferred =
            UiReducer.reduce(
                prompt,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.XmlTemplate,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            )

        // Never open the picker over a destructive confirmation; defer both demand and read.
        assertNull(deferred.state.pendingImportFolderPick)
        assertEquals(WindowsImportKind.XmlTemplate, deferred.state.deferredImportFolderPick)
        assertTrue(deferred.state.pendingImportFolderPickLoad)
        assertTrue(deferred.effects.isEmpty())

        val dismissed = UiReducer.reduce(deferred.state, UiIntent.DismissConfirmation)

        assertNull(dismissed.state.pendingConfirmation)
        assertFalse(dismissed.state.pendingImportFolderPickLoad)
        assertNull(dismissed.state.deferredImportFolderPick)
        assertEquals(WindowsImportKind.XmlTemplate, dismissed.state.pendingImportFolderPick)
        assertEquals(
            deferred.state.ventoyFoldersRequest + 1L,
            dismissed.state.ventoyFoldersRequest,
        )
        val effect = dismissed.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(dismissed.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun confirmDestructiveKeepsDeferredReopenForLaterRelease() {
        val prompt =
            UiReducer.reduce(
                withVentoy(),
                UiIntent.RequestDestructive(DestructiveAction.WIPE),
            ).state
        val device = requireNotNull(prompt.device)
        val deferred =
            UiReducer.reduce(
                prompt,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            ).state

        val confirmed = UiReducer.reduce(deferred, UiIntent.ConfirmDestructive)

        // Confirming starts the destructive operation: no folder mount and no picker yet.
        assertNull(confirmed.state.pendingConfirmation)
        assertNull(confirmed.state.pendingImportFolderPick)
        assertEquals(WindowsImportKind.Image, confirmed.state.deferredImportFolderPick)
        assertTrue(confirmed.state.pendingImportFolderPickLoad)
        assertTrue(confirmed.effects.none { it is UiEffect.LoadVentoyFolders })
        assertEquals(1, confirmed.effects.count { it is UiEffect.SubmitDestructive })
    }

    @Test
    fun terminalMutationKeepsDeferredPickUntilQuietIntent() {
        val base =
            withVentoy().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
            )
        val device = requireNotNull(base.device)
        val deferred =
            UiReducer.reduce(
                base,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            ).state

        val mutated =
            UiReducer.reduce(
                deferred,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Partial,
                        title = OperationTitle.DeleteEntry("gone.bin"),
                        errorMessage = "partial",
                    ),
                ),
            )

        // The same intent already mounts Files; the folder read must not run beside it, but
        // an unrelated mutation on the same attachment must not drop the demand either.
        assertNull(mutated.state.ventoyFolders)
        assertEquals(WindowsImportKind.Image, mutated.state.deferredImportFolderPick)
        assertNull(mutated.state.pendingImportFolderPick)
        assertTrue(mutated.state.pendingImportFolderPickLoad)
        assertEquals(1, mutated.effects.count { it is UiEffect.LoadFiles })
        assertTrue(mutated.effects.none { it is UiEffect.LoadVentoyFolders })

        val quiet =
            UiReducer.reduce(
                mutated.state,
                UiIntent.Status(UiMessage.Raw("settled")),
            )

        assertEquals(WindowsImportKind.Image, quiet.state.pendingImportFolderPick)
        assertNull(quiet.state.deferredImportFolderPick)
        assertFalse(quiet.state.pendingImportFolderPickLoad)
        assertEquals(
            mutated.state.ventoyFoldersRequest + 1L,
            quiet.state.ventoyFoldersRequest,
        )
        val folderLoad =
            quiet.effects.filterIsInstance<UiEffect.LoadVentoyFolders>().single()
        assertEquals(quiet.state.ventoyFoldersRequest, folderLoad.requestToken)
    }

    @Test
    fun refusedConfirmDoesNotSuppressRelease() {
        val deferred =
            withVentoy().copy(
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
            )

        val refused = UiReducer.reduce(deferred, UiIntent.ConfirmDestructive)

        // No pending action, so no operation starts and no other USB effect exists: the
        // deferred read may be released right away.
        assertNull(refused.state.pendingConfirmation)
        assertEquals(WindowsImportKind.Image, refused.state.pendingImportFolderPick)
        assertNull(refused.state.deferredImportFolderPick)
        assertFalse(refused.state.pendingImportFolderPickLoad)
        val effect = refused.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(refused.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun manualFolderLoadDeduplicatesTheDeferredRelease() {
        val deferred =
            withVentoy().copy(
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
            )

        val loaded = UiReducer.reduce(deferred, UiIntent.LoadVentoyFolders)

        // The manual refresh is the only USB work; the release must not add a second mount.
        assertEquals(1, loaded.effects.size)
        val effect = loaded.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(loaded.state.ventoyFoldersRequest, effect.requestToken)
        assertEquals(WindowsImportKind.Image, loaded.state.pendingImportFolderPick)
        assertNull(loaded.state.deferredImportFolderPick)
        assertFalse(loaded.state.pendingImportFolderPickLoad)
    }

    @Test
    fun directInspectionBlocksDeferredReleaseUntilFinished() {
        val deferred =
            withVentoy().copy(
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
                isoImportGeneration = 5L,
                isoImportUsbInFlight = 5L,
            )

        val quiet =
            UiReducer.reduce(
                deferred,
                UiIntent.Status(UiMessage.Raw("still inspecting")),
            )
        assertNull(quiet.state.pendingImportFolderPick)
        assertTrue(quiet.state.pendingImportFolderPickLoad)
        assertTrue(quiet.effects.isEmpty())

        val finished =
            UiReducer.reduce(quiet.state, UiIntent.WindowsIsoImportFinished(5L))

        assertNull(finished.state.isoImportUsbInFlight)
        assertEquals(WindowsImportKind.Image, finished.state.pendingImportFolderPick)
        assertFalse(finished.state.pendingImportFolderPickLoad)
        val effect = finished.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(finished.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun staleInspectionResultDoesNotClearLiveGuard() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportUsbInFlight = 7L,
            )

        val stale =
            UiReducer.reduce(
                base,
                UiIntent.WindowsIsoImportRejected(
                    problem = UnreadableProblem.IoFailure,
                    reason = "old failure",
                    generation = 6L,
                ),
            )

        // A result from the cancelled generation must neither overwrite the live error nor
        // end the guard of the inspection that still holds the disk.
        assertEquals(7L, stale.state.isoImportUsbInFlight)
        assertNull(stale.state.isoImportError)
        assertEquals(base.ventoyFolders, stale.state.ventoyFolders)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun cancelKeepsGuardUntilDiskCloses() {
        val inspecting =
            withVentoy().copy(
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
                isoImportGeneration = 7L,
                isoImportSafInFlight = 7L,
                isoImportUsbInFlight = 7L,
            )

        val cancelled = UiReducer.reduce(inspecting, UiIntent.CancelWindowsIsoImport)

        // UI cancel invalidates the pending dialog/result only; direct I/O may still be open.
        assertNull(cancelled.state.isoImportSafInFlight)
        assertEquals(7L, cancelled.state.isoImportUsbInFlight)
        assertEquals(8L, cancelled.state.isoImportGeneration)
        assertTrue(cancelled.state.pendingImportFolderPickLoad)
        assertNull(cancelled.state.pendingImportFolderPick)
        assertTrue(cancelled.effects.isEmpty())

        val lateRejection =
            UiReducer.reduce(
                cancelled.state,
                UiIntent.WindowsIsoImportRejected(
                    problem = UnreadableProblem.IoFailure,
                    reason = "late",
                    generation = 7L,
                ),
            )
        assertNull(lateRejection.state.isoImportError)
        assertEquals(7L, lateRejection.state.isoImportUsbInFlight)

        val finished =
            UiReducer.reduce(
                lateRejection.state,
                UiIntent.WindowsIsoImportFinished(7L),
            )
        assertNull(finished.state.isoImportUsbInFlight)
        assertFalse(finished.state.pendingImportFolderPickLoad)
        assertEquals(WindowsImportKind.Image, finished.state.pendingImportFolderPick)
        val effect = finished.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(finished.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun secondInspectionRequestIsRefusedWhileOneRuns() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportSafInFlight = 7L,
            )

        val refused =
            UiReducer.reduce(
                base,
                UiIntent.RequestImportWindowsIso(uri = "content://example/a.iso"),
            )

        assertEquals(7L, refused.state.isoImportGeneration)
        assertEquals(7L, refused.state.isoImportSafInFlight)
        assertEquals(UiMessage.Key(UiMessageKey.WaitForOperation), refused.state.statusMessage)
        assertTrue(refused.effects.isEmpty())
    }

    @Test
    fun cancelThenNewImportStartsWhileOldSafJobMayBeStuck() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportSafInFlight = 7L,
            )

        val cancelled = UiReducer.reduce(base, UiIntent.CancelWindowsIsoImport)
        assertNull(cancelled.state.isoImportSafInFlight)
        assertEquals(8L, cancelled.state.isoImportGeneration)

        // A provider read that ignores cancellation must not block a fresh import.
        val next =
            UiReducer.reduce(
                cancelled.state,
                UiIntent.RequestImportWindowsIso(uri = "content://example/b.iso"),
            )

        assertEquals(9L, next.state.isoImportGeneration)
        assertEquals(9L, next.state.isoImportSafInFlight)
        val effect = next.effects.single() as UiEffect.InspectIsoImport
        assertEquals(9L, effect.requestGeneration)
    }

    @Test
    fun safPhaseDoesNotBlockDeferredFolderMount() {
        val base =
            withVentoy().copy(
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
                isoImportGeneration = 7L,
                isoImportSafInFlight = 7L,
            )

        val released =
            UiReducer.reduce(
                base,
                UiIntent.Status(UiMessage.Raw("saf still reading")),
            )

        // SAF runs off the disk worker; only the USB phase owns the disk.
        assertEquals(WindowsImportKind.Image, released.state.pendingImportFolderPick)
        assertFalse(released.state.pendingImportFolderPickLoad)
        val effect = released.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(released.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun usbStartedMovesOwnershipFromSaf() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportSafInFlight = 7L,
            )

        val started = UiReducer.reduce(base, UiIntent.WindowsIsoImportUsbStarted(7L))

        assertNull(started.state.isoImportSafInFlight)
        assertEquals(7L, started.state.isoImportUsbInFlight)

        val lateSaf = UiReducer.reduce(started.state, UiIntent.WindowsIsoImportSafFinished(7L))
        assertEquals(7L, lateSaf.state.isoImportUsbInFlight)

        val done = UiReducer.reduce(lateSaf.state, UiIntent.WindowsIsoImportFinished(7L))
        assertNull(done.state.isoImportUsbInFlight)
    }

    @Test
    fun staleSafFinishedKeepsNewOwnership() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 8L,
                isoImportSafInFlight = 8L,
            )

        val stale =
            UiReducer.reduce(base, UiIntent.WindowsIsoImportSafFinished(7L))

        assertEquals(8L, stale.state.isoImportSafInFlight)
    }

    @Test
    fun sameStickProbeKeepsInFlightInspectionAndDeferredPick() {
        val base =
            withVentoy().copy(
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
                isoImportGeneration = 7L,
                isoImportUsbInFlight = 7L,
            )
        val device = requireNotNull(base.device)

        val probed =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            )

        assertEquals(7L, probed.state.isoImportUsbInFlight)
        assertEquals(7L, probed.state.isoImportGeneration)
        assertEquals(WindowsImportKind.Image, probed.state.deferredImportFolderPick)
        assertTrue(probed.state.pendingImportFolderPickLoad)
        assertTrue(probed.effects.isEmpty())
    }

    @Test
    fun sameStickProbeKeepsGenerationSoUsbStartedOwnsDisk() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportSafInFlight = 7L,
            )
        val device = requireNotNull(base.device)

        val probed =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            )
        assertEquals(7L, probed.state.isoImportGeneration)
        assertEquals(7L, probed.state.isoImportSafInFlight)

        val started =
            UiReducer.reduce(probed.state, UiIntent.WindowsIsoImportUsbStarted(7L))

        assertNull(started.state.isoImportSafInFlight)
        assertEquals(7L, started.state.isoImportUsbInFlight)
    }

    @Test
    fun sameStickProbeKeepsGenerationSoInspectedIsAccepted() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportUsbInFlight = 7L,
            )
        val device = requireNotNull(base.device)

        val probed =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            )
        assertEquals(7L, probed.state.isoImportGeneration)

        val inspected =
            UiReducer.reduce(
                probed.state,
                UiIntent.WindowsIsoImportInspected(
                    uri = "content://example/win.iso",
                    name = "win.iso",
                    sizeBytes = 1L,
                    targetName = "win.iso",
                    exists = true,
                    generation = 7L,
                    directory = "/ISO",
                ),
            )

        assertNotNull(inspected.state.pendingIsoImport)
        assertEquals(7L, inspected.state.isoImportUsbInFlight)
    }

    @Test
    fun sameStickProbeSequenceBusyInspectedReadyKeepsOverwriteProposal() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportUsbInFlight = 7L,
            )
        val device = requireNotNull(base.device)

        val busy =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            ).state
        assertEquals(7L, busy.isoImportGeneration)
        assertFalse(busy.actionsEnabled)

        // The host posts the inspection result while the probe still shows Busy.
        val inspected =
            UiReducer.reduce(
                busy,
                UiIntent.WindowsIsoImportInspected(
                    uri = "content://example/win.iso",
                    name = "win.iso",
                    sizeBytes = 1L,
                    targetName = "win.iso",
                    exists = true,
                    generation = 7L,
                    directory = "/ISO",
                ),
            ).state
        assertNotNull(inspected.pendingIsoImport)

        val ready =
            UiReducer.reduce(
                inspected,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            ).state
        assertNotNull(ready.pendingIsoImport)
        assertEquals(7L, ready.isoImportGeneration)
    }

    @Test
    fun nonOverwritingInspectionIsDeferredAcrossProbeAndStartsAfterDiskCloses() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportUsbInFlight = 7L,
            )
        val device = requireNotNull(base.device)

        val busy =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            ).state

        val inspected =
            UiReducer.reduce(
                busy,
                UiIntent.WindowsIsoImportInspected(
                    uri = "content://example/win.iso",
                    name = "win.iso",
                    sizeBytes = 1L,
                    targetName = "win.iso",
                    exists = false,
                    generation = 7L,
                    directory = "/ISO",
                ),
            )
        assertNotNull(inspected.state.deferredIsoImport)
        assertNull(inspected.state.pendingIsoImport)
        assertTrue(inspected.effects.isEmpty())

        val ready =
            UiReducer.reduce(
                inspected.state,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            )
        // Same stick: the held import survives; it waits for the disk to close.
        assertNotNull(ready.state.deferredIsoImport)
        assertEquals(7L, ready.state.isoImportGeneration)

        // The Ready probe also re-lists Files; the import must not race that mount.
        val listed =
            UiReducer.reduce(
                ready.state,
                UiIntent.FilesListed(
                    path = ready.state.currentPath,
                    files = emptyList(),
                    generation = ready.state.filesListGeneration,
                    identity = requireNotNull(ready.state.device).identity,
                    partitionId = requireNotNull(ready.state.selectedPartitionId),
                    attachGeneration = requireNotNull(ready.state.device).attachGeneration,
                ),
            )
        assertFalse(listed.state.filesPending)
        assertNotNull(listed.state.deferredIsoImport)
        assertTrue(listed.effects.none { it is UiEffect.ImportIso })

        val finished =
            UiReducer.reduce(listed.state, UiIntent.WindowsIsoImportFinished(7L))

        assertNull(finished.state.deferredIsoImport)
        val effect = finished.effects.single() as UiEffect.ImportIso
        assertEquals(false, effect.overwrite)
        assertEquals("win.iso", effect.isoImport.targetName)
        assertEquals(7L, effect.requestGeneration)
    }

    @Test
    fun deferredImportIsDroppedOnAttachChange() {
        val base =
            withVentoy().copy(
                isoImportGeneration = 7L,
                isoImportUsbInFlight = 7L,
            )
        val device = requireNotNull(base.device)

        val busy =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            ).state
        val inspected =
            UiReducer.reduce(
                busy,
                UiIntent.WindowsIsoImportInspected(
                    uri = "content://example/win.iso",
                    name = "win.iso",
                    sizeBytes = 1L,
                    targetName = "win.iso",
                    exists = false,
                    generation = 7L,
                    directory = "/ISO",
                ),
            ).state
        assertNotNull(inspected.deferredIsoImport)

        val swapped =
            UiReducer.reduce(
                inspected,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device(gen = 2L),
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            )

        assertNull(swapped.state.deferredIsoImport)
        assertTrue(swapped.effects.none { it is UiEffect.ImportIso })
    }

    @Test
    fun terminalImportFailureReopenUsesCapturedGeneration() {
        val base = withVentoy()
        val running =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.ImportTarget("win.iso"),
                    ),
                ),
            ).state
        val failed =
            UiReducer.reduce(
                running,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Failed,
                        title = OperationTitle.ImportTarget("win.iso"),
                        errorMessage = "import folder is gone",
                    ),
                ),
            ).state
        assertTrue(failed.isoImportGeneration > base.isoImportGeneration)
        val device = requireNotNull(failed.device)

        // The host reads the live generation after the terminal, then pins the reopen to it.
        val reopened =
            UiReducer.reduce(
                failed,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = failed.isoImportGeneration,
                ),
            )

        assertEquals(WindowsImportKind.Image, reopened.state.pendingImportFolderPick)
        assertNull(reopened.state.ventoyFolders)
        assertEquals(
            failed.ventoyFoldersRequest + 1L,
            reopened.state.ventoyFoldersRequest,
        )
        assertTrue(reopened.effects.single() is UiEffect.LoadVentoyFolders)
    }

    @Test
    fun reopenWithPreCancelGenerationIsStale() {
        val base = withVentoy().copy(isoImportGeneration = 7L)
        val cancelled = UiReducer.reduce(base, UiIntent.CancelWindowsIsoImport).state
        assertEquals(8L, cancelled.isoImportGeneration)
        val device = requireNotNull(cancelled.device)

        val stale =
            UiReducer.reduce(
                cancelled,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 7L,
                ),
            )

        assertEquals(base.ventoyFolders, stale.state.ventoyFolders)
        assertEquals(base.ventoyFoldersRequest, stale.state.ventoyFoldersRequest)
        assertNull(stale.state.pendingImportFolderPick)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun attachChangeDropsInspectionGuardsAndDeferredDemand() {
        val base =
            withVentoy().copy(
                deferredImportFolderPick = WindowsImportKind.Image,
                pendingImportFolderPickLoad = true,
                ventoyFolders = null,
                isoImportGeneration = 7L,
                isoImportSafInFlight = 7L,
                isoImportUsbInFlight = 7L,
            )

        val swapped =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device(gen = 2L),
                    partitions = listOf(partition()),
                ),
            )

        // The host ticket is disowned on replug, so no old worker can reach the new stick.
        // Dropping the guard lets a fresh import run without waiting on a worker that may
        // never publish (provider read ignoring cancellation); a late Finished is gated by
        // its generation and cannot clear the new guard.
        assertNull(swapped.state.isoImportSafInFlight)
        assertNull(swapped.state.isoImportUsbInFlight)
        assertNull(swapped.state.deferredImportFolderPick)
        assertFalse(swapped.state.pendingImportFolderPickLoad)
        assertTrue(swapped.effects.none { it is UiEffect.LoadVentoyFolders })

        val late = UiReducer.reduce(swapped.state, UiIntent.WindowsIsoImportFinished(7L))
        assertNull(late.state.isoImportUsbInFlight)
        assertNull(late.state.isoImportSafInFlight)
    }

    @Test
    fun staleReopenIsDroppedByGeneration() {
        val base = withVentoy().copy(isoImportGeneration = 8L)
        val device = requireNotNull(base.device)

        val stale =
            UiReducer.reduce(
                base,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 7L,
                ),
            )

        assertEquals(base.ventoyFolders, stale.state.ventoyFolders)
        assertEquals(base.ventoyFoldersRequest, stale.state.ventoyFoldersRequest)
        assertNull(stale.state.pendingImportFolderPick)
        assertNull(stale.state.deferredImportFolderPick)
        assertFalse(stale.state.pendingImportFolderPickLoad)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun sameStickBusyKeepsTheDeferredPick() {
        val base =
            withVentoy().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
            )
        val device = requireNotNull(base.device)
        val deferred =
            UiReducer.reduce(
                base,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            ).state

        val busy =
            UiReducer.reduce(
                deferred,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            )

        assertNull(busy.state.pendingImportFolderPick)
        assertEquals(WindowsImportKind.Image, busy.state.deferredImportFolderPick)
        assertTrue(busy.state.pendingImportFolderPickLoad)
        assertTrue(busy.effects.isEmpty())
    }

    @Test
    fun sameStickReadyKeepsDeferredPickAndReleasesOnQuietIntent() {
        val prompt =
            UiReducer.reduce(
                withVentoy(),
                UiIntent.RequestDestructive(DestructiveAction.WIPE),
            ).state
        val device = requireNotNull(prompt.device)
        val deferred =
            UiReducer.reduce(
                prompt,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.Image,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            ).state

        val ready =
            UiReducer.reduce(
                deferred,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(partition()),
                    files = emptyList(),
                ),
            )

        // Same attachment: the demand survives, but this intent already mounts Files.
        assertEquals(WindowsImportKind.Image, ready.state.deferredImportFolderPick)
        assertTrue(ready.state.pendingImportFolderPickLoad)
        assertTrue(ready.effects.any { it is UiEffect.LoadFiles })
        assertTrue(ready.effects.none { it is UiEffect.LoadVentoyFolders })

        val quiet =
            UiReducer.reduce(
                ready.state,
                UiIntent.Status(UiMessage.Raw("settled")),
            )

        assertEquals(WindowsImportKind.Image, quiet.state.pendingImportFolderPick)
        assertNull(quiet.state.deferredImportFolderPick)
        assertFalse(quiet.state.pendingImportFolderPickLoad)
        val effect = quiet.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals(quiet.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun attachChangeDropsDeferredImportFolderLoad() {
        val base =
            withVentoy().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
            )
        val device = requireNotNull(base.device)
        val deferred =
            UiReducer.reduce(
                base,
                UiIntent.ReopenImportFolderPick(
                    kind = WindowsImportKind.XmlTemplate,
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                    requestGeneration = 0L,
                ),
            ).state

        val replugged =
            UiReducer.reduce(
                deferred,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device(gen = 2L),
                    partitions = listOf(partition()),
                ),
            ).state

        assertNull(replugged.pendingImportFolderPick)
        assertNull(replugged.deferredImportFolderPick)
        assertFalse(replugged.pendingImportFolderPickLoad)
    }

    // --- lazy data-partition folder navigation ---

    @Test
    fun refreshReadsTheFolderThatIsOpen() {
        val base =
            withVentoy().copy(
                ventoyFolderPath = "/ISO",
                ventoyFolders = listOf("/ISO/Windows"),
            )

        val next = UiReducer.reduce(base, UiIntent.LoadVentoyFolders)

        assertEquals("/ISO", next.state.ventoyFolderPath)
        // A refresh keeps the rows until the new answer lands.
        assertEquals(listOf("/ISO/Windows"), next.state.ventoyFolders)
        assertTrue(next.state.ventoyFoldersLoading)
        assertEquals(base.ventoyFoldersRequest + 1L, next.state.ventoyFoldersRequest)
        val effect = next.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals("/ISO", effect.path)
        assertEquals(next.state.ventoyFoldersRequest, effect.requestToken)
    }

    @Test
    fun enteringAListedChildLoadsThatFolder() {
        val base = withVentoy().copy(ventoyFolders = listOf("/ISO", "/ventoy"))

        val next = UiReducer.reduce(base, UiIntent.OpenVentoyFolder("/ventoy"))

        assertEquals("/ventoy", next.state.ventoyFolderPath)
        // Rows from the root must never be shown as children of the new folder.
        assertNull(next.state.ventoyFolders)
        assertNull(next.state.ventoyFoldersError)
        assertTrue(next.state.ventoyFoldersLoading)
        assertEquals(base.ventoyFoldersRequest + 1L, next.state.ventoyFoldersRequest)
        val effect = next.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals("/ventoy", effect.path)
        assertEquals(next.state.ventoyFoldersRequest, effect.requestToken)

        val settled =
            UiReducer.reduce(
                next.state,
                UiIntent.VentoyFoldersLoaded(
                    listOf("/ventoy/theme"),
                    epoch = next.state.ventoyCacheEpoch,
                    requestToken = next.state.ventoyFoldersRequest,
                    path = "/ventoy",
                ),
            ).state
        assertEquals(listOf("/ventoy/theme"), settled.ventoyFolders)
        assertFalse(settled.ventoyFoldersLoading)
    }

    @Test
    fun enteringAnUnlistedOrRelativeFolderIsRefused() {
        val base = withVentoy().copy(ventoyFolders = listOf("/ISO"))

        for (path in listOf("/ETC", "ISO", "/ISO/Windows", "/../ISO")) {
            val next = UiReducer.reduce(base, UiIntent.OpenVentoyFolder(path))
            assertEquals(base.ventoyFolderPath, next.state.ventoyFolderPath)
            assertEquals(base.ventoyFolders, next.state.ventoyFolders)
            assertEquals(base.ventoyFoldersRequest, next.state.ventoyFoldersRequest)
            assertTrue(next.effects.isEmpty())
            assertNotNull(next.state.statusMessage)
        }
    }

    @Test
    fun upLoadsTheParentAndRootUpIsANoOp() {
        val nested =
            withVentoy().copy(
                ventoyFolderPath = "/ISO/Windows",
                ventoyFolders = listOf("/ISO/Windows/11"),
            )
        val up = UiReducer.reduce(nested, UiIntent.VentoyFolderUp)
        assertEquals("/ISO", up.state.ventoyFolderPath)
        assertNull(up.state.ventoyFolders)
        assertTrue(up.state.ventoyFoldersLoading)
        assertEquals("/ISO", (up.effects.single() as UiEffect.LoadVentoyFolders).path)

        val root = nested.copy(ventoyFolderPath = "/", ventoyFolders = listOf("/ISO"))
        val atRoot = UiReducer.reduce(root, UiIntent.VentoyFolderUp)
        assertEquals(root, atRoot.state)
        assertTrue(atRoot.effects.isEmpty())
    }

    @Test
    fun answerForThePreviousFolderIsDropped() {
        val base =
            withVentoy().copy(
                ventoyFolderPath = "/ISO",
                ventoyFolders = listOf("/ISO/Windows"),
                ventoyFoldersRequest = 4L,
            )

        // Same epoch and token, but it belongs to the root: navigation disowns it.
        val stale =
            UiReducer.reduce(
                base,
                UiIntent.VentoyFoldersLoaded(
                    listOf("/ventoy"),
                    epoch = base.ventoyCacheEpoch,
                    requestToken = 4L,
                    path = "/",
                ),
            ).state

        assertEquals("/ISO", stale.ventoyFolderPath)
        assertEquals(listOf("/ISO/Windows"), stale.ventoyFolders)
    }

    @Test
    fun refreshFailureClearsStaleRowsAndReportsTheError() {
        val base =
            withVentoy().copy(
                ventoyFolderPath = "/ISO",
                ventoyFolders = listOf("/ISO/Windows"),
                ventoyFoldersRequest = 2L,
            )

        val failed =
            UiReducer.reduce(
                base,
                UiIntent.VentoyFoldersLoaded(
                    emptyList(),
                    error = FolderReadProblem.Missing,
                    epoch = base.ventoyCacheEpoch,
                    requestToken = 2L,
                    path = "/ISO",
                ),
            ).state

        assertEquals(emptyList<String>(), failed.ventoyFolders)
        assertEquals(FolderReadProblem.Missing, failed.ventoyFoldersError)
        assertFalse(failed.ventoyFoldersLoading)
    }

    @Test
    fun folderNavigationIsNotQueuedWhileBusy() {
        val busy =
            withVentoy().copy(
                tab = ShellTab.Ventoy,
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.Upload,
                    ),
                ventoyFolderPath = "/ISO",
                ventoyFolders = listOf("/ISO/Windows"),
            )

        for (intent in
            listOf(
                UiIntent.VentoyFolderUp,
                UiIntent.OpenVentoyFolder("/ISO/Windows"),
            )) {
            val next = UiReducer.reduce(busy, intent)
            assertTrue("$intent must not queue USB work", next.effects.isEmpty())
            assertEquals("/ISO", next.state.ventoyFolderPath)
            assertEquals(listOf("/ISO/Windows"), next.state.ventoyFolders)
            assertEquals(busy.ventoyFoldersRequest, next.state.ventoyFoldersRequest)
            assertFalse(next.state.ventoyFoldersLoading)
            assertFalse(next.state.ventoyFoldersPending)
        }
    }

    @Test
    fun busyProbeFolderRefreshWaitsAndIsReleasedOnce() {
        // Probe Busy has no running operation: only the presence blocks folder reads.
        val busy =
            withVentoy().copy(
                tab = ShellTab.Ventoy,
                presence = DevicePresence.Busy,
                ventoyFolderPath = "/ISO",
                ventoyFolders = null,
            )

        val queued = UiReducer.reduce(busy, UiIntent.LoadVentoyFolders)
        assertTrue(queued.effects.isEmpty())
        assertTrue(queued.state.ventoyFoldersPending)
        assertFalse(queued.state.ventoyFoldersLoading)
        assertEquals(busy.ventoyFoldersRequest, queued.state.ventoyFoldersRequest)

        // Repeated taps while busy must not stack requests or bump the token.
        val repeated = UiReducer.reduce(queued.state, UiIntent.LoadVentoyFolders)
        assertTrue(repeated.effects.isEmpty())
        assertTrue(repeated.state.ventoyFoldersPending)
        assertEquals(queued.state.ventoyFoldersRequest, repeated.state.ventoyFoldersRequest)

        val ready =
            UiReducer.reduce(
                repeated.state,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device(gen = 1L),
                    partitions = listOf(partition()),
                ),
            )
        val effect = ready.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals("/ISO", effect.path)
        assertEquals(ready.state.ventoyFoldersRequest, effect.requestToken)
        assertEquals(busy.ventoyFoldersRequest + 1L, ready.state.ventoyFoldersRequest)
        assertTrue(ready.state.ventoyFoldersLoading)
        assertFalse(ready.state.ventoyFoldersPending)
        assertNull(ready.state.statusMessage)

        // A failed answer settles into an error; the next Ready edge must not retry forever.
        val failed =
            UiReducer.reduce(
                ready.state,
                UiIntent.VentoyFoldersLoaded(
                    emptyList(),
                    error = FolderReadProblem.Missing,
                    epoch = ready.state.ventoyCacheEpoch,
                    requestToken = ready.state.ventoyFoldersRequest,
                    path = "/ISO",
                ),
            ).state
        assertFalse(failed.ventoyFoldersLoading)
        assertFalse(failed.ventoyFoldersPending)
        assertEquals(FolderReadProblem.Missing, failed.ventoyFoldersError)

        val quiet =
            UiReducer.reduce(
                failed,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device(gen = 1L),
                    partitions = listOf(partition()),
                ),
            )
        assertTrue(quiet.effects.none { it is UiEffect.LoadVentoyFolders })
        assertFalse(quiet.state.ventoyFoldersPending)
    }

    @Test
    fun runningOperationFolderRefreshIsReleasedOnTerminal() {
        // A Running operation keeps actionsEnabled false even when DeviceChanged says Ready.
        val busy =
            withVentoy().copy(
                tab = ShellTab.Ventoy,
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.ReadScan,
                    ),
                ventoyFolderPath = "/ISO",
                ventoyFolders = null,
            )

        val queued = UiReducer.reduce(busy, UiIntent.LoadVentoyFolders)
        assertTrue(queued.effects.isEmpty())
        assertTrue(queued.state.ventoyFoldersPending)

        val stillBusy =
            UiReducer.reduce(
                queued.state,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Running,
                        title = OperationTitle.ReadScan,
                    ),
                ),
            )
        assertTrue(stillBusy.effects.isEmpty())
        assertTrue(stillBusy.state.ventoyFoldersPending)

        val released =
            UiReducer.reduce(
                stillBusy.state,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Completed,
                        title = OperationTitle.ReadScan,
                    ),
                ),
            )
        val effect = released.effects.single() as UiEffect.LoadVentoyFolders
        assertEquals("/ISO", effect.path)
        assertEquals(released.state.ventoyFoldersRequest, effect.requestToken)
        assertEquals(busy.ventoyFoldersRequest + 1L, released.state.ventoyFoldersRequest)
        assertEquals(DevicePresence.Ready, released.state.presence)
        assertTrue(released.state.ventoyFoldersLoading)
        assertFalse(released.state.ventoyFoldersPending)

        // A failed answer and a repeated terminal must not re-arm the request.
        val failed =
            UiReducer.reduce(
                released.state,
                UiIntent.VentoyFoldersLoaded(
                    emptyList(),
                    error = FolderReadProblem.Missing,
                    epoch = released.state.ventoyCacheEpoch,
                    requestToken = released.state.ventoyFoldersRequest,
                    path = "/ISO",
                ),
            ).state
        assertFalse(failed.ventoyFoldersPending)
        val repeatedTerminal =
            UiReducer.reduce(
                failed,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Completed,
                        title = OperationTitle.ReadScan,
                    ),
                ),
            )
        assertTrue(repeatedTerminal.effects.none { it is UiEffect.LoadVentoyFolders })
        assertFalse(repeatedTerminal.state.ventoyFoldersPending)
    }

    @Test
    fun sameStickProbeKeepsTheOpenFolder() {
        val loaded =
            withVentoy().copy(
                ventoyFolderPath = "/ISO/Windows",
                ventoyFolders = listOf("/ISO/Windows/11"),
            )

        val next =
            UiReducer.reduce(
                loaded,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Busy,
                    device = device(gen = 1L),
                    partitions = emptyList(),
                ),
            ).state

        assertEquals("/ISO/Windows", next.ventoyFolderPath)
        assertEquals(listOf("/ISO/Windows/11"), next.ventoyFolders)
    }

    @Test
    fun attachChangeResetsBrowserToTheRoot() {
        val loaded =
            withVentoy().copy(
                ventoyFolderPath = "/ISO/Windows",
                ventoyFolders = listOf("/ISO/Windows/11"),
            )

        val next =
            UiReducer.reduce(
                loaded,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device(gen = 2L),
                    partitions = listOf(partition()),
                ),
            ).state

        assertEquals("/", next.ventoyFolderPath)
        assertNull(next.ventoyFolders)
        assertNull(next.ventoyFoldersError)
        assertFalse(next.ventoyFoldersLoading)
    }

    @Test
    fun invalidationResetsBrowserToTheRoot() {
        val loaded =
            withVentoy().copy(
                ventoyFolderPath = "/ISO/Windows",
                ventoyFolders = listOf("/ISO/Windows/11"),
            )

        val next = UiReducer.reduce(loaded, UiIntent.InvalidateVentoyStickCaches).state

        assertEquals("/", next.ventoyFolderPath)
        assertNull(next.ventoyFolders)
        assertNull(next.ventoyFoldersError)
        assertFalse(next.ventoyFoldersLoading)
    }
}
