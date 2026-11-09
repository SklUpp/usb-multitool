package usb.partition

import java.nio.ByteBuffer
import usb.device.BlockDevice

class RecordingBlockDevice(
    override val sectorCount: ULong,
    override val sectorSize: Int = 512,
) : BlockDevice {
    val reads = mutableListOf<ULong>()
    val writes = mutableListOf<ULong>()
    var lastWrite: ByteArray? = null
    var syncCount: Int = 0
        private set

    override fun read(lba: ULong, destination: ByteBuffer) {
        reads.add(lba)
        val n = destination.remaining()
        destination.position(destination.position() + n)
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        writes.add(lba)
        val copy = source.slice()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        lastWrite = bytes
        source.position(source.limit())
    }
    override fun sync() {
        syncCount++
    }
}
