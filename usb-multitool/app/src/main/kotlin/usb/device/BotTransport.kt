package usb.device

import java.nio.ByteBuffer

/** Low-level BOT phases over USB bulk endpoints. */
interface BlockTransport {
    fun command(cdb: ByteArray)
    fun dataIn(destination: ByteBuffer, length: Int): Int
    fun dataOut(source: ByteBuffer, length: Int): Int
    fun status(): BotStatus
    fun clearHalt()
    fun requestSense(): ByteArray
    fun bulkOnlyReset()
    fun isConnected(): Boolean
}

enum class BotStatus {
    OK,
    STALL,
    FAIL,
}

object Recovery {
    fun bulkOnlyReset(transport: BlockTransport) {
        transport.clearHalt()
        transport.requestSense()
        transport.bulkOnlyReset()
    }
}

sealed class DiskException(message: String) : Exception(message) {
    class Disconnected : DiskException("USB mass-storage device disconnected")
    class IoFailure(message: String) : DiskException(message)
    class Closed : DiskException("disk session closed")
}
