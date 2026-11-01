package usb.device

import java.nio.ByteBuffer

class FakeTransport(
    private val stallsBeforeSuccess: Int = 0,
    private val disconnectOnCommand: Boolean = false,
    private val maxTransfer: Int = Int.MAX_VALUE,
) : BlockTransport {
    var resetCount: Int = 0
        private set

    private var remainingStalls = stallsBeforeSuccess
    private var connected = true
    private var phase = Phase.IDLE
    private var pendingLength = 0
    private var expectIn = true

    override fun command(cdb: ByteArray) {
        if (disconnectOnCommand || !connected) {
            connected = false
            throw DiskException.Disconnected()
        }
        expectIn = when (cdb[0].toInt() and 0xFF) {
            0x28, 0x88 -> true
            0x2A, 0x8A -> false
            else -> true
        }
        val blocks = if (cdb.size >= 16) {
            ((cdb[10].toInt() and 0xFF) shl 24) or
                ((cdb[11].toInt() and 0xFF) shl 16) or
                ((cdb[12].toInt() and 0xFF) shl 8) or
                (cdb[13].toInt() and 0xFF)
        } else {
            ((cdb[7].toInt() and 0xFF) shl 8) or (cdb[8].toInt() and 0xFF)
        }
        // Sector size unknown here; UsbDisk always requests whole sectors. Length set by data calls.
        pendingLength = blocks // filled in data* via destination.remaining tracking
        phase = Phase.DATA
        // Keep blocks so data phase knows expected total via first data call remaining arg.
        this.blocksHint = blocks
    }

    private var blocksHint = 0
    private var transferred = 0
    private var expected = 0

    override fun dataIn(destination: ByteBuffer, length: Int): Int {
        check(phase == Phase.DATA && expectIn)
        if (expected == 0) expected = length
        val n = minOf(length, maxTransfer, destination.remaining())
        val start = destination.position()
        for (i in 0 until n) {
            destination.put((0xA0 + ((start + i) and 0x0F)).toByte())
        }
        transferred += n
        pendingLength = expected - transferred
        return n
    }

    override fun dataOut(source: ByteBuffer, length: Int): Int {
        check(phase == Phase.DATA && !expectIn)
        if (expected == 0) expected = length
        val n = minOf(length, maxTransfer, source.remaining())
        source.position(source.position() + n)
        transferred += n
        pendingLength = expected - transferred
        return n
    }

    override fun status(): BotStatus {
        check(phase == Phase.DATA)
        phase = Phase.IDLE
        val done = transferred
        transferred = 0
        expected = 0
        pendingLength = 0
        if (remainingStalls > 0) {
            remainingStalls--
            return BotStatus.STALL
        }
        return if (done > 0 || blocksHint == 0) BotStatus.OK else BotStatus.FAIL
    }

    override fun clearHalt() {
        // no-op for fake
    }

    override fun requestSense(): ByteArray = ByteArray(18)

    override fun bulkOnlyReset() {
        resetCount++
        phase = Phase.IDLE
        transferred = 0
        expected = 0
    }

    override fun isConnected(): Boolean = connected

    private enum class Phase { IDLE, DATA }
}
