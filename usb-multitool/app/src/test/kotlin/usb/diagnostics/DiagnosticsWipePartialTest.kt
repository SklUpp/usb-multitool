package usb.diagnostics

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk
import usb.service.DiskOperation
import java.nio.ByteBuffer

class DiagnosticsWipePartialTest {
    @Test fun firstWriteFailureMarksPartial() {
        val disk = FailingDisk(failAt = 0)
        val op = DiskOperation("wipe")
        assertThrows(Throwable::class.java) { Diagnostics.wipe(disk, "id", DestructiveConfirmation("id", disk.geometry.capacityBytes), operation = op) }
        assertEquals(usb.service.OperationState.Partial, op.state)
        assertEquals(true, op.mutationAttempted)
    }

    @Test fun preflightFailureStaysClean() {
        val disk = FailingDisk(failAt = 0)
        val op = DiskOperation("wipe")
        assertThrows(Throwable::class.java) { Diagnostics.wipe(disk, "other", DestructiveConfirmation("id", disk.geometry.capacityBytes), operation = op) }
        assertEquals(usb.service.OperationState.Queued, op.state)
    }

    private class FailingDisk(private val failAt: Long) : UsbDisk(FakeBotSession(), BlockGeometry(512, 8UL)) {
        override fun write(lba: ULong, source: ByteBuffer) { if (lba.toLong() == failAt) error("write") }
    }
}
