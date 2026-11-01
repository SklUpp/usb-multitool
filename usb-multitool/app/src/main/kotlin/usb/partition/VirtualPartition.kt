package usb.partition

import java.nio.ByteBuffer
import usb.device.UsbDisk

class VirtualPartition(
    val startLba: ULong,
    val sectorCount: ULong,
    private val disk: UsbDisk,
) {
    init {
        require(sectorCount > 0UL) { "sectorCount must be positive" }
        val end = startLba + sectorCount
        require(end >= startLba) { "startLba + sectorCount overflows" }
        require(end <= disk.geometry.sectorCount) {
            "partition exceeds disk capacity"
        }
    }

    fun read(lba: ULong, destination: ByteBuffer) {
        val absolute = mapLba(lba, destination.remaining())
        disk.read(absolute, destination)
    }

    fun write(lba: ULong, source: ByteBuffer) {
        val absolute = mapLba(lba, source.remaining())
        disk.write(absolute, source)
    }

    private fun mapLba(lba: ULong, byteCount: Int): ULong {
        val sectorSize = disk.geometry.sectorSize
        require(byteCount % sectorSize == 0) {
            "length $byteCount not multiple of sectorSize $sectorSize"
        }
        val blocks = (byteCount / sectorSize).toULong()
        if (blocks == 0UL) throw IndexOutOfBoundsException("empty transfer")
        if (lba >= sectorCount || lba + blocks > sectorCount || lba + blocks < lba) {
            throw IndexOutOfBoundsException(
                "lba=$lba blocks=$blocks outside partition sectorCount=$sectorCount",
            )
        }
        val absolute = startLba + lba
        if (absolute < startLba) {
            throw IndexOutOfBoundsException("absolute LBA overflow")
        }
        return absolute
    }
}
