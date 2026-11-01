package usb.device

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Records BOT reset recovery order on a fake channel. */
class RecordingChannel(
    private var connected: Boolean = true,
) : UsbBulkChannel {
    val events = mutableListOf<String>()
    override val interfaceId: Int = 0

    override fun bulkIn(destination: ByteBuffer, length: Int): Int {
        events += "bulkIn:$length"
        destination.position(destination.position() + length)
        return length
    }

    override fun bulkOut(source: ByteBuffer, length: Int): Int {
        events += "bulkOut:$length"
        source.position(source.position() + length)
        return length
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
    ): Int {
        if (requestType == BotConstants.REQUEST_TYPE_CLASS_INTERFACE_OUT &&
            request == BotConstants.REQUEST_BULK_ONLY_RESET
        ) {
            events += "massStorageReset"
            return 0
        }
        events += "control:$requestType/$request"
        return 0
    }

    override fun clearHaltIn() {
        events += "clearHaltIn"
    }

    override fun clearHaltOut() {
        events += "clearHaltOut"
    }

    override fun isConnected(): Boolean = connected

    override fun close() {
        connected = false
        events += "close"
    }

    fun disconnect() {
        connected = false
    }
}

/**
 * Scriptable BOT session for UsbDisk unit tests.
 * Supports short data chunks, staged stalls, capacity responses, and sense scripts.
 */
