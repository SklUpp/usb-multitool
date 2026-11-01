package usb.partition

import java.nio.ByteBuffer
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualPartitionTest {
    @Test fun offsetIsAppliedOnceAndBoundsRejectOverflow() {
        val disk = RecordingDisk(sectorCount = 10UL)
        val part = VirtualPartition(2UL, 3UL, disk)
        part.read(1UL, ByteBuffer.allocate(512))
        assertEquals(listOf(3UL), disk.reads)
        assertFailsWith<IndexOutOfBoundsException> {
            part.read(3UL, ByteBuffer.allocate(512))
        }
    }
}
