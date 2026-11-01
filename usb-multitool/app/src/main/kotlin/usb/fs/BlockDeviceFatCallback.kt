package usb.fs

import java.nio.ByteBuffer
import usb.device.BlockDevice
import usb.device.DiskException

/**
 * Bridges the FatFs JNI block backend onto any [BlockDevice].
 *
 * The C engine calls with a sector-aligned byte array and needs `0` on success and a
 * negative errno on failure. Failures are never swallowed: they become the matching
 * errno so FatFs maps them to [FatFsException]/FR_DISK_ERR instead of silently
 * returning stale data.
 */
class BlockDeviceFatCallback(
    private val device: BlockDevice,
) : FatBlockCallback {
    private val sectorSize = device.sectorSize

    override fun readSectors(lba: Long, buffer: ByteArray): Int {
        if (!validRequest(lba, buffer.size)) return -EINVAL
        return try {
            device.read(lba.toULong(), ByteBuffer.wrap(buffer))
            0
        } catch (_: DiskException) {
            -EIO
        } catch (_: IndexOutOfBoundsException) {
            -EINVAL
        } catch (_: IllegalArgumentException) {
            -EINVAL
        }
    }

    override fun writeSectors(lba: Long, buffer: ByteArray): Int {
        if (!validRequest(lba, buffer.size)) return -EINVAL
        return try {
            device.write(lba.toULong(), ByteBuffer.wrap(buffer))
            0
        } catch (_: DiskException) {
            -EIO
        } catch (_: IndexOutOfBoundsException) {
            -EINVAL
        } catch (_: IllegalArgumentException) {
            -EINVAL
        }
    }

    override fun sync(): Int =
        try {
            device.sync()
            0
        } catch (_: DiskException) {
            -EIO
        }

    private fun validRequest(lba: Long, bytes: Int): Boolean {
        if (lba < 0L || bytes <= 0 || bytes % sectorSize != 0) return false
        val blocks = (bytes / sectorSize).toULong()
        val capacity = device.sectorCount
        return blocks <= capacity && lba.toULong() <= capacity - blocks
    }

    private companion object {
        /** Linux errno values the FatFs bridge understands. */
        const val EIO = 5
        const val EINVAL = 22
    }
}
