package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.partition.Partition
import usb.fs.FilesystemId

class PartitionTabMapperTest {
    @Test
    fun mapsStableRowsAndEfiPolicyWithoutDisk() {
        val rows = mapPartitionTabs(
            listOf(
                Partition(2048UL, 4096UL, name = "", type = "basic"),
                Partition(8192UL, 1024UL, name = "VTOYEFI", type = "EFI"),
            ),
        ) { FilesystemId.FAT }
        assertEquals(listOf("1", "2"), rows.map { it.id })
        assertEquals("Partition 1", rows[0].name)
        assertEquals("VTOYEFI", rows[1].name)
        assertTrue(!rows[0].readOnly)
        assertTrue(rows[1].readOnly)
        assertEquals(FilesystemId.FAT, rows[0].filesystemId)
    }
}
