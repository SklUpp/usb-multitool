package usb.device

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * CBW → optional data → CSW over [UsbBulkChannel].
 * Short bulk transfers are retried until the requested length is satisfied.
 */
class BotSessionImpl(
    private val channel: UsbBulkChannel,
    private val lun: Byte = 0,
) : BotSession {
    private val tagCounter = AtomicInteger(1)
    private val cbwBuffer = ByteBuffer.allocate(BotConstants.CBW_SIZE)
    private val cswBuffer = ByteBuffer.allocate(BotConstants.CSW_SIZE)

    @Synchronized
    override fun transfer(cdb: ScsiCdb, data: ByteBuffer?): CswResult {
        if (!channel.isConnected()) throw DiskException.Disconnected()
        require(cdb.bytes.size in 1..16) { "CDB length ${cdb.bytes.size} out of range" }
        when (cdb.direction) {
            ScsiDirection.NONE -> require(cdb.transferLength == 0 && data == null)
            ScsiDirection.IN, ScsiDirection.OUT -> {
                require(cdb.transferLength > 0)
                require(data != null) { "data buffer required" }
                require(data.remaining() >= cdb.transferLength) {
                    "data remaining ${data.remaining()} < ${cdb.transferLength}"
                }
            }
        }

        val tag = tagCounter.getAndIncrement()
        sendCbw(tag, cdb)

        when (cdb.direction) {
            ScsiDirection.IN -> {
                val dest = data!!
                val mark = dest.position()
                val oldLimit = dest.limit()
                try {
                    dest.limit(mark + cdb.transferLength)
                    transferExactIn(dest, cdb.transferLength)
                } finally {
                    dest.limit(oldLimit)
                }
            }
            ScsiDirection.OUT -> {
                val src = data!!
                val mark = src.position()
                val oldLimit = src.limit()
                try {
                    src.limit(mark + cdb.transferLength)
                    transferExactOut(src, cdb.transferLength)
                } finally {
                    src.limit(oldLimit)
                }
            }
            ScsiDirection.NONE -> Unit
        }

        return readCsw(tag)
    }

    @Synchronized
    override fun bulkOnlyReset() {
        Recovery.bulkOnlyReset(channel)
    }

    override fun isConnected(): Boolean = channel.isConnected()

    override fun close() {
        channel.close()
    }

    private fun sendCbw(tag: Int, cdb: ScsiCdb) {
        cbwBuffer.clear()
        cbwBuffer.order(ByteOrder.LITTLE_ENDIAN)
        cbwBuffer.putInt(BotConstants.CBW_SIGNATURE)
        cbwBuffer.putInt(tag)
        cbwBuffer.putInt(cdb.transferLength)
        cbwBuffer.put(
            when (cdb.direction) {
                ScsiDirection.IN -> 0x80.toByte()
                ScsiDirection.OUT, ScsiDirection.NONE -> 0x00
            },
        )
        cbwBuffer.put(lun)
        cbwBuffer.put(cdb.bytes.size.toByte())
        cbwBuffer.put(cdb.bytes)
        while (cbwBuffer.position() < BotConstants.CBW_SIZE) {
            cbwBuffer.put(0)
        }
        cbwBuffer.flip()
        transferExactOut(cbwBuffer, BotConstants.CBW_SIZE)
    }

    private fun readCsw(expectedTag: Int): CswResult {
        cswBuffer.clear()
        transferExactIn(cswBuffer, BotConstants.CSW_SIZE)
        cswBuffer.flip()
        cswBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val signature = cswBuffer.int
        if (signature != BotConstants.CSW_SIGNATURE) {
            throw DiskException.IoFailure("bad CSW signature 0x${signature.toString(16)}")
        }
        val tag = cswBuffer.int
        val residue = cswBuffer.int
        val status = CswStatus.from(cswBuffer.get().toInt() and 0xFF)
        if (tag != expectedTag) {
            throw DiskException.IoFailure("CSW tag mismatch: got $tag expected $expectedTag")
        }
        return CswResult(status, tag, residue)
    }

    private fun transferExactIn(destination: ByteBuffer, length: Int) {
        var remaining = length
        while (remaining > 0) {
            if (!channel.isConnected()) throw DiskException.Disconnected()
            val n = channel.bulkIn(destination, remaining)
            if (n <= 0) throw DiskException.IoFailure("short bulk IN returned $n")
            remaining -= n
        }
    }

    private fun transferExactOut(source: ByteBuffer, length: Int) {
        var remaining = length
        while (remaining > 0) {
            if (!channel.isConnected()) throw DiskException.Disconnected()
            val n = channel.bulkOut(source, remaining)
            if (n <= 0) throw DiskException.IoFailure("short bulk OUT returned $n")
            remaining -= n
        }
    }
}
