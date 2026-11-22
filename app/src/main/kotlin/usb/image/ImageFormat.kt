package usb.image

import java.io.InputStream

private const val ZERO_READ_LIMIT = 3

/** Raw/compressed encoding of an image source. */
enum class ImageFormat {
    ISO,
    IMG,
    GZIP,
    XZ,
}

/**
 * Pure filename/magic rules shared by the UI planner and ImageSource. Kept free of Android
 * types so host-side UI tests can decide whether a stored size is a decoded size.
 */
object ImageFormatNames {
    fun isCompressed(name: String?): Boolean =
        when (detectImageFormat(name.orEmpty())) {
            ImageFormat.GZIP, ImageFormat.XZ -> true
            ImageFormat.ISO, ImageFormat.IMG -> false
        }

    /** Stored bytes are the decoded image only when the name does not say compressed. */
    fun decodedBytesFromStored(name: String?, storedBytes: Long?): Long? =
        if (storedBytes == null || isCompressed(name)) null else storedBytes

    /** Read up to 6 bytes or EOF, tolerating short reads, then apply the magic rule. */
    fun readCompressedMagic(input: InputStream): Boolean {
        val prefix = ByteArray(6)
        var offset = 0
        var zeroReads = 0
        while (offset < prefix.size) {
            val n = input.read(prefix, offset, prefix.size - offset)
            if (n < 0) break
            if (n == 0) {
                if (++zeroReads >= ZERO_READ_LIMIT) {
                    throw java.io.IOException("stream made no progress while reading magic")
                }
                continue
            }
            zeroReads = 0
            offset += n
        }
        return isCompressedMagic(prefix, offset)
    }

    fun isCompressedMagic(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) return true
        if (length >= 6 &&
            bytes[0] == 0xFD.toByte() && bytes[1] == '7'.code.toByte() &&
            bytes[2] == 'z'.code.toByte() && bytes[3] == 'X'.code.toByte() &&
            bytes[4] == 'Z'.code.toByte() && bytes[5] == 0.toByte()
        ) {
            return true
        }
        return false
    }
}

/** Name-based format; compressed magic still wins in [classifyImageContent]. */
fun detectImageFormat(name: String): ImageFormat {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".gz") || lower.endsWith(".img.gz") || lower.endsWith(".iso.gz") ->
            ImageFormat.GZIP
        lower.endsWith(".xz") || lower.endsWith(".img.xz") || lower.endsWith(".iso.xz") ->
            ImageFormat.XZ
        lower.endsWith(".iso") -> ImageFormat.ISO
        else -> ImageFormat.IMG
    }
}

/** Magic-only format; empty/raw bytes classify as [ImageFormat.IMG]. */
fun detectImageFormatBytes(bytes: ByteArray): ImageFormat {
    if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
        return ImageFormat.GZIP
    }
    if (bytes.size >= 6 &&
        bytes[0] == 0xFD.toByte() &&
        bytes[1] == '7'.code.toByte() &&
        bytes[2] == 'z'.code.toByte() &&
        bytes[3] == 'X'.code.toByte() &&
        bytes[4] == 'Z'.code.toByte() &&
        bytes[5] == 0.toByte()
    ) {
        return ImageFormat.XZ
    }
    return ImageFormat.IMG
}

/**
 * Same name/magic reconciliation used by the engine. Compressed magic always wins; a
 * compressed DISPLAY_NAME with raw magic stays compressed so the decoder fails loudly
 * instead of writing encoded bytes as a raw image.
 */
fun classifyImageContent(displayName: String?, header: ByteArray): ImageFormat {
    val magic = detectImageFormatBytes(header)
    if (magic == ImageFormat.GZIP || magic == ImageFormat.XZ) return magic
    val named = displayName?.let(::detectImageFormat)
    return when (named) {
        ImageFormat.GZIP -> ImageFormat.GZIP
        ImageFormat.XZ -> ImageFormat.XZ
        ImageFormat.ISO -> ImageFormat.ISO
        else -> ImageFormat.IMG
    }
}
