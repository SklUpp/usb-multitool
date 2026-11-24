package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.fs.FilesystemSpace
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey

/**
 * Characterization net for the UiReducer/UiModels split: identity-sensitive no-ops,
 * generation fences, and deferred Files listing release must stay bit-identical.
 */
class ReducerCompositionTest {
    private val device =
        DeviceIdentity(
            label = "SanDisk",
            capacityBytes = 8_000_000_000uL,
            serialNumber = "SN-1",
            attachGeneration = 3L,
        )

    private val data =
        PartitionTab(
            id = "1",
            name = "DATA",
            readOnly = false,
            startLba = 2048uL,
            sectorCount = 1_000_000uL,
            filesystemId = FilesystemId.FAT,
        )

    private fun ready(
        listing: FilesListingState = FilesListingState.Stale,
        generation: Long = 7L,
        pendingImportLoad: Boolean = false,
        pickMode: FilesPickMode? = null,
    ): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            tab = ShellTab.Files,
            partitions = listOf(data),
            selectedPartitionId = data.id,
            currentPath = "/",
            files = emptyList(),
            filesListGeneration = generation,
            filesListingState = listing,
            pendingImportFolderPickLoad = pendingImportLoad,
            deferredImportFolderPick = if (pendingImportLoad) WindowsImportKind.Image else null,
            filesPickMode = pickMode,
            diskSectorSize = 512,
        )

    @Test
    fun unrelatedFilesystemUsageResultKeepsStateIdentity() {
        val state = ready(listing = FilesListingState.Loaded)
        val unrelated =
            FilesystemUsageKey(
                attachGeneration = 99L,
                identity = "other",
                partitionStartLba = 0uL,
                partitionSectorCount = 1uL,
                sectorSize = 512,
                filesystem = FilesystemId.NTFS,
            )
        val result =
            UiReducer.reduce(
                state,
                UiIntent.FilesystemUsageResulted(
                    generation = 0L,
                    key = unrelated,
                    usage =
                        FilesystemUsage.Known(
                            FilesystemSpace(totalBytes = 1uL, freeBytes = 1uL),
                            measuredAtMillis = 1L,
                        ),
                ),
            )

        assertSame(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun staleFilesListedGenerationIsDropped() {
        val state = ready(listing = FilesListingState.Loading, generation = 7L)
        val result =
            UiReducer.reduce(
                state,
                UiIntent.FilesListed(
                    path = "/",
                    files = listOf(FileEntry("x.bin", isDirectory = false, sizeBytes = 1uL)),
                    generation = state.filesListGeneration - 1L,
                    identity = device.identity,
                    partitionId = data.id,
                    attachGeneration = device.attachGeneration,
                ),
            )

        assertSame(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun quietAcceptedIntentReleasesDeferredFilesListing() {
        // FilesListed is not in filesReleaseAllowed; Status is the quiet accepted path
        // that must still fire releaseDeferredFilesListing after identity-sensitive accepted.
        val pick =
            FilesPickMode(
                sessionId = "s1",
                purpose = FilesPickPurpose.WindowsImage,
                returnTab = ShellTab.Ventoy,
                target = FilesPickTarget.File,
                identity = device.identity,
                attachGeneration = device.attachGeneration,
                partitionId = data.id,
                startLba = data.startLba,
                sectorCount = data.sectorCount,
            )
        val state =
            ready(
                listing = FilesListingState.Stale,
                pendingImportLoad = true,
                pickMode = pick,
            )
        val result =
            UiReducer.reduce(
                state,
                UiIntent.Status(UiMessage.Key(UiMessageKey.NoDevice)),
            )

        assertEquals(1, result.effects.size)
        assertEquals(UiEffect.LoadFiles(path = "/"), result.effects.single())
        assertFalse(result.state.pendingImportFolderPickLoad)
        assertEquals(FilesListingState.Loading, result.state.filesListingState)
    }
}
