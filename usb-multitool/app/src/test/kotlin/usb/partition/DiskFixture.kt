package usb.partition

import java.nio.ByteBuffer
import usb.device.BlockDevice

object DiskFixture {
    fun load(resource: String, sectorSize: Int = 512): BlockDevice {
        val bytes = checkNotNull(
            DiskFixture::class.java.classLoader.getResourceAsStream(resource),
        ) { "missing resource $resource" }.readBytes()
        require(bytes.size % sectorSize == 0) {
            "fixture size ${bytes.size} not multiple of $sectorSize"
        }
        val sectors = (bytes.size / sectorSize).toULong()
        return ImageBlockDevice(bytes, sectorSize, sectors)
    }

    fun empty(sectorCount: ULong, sectorSize: Int = 512): BlockDevice {
        val bytes = ByteArray((sectorCount * sectorSize.toULong()).toInt())
        return ImageBlockDevice(bytes, sectorSize, sectorCount)
    }
}

class ImageBlockDevice(
    private val image: ByteArray,
    override val sectorSize: Int,
    override val sectorCount: ULong,
) : BlockDevice {
    val writes: MutableList<ULong> = mutableListOf()
    override fun read(lba: ULong, destination: ByteBuffer) {
        val bytes = destination.remaining()
        require(bytes % sectorSize == 0)
        val blocks = bytes / sectorSize
        require(blocks.toULong() <= sectorCount && lba <= sectorCount - blocks.toULong())
        val offset = (lba * sectorSize.toULong()).toInt()
        destination.put(image, offset, bytes)
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        writes.add(lba)
        val bytes = source.remaining()
        require(bytes % sectorSize == 0)
        val blocks = bytes / sectorSize
        require(blocks.toULong() <= sectorCount && lba <= sectorCount - blocks.toULong())
        val offset = (lba * sectorSize.toULong()).toInt()
        source.get(image, offset, bytes)
    }

    override fun sync() = Unit

    fun snapshot(): ByteArray = image.copyOf()
}
