package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockDevice
import java.nio.ByteBuffer

/**
 * Pure-JVM gate tests. Native check/repair path is covered by the host C
 * binary wired into GitHub Actions (`exfat_fsck_host_test`).
 */
class ExfatFsckGateTest {
    private class FakeDevice : BlockDevice {
        override val sectorSize: Int = 512
        override val sectorCount: ULong = 2048uL
        override fun read(lba: ULong, destination: ByteBuffer) = Unit
        override fun write(lba: ULong, source: ByteBuffer) = Unit
        override fun sync() = Unit
    }

    @Test
    fun repairConfirmationConstantIsStable() {
        assertEquals("CONFIRM_EXFAT_REPAIR", ExfatFsck.REPAIR_CONFIRMATION)
    }

    @Test
    fun wrongConfirmationRejectedBeforeCloseOrNative() {
        var closed = false
        val gate = ExfatFsck.MountedVolumeGate { closed = true }
        val result = ExfatFsck.repair(
            device = FakeDevice(),
            confirmationToken = "nope",
            volumeGate = gate,
        )
        assertEquals(ExfatFsck.Result.OperationError, result)
        assertFalse(closed)
    }

    @Test
    fun mountedVolumeGateClosesBeforeRepairWrites() {
        var closed = false
        val gate = ExfatFsck.MountedVolumeGate { closed = true }
        gate.closeAll(FakeDevice())
        assertTrue(closed)
        assertTrue(ExfatFsck.REPAIR_CONFIRMATION.startsWith("CONFIRM_"))
    }
}
