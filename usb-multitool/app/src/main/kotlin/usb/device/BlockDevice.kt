package usb.device

import java.nio.ByteBuffer

/**
 * Relative-LBA block view. Partition start is applied exactly once by the
 * implementor (e.g. VirtualPartition). Engines must not add a second offset.
 *
 * LBA parameters use [Long] (not ULong) so JNI can bind stable JVM names
 * `read`/`write` without Kotlin inline-class mangling.
 */
interface BlockDevice {
    val sectorSize: Int
    val sectorCount: ULong
    fun read(lba: Long, destination: ByteBuffer)
    fun write(lba: Long, source: ByteBuffer)
    fun sync()
}
