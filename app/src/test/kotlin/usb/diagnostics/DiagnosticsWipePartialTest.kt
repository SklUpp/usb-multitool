package usb.diagnostics

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk
import usb.operation.DiskOperation
import java.nio.ByteBuffer

class DiagnosticsWipePartialTest {
    @Test fun wipeWritesFullChunksExceptTail() {
        val disk = RecordingDisk(sectorCount = 256UL)
        val op = DiskOperation("wipe")
        Diagnostics.wipe(disk, "id", DestructiveConfirmation("id", disk.geometry.capacityBytes), operation = op)
        assertEquals(listOf(0UL to 128, 128UL to 128), disk.writes)
        assertEquals(256L * 512L, op.bytesDone)
    }

    @Test fun firstWriteFailureMarksPartial() {
        val disk = FailingDisk(failAt = 0)
        val op = DiskOperation("wipe")
        assertThrows(Throwable::class.java) { Diagnostics.wipe(disk, "id", DestructiveConfirmation("id", disk.geometry.capacityBytes), operation = op) }
        assertEquals(usb.operation.OperationState.Partial, op.state)
        assertEquals(true, op.mutationAttempted)
    }

    @Test fun preflightFailureStaysClean() {
        val disk = FailingDisk(failAt = 0)
        val op = DiskOperation("wipe")
        assertThrows(Throwable::class.java) { Diagnostics.wipe(disk, "other", DestructiveConfirmation("id", disk.geometry.capacityBytes), operation = op) }
        assertEquals(usb.operation.OperationState.Queued, op.state)
    }

    private class RecordingDisk(sectorCount: ULong) :
        UsbDisk(FakeBotSession(), BlockGeometry(512, sectorCount)) {
        val writes = mutableListOf<Pair<ULong, Int>>()
        override fun write(lba: ULong, source: ByteBuffer) {
            writes += lba to source.remaining() / sectorSize
        }
    }

    private class FailingDisk(private val failAt: Long) : UsbDisk(FakeBotSession(), BlockGeometry(512, 8UL)) {
        override fun write(lba: ULong, source: ByteBuffer) { if (lba.toLong() == failAt) error("write") }
    }
}
