package usb.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmedPartitionLookupTest {
    private val target = Partition(startLba = 2048UL, sectorCount = 4096UL, name = "target")
    private val partitions = listOf(target, Partition(startLba = 8192UL, sectorCount = 1024UL))

    @Test
    fun returnsPartitionWhenStartAndCountMatch() {
        assertEquals(target, findConfirmedPartition(partitions, 2048UL, 4096UL))
    }

    @Test
    fun rejectsChangedGeometry() {
        assertNull(findConfirmedPartition(partitions, 2048UL, 4097UL))
        assertNull(findConfirmedPartition(partitions, 2049UL, 4096UL))
    }

    @Test
    fun rejectsMissingTargetOrConfirmationGeometry() {
        assertNull(findConfirmedPartition(partitions, 12345UL, 7UL))
        assertNull(findConfirmedPartition(partitions, null, 4096UL))
        assertNull(findConfirmedPartition(partitions, 2048UL, null))
    }
}
