package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.EngineMissingException
import usb.device.BlockDevice
import java.nio.ByteBuffer


/**
 * Pure JVM gate tests verifying confirmation and volume closure rules.
 */
class NtfsFsGateTest {

    private class FakeBlockDevice : BlockDevice {
        override val sectorSize: Int = 512
        override val sectorCount: ULong = 4096uL
        override fun read(lba: Long, destination: ByteBuffer) = Unit
        override fun write(lba: Long, source: ByteBuffer) = Unit
        override fun read(lba: ULong, destination: ByteBuffer) = read(lba.toLong(), destination)
        override fun write(lba: ULong, source: ByteBuffer) = write(lba.toLong(), source)
        override fun sync() = Unit
    }

    @Test
    fun confirmationConstantIsStable() {
        assertEquals("CONFIRM_NTFS_CLEAR_DIRTY", NtfsFs.CLEAR_DIRTY_CONFIRMATION)
    }

    @Test
    fun wrongConfirmationRejectedBeforeGateOrNative() {
        var gateCalled = false
        val gate = NtfsFs.MountedVolumeGate { gateCalled = true }
        val dev = FakeBlockDevice()

        try {
            NtfsFs.clearDirtyFlag(dev, "WRONG_TOKEN", gate)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("token mismatch") == true)
        }
        assertFalse(gateCalled)
    }

    @Test
    fun mountedVolumeGateClosesBeforeClear() {
        var gateCalled = false
        val gate = NtfsFs.MountedVolumeGate { gateCalled = true }
        val dev = FakeBlockDevice()

        try {
            // Will fail at native loading/call if in pure JVM, but gate must have executed first!
            NtfsFs.clearDirtyFlag(dev, NtfsFs.CLEAR_DIRTY_CONFIRMATION, gate)
        } catch (_: EngineMissingException) {
            // Native NTFS symbols absent when USB_WITH_NTFS=OFF / host JVM.
        } catch (_: UnsatisfiedLinkError) {
            // Native library absent in pure JVM test runner
        } catch (_: Exception) {
            // Native invocation or mock failure
        }
        assertTrue("Gate closeAll must be called before write path", gateCalled)

    }
}
