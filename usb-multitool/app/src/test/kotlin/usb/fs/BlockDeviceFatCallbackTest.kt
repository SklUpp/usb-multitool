package usb.fs

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockDevice
import usb.device.DiskException
import usb.partition.ImageBlockDevice

/**
 * Behaviour of the FatFs block backend on a plain JVM image:
 * absolute sector placement, request validation, and errno mapping.
 */
class BlockDeviceFatCallbackTest {
    private val sectorSize = 512

    private fun disk(sectors: Int = 16): ImageBlockDevice =
        ImageBlockDevice(ByteArray(sectors * sectorSize), sectorSize, sectors.toULong())

    @Test
    fun multiSectorWriteLandsAtAbsoluteSectors() {
        val disk = disk()
        val callback = BlockDeviceFatCallback(disk)
        val payload = ByteArray(sectorSize * 3) { index -> (index % 251).toByte() }

        assertEquals(0, callback.writeSectors(4, payload))

        val image = disk.snapshot()
        assertArrayEquals(payload, image.copyOfRange(4 * sectorSize, 7 * sectorSize))
        assertTrue(image.copyOfRange(0, 4 * sectorSize).all { it == 0.toByte() })
        assertTrue(image.copyOfRange(7 * sectorSize, image.size).all { it == 0.toByte() })
    }

    @Test
    fun multiSectorReadReturnsTheAbsoluteSectors() {
        val disk = disk()
        val pattern = ByteArray(sectorSize * 2) { index -> (index + 1).toByte() }
        disk.write(5uL, ByteBuffer.wrap(pattern))
        val callback = BlockDeviceFatCallback(disk)

        val buffer = ByteArray(sectorSize * 2)
        assertEquals(0, callback.readSectors(5L, buffer))
        assertArrayEquals(pattern, buffer)
    }

    @Test
    fun misalignedAndEmptyRequestsAreRejectedWithoutIo() {
        val disk = disk()
        val callback = BlockDeviceFatCallback(disk)

        assertEquals(FAT_EINVAL, callback.readSectors(0L, ByteArray(sectorSize + 1)))
        assertEquals(FAT_EINVAL, callback.writeSectors(0L, ByteArray(sectorSize / 2)))
        assertEquals(FAT_EINVAL, callback.readSectors(0L, ByteArray(0)))
        assertEquals(FAT_EINVAL, callback.writeSectors(0L, ByteArray(0)))
        assertTrue(disk.snapshot().all { it == 0.toByte() })
    }

    @Test
    fun outOfRangeRequestsAreRejectedWithoutIo() {
        val disk = disk(sectors = 8)
        val callback = BlockDeviceFatCallback(disk)

        // Two sectors starting at the last sector (7) cross the end of the device.
        assertEquals(FAT_EINVAL, callback.writeSectors(7L, ByteArray(sectorSize * 2)))
        assertEquals(FAT_EINVAL, callback.readSectors(-1L, ByteArray(sectorSize)))
        assertEquals(FAT_EINVAL, callback.readSectors(8L, ByteArray(sectorSize)))
        assertEquals(FAT_EINVAL, callback.writeSectors(8L, ByteArray(sectorSize)))
        assertTrue(disk.snapshot().all { it == 0.toByte() })
    }

    @Test
    fun deviceFailuresMapToErrnoCodes() {
        val failing = object : BlockDevice {
            override val sectorSize = 512
            override val sectorCount = 8uL

            override fun read(lba: ULong, destination: ByteBuffer) {
                throw DiskException.IoFailure("bulk transfer failed")
            }

            override fun write(lba: ULong, source: ByteBuffer) {
                throw IllegalArgumentException("rejected by device")
            }

            override fun sync() {
                throw DiskException.Disconnected()
            }
        }
        val callback = BlockDeviceFatCallback(failing)

        assertEquals(FAT_EIO, callback.readSectors(0L, ByteArray(sectorSize)))
        assertEquals(FAT_EINVAL, callback.writeSectors(0L, ByteArray(sectorSize)))
        assertEquals(FAT_EIO, callback.sync())
    }

    @Test
    fun syncIsForwardedToTheDevice() {
        var syncs = 0
        val counting = object : BlockDevice by disk() {
            override fun sync() {
                syncs++
            }
        }

        assertEquals(0, BlockDeviceFatCallback(counting).sync())
        assertEquals(1, syncs)
    }
}
