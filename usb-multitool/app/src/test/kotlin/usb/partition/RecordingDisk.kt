package usb.partition

import java.nio.ByteBuffer
import usb.device.BlockGeometry
import usb.device.BlockTransport
import usb.device.BotStatus
import usb.device.UsbDisk

class RecordingDisk(
    sectorCount: ULong,
    sectorSize: Int = 512,
) : UsbDisk(NullTransport, BlockGeometry(sectorSize, sectorCount)) {
    val reads = mutableListOf<ULong>()
    val writes = mutableListOf<ULong>()
    var bytesWritten: Int = 0
        private set

    override fun read(lba: ULong, destination: ByteBuffer) {
        reads.add(lba)
        val n = destination.remaining()
        destination.position(destination.position() + n)
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        writes.add(lba)
        val n = source.remaining()
        bytesWritten += n
        source.position(source.position() + n)
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
