package usb.device

import java.nio.ByteBuffer

/**
 * Relative-LBA block view. Partition start is applied exactly once by the
 * implementor (e.g. VirtualPartition). Engines must not add a second offset.
 */
interface BlockDevice {
    val sectorSize: Int
    val sectorCount: ULong
    fun read(lba: ULong, destination: ByteBuffer)
    fun write(lba: ULong, source: ByteBuffer)
    fun sync()
}
