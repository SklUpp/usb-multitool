package usb.device

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BotSessionImplTest {

    private class ScriptedBulkChannel(
        private var connected: Boolean = true,
    ) : UsbBulkChannel {
        override val interfaceId: Int = 0
        val inCalls = mutableListOf<Int>()
        val outCalls = mutableListOf<ByteArray>()

        // Queued responses for bulkIn
        private val inResponses = ArrayDeque<ByteArray>()

        fun queueIn(bytes: ByteArray) {
            inResponses.addLast(bytes)
        }

        fun queueCsw(tag: Int, residue: Int = 0, status: CswStatus = CswStatus.PASSED) {
            val buf = ByteBuffer.allocate(BotConstants.CSW_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(BotConstants.CSW_SIGNATURE)
            buf.putInt(tag)
            buf.putInt(residue)
            buf.put(status.code.toByte())
            inResponses.addLast(buf.array())
        }

        override fun bulkIn(destination: ByteBuffer, length: Int, timeoutMs: Int): Int {
            if (!connected) throw DiskException.Disconnected()
            inCalls.add(length)
            val resp = if (inResponses.isNotEmpty()) inResponses.removeFirst() else ByteArray(0)
            val toTake = minOf(length, resp.size)
            destination.put(resp, 0, toTake)
            // If response was larger than length, put remainder back
            if (resp.size > toTake) {
                inResponses.addFirst(resp.copyOfRange(toTake, resp.size))
            }
            return toTake
        }

        override fun bulkOut(source: ByteBuffer, length: Int, timeoutMs: Int): Int {
            if (!connected) throw DiskException.Disconnected()
            val bytes = ByteArray(length)
            source.get(bytes)
            outCalls.add(bytes)
            return length
        }

        override fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray?,
            length: Int,
        ): Int = 0

        override fun clearHaltIn() {}
        override fun clearHaltOut() {}
        override fun isConnected(): Boolean = connected
        override fun close() {
            connected = false
        }
    }

    @Test
    fun shortDataInDoesNotConsumeCswIntoDataBuffer() {
        val channel = ScriptedBulkChannel()
        val session = BotSessionImpl(channel)

        // Request 96 bytes (e.g. INQUIRY), device sends only 36 bytes of data,
        // then 13-byte CSW with residue 60.
        val inquiryData = ByteArray(36) { (it + 1).toByte() }
        channel.queueIn(inquiryData)
        channel.queueCsw(tag = 1, residue = 60, status = CswStatus.PASSED)

        val cdb = ScsiCommand.inquiry(96)
        val dataBuf = ByteBuffer.allocate(96)

        val csw = session.transfer(cdb, dataBuf)

        assertEquals(CswStatus.PASSED, csw.status)
        assertEquals(1, csw.tag)
        assertEquals(60, csw.residue)

        // Exactly 36 bytes in data buffer
        assertEquals(36, dataBuf.position())
        for (i in 0 until 36) {
            assertEquals((i + 1).toByte(), dataBuf.get(i))
        }

        // Verify channel bulkIn calls: first asked for 96 bytes (returned 36),
        // then next call MUST BE for 13 bytes (CSW), NOT retrying remaining data!
        assertEquals(listOf(96, BotConstants.CSW_SIZE), channel.inCalls)
    }

    @Test
    fun fullDataInConsumesRequestedBytesThenCsw() {
        val channel = ScriptedBulkChannel()
        val session = BotSessionImpl(channel)

        val data = ByteArray(512) { 0x42.toByte() }
        channel.queueIn(data)
        channel.queueCsw(tag = 1, residue = 0, status = CswStatus.PASSED)

        val cdb = ScsiCommand.read(0UL, 1, 512)
        val dataBuf = ByteBuffer.allocate(512)

        val csw = session.transfer(cdb, dataBuf)

        assertEquals(CswStatus.PASSED, csw.status)
        assertEquals(0, csw.residue)
        assertEquals(512, dataBuf.position())
        assertEquals(listOf(512, BotConstants.CSW_SIZE), channel.inCalls)
    }

    @Test
    fun dataOutSendsCbwThenDataThenReadsCsw() {
        val channel = ScriptedBulkChannel()
        val session = BotSessionImpl(channel)

        channel.queueCsw(tag = 1, residue = 0, status = CswStatus.PASSED)

        val cdb = ScsiCommand.write(0UL, 1, 512)
        val dataBuf = ByteBuffer.allocate(512)
        for (i in 0 until 512) dataBuf.put(0x55.toByte())
        dataBuf.flip()

        val csw = session.transfer(cdb, dataBuf)

        assertEquals(CswStatus.PASSED, csw.status)
        assertEquals(0, csw.residue)
        assertEquals(2, channel.outCalls.size) // CBW then data OUT
        assertEquals(BotConstants.CBW_SIZE, channel.outCalls[0].size)
        assertEquals(512, channel.outCalls[1].size)
        assertEquals(listOf(BotConstants.CSW_SIZE), channel.inCalls)
    }

    @Test
    fun badCswSignatureThrowsIoFailure() {
        val channel = ScriptedBulkChannel()
        val session = BotSessionImpl(channel)

        // Queue bad signature (0x12345678 instead of USBS)
        val badCsw = ByteArray(BotConstants.CSW_SIZE) { 0 }
        channel.queueIn(badCsw)

        val cdb = ScsiCommand.testUnitReady()
        assertThrows(DiskException.IoFailure::class.java) {
            session.transfer(cdb, null)
        }
    }

    @Test
    fun cswTagMismatchThrowsIoFailure() {
        val channel = ScriptedBulkChannel()
        val session = BotSessionImpl(channel)

        // Queue wrong tag (99 instead of 1)
        channel.queueCsw(tag = 99)

        val cdb = ScsiCommand.testUnitReady()
        assertThrows(DiskException.IoFailure::class.java) {
            session.transfer(cdb, null)
        }
    }

    @Test
    fun disconnectedChannelThrowsDisconnected() {
        val channel = ScriptedBulkChannel(connected = false)
        val session = BotSessionImpl(channel)

        assertThrows(DiskException.Disconnected::class.java) {
            session.transfer(ScsiCommand.testUnitReady(), null)
        }
    }
}
