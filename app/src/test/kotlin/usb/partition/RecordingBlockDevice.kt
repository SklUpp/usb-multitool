package usb.partition

import java.nio.ByteBuffer
import usb.device.BlockDevice

/**
 * Sparse in-memory [BlockDevice] that records every read/write LBA while still
 * honouring durable read-after-write: bytes written to an LBA come back on the
 * next read. Unwritten sectors read as zeros. Suitable for huge geometries
 * (e.g. >2^32 sectors) where a dense image cannot be allocated.
 */
class RecordingBlockDevice(
    override val sectorCount: ULong,
    override val sectorSize: Int = 512,
) : BlockDevice {
    val reads = mutableListOf<ULong>()
    val writes = mutableListOf<ULong>()
    var lastWrite: ByteArray? = null
    var syncCount: Int = 0
        private set

    private val sectors = mutableMapOf<ULong, ByteArray>()

    override fun read(lba: ULong, destination: ByteBuffer) {
        reads.add(lba)
        val n = destination.remaining()
        require(n % sectorSize == 0)
        val blocks = n / sectorSize
        require(blocks.toULong() <= sectorCount && lba <= sectorCount - blocks.toULong())
        for (i in 0 until blocks) {
            val sector = sectors[lba + i.toULong()] ?: ByteArray(sectorSize)
            destination.put(sector)
        }
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        writes.add(lba)
        val copy = source.slice()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        lastWrite = bytes
        require(bytes.size % sectorSize == 0)
        val blocks = bytes.size / sectorSize
        require(blocks.toULong() <= sectorCount && lba <= sectorCount - blocks.toULong())
        for (i in 0 until blocks) {
            val offset = i * sectorSize
            sectors[lba + i.toULong()] = bytes.copyOfRange(offset, offset + sectorSize)
        }
        source.position(source.limit())
    }

    override fun sync() {
        syncCount++
    }
}
