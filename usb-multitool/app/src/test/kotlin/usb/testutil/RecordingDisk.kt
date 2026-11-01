package usb.testutil

import java.nio.ByteBuffer
import usb.device.BlockGeometry
import usb.device.BlockTransport
import usb.device.UsbDisk
import usb.partition.VirtualPartition

/** In-memory USB disk used by native-pipeline unit tests. */
class RecordingDisk private constructor(
    private val memory: MemoryTransport,
    geometry: BlockGeometry,
) : UsbDisk(transport = memory, geometry = geometry) {

    constructor(
        sectorCount: ULong,
        sectorSize: Int = 512,
        initial: ByteArray? = null,
    ) : this(
        memory = MemoryTransport(sectorSize, sectorCount, initial),
        geometry = BlockGeometry(sectorSize, sectorCount),
    )

    val reads: List<ULong> get() = memory.reads.toList()
    val writes: List<ULong> get() = memory.writes.toList()
    val bytesWritten: Int get() = memory.bytesWritten

    fun bytes(): ByteArray = memory.bytes.copyOf()

    companion object {
        fun fromBytes(image: ByteArray, sectorSize: Int = 512): RecordingDisk {
            require(image.size % sectorSize == 0) { "image not sector-aligned" }
            val sectors = (image.size / sectorSize).toULong()
            return RecordingDisk(sectorCount = sectors, sectorSize = sectorSize, initial = image)
        }

        fun fromAsset(path: String, sectorSize: Int = 512): RecordingDisk {
            val stream = checkNotNull(
                RecordingDisk::class.java.classLoader!!.getResourceAsStream(path),
            ) { "missing test asset $path" }
            return fromBytes(stream.use { it.readBytes() }, sectorSize)
        }
    }
}

private class MemoryTransport(
    private val sectorSize: Int,
    private val sectorCount: ULong,
    initial: ByteArray?,
) : BlockTransport {
    val bytes: ByteArray = ByteArray((sectorCount * sectorSize.toULong()).toInt())
    val reads = mutableListOf<ULong>()
    val writes = mutableListOf<ULong>()
    var bytesWritten: Int = 0
        private set

    init {
        if (initial != null) {
            require(initial.size <= bytes.size)
            System.arraycopy(initial, 0, bytes, 0, initial.size)
        }
    }

    override fun read(lba: ULong, destination: ByteBuffer) {
        reads += lba
        val offset = (lba * sectorSize.toULong()).toInt()
        val count = destination.remaining()
        destination.put(bytes, offset, count)
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        writes += lba
        val offset = (lba * sectorSize.toULong()).toInt()
        val count = source.remaining()
        source.get(bytes, offset, count)
        bytesWritten += count
    }

    override fun bulkOnlyReset() = Unit
}

object FatImage {
    /** Minimal FAT16 boot sector + reserved space for probe tests. */
    fun partition(sectorCount: ULong = 2048UL): VirtualPartition {
        val sectorSize = 512
        val image = ByteArray((sectorCount * sectorSize.toULong()).toInt())
        // Jump + OEM
        image[0] = 0xEB.toByte()
        image[1] = 0x3C.toByte()
        image[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray(Charsets.US_ASCII).copyInto(image, 3)
        // BPB
        put16(image, 11, sectorSize)
        image[13] = 4 // sectors per cluster
        put16(image, 14, 1) // reserved
        image[16] = 2 // FAT count
        put16(image, 17, 512) // root entries
        put16(image, 19, sectorCount.toInt().coerceAtMost(0xFFFF))
        image[21] = 0xF8.toByte()
        put16(image, 22, 32) // FAT size
        "FAT16   ".toByteArray(Charsets.US_ASCII).copyInto(image, 54)
        image[510] = 0x55.toByte()
        image[511] = 0xAA.toByte()
        val disk = RecordingDisk.fromBytes(image, sectorSize)
        return VirtualPartition(0UL, sectorCount, disk)
    }

    private fun put16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
