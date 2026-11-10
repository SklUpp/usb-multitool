package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.partition.EFI_SYSTEM_TYPE_GUID
import usb.partition.Partition

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

    @Test
    fun mapsEfiTypeAndReadOnlyAxesIndependently() {
        val rows = mapPartitionTabs(
            listOf(
                Partition(1UL, 2UL, type = EFI_SYSTEM_TYPE_GUID, name = "ESP"),
                Partition(3UL, 2UL, type = "0xEF", name = "P2"),
                Partition(5UL, 2UL, type = "BASIC", name = "VTOYEFI"),
                Partition(7UL, 2UL, type = "BASIC", name = "DATA"),
                Partition(9UL, 2UL, type = "BASIC", name = "x", esp = true),
            ),
        )
        assertEquals(listOf(true, true, false, false, true), rows.map { it.isEfiType })
        assertEquals(listOf(true, true, true, false, true), rows.map { it.readOnly })
        assertFalse(rows[2].isEfiType)
        assertTrue(rows[2].readOnly)
    }
}
