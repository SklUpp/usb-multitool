package usb.device

import java.nio.ByteBuffer

/**
 * Relative-LBA block contract shared by engines and UI facades.
 * Callers pass partition-relative LBAs; absolute offset applied once upstream.
 */
interface BlockDevice {
    val sectorSize: Int
    val sectorCount: ULong

    fun read(lba: Long, destination: ByteBuffer)

    fun write(lba: Long, source: ByteBuffer)

    fun sync()
}
