package usb.device

import java.nio.ByteBuffer

/** Sector geometry used by SCSI planning and partition views. */
data class BlockGeometry(
    val sectorSize: Int,
    val sectorCount: ULong,
) {
    init {
        require(sectorSize in 512..4096 && sectorSize.countOneBits() == 1) {
            "sectorSize must be power-of-two in 512..4096"
        }
    }

    val capacityBytes: ULong get() = sectorSize.toULong() * sectorCount
}

/** Testable BOT/SCSI boundary. */
interface BlockTransport {
    fun read(lba: ULong, destination: ByteBuffer)
    fun write(lba: ULong, source: ByteBuffer)
    fun bulkOnlyReset()
}

sealed class DiskException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Disconnected(cause: Throwable? = null) : DiskException("USB disk disconnected", cause)
    class Io(message: String, cause: Throwable? = null) : DiskException(message, cause)
}

/**
 * Exclusive disk session.
 * ponytail: native pipeline stub matching plan Task 3 surface; Core owns full BOT recovery.
 */
open class UsbDisk(
    private val transport: BlockTransport,
    val geometry: BlockGeometry,
) {
    @Volatile
    var isClosed: Boolean = false
        protected set

    open fun read(lba: ULong, destination: ByteBuffer) {
        checkOpen()
        requireInRange(lba, destination.remaining())
        transport.read(lba, destination)
    }

    open fun write(lba: ULong, source: ByteBuffer) {
        checkOpen()
        requireInRange(lba, source.remaining())
        transport.write(lba, source)
    }

    fun close() {
        isClosed = true
    }

    private fun checkOpen() {
        if (isClosed) throw DiskException.Disconnected()
    }

    private fun requireInRange(lba: ULong, byteCount: Int) {
        require(byteCount % geometry.sectorSize == 0) { "buffer not sector-aligned" }
        val blocks = (byteCount / geometry.sectorSize).toULong()
        require(blocks > 0u) { "empty transfer" }
        require(lba < geometry.sectorCount) { "LBA out of range" }
        require(lba + blocks <= geometry.sectorCount) { "transfer past end of disk" }
    }
}
