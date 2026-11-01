package usb.partition

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VirtualPartitionTest {
    @Test
    fun offsetIsAppliedOnceAndBoundsRejectOverflow() {
        val disk = RecordingBlockDevice(sectorCount = 10UL)
        val part = VirtualPartition(2UL, 3UL, disk)
        part.read(1UL, ByteBuffer.allocate(512))
        assertEquals(listOf(3UL), disk.reads)
        assertThrows(IndexOutOfBoundsException::class.java) {
            part.read(3UL, ByteBuffer.allocate(512))
        }
    }

    @Test
    fun syncDelegatesToDisk() {
        val disk = RecordingBlockDevice(sectorCount = 10UL)
        val part = VirtualPartition(0UL, 4UL, disk)
        part.sync()
        assertEquals(1, disk.syncCount)
    }

    @Test
    fun sectorCountIsPartitionNotDisk() {
        val disk = RecordingBlockDevice(sectorCount = 100UL)
        val part = VirtualPartition(10UL, 5UL, disk)
        assertEquals(5UL, part.sectorCount)
        assertEquals(512, part.sectorSize)
    }
}
