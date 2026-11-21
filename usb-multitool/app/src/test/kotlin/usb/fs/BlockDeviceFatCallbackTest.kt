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

    private fun FatBlockCallback.read(lba: Long, buffer: ByteArray): Int =
        readSectors(lba, buffer, buffer.size)

    private fun FatBlockCallback.write(lba: Long, buffer: ByteArray): Int =
        writeSectors(lba, buffer, buffer.size)

    @Test
    fun multiSectorWriteLandsAtAbsoluteSectors() {
        val disk = disk()
        val callback = BlockDeviceFatCallback(disk)
        val payload = ByteArray(sectorSize * 3) { index -> (index % 251).toByte() }

        assertEquals(0, callback.write(4, payload))

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
        assertEquals(0, callback.read(5L, buffer))
        assertArrayEquals(pattern, buffer)
    }

    @Test
    fun reusableScratchArrayTransfersOnlyTheDeclaredLength() {
        val disk = disk()
        val pattern = ByteArray(sectorSize * 2) { index -> (index + 3).toByte() }
        disk.write(1uL, ByteBuffer.wrap(pattern))
        val callback = BlockDeviceFatCallback(disk)
        val scratch = ByteArray(sectorSize * 4)

        assertEquals(0, callback.readSectors(1L, scratch, sectorSize * 2))
        assertArrayEquals(pattern, scratch.copyOfRange(0, sectorSize * 2))
        assertTrue(scratch.copyOfRange(sectorSize * 2, scratch.size).all { it == 0.toByte() })

        val write = ByteArray(sectorSize * 4) { index -> (index + 7).toByte() }
        assertEquals(0, callback.writeSectors(3L, write, sectorSize * 2))
        val image = disk.snapshot()
        assertArrayEquals(
            write.copyOfRange(0, sectorSize * 2),
            image.copyOfRange(3 * sectorSize, 5 * sectorSize),
        )
        assertTrue(image.copyOfRange(5 * sectorSize, 7 * sectorSize).all { it == 0.toByte() })
    }

    @Test
    fun misalignedAndEmptyRequestsAreRejectedWithoutIo() {
        val disk = disk()
        val callback = BlockDeviceFatCallback(disk)

        assertEquals(FAT_EINVAL, callback.read(0L, ByteArray(sectorSize + 1)))
        assertEquals(FAT_EINVAL, callback.write(0L, ByteArray(sectorSize / 2)))
        assertEquals(FAT_EINVAL, callback.read(0L, ByteArray(0)))
        assertEquals(FAT_EINVAL, callback.write(0L, ByteArray(0)))
        assertTrue(disk.snapshot().all { it == 0.toByte() })
    }

    @Test
    fun outOfRangeRequestsAreRejectedWithoutIo() {
        val disk = disk(sectors = 8)
        val callback = BlockDeviceFatCallback(disk)

        // Two sectors starting at the last sector (7) cross the end of the device.
        assertEquals(FAT_EINVAL, callback.write(7L, ByteArray(sectorSize * 2)))
        assertEquals(FAT_EINVAL, callback.read(-1L, ByteArray(sectorSize)))
        assertEquals(FAT_EINVAL, callback.read(8L, ByteArray(sectorSize)))
        assertEquals(FAT_EINVAL, callback.write(8L, ByteArray(sectorSize)))
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

        assertEquals(FAT_EIO, callback.read(0L, ByteArray(sectorSize)))
        assertEquals(FAT_EINVAL, callback.write(0L, ByteArray(sectorSize)))
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
