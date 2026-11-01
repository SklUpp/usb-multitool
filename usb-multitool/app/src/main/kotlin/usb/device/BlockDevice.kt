package usb.device

import java.nio.ByteBuffer

/** Relative-LBA block backend shared by disk, partitions, and JNI bridges. */
interface BlockDevice {
    val sectorSize: Int
    val sectorCount: ULong

    fun read(lba: ULong, destination: ByteBuffer)

    fun write(lba: ULong, source: ByteBuffer)

    fun sync()
}
