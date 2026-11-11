package usb.image

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content-URI format selection sniffs the live write stream: short reads must still
 * assemble a 6-byte magic prefix, open failures must abort, and a compressed magic
 * always wins over a mislabeled .iso so gzip/xz bytes are never written raw.
 */
class ImageSourceTest {
    @Test
    fun opaqueUriWithGzipMagicDecodesWithoutDeclaredCompressedSize() {
        val payload = ByteArray(64) { 7 }
        val gzipped = gzip(payload)
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(gzipped) },
                displayName = null,
                size = gzipped.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.GZIP, source.format)
            assertTrue(input.readBytes().contentEquals(payload))
            assertNull(input.decodedSizeHint)
        }
        assertNull(source.declaredDecodedSize)
    }

    @Test
    fun shortReadsStillAssembleGzipMagic() {
        val payload = ByteArray(48) { 9 }
        val gzipped = gzip(payload)
        val source =
            ImageSource.fromContent(
                openRaw = { OneByteAtATimeStream(gzipped) },
                displayName = "msf:1000000123",
                size = gzipped.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.GZIP, source.format)
            assertTrue(input.readBytes().contentEquals(payload))
        }
    }

    @Test
    fun isoNameWithGzipMagicDecodesAsGzip() {
        val payload = ByteArray(32) { 3 }
        val gzipped = gzip(payload)
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(gzipped) },
                displayName = "ubuntu.iso",
                size = gzipped.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.GZIP, source.format)
            assertTrue(input.readBytes().contentEquals(payload))
            assertNull(input.decodedSizeHint)
        }
        assertNull(source.declaredDecodedSize)
    }

    @Test
    fun gzipNameWithRawMagicKeepsGzipAndFailsLoudly() {
        val raw = ByteArray(64) { 1 }
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(raw) },
                displayName = "stick.img.gz",
                size = raw.size.toLong(),
            )
        assertThrows(java.util.zip.ZipException::class.java) {
            source.open().use { it.read() }
        }
        assertEquals(ImageFormat.GZIP, source.format)
    }

    @Test
    fun openFailurePropagatesInsteadOfFallingBackToImg() {
        val source =
            ImageSource.fromContent(
                openRaw = { throw java.io.IOException("resolver denied") },
                displayName = null,
                size = 100L,
            )
        val failure = assertThrows(java.io.IOException::class.java) {
            source.open()
        }
        assertTrue(failure.message!!.contains("resolver denied"))
    }

    @Test
    fun rawImgKeepsOpenableSizeAsDecodedHint() {
        val raw = ByteArray(512) { 1 }
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(raw) },
                displayName = "stick.img",
                size = raw.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.IMG, source.format)
            assertEquals(512L, input.decodedSizeHint)
            assertTrue(input.readBytes().contentEquals(raw))
        }
    }

    @Test
    fun displayNameGzipWithMatchingMagicDecodes() {
        val payload = ByteArray(32) { 3 }
        val gzipped = gzip(payload)
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(gzipped) },
                displayName = "ubuntu.img.gz",
                size = gzipped.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.GZIP, source.format)
            assertTrue(input.readBytes().contentEquals(payload))
        }
        assertNull(source.declaredDecodedSize)
    }

    @Test
    fun fromBytesNeverPublishesGzipFooterAsDecodedSize() {
        // ISIZE is mod 2^32 and repeated members each carry their own trailer, so the
        // footer is not a full decoded length: keep the size unknown and let the stream
        // decide when it overflows the selected range.
        val payload = ByteArray(96) { 4 }
        val multiMember = gzip(payload) + gzip(payload)
        val source = ImageSource.fromBytes(multiMember)
        assertEquals(ImageFormat.GZIP, source.format)
        assertNull(source.declaredDecodedSize)
        source.open().use { input ->
            assertNull(input.decodedSizeHint)
            assertTrue(input.readBytes().contentEquals(payload + payload))
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    /** Forces every read to return at most one byte — covers short-read magic assembly. */
    private class OneByteAtATimeStream(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            val n = read()
            if (n < 0) return -1
            b[off] = n.toByte()
            return 1
        }
    }
}
