package usb.device

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartParserTest {
    @Test
    fun identifyParsesByteSwappedStringsAndSmartFlags() {
        val words = ShortArray(256)
        writeAtaString(words, 10, "SERIAL123456")
        writeAtaString(words, 23, "FW1.0")
        writeAtaString(words, 27, "MODEL SSD 1TB")
        words[82] = 0x0001 // SMART supported
        words[85] = 0x0001 // SMART enabled
        val buf = wordsToBuffer(words)

        val info = SmartParser.parseIdentify(buf)
        assertEquals("SERIAL123456", info.serial)
        assertEquals("FW1.0", info.firmware)
        assertEquals("MODEL SSD 1TB", info.model)
        assertEquals(true, info.smartSupported)
        assertEquals(true, info.smartEnabled)
    }

    @Test
    fun smartAttributesSkipEmptySlotsAndReportChecksum() {
        val page = ByteArray(512)
        // id 5 reallocated; flags LE 0x0003; current 100; worst 99; raw 7
        page[2] = 5
        page[3] = 0x03
        page[4] = 0x00
        page[5] = 100
        page[6] = 99
        page[7] = 7
        // checksum: last byte makes sum 0
        var sum = 0
        for (i in 0 until 511) sum = (sum + (page[i].toInt() and 0xFF)) and 0xFF
        page[511] = ((0x100 - sum) and 0xFF).toByte()

        val (attrs, ok) = SmartParser.parseSmartAttributes(ByteBuffer.wrap(page))
        assertTrue(ok)
        assertEquals(1, attrs.size)
        assertEquals(5, attrs[0].id)
        assertEquals(0x0003, attrs[0].flags)
        assertEquals(100, attrs[0].current)
        assertEquals(99, attrs[0].worst)
        assertEquals(7L, attrs[0].raw)
    }

    @Test
    fun checksumMismatchReported() {
        val page = ByteArray(512) { 1 }
        page[0] = 2
        val (_, ok) = SmartParser.parseSmartAttributes(ByteBuffer.wrap(page))
        assertFalse(ok)
    }

    @Test
    fun findsAtaReturnDescriptorWithinDeliveredBounds() {
        val sense = ByteArray(22)
        sense[0] = 0x72
        sense[7] = 14
        sense[8] = 0x09
        sense[9] = 0x0C
        sense[11] = 0x04 // error
        sense[21] = 0x51 // status ERR
        val found = SmartParser.findAtaReturn(sense, delivered = 22)
        assertNotNull(found)
        assertEquals(0x51, found!!.status)
        assertEquals(0x04, found.error)
        assertTrue(found.hasError)
    }

    @Test
    fun ataDeviceFaultCountsAsError() {
        val sense = ByteArray(22)
        sense[0] = 0x72
        sense[7] = 14
        sense[8] = 0x09
        sense[9] = 0x0C
        sense[21] = 0x70 // DRDY | DF, ERR clear
        val found = SmartParser.findAtaReturn(sense, delivered = 22)
        assertNotNull(found)
        assertTrue(found!!.hasError)
    }

    @Test
    fun shortDeliveredSenseYieldsNoDescriptor() {
        val sense = ByteArray(22)
        sense[0] = 0x72
        sense[7] = 14
        sense[8] = 0x09
        sense[9] = 0x0C
        sense[21] = 0x50
        assertNull(SmartParser.findAtaReturn(sense, delivered = 10))
    }

    private fun writeAtaString(words: ShortArray, startWord: Int, text: String) {
        val padded = text.padEnd(40, ' ').take(40)
        var w = startWord
        var i = 0
        while (i + 1 < padded.length && w < words.size) {
            val hi = padded[i].code and 0xFF
            val lo = padded[i + 1].code and 0xFF
            words[w] = ((hi shl 8) or lo).toShort()
            w++
            i += 2
        }
    }

    private fun wordsToBuffer(words: ShortArray): ByteBuffer {
        val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        for (w in words) buf.putShort(w)
        buf.flip()
        return buf
    }
}
