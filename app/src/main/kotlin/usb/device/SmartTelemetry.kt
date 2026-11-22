package usb.device

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Read-only SMART / IDENTIFY facts from SAT ATA PASS-THROUGH (16).
 * Never invents temperature, wear, or health scores from missing attributes.
 */
enum class SmartAvailability {
    /** IDENTIFY + SMART READ DATA both returned usable payloads. */
    Available,
    /** Bridge/device rejected SAT or SMART (illegal request / invalid command). */
    Unsupported,
    /** Transport/sense failed without a clear unsupported signal. */
    Unavailable,
}

data class SmartAttribute(
    val id: Int,
    val flags: Int,
    val current: Int,
    val worst: Int,
    /** Little-endian 48-bit raw; interpretation is vendor-specific. */
    val raw: Long,
)

data class SmartIdentifyInfo(
    val model: String?,
    val serial: String?,
    val firmware: String?,
    /** Word 82 bit 0: SMART feature set supported. Null when IDENTIFY too short. */
    val smartSupported: Boolean?,
    /** Word 85 bit 0: SMART enabled. Null when IDENTIFY too short. */
    val smartEnabled: Boolean?,
)

data class SmartTelemetry(
    val availability: SmartAvailability,
    val identify: SmartIdentifyInfo? = null,
    val attributes: List<SmartAttribute> = emptyList(),
    val checksumOk: Boolean? = null,
    val ataStatus: Int? = null,
    val ataError: Int? = null,
    val detail: String? = null,
) {
    companion object {
        fun unsupported(detail: String? = null): SmartTelemetry =
            SmartTelemetry(SmartAvailability.Unsupported, detail = detail)

        fun unavailable(detail: String? = null): SmartTelemetry =
            SmartTelemetry(SmartAvailability.Unavailable, detail = detail)
    }
}

object SmartParser {
    private val ATA_STRING_CHARSET: Charset = Charset.forName("US-ASCII")

    /** ATA Status Return descriptor code (SAT). */
    const val DESC_ATA_STATUS_RETURN = 0x09

    fun parseIdentify(buffer: ByteBuffer): SmartIdentifyInfo {
        require(buffer.remaining() >= ScsiCommand.ATA_SECTOR_BYTES) {
            "IDENTIFY payload shorter than 512 bytes"
        }
        val bytes = ByteArray(ScsiCommand.ATA_SECTOR_BYTES)
        val mark = buffer.position()
        buffer.get(bytes)
        buffer.position(mark)
        val words = ShortArray(256)
        val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in words.indices) {
            words[i] = le.short
        }
        val smartSupported = if (bytes.size >= 166) (words[82].toInt() and 0x0001) != 0 else null
        val smartEnabled = if (bytes.size >= 172) (words[85].toInt() and 0x0001) != 0 else null
        return SmartIdentifyInfo(
            model = ataString(words, 27, 40),
            serial = ataString(words, 10, 20),
            firmware = ataString(words, 23, 8),
            smartSupported = smartSupported,
            smartEnabled = smartEnabled,
        )
    }

    fun parseSmartAttributes(buffer: ByteBuffer): Pair<List<SmartAttribute>, Boolean> {
        require(buffer.remaining() >= ScsiCommand.ATA_SECTOR_BYTES) {
            "SMART payload shorter than 512 bytes"
        }
        val bytes = ByteArray(ScsiCommand.ATA_SECTOR_BYTES)
        val mark = buffer.position()
        buffer.get(bytes)
        buffer.position(mark)
        var sum = 0
        for (b in bytes) {
            sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        }
        val checksumOk = sum == 0
        val attributes = ArrayList<SmartAttribute>(30)
        for (i in 0 until 30) {
            val base = 2 + i * 12
            val id = bytes[base].toInt() and 0xFF
            if (id == 0) continue
            val flags = ((bytes[base + 2].toInt() and 0xFF) shl 8) or
                (bytes[base + 1].toInt() and 0xFF)
            val current = bytes[base + 3].toInt() and 0xFF
            val worst = bytes[base + 4].toInt() and 0xFF
            var raw = 0L
            for (b in 0 until 6) {
                raw = raw or ((bytes[base + 5 + b].toInt() and 0xFF).toLong() shl (8 * b))
            }
            attributes += SmartAttribute(id, flags, current, worst, raw)
        }
        return attributes to checksumOk
    }

    /**
     * Walk descriptor-format sense for ATA Status Return (0x09).
     * Bounds every read by [delivered] bytes actually present.
     */
    fun findAtaReturn(sense: ByteArray, delivered: Int = sense.size): AtaReturn? {
        val len = delivered.coerceAtMost(sense.size)
        if (len < 8) return null
        val response = sense[0].toInt() and 0x7F
        if (response != 0x72 && response != 0x73) return null
        val addLen = sense[7].toInt() and 0xFF
        val end = minOf(len, 8 + addLen)
        var p = 8
        while (p + 1 < end) {
            val code = sense[p].toInt() and 0xFF
            val dLen = sense[p + 1].toInt() and 0xFF
            val next = p + 2 + dLen
            if (next > end) break
            if (code == DESC_ATA_STATUS_RETURN && dLen >= 0x0C && p + 2 + 12 <= end) {
                // Descriptor: [code][len][...]; error at offset 3, status at offset 13 of whole desc.
                val error = sense[p + 3].toInt() and 0xFF
                val status = sense[p + 13].toInt() and 0xFF
                return AtaReturn(status = status, error = error)
            }
            p = next
        }
        return null
    }

    data class AtaReturn(val status: Int, val error: Int) {
        /** ERR (0x01) or DF (0x20) means the ATA command did not succeed. */
        val hasError: Boolean get() = (status and 0x21) != 0
    }

    private fun ataString(words: ShortArray, startWord: Int, byteCount: Int): String? {
        require(byteCount % 2 == 0)
        val wordCount = byteCount / 2
        if (startWord + wordCount > words.size) return null
        val bytes = ByteArray(byteCount)
        for (i in 0 until wordCount) {
            val w = words[startWord + i].toInt() and 0xFFFF
            // ATA IDENTIFY strings are byte-swapped within each 16-bit word.
            bytes[i * 2] = ((w shr 8) and 0xFF).toByte()
            bytes[i * 2 + 1] = (w and 0xFF).toByte()
        }
        val text = String(bytes, ATA_STRING_CHARSET).trim { it <= ' ' || it == '\u0000' }
        return text.takeIf { it.isNotEmpty() }
    }
}
