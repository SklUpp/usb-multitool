package usb.device

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

class UsbDiskTest {
    @Test fun stallRetriesOnceThenReads() {
        val transport = FakeTransport(stallsBeforeSuccess = 1)
        val disk = UsbDisk(transport, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        disk.read(4UL, out)
        assertEquals(1, transport.resetCount)
        assertEquals(512, out.position())
    }

    @Test fun secondStallDoesNotResetAgain() {
        val transport = FakeTransport(stallsBeforeSuccess = 2)
        val disk = UsbDisk(transport, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        try {
            disk.read(0UL, out)
            fail("expected DiskException")
        } catch (e: DiskException) {
            assertEquals(1, transport.resetCount)
            assertTrue(disk.isClosed)
        }
    }

    @Test fun disconnectThrowsAndClosesSession() {
        val transport = FakeTransport(disconnectOnCommand = true)
        val disk = UsbDisk(transport, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        try {
            disk.read(0UL, out)
            fail("expected DiskException.Disconnected")
        } catch (e: DiskException.Disconnected) {
            assertTrue(disk.isClosed)
        }
    }

    @Test fun shortReadFillsRequestedBytesAcrossChunks() {
        val transport = FakeTransport(maxTransfer = 256)
        val disk = UsbDisk(transport, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        disk.read(1UL, out)
        assertEquals(512, out.position())
        assertEquals(0, transport.resetCount)
    }
}
