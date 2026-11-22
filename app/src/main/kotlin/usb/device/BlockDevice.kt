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

    /**
     * Stable JNI entry points taking signed [Long] so JNI can bind stable JVM
     * names `read`/`write` without Kotlin value-class name mangling.
     */
    fun read(lba: Long, destination: ByteBuffer) = read(lba.toULong(), destination)
    fun write(lba: Long, source: ByteBuffer) = write(lba.toULong(), source)

    fun sync()
}

/** Byte capacity derived from geometry; avoids requiring [UsbDisk] where only a block device is needed. */
val BlockDevice.capacityBytes: ULong
    get() = sectorCount * sectorSize.toULong()
