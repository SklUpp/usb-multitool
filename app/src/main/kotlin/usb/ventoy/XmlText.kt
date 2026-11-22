package usb.ventoy

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Encoding-preserving XML text codec for raw editor round-trips.
 * Detects BOM and XML declaration charset; re-encodes with the same BOM.
 */
data class DecodedXml(
    val text: String,
    val charset: Charset,
    val bom: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedXml) return false
        return text == other.text && charset == other.charset && bom.contentEquals(other.bom)
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + charset.hashCode()
        result = 31 * result + bom.contentHashCode()
        return result
    }
}

object XmlText {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val DECL_ENCODING =
        Regex("""(?is)encoding\s*=\s*["']\s*([A-Za-z0-9._-]+)\s*["']""")

    fun decode(bytes: ByteArray): DecodedXml {
        val (bom, charsetHint) = detectBom(bytes)
        val body = if (bom.isEmpty()) bytes else bytes.copyOfRange(bom.size, bytes.size)
        val charset =
            charsetHint
                ?: detectBomlessUtf16(body)
                ?: declarationCharset(peekAscii(body))
                ?: StandardCharsets.UTF_8
        val text = decodeStrict(body, charset)
        return DecodedXml(text = text, charset = charset, bom = bom)
    }

    fun encode(text: String, charset: Charset, bom: ByteArray = ByteArray(0)): ByteArray {
        val encoded = encodeStrict(text, charset)
        if (bom.isEmpty()) return encoded
        return bom + encoded
    }

    fun declarationCharset(text: String): Charset? {
        val head = if (text.length <= 256) text else text.substring(0, 256)
        val name = DECL_ENCODING.find(head)?.groupValues?.getOrNull(1) ?: return null
        return when (name.lowercase()) {
            "utf-8", "utf8" -> StandardCharsets.UTF_8
            "utf-16le", "utf16le" -> StandardCharsets.UTF_16LE
            "utf-16be", "utf16be" -> StandardCharsets.UTF_16BE
            "utf-16", "utf16" -> StandardCharsets.UTF_16BE
            else -> null
        }
    }

    private fun detectBom(bytes: ByteArray): Pair<ByteArray, Charset?> {
        if (startsWith(bytes, UTF8_BOM)) return UTF8_BOM to StandardCharsets.UTF_8
        if (startsWith(bytes, UTF16LE_BOM)) return UTF16LE_BOM to StandardCharsets.UTF_16LE
        if (startsWith(bytes, UTF16BE_BOM)) return UTF16BE_BOM to StandardCharsets.UTF_16BE
        return ByteArray(0) to null
    }

    /** BOM-less UTF-16 starts with `<?`/`<` as 16-bit code units. */
    private fun detectBomlessUtf16(bytes: ByteArray): Charset? {
        if (bytes.size < 2 || bytes.size % 2 != 0) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == '<'.code && b1 == 0 -> StandardCharsets.UTF_16LE
            b0 == 0 && b1 == '<'.code -> StandardCharsets.UTF_16BE
            else -> null
        }
    }


    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }

    private fun peekAscii(bytes: ByteArray): String {
        val n = minOf(bytes.size, 256)
        val chars = CharArray(n)
        for (i in 0 until n) {
            chars[i] = (bytes[i].toInt() and 0xFF).toChar()
        }
        return String(chars)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String =
        try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("XML bytes are not valid ${charset.name()}", failure)
        }

    private fun encodeStrict(text: String, charset: Charset): ByteArray =
        try {
            val buffer =
                charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text))
            val out = ByteArray(buffer.remaining())
            buffer.get(out)
            out
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("XML text cannot encode as ${charset.name()}", failure)
        }
}
