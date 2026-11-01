package usb.device

import java.nio.ByteBuffer

/**
 * Relative-LBA block backend shared across disk sessions, partitions, and JNI bridges.
 *
 * Implementations add partition start offsets exactly once. No downstream engine
 * receives a pre-adjusted LBA. Reads and writes outside 0 until sectorCount must fail.
 */
interface BlockDevice {
    val sectorSize: Int
    val sectorCount: ULong

    fun read(lba: Long, destination: ByteBuffer)
    fun write(lba: Long, source: ByteBuffer)
    fun sync()
}
