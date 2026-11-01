package usb.partition

import java.nio.ByteBuffer
import usb.device.BlockGeometry
import usb.device.BlockTransport
import usb.device.BotStatus
import usb.device.UsbDisk

object DiskFixture {
    fun load(resource: String, sectorSize: Int = 512): UsbDisk {
        val bytes = checkNotNull(
            DiskFixture::class.java.classLoader.getResourceAsStream(resource),
        ) { "missing resource $resource" }.readBytes()
        require(bytes.size % sectorSize == 0) {
            "fixture size ${bytes.size} not multiple of $sectorSize"
        }
        val sectors = (bytes.size / sectorSize).toULong()
        return ImageDisk(bytes, BlockGeometry(sectorSize, sectors))
    }

    fun empty(sectorCount: ULong, sectorSize: Int = 512): UsbDisk {
        val bytes = ByteArray((sectorCount * sectorSize.toULong()).toInt())
        return ImageDisk(bytes, BlockGeometry(sectorSize, sectorCount))
    }
}

private class ImageDisk(
    private val image: ByteArray,
    geometry: BlockGeometry,
) : UsbDisk(NullTransport, geometry) {
    override fun read(lba: ULong, destination: ByteBuffer) {
        val sectorSize = geometry.sectorSize
        val bytes = destination.remaining()
        require(bytes % sectorSize == 0)
        val blocks = bytes / sectorSize
        require(lba + blocks.toULong() <= geometry.sectorCount)
        val offset = (lba * sectorSize.toULong()).toInt()
        destination.put(image, offset, bytes)
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        throw UnsupportedOperationException("fixture is read-only")
    }

    private object NullTransport : BlockTransport {
        override fun command(cdb: ByteArray) = Unit
        override fun dataIn(destination: ByteBuffer, length: Int) = 0
        override fun dataOut(source: ByteBuffer, length: Int) = 0
        override fun status() = BotStatus.OK
        override fun clearHalt() = Unit
        override fun requestSense() = ByteArray(0)
        override fun bulkOnlyReset() = Unit
        override fun isConnected() = true
    }
}
