package usb.fs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import usb.partition.ImageBlockDevice

class BlockDeviceFatCallbackTest {
    private fun device(): ImageBlockDevice {
        val image = ByteArray(512 * 16)
        image[512 * 3 + 7] = 0x5A
        return ImageBlockDevice(image, 512, 16UL)
    }

    @Test
    fun multiSectorReadMapsAbsoluteOffsets() {
        val callback = BlockDeviceFatCallback(device())
        val buffer = ByteArray(1024)

        assertEquals(0, callback.readSectors(2L, buffer))
        assertEquals(0x5A.toByte(), buffer[512 + 7])
    }

    @Test
    fun multiSectorWriteLandsAtTheRequestedLba() {
        val disk = device()
        val callback = BlockDeviceFatCallback(disk)
        val payload = ByteArray(1024) { 0x11 }

        assertEquals(0, callback.writeSectors(4L, payload))

        val readBack = ByteArray(1024)
        assertEquals(0, callback.readSectors(4L, readBack))
        assertArrayEquals(payload, readBack)
    }

    @Test
    fun misalignedAndOutOfRangeRequestsAreRejected() {
        val callback = BlockDeviceFatCallback(device())

        assertEquals(-22, callback.readSectors(0L, ByteArray(500)))
        assertEquals(-22, callback.readSectors(16L, ByteArray(512)))
        assertEquals(-22, callback.readSectors(15L, ByteArray(1024)))
        assertEquals(-22, callback.writeSectors(0L, ByteArray(0)))
    }
}