class FakeBotSession(
    private var stallsBeforeSuccess: Int = 0,
    private val disconnectOnCommand: Boolean = false,
    private val maxChunk: Int = Int.MAX_VALUE,
    private val failAlways: Boolean = false,
    private val capacityLastLba10: ULong = 99UL,
    private val capacityBlockLength: Int = 512,
    private val capacity16LastLba: ULong? = null,
    private val syncSense: DiskException.SenseError? = null,
    private val phaseErrorOnOps: Set<Int> = emptySet(),
    private val residue: Int = 0,
) : BotSession {
    var resetCount: Int = 0
        private set
    val resetOrders = mutableListOf<List<String>>()
    val commands = mutableListOf<Int>()

    private var connected = true
    private val media = HashMap<ULong, ByteArray>()

    fun putSector(lba: ULong, data: ByteArray) {
        require(data.size == capacityBlockLength)
        media[lba] = data.copyOf()
    }

    override fun transfer(cdb: ScsiCdb, data: ByteBuffer?): CswResult {
        if (disconnectOnCommand || !connected) {
            connected = false
            throw DiskException.Disconnected()
        }
        val op = cdb.bytes[0].toInt() and 0xFF
        commands += op

        if (op in phaseErrorOnOps) {
            return CswResult(CswStatus.PHASE_ERROR, 0, 0)
        }
        if (failAlways && op != ScsiCommand.OP_REQUEST_SENSE) {
            return CswResult(CswStatus.FAILED, 0, residue)
        }
        if (stallsBeforeSuccess > 0 && op != ScsiCommand.OP_REQUEST_SENSE) {
            stallsBeforeSuccess--
            throw DiskException.IoFailure("simulated STALL")
        }

        return when (op) {
            ScsiCommand.OP_READ_CAPACITY10 -> {
                val buf = requireNotNull(data)
                buf.order(ByteOrder.BIG_ENDIAN)
                buf.putInt((capacityLastLba10 and 0xFFFF_FFFFUL).toInt())
                buf.putInt(capacityBlockLength)
                CswResult(CswStatus.PASSED, 0, 0)
            }
            ScsiCommand.OP_READ_CAPACITY16 -> {
                val last = capacity16LastLba
                    ?: throw DiskException.IoFailure("CAPACITY(16) not configured")
                val buf = requireNotNull(data)
                buf.order(ByteOrder.BIG_ENDIAN)
                buf.putLong(last.toLong())
                buf.putInt(capacityBlockLength)
                while (buf.position() < cdb.transferLength) {
                    buf.put(0)
                }
                CswResult(CswStatus.PASSED, 0, 0)
            }
            ScsiCommand.OP_REQUEST_SENSE -> {
                val buf = requireNotNull(data)
                val sense = syncSense ?: DiskException.SenseError(0x70, 0x00, 0x00, 0x00)
                val start = buf.position()
                for (i in 0 until cdb.transferLength) {
                    buf.put(0)
                }
                buf.put(start, (sense.responseCode or 0x70).toByte())
                buf.put(start + 2, sense.senseKey.toByte())
                buf.put(start + 12, sense.asc.toByte())
                buf.put(start + 13, sense.ascq.toByte())
                CswResult(CswStatus.PASSED, 0, 0)
            }
            ScsiCommand.OP_SYNCHRONIZE_CACHE10 -> {
                if (syncSense != null) {
                    CswResult(CswStatus.FAILED, 0, 0)
                } else {
                    CswResult(CswStatus.PASSED, 0, 0)
                }
            }
            ScsiCommand.OP_READ10, ScsiCommand.OP_READ16 -> {
                val (lba, blocks) = decodeLbaBlocks(cdb)
                fillRead(requireNotNull(data), lba, blocks)
                CswResult(CswStatus.PASSED, 0, residue)
            }
            ScsiCommand.OP_WRITE10, ScsiCommand.OP_WRITE16 -> {
                val (lba, blocks) = decodeLbaBlocks(cdb)
                drainWrite(requireNotNull(data), lba, blocks)
                CswResult(CswStatus.PASSED, 0, residue)
            }
            else -> CswResult(CswStatus.PASSED, 0, residue)
        }
    }

    override fun bulkOnlyReset() {
        resetCount++
        val channel = RecordingChannel()
        Recovery.bulkOnlyReset(channel)
        resetOrders += channel.events.toList()
    }

    override fun isConnected(): Boolean = connected

    override fun close() {
        connected = false
    }

    private fun fillRead(destination: ByteBuffer, lba: ULong, blocks: Int) {
        var cur = lba
        repeat(blocks) {
            val sector = media[cur] ?: ByteArray(capacityBlockLength) { i ->
                (0xA0 + ((i + cur.toInt()) and 0x0F)).toByte()
            }
            var offset = 0
            while (offset < capacityBlockLength) {
                val n = minOf(capacityBlockLength - offset, maxChunk, destination.remaining())
                require(n > 0) { "destination exhausted mid-sector" }
                destination.put(sector, offset, n)
                offset += n
            }
            cur++
        }
    }

    private fun drainWrite(source: ByteBuffer, lba: ULong, blocks: Int) {
        var cur = lba
        repeat(blocks) {
            val sector = ByteArray(capacityBlockLength)
            var offset = 0
            while (offset < capacityBlockLength) {
                val n = minOf(capacityBlockLength - offset, maxChunk, source.remaining())
                require(n > 0) { "source exhausted mid-sector" }
                source.get(sector, offset, n)
                offset += n
            }
            media[cur] = sector
            cur++
        }
    }

    private fun decodeLbaBlocks(cdb: ScsiCdb): Pair<ULong, Int> {
        val b = cdb.bytes
        val op = b[0].toInt() and 0xFF
        return if (op == ScsiCommand.OP_READ16 || op == ScsiCommand.OP_WRITE16) {
            var lba = 0UL
            for (i in 0 until 8) {
                lba = (lba shl 8) or (b[2 + i].toULong() and 0xFFu)
            }
            val blocks = ((b[10].toInt() and 0xFF) shl 24) or
                ((b[11].toInt() and 0xFF) shl 16) or
                ((b[12].toInt() and 0xFF) shl 8) or
                (b[13].toInt() and 0xFF)
            lba to blocks
        } else {
            val lba = ((b[2].toULong() and 0xFFu) shl 24) or
                ((b[3].toULong() and 0xFFu) shl 16) or
                ((b[4].toULong() and 0xFFu) shl 8) or
                (b[5].toULong() and 0xFFu)
            val blocks = ((b[7].toInt() and 0xFF) shl 8) or (b[8].toInt() and 0xFF)
            lba to blocks
        }
    }
}
