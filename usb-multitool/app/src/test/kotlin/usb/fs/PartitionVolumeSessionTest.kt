package usb.fs

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk

/**
 * Mount failure must keep the probe/mount cause and attach a disk-close error as
 * suppressed — never swap primary for the secondary close failure.
 */
class PartitionVolumeSessionTest {
    @Test
    fun mountFailureKeepsPrimaryAndSuppressesDiskClose() {
        val session =
            object : FakeBotSession() {
                override fun close() {
                    throw IOException("disk close failed after mount")
                }
            }
        // Empty media: probe finds no FS signature → open fails before any mount.
        val disk = UsbDisk(session = session, geometry = BlockGeometry(512, 64UL))

        val thrown =
            assertThrows(UnsupportedFilesystemException::class.java) {
                PartitionVolumeSession.open(disk, startLba = 0UL, sectorCount = 64UL)
            }

        assertTrue(thrown.message!!.contains("no supported filesystem signature"))
        assertEquals(1, thrown.suppressed.size)
        assertTrue(thrown.suppressed.single() is IOException)
        assertTrue(thrown.suppressed.single().message!!.contains("disk close failed after mount"))
    }
}
