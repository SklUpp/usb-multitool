package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesBatchSelectionTest {
    private val device = DeviceIdentity(label = "Stick", capacityBytes = 8_000_000_000uL, attachGeneration = 3L)
    private val partition =
        PartitionTab(
            id = "1",
            name = "DATA",
            readOnly = false,
            startLba = 2048uL,
            sectorCount = 4096uL,
        )

    private fun loaded(
        files: List<FileEntry>,
        path: String = "/",
        deviceOverride: DeviceIdentity = device,
        partitionOverride: PartitionTab = partition,
    ) = AppUiState(
        presence = DevicePresence.Ready,
        device = deviceOverride,
        partitions = listOf(partitionOverride),
        selectedPartitionId = partitionOverride.id,
        currentPath = path,
        files = files,
        filesListingState = FilesListingState.Loaded,
    )

    private fun selection(
        vararg items: FilesBatchItem,
        directory: String = "/",
    ) = FilesBatchSelection(
        directory = directory,
        items = items.toList(),
        identity = device.identity,
        attachGeneration = device.attachGeneration,
        partitionId = partition.id,
        startLba = partition.startLba,
        sectorCount = partition.sectorCount,
    )

    @Test
    fun matchesRequiresLoadedListingAndExactEntries() {
        val items = arrayOf(
            FilesBatchItem("a.bin", false, 10uL),
            FilesBatchItem("docs", true, 0uL),
        )
        val state = loaded(
            listOf(FileEntry("a.bin", false, 10uL), FileEntry("docs", true, 0uL)),
        )
        assertTrue(selection(*items).matches(state))
    }

    @Test
    fun matchesRejectsStaleListingGeometryOrTypeDrift() {
        val sel = selection(FilesBatchItem("a.bin", false, 10uL))
        assertFalse(sel.matches(loaded(listOf(FileEntry("a.bin", false, 10uL))).copy(filesListingState = FilesListingState.Loading)))
        assertFalse(sel.matches(loaded(listOf(FileEntry("a.bin", false, 11uL)))))
        assertFalse(sel.matches(loaded(listOf(FileEntry("a.bin", true, 10uL)))))
        assertFalse(sel.matches(loaded(listOf(FileEntry("a.bin", false, 10uL)), path = "/other")))
        assertFalse(
            sel.matches(
                loaded(
                    listOf(FileEntry("a.bin", false, 10uL)),
                    partitionOverride = partition.copy(startLba = 4096uL),
                ),
            ),
        )
    }

    @Test
    fun matchesRejectsBlankDotDotSlashAndDuplicateNames() {
        val state = loaded(listOf(FileEntry("a.bin", false, 1uL)))
        assertFalse(selection(FilesBatchItem("", false, 1uL)).matches(state))
        assertFalse(selection(FilesBatchItem("..", false, 1uL)).matches(state))
        assertFalse(selection(FilesBatchItem("a/b", false, 1uL)).matches(state))
        assertFalse(
            selection(
                FilesBatchItem("a.bin", false, 1uL),
                FilesBatchItem("a.bin", false, 1uL),
            ).matches(state),
        )
        assertFalse(selection().matches(state))
    }

    @Test
    fun requiresTreeDestinationForMultiOrDirectory() {
        assertFalse(selection(FilesBatchItem("a.bin", false, 1uL)).requiresTreeDestination())
        assertTrue(selection(FilesBatchItem("docs", true, 0uL)).requiresTreeDestination())
        assertTrue(
            selection(
                FilesBatchItem("a.bin", false, 1uL),
                FilesBatchItem("b.bin", false, 2uL),
            ).requiresTreeDestination(),
        )
    }

    @Test
    fun batchEnablementRules() {
        val file = listOf(FilesBatchItem("a.bin", false, 1uL))
        val folder = listOf(FilesBatchItem("docs", true, 0uL))
        val multi = file + folder
        assertTrue(filesBatchDownloadEnabled(true, file))
        assertTrue(filesBatchDownloadEnabled(true, folder))
        assertTrue(filesBatchDownloadEnabled(true, multi))
        assertFalse(filesBatchDownloadEnabled(false, file))
        assertFalse(filesBatchDownloadEnabled(true, emptyList()))

        assertTrue(filesBatchRenameEnabled(true, file))
        assertFalse(filesBatchRenameEnabled(true, multi))
        assertFalse(filesBatchRenameEnabled(false, file))

        assertTrue(filesBatchDeleteEnabled(true, folder))
        assertTrue(filesBatchDeleteEnabled(true, multi))
        assertFalse(filesBatchDeleteEnabled(false, multi))
        assertFalse(filesBatchDeleteEnabled(true, emptyList()))
    }

    @Test
    fun singleFileDownloadActionStillRejectsDirectories() {
        assertTrue(downloadActionEnabled(true, FileEntry("a.bin", false, 1uL)))
        assertFalse(downloadActionEnabled(true, FileEntry("docs", true, 0uL)))
        assertEquals("Download 3 entries", OperationTitle.DownloadBatch(3).logLabel)
        assertEquals("Upload 2 entries", OperationTitle.UploadBatch(2).logLabel)
        assertEquals("Delete 4 entries", OperationTitle.DeleteBatch(4).logLabel)
    }

    @Test
    fun fatChildPathPreservesExactLeafAndRejectsTrimmed() {
        assertEquals("/docs/a.bin", fatChildPath("/docs", "a.bin"))
        // Trailing/leading whitespace would silently retarget under trim — reject.
        assertThrows(IllegalArgumentException::class.java) {
            fatChildPath("/", "victim ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fatChildPath("/", " victim")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fatChildPath("/docs", "a/b")
        }
    }

    @Test
    fun conflictPathDisplayUsesFullPathLabelOnce() {
        val conflict =
            FilesConflict(
                pathLabel = "/docs/a.bin",
                name = "a.bin",
                isDirectory = false,
                destinationEmpty = null,
            )
        assertEquals("/docs/a.bin", filesBatchConflictPathDisplay(conflict))
        assertEquals(
            "a.bin",
            filesBatchConflictPathDisplay(
                FilesConflict(
                    pathLabel = "",
                    name = "a.bin",
                    isDirectory = false,
                    destinationEmpty = null,
                ),
            ),
        )
    }


}
