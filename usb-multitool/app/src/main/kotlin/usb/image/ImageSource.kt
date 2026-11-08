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
    initialFormat: ImageFormat,
    val declaredDecodedSize: Long? = null,
) {
    /** Updated on [open] when content sniffing reconciles name vs magic. */
    var format: ImageFormat = initialFormat
        private set

    fun open(): CountedInputStream = openStream()

    private fun noteFormat(detected: ImageFormat) {
        format = detected
    }

    companion object {
        fun open(uri: Uri): CountedInputStream = fromUri(uri).open()

        fun fromUri(uri: Uri): ImageSource {
            val path = uri.toString()
            val format = detectFormat(path)
            val declaredSize = if (format == ImageFormat.ISO || format == ImageFormat.IMG) {
                queryFileSize(uri)
            } else null
            return ImageSource(
                openStream = {
                    val raw = openRaw(uri)
                    wrap(raw, format, declaredSize)
                },
                initialFormat = format,
                declaredDecodedSize = declaredSize,
            )
        }

        /** SAF/content URIs: the only stream source Android can read without a path. */
        fun fromContentUri(uri: Uri, resolver: android.content.ContentResolver): ImageSource {
            // lastPathSegment on content:// is often an opaque doc id (msf:123), never a
            // filename. Prefer OpenableColumns.DISPLAY_NAME; magic comes from the live stream.
            val displayName = queryOpenableString(resolver, uri, android.provider.OpenableColumns.DISPLAY_NAME)
            val size = queryOpenableLong(resolver, uri, android.provider.OpenableColumns.SIZE)
            return fromContent(
                openRaw = {
                    resolver.openInputStream(uri)
                        ?: throw java.io.FileNotFoundException("cannot open $uri")
                },
                displayName = displayName,
                size = size,
            )
        }

        /**
         * Content-URI decode without Android types. Used by [fromContentUri] and host tests.
         * Format is decided on the stream that will be written: read up to 6 magic bytes
         * (tolerating short reads), reconcile with DISPLAY_NAME, prepend the prefix so no
         * payload is lost. Open/read failures propagate — never fall back to raw IMG.
         */
        internal fun fromContent(
            openRaw: () -> InputStream,
            displayName: String?,
            size: Long?,
        ): ImageSource {
            val named = displayName?.let { detectFormat(it) }
            val provisional = when (named) {
                ImageFormat.GZIP, ImageFormat.XZ, ImageFormat.ISO -> named
                else -> ImageFormat.IMG
            }
            // SIZE from the provider is the on-disk/compressed length. Do not publish it as
            // decodedSize until magic on the live stream confirms an uncompressed image;
            // a mislabeled .iso that is actually gzip must never early-reject on SIZE.
            lateinit var source: ImageSource
            source = ImageSource(
                openStream = {
                    val raw = openRaw()
                    try {
                        val header = ByteArray(6)
                        val n = readPrefix(raw, header)
                        val magic =
                            if (n <= 0) ImageFormat.IMG else detectFormatBytes(header.copyOf(n))
                        val format = reconcileContentFormat(named, magic)
                        source.noteFormat(format)
                        val restored = prependPrefix(header, n, raw)
                        val decodedSize =
                            if (format == ImageFormat.ISO || format == ImageFormat.IMG) size else null
                        wrap(restored, format, decodedSize)
                    } catch (t: Throwable) {
                        runCatching { raw.close() }
                        throw t
                    }
                },
                initialFormat = provisional,
                declaredDecodedSize = null,
            )
            return source
        }

        fun fromFile(file: java.io.File): ImageSource {
            val format = detectFormat(file.name)
            val declaredSize = if (format == ImageFormat.ISO || format == ImageFormat.IMG) {
                if (file.exists() && file.isFile) file.length() else null
            } else null
            return ImageSource(
                openStream = {
                    wrap(FileInputStream(file), format, declaredSize)
                },
                initialFormat = format,
                declaredDecodedSize = declaredSize,
            )
        }

        fun fromBytes(
            bytes: ByteArray,
            format: ImageFormat = detectFormatBytes(bytes),
            decodedSize: Long? = null,
        ): ImageSource = ImageSource(
            openStream = { wrap(ByteArrayInputStream(bytes), format, decodedSize) },
            initialFormat = format,
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
        private fun queryFileSize(uri: Uri): Long? {
            return runCatching {
                val scheme = uri.scheme?.lowercase()
                if (scheme == null || scheme == "file") {
                    val path = if (scheme == "file") uri.path else (uri.path ?: uri.toString())
                    if (path != null) {
                        val file = java.io.File(path)
                        if (file.exists() && file.isFile) file.length() else null
                    } else null
                } else null
            }.getOrNull()
        }

        private fun queryOpenableString(
            resolver: android.content.ContentResolver,
            uri: Uri,
            column: String,
        ): String? =
            runCatching {
                resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
            }.getOrNull()

        private fun queryOpenableLong(
            resolver: android.content.ContentResolver,
            uri: Uri,
            column: String,
        ): Long? =
            runCatching {
                resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            }.getOrNull()

        /**
         * Compressed magic always wins so a mislabeled .iso never writes gzip/xz bytes raw.
         * A compressed DISPLAY_NAME with non-compressed magic keeps the name so the decoder
         * fails loudly instead of silently writing the stream as an image.
         */
        private fun reconcileContentFormat(named: ImageFormat?, magic: ImageFormat): ImageFormat {
            if (magic == ImageFormat.GZIP || magic == ImageFormat.XZ) return magic
            return when (named) {
                ImageFormat.GZIP, ImageFormat.XZ, ImageFormat.ISO -> named
                else -> ImageFormat.IMG
            }
        }

        /** Read up to [buffer].size bytes, tolerating short reads, stopping at EOF. */
        private fun readPrefix(input: InputStream, buffer: ByteArray): Int {
            var off = 0
            while (off < buffer.size) {
                val n = input.read(buffer, off, buffer.size - off)
                if (n < 0) break
                off += n
            }
            return off
        }

        private fun prependPrefix(header: ByteArray, n: Int, rest: InputStream): InputStream {
            if (n <= 0) return rest
            return java.io.SequenceInputStream(ByteArrayInputStream(header, 0, n), rest)
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
