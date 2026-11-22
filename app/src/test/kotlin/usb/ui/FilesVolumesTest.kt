package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import usb.fs.FilesystemId

class FilesVolumesTest {
    private val partition = PartitionTab(
        id = "1",
        name = "DATA",
        readOnly = false,
        startLba = 2048UL,
        sectorCount = 4096UL,
    )

    private val image = PartitionTab(
        id = "whole",
        name = "ISO/IMG",
        readOnly = true,
        filesystemId = FilesystemId.ISO,
        startLba = 0UL,
        sectorCount = 8192UL,
        wholeDisk = true,
    )

    @Test
    fun hybridImageIsBrowsableNextToItsPartitions() {
        val state = AppUiState(
            partitions = listOf(partition),
            wholeDiskVolume = image,
            selectedPartitionId = "whole",
        )

        assertEquals(listOf(partition, image), state.filesVolumes)
        assertEquals(image, state.selectedFilesVolume())
        // Partition-editor selection stays constrained to real table slots.
        assertNull(state.selectedPartition())
    }

    @Test
    fun selectionFallsBackToFirstVolumeWhenStale() {
        val state = AppUiState(
            partitions = listOf(partition),
            wholeDiskVolume = image,
            selectedPartitionId = "gone",
        )

        assertEquals(partition, state.selectedFilesVolume())
        assertNull(state.selectedPartition())
    }
}
