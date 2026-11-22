package usb.device

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDiskSmartTest {
    @Test
    fun identifyThenSmartAvailableWithChecksumWarning() {
        val identify = identifyPayload(smartSupported = true, smartEnabled = true)
        val smart = smartPayload(id = 5, raw = 3, validChecksum = false)
        val session = FakeBotSession(
            satIdentifyData = identify,
            satSmartData = smart,
            satIdentifyCswFailedWithAtaOk = true,
            satSmartCswFailedWithAtaOk = true,
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val result = disk.readSmartTelemetry()
        assertEquals(SmartAvailability.Available, result.availability)
        assertEquals("MODEL SSD", result.identify?.model)
        assertEquals(1, result.attributes.size)
        assertEquals(5, result.attributes[0].id)
        assertEquals(3L, result.attributes[0].raw)
        assertEquals(false, result.checksumOk)
        assertTrue(result.detail!!.contains("checksum"))
        assertFalse(disk.isClosed)
        assertEquals(
            listOf(
                ScsiCommand.OP_ATA_PASS_THROUGH_16,
                ScsiCommand.OP_REQUEST_SENSE,
                ScsiCommand.OP_ATA_PASS_THROUGH_16,
                ScsiCommand.OP_REQUEST_SENSE,
            ),
            session.commands,
        )
    }

    @Test
    fun nonSatBridgeReportsUnsupportedWithoutClosing() {
        val session = FakeBotSession(satUnsupported = true)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val result = disk.readSmartTelemetry()
        assertEquals(SmartAvailability.Unsupported, result.availability)
        assertFalse(disk.isClosed)
        assertEquals(
            listOf(ScsiCommand.OP_ATA_PASS_THROUGH_16, ScsiCommand.OP_REQUEST_SENSE),
            session.commands,
        )
    }

    @Test
    fun identifyWithoutSmartFeatureIsUnsupported() {
        val session = FakeBotSession(
            satIdentifyData = identifyPayload(smartSupported = false, smartEnabled = false),
            satIdentifyCswFailedWithAtaOk = true,
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val result = disk.readSmartTelemetry()
        assertEquals(SmartAvailability.Unsupported, result.availability)
        assertEquals(false, result.identify?.smartSupported)
        assertTrue(result.attributes.isEmpty())
        assertFalse(disk.isClosed)
    }

    @Test
    fun ataErrorOnSmartYieldsUnavailable() {
        val session = FakeBotSession(
            satIdentifyData = identifyPayload(smartSupported = true, smartEnabled = true),
            satSmartData = smartPayload(id = 5, raw = 1, validChecksum = true),
            satIdentifyCswFailedWithAtaOk = true,
            satAtaErrorStatus = 0x51,
            satAtaErrorCode = 0x04,
            satAtaErrorCommand = 0xB0,
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val result = disk.readSmartTelemetry()
        assertEquals(SmartAvailability.Unavailable, result.availability)
        assertEquals("MODEL SSD", result.identify?.model)
        assertEquals(0x51, result.ataStatus)
        assertEquals(0x04, result.ataError)
        assertFalse(disk.isClosed)
        assertEquals(
            listOf(
                ScsiCommand.OP_ATA_PASS_THROUGH_16,
                ScsiCommand.OP_REQUEST_SENSE,
                ScsiCommand.OP_ATA_PASS_THROUGH_16,
                ScsiCommand.OP_REQUEST_SENSE,
            ),
            session.commands,
        )
    }

    @Test
    fun cancellationAfterIdentifyStopsBeforeSmart() {
        val session = FakeBotSession(
            satIdentifyData = identifyPayload(smartSupported = true, smartEnabled = true),
            satSmartData = smartPayload(id = 5, raw = 1, validChecksum = true),
            satIdentifyCswFailedWithAtaOk = true,
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            disk.readSmartTelemetry {
                checks++
                checks == 1
            }
        }
        assertEquals(2, checks)
        assertEquals(
            listOf(ScsiCommand.OP_ATA_PASS_THROUGH_16, ScsiCommand.OP_REQUEST_SENSE),
            session.commands,
        )
        assertFalse(disk.isClosed)
    }

    @Test
    fun requestSensePhaseErrorClosesSession() {
        val session = FakeBotSession(
            satIdentifyData = identifyPayload(smartSupported = true, smartEnabled = true),
            phaseErrorOnOps = setOf(ScsiCommand.OP_REQUEST_SENSE),
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val failure = assertThrows(DiskException.IoFailure::class.java) {
            disk.readSmartTelemetry()
        }
        assertTrue(failure.message!!.contains("phase error"))
        assertEquals(1, session.resetCount)
        assertTrue(disk.isClosed)
    }

    @Test
    fun contradictorySatDataClosesSession() {
        val session = FakeBotSession(
            satIdentifyData = identifyPayload(smartSupported = true, smartEnabled = true),
            satDeliveredBytes = 256,
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val failure = assertThrows(DiskException.IoFailure::class.java) {
            disk.readSmartTelemetry()
        }
        assertTrue(failure.message!!.contains("mismatch"))
        assertEquals(1, session.resetCount)
        assertTrue(disk.isClosed)
    }

    @Test
    fun ataCdbBytesAreWhitelistedConstants() {
        val id = ScsiCommand.ataIdentifyDevice()
        assertArrayEquals(
            byteArrayOf(
                0x85.toByte(), 0x08, 0x2E, 0, 0, 0, 0x01, 0,
                0, 0, 0, 0, 0, 0, 0xEC.toByte(), 0,
            ),
            id.bytes,
        )
        assertEquals(ScsiDirection.IN, id.direction)
        assertEquals(512, id.transferLength)

        val smart = ScsiCommand.ataSmartReadData()
        assertArrayEquals(
            byteArrayOf(
                0x85.toByte(), 0x08, 0x2E, 0, 0xD0.toByte(), 0, 0x01, 0,
                0, 0, 0x4F, 0, 0xC2.toByte(), 0, 0xB0.toByte(), 0,
            ),
            smart.bytes,
        )
        assertEquals(ScsiDirection.IN, smart.direction)
        assertEquals(512, smart.transferLength)
    }

    private fun identifyPayload(smartSupported: Boolean, smartEnabled: Boolean): ByteArray {
        val words = ShortArray(256)
        writeAtaString(words, 10, "SN42")
        writeAtaString(words, 23, "FW")
        writeAtaString(words, 27, "MODEL SSD")
        words[82] = if (smartSupported) 0x0001 else 0x0000
        words[85] = if (smartEnabled) 0x0001 else 0x0000
        val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        for (w in words) buf.putShort(w)
        return buf.array()
    }

    private fun smartPayload(id: Int, raw: Long, validChecksum: Boolean): ByteArray {
        val page = ByteArray(512)
        page[2] = id.toByte()
        page[3] = 0x03
        page[5] = 100
        page[6] = 100
        page[7] = (raw and 0xFF).toByte()
        if (validChecksum) {
            var sum = 0
            for (i in 0 until 511) sum = (sum + (page[i].toInt() and 0xFF)) and 0xFF
            page[511] = ((0x100 - sum) and 0xFF).toByte()
        } else {
            page[511] = 0x11
        }
        return page
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
}
