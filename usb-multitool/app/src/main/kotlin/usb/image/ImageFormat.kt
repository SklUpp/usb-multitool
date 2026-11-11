package usb.image

/** Raw/compressed encoding of an image source. */
enum class ImageFormat {
    ISO,
    IMG,
    GZIP,
    XZ,
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
