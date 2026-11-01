package usb.fs

import java.nio.ByteBuffer
import usb.device.BlockDevice

/**
 * Bridges the FatFs JNI block backend onto any [BlockDevice].
 *
 * The C engine calls with a sector-aligned byte array and expects `0` on success,
 * a negative errno on failure. Failures are never swallowed silently: exceptions are
 * mapped to negative codes, and a device that stops respecting the geometry is
 * reported as an I/O error rather than corrupting the volume.
 */
class BlockDeviceFatCallback(
    private val device: BlockDevice,
) : FatBlockCallback {
    private val sectorSize = device.sectorSize

    override fun readSectors(lba: Long, buffer: ByteArray): Int {
        if (!validRequest(lba, buffer.size)) return -EINVAL
        return try {
            val target = ByteBuffer.wrap(buffer)
            device.read(lba.toULong(), target)
            0
        } catch (_: DiskBackedIoFailure) {
            -EIO
        } catch (_: IllegalArgumentException) {
            -EINVAL
        }
    }

    override fun writeSectors(lba: Long, buffer: ByteArray): Int {
        if (!validRequest(lba, buffer.size)) return -EINVAL
        return try {
            device.write(lba.toULong(), ByteBuffer.wrap(buffer))
            0
        } catch (_: DiskBackedIoFailure) {
            -EIO
        } catch (_: IllegalArgumentException) {
            -EINVAL
        }
    }

    override fun sync(): Int =
        try {
            device.sync()
            0
        } catch (_: DiskBackedIoFailure) {
            -EIO
        }

    private fun validRequest(lba: Long, bytes: Int): Boolean {
        if (lba < 0L || bytes <= 0 || bytes % sectorSize != 0) return false
        val blocks = (bytes / sectorSize).toULong()
        val capacity = device.sectorCount
        return blocks <= capacity && lba.toULong() <= capacity - blocks
    }

    /** Device failures surface as [Exception] subtypes; isolate the ones we map. */
    private class DiskBackedIoFailure : Exception()
}
