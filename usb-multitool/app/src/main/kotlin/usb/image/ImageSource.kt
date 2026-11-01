package usb.image

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

enum class ImageFormat {
    ISO,
    IMG,
    GZIP,
    XZ,
}

class CountedInputStream(
    private val input: InputStream,
    val decodedSizeHint: Long? = null,
) : InputStream() {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) bytesRead += n.toLong()
        return n
    }

    override fun close() = input.close()
}

class ImageSource private constructor(
    private val openStream: () -> CountedInputStream,
    val format: ImageFormat,
    val declaredDecodedSize: Long? = null,
) {
    fun open(): CountedInputStream = openStream()

    companion object {
        fun open(uri: Uri): CountedInputStream = fromUri(uri).open()

        fun fromUri(uri: Uri): ImageSource {
            val path = uri.toString()
            val format = detectFormat(path)
            return ImageSource(
                openStream = {
                    val raw = openRaw(uri)
                    wrap(raw, format)
                },
                format = format,
            )
        }

        fun fromBytes(
            bytes: ByteArray,
            format: ImageFormat = detectFormatBytes(bytes),
            decodedSize: Long? = null,
        ): ImageSource = ImageSource(
            openStream = { wrap(ByteArrayInputStream(bytes), format, decodedSize) },
            format = format,
            declaredDecodedSize = decodedSize,
        )

        fun fromBytes(gzipOrRaw: ByteArray): ImageSource {
            val format = detectFormatBytes(gzipOrRaw)
            val decoded = when (format) {
                ImageFormat.GZIP -> gzipISize(gzipOrRaw)
                else -> null
            }
            return fromBytes(bytes = gzipOrRaw, format = format, decodedSize = decoded)
        }

        private fun openRaw(uri: Uri): InputStream {
            val scheme = uri.scheme?.lowercase()
            return when (scheme) {
                "http", "https" -> {
                    val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    conn.inputStream
                }
                "file" -> FileInputStream(uri.path!!)
                else -> FileInputStream(uri.path ?: uri.toString())
            }
        }

        private fun wrap(
            raw: InputStream,
            format: ImageFormat,
            decodedSize: Long? = null,
        ): CountedInputStream {
            val decoded: InputStream = when (format) {
                ImageFormat.GZIP -> GZIPInputStream(raw)
                ImageFormat.XZ -> XZCompressorInputStream(raw)
                ImageFormat.ISO, ImageFormat.IMG -> raw
            }
            return CountedInputStream(decoded, decodedSize)
        }

        private fun detectFormat(path: String): ImageFormat {
            val lower = path.lowercase()
            return when {
                lower.endsWith(".gz") || lower.endsWith(".img.gz") || lower.endsWith(".iso.gz") ->
                    ImageFormat.GZIP
                lower.endsWith(".xz") || lower.endsWith(".img.xz") || lower.endsWith(".iso.xz") ->
                    ImageFormat.XZ
                lower.endsWith(".iso") -> ImageFormat.ISO
                else -> ImageFormat.IMG
            }
        }

        private fun detectFormatBytes(bytes: ByteArray): ImageFormat {
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                return ImageFormat.GZIP
            }
            if (bytes.size >= 6 &&
                bytes[0] == 0xFD.toByte() && bytes[1] == '7'.code.toByte() &&
                bytes[2] == 'z'.code.toByte() && bytes[3] == 'X'.code.toByte() &&
                bytes[4] == 'Z'.code.toByte() && bytes[5] == 0.toByte()
            ) {
                return ImageFormat.XZ
            }
            return ImageFormat.IMG
        }

        /** Gzip ISIZE footer (uncompressed size mod 2^32). */
        private fun gzipISize(bytes: ByteArray): Long? {
            if (bytes.size < 8) return null
            var n = 0L
            for (i in 0 until 4) {
                n = n or ((bytes[bytes.size - 4 + i].toLong() and 0xFFL) shl (8 * i))
            }
            return n
        }
    }
}
