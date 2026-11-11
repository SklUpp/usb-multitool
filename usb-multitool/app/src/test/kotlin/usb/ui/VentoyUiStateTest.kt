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
        ventoyGlobalSettings = emptyList(),
        ventoyFolders = listOf("/", "/ISO"),
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
        assertEquals(listOf("/", "/ISO"), next.ventoyFolders)
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
        val effect = result.effects.single()
        assertEquals(UiEffect.Kind.BindAutounattend, effect.kind)
        assertEquals(listOf(pathA, pathB), effect.imagePaths)
    }

    @Test
    fun loadWindowsIsoCandidatesReadyEmitsBusyWaits() {
        val readyResult = UiReducer.reduce(ready(), UiIntent.LoadWindowsIsoCandidates)
        assertEquals(UiEffect.Kind.LoadWindowsIsoCandidates, readyResult.effects.single().kind)

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
        assertEquals(UiEffect.Kind.LoadWindowsIsoCandidates, result.effects.single().kind)
        assertEquals(
            afterDrop.windowsIsoCandidatesGeneration + 1,
            result.state.windowsIsoCandidatesGeneration,
        )
        assertEquals(
            result.state.windowsIsoCandidatesGeneration,
            result.effects.single().requestGeneration,
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
                usb.ventoy.VentoyGlobalSettings(emptyList()),
                epoch = 2L,
            ),
        ).state
        assertNull(staleSettings.ventoyGlobalSettings)

        val freshFolders = UiReducer.reduce(
            after,
            UiIntent.VentoyFoldersLoaded(listOf("/", "/ISO"), epoch = 3L),
        ).state
        assertEquals(listOf("/", "/ISO"), freshFolders.ventoyFolders)

        val freshSettings = UiReducer.reduce(
            after,
            UiIntent.VentoyGlobalSettingsLoaded(
                usb.ventoy.VentoyGlobalSettings(emptyList()),
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
                usb.ventoy.VentoyGlobalSettings(emptyList(), error = "mount failed"),
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
        assertEquals(1, loading.effects.count { it.kind == UiEffect.Kind.LoadAutounattend })

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
}
