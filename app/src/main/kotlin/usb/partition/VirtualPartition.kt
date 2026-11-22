package usb.partition

import java.nio.ByteBuffer
import usb.device.BlockDevice

/**
 * Range-checked view over [disk]. Adds [startLba] exactly once.
 * Relative LBAs only — JNI/FatFs must never see absolute disk LBAs.
 */
class VirtualPartition(
    val startLba: ULong,
    override val sectorCount: ULong,
    private val disk: BlockDevice,
) : BlockDevice {
    override val sectorSize: Int get() = disk.sectorSize

    init {
        require(sectorCount > 0UL) { "sectorCount must be positive" }
        val end = startLba + sectorCount
        require(end >= startLba) { "startLba + sectorCount overflows" }
        require(end <= disk.sectorCount) { "partition exceeds disk capacity" }
    }

    override fun read(lba: ULong, destination: ByteBuffer) {
        disk.read(mapLba(lba, destination.remaining()), destination)
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        disk.write(mapLba(lba, source.remaining()), source)
    }

    override fun sync() {
        disk.sync()
    }

    private fun mapLba(lba: ULong, byteCount: Int): ULong {
        require(byteCount % sectorSize == 0) {
            "length $byteCount not multiple of sectorSize $sectorSize"
        }
        val blocks = (byteCount / sectorSize).toULong()
        if (blocks == 0UL) throw IndexOutOfBoundsException("empty transfer")
        if (lba >= sectorCount || blocks > sectorCount - lba) {
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
