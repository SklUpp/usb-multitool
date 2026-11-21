package usb.fs

import java.nio.ByteBuffer
import usb.device.BlockDevice
import usb.device.DiskException

/** errno values the FatFs block backend understands. */
internal const val FAT_EIO: Int = -5
internal const val FAT_EINVAL: Int = -22

/**
 * Bridges the FatFs JNI block backend onto any [BlockDevice].
 *
 * FatFs calls with a sector-aligned byte array and expects `0` on success, a negative
 * errno on failure. Geometry violations are rejected before any I/O; device/transport
 * failures become [FAT_EIO]; unknown failures are also reported as errors, never success.
 * The only per-call allocation is the [ByteBuffer] view over the caller's array.
 */
class BlockDeviceFatCallback(
    private val device: BlockDevice,
) : FatBlockCallback {
    private val sectorSize = device.sectorSize

    override fun readSectors(lba: Long, buffer: ByteArray, length: Int): Int =
        transfer(lba, buffer, length) {
            device.read(lba.toULong(), ByteBuffer.wrap(buffer, 0, length))
        }

    override fun writeSectors(lba: Long, buffer: ByteArray, length: Int): Int =
        transfer(lba, buffer, length) {
            device.write(lba.toULong(), ByteBuffer.wrap(buffer, 0, length))
        }

    override fun sync(): Int =
        runCatching { device.sync() }.fold({ 0 }, ::errno)

    private inline fun transfer(lba: Long, buffer: ByteArray, bytes: Int, block: () -> Unit): Int {
        if (!validRequest(lba, buffer, bytes)) return FAT_EINVAL
        return runCatching(block).fold({ 0 }, ::errno)
    }

    /** Aligned, non-empty, within the scratch array, and inside the device geometry. */
    private fun validRequest(lba: Long, buffer: ByteArray, bytes: Int): Boolean {
        if (lba < 0L || bytes <= 0 || bytes > buffer.size || bytes % sectorSize != 0) return false
        val blocks = (bytes / sectorSize).toULong()
        val capacity = device.sectorCount
        return blocks <= capacity && lba.toULong() <= capacity - blocks
    }

    private fun errno(failure: Throwable): Int =
        when (failure) {
            // Real transport/device faults: I/O error, not a bad request.
            is DiskException -> FAT_EIO
            // Geometry/argument violations raised by UsbDisk or VirtualPartition.
            is IllegalArgumentException -> FAT_EINVAL
            is IndexOutOfBoundsException -> FAT_EINVAL
            // Anything unknown must fail the FatFs call rather than fake success.
            else -> FAT_EIO
        }
}
