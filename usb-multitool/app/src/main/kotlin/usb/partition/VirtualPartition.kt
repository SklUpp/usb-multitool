package usb.partition

import java.nio.ByteBuffer
import usb.device.UsbDisk

data class Partition(
    val startLba: ULong,
    val sectorCount: ULong,
    val type: PartitionType = PartitionType.BASIC,
    val name: String = "",
    val bootable: Boolean = false,
    val esp: Boolean = false,
)

data class FreeRegion(
    val startLba: ULong,
    val sectorCount: ULong,
)

enum class PartitionType {
    BASIC,
    EFI,
    VTOYEFI,
    LINUX,
    NTFS,
    FAT,
    UNKNOWN,
}

/**
 * Range-checked partition view. Adds [startLba] exactly once.
 * ponytail: native stub of plan Task 4; Core owns production bounds tests.
 */
class VirtualPartition(
    val startLba: ULong,
    val sectorCount: ULong,
    val disk: UsbDisk,
) {
    val sectorSize: Int get() = disk.geometry.sectorSize

    fun read(lba: ULong, destination: ByteBuffer) {
        val absolute = checkedAbsolute(lba, destination.remaining())
        disk.read(absolute, destination)
    }

    fun write(lba: ULong, source: ByteBuffer) {
        val absolute = checkedAbsolute(lba, source.remaining())
        disk.write(absolute, source)
    }

    private fun checkedAbsolute(relativeLba: ULong, byteCount: Int): ULong {
        require(byteCount % sectorSize == 0) { "buffer not sector-aligned" }
        val blocks = (byteCount / sectorSize).toULong()
        if (relativeLba >= sectorCount || relativeLba + blocks > sectorCount) {
            throw IndexOutOfBoundsException(
                "relative LBA $relativeLba+$blocks outside partition of $sectorCount",
            )
        }
        val absolute = startLba + relativeLba
        if (absolute < startLba) {
            throw IndexOutOfBoundsException("LBA addition overflow")
        }
        return absolute
    }
}
