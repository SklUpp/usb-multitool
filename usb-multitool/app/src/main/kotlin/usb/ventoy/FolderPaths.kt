package usb.ventoy

/**
 * Pure path rules for the lazy Ventoy data-partition folder browser.
 *
 * Every path is absolute, slash-separated and already normalized: no trailing slash, no
 * empty segment, no `.`/`..`. Root is exactly `/`. Rejecting traversal here keeps a stale
 * or forged UI value from addressing anything outside the mounted data partition; the
 * volume API itself would happily accept `..` segments.
 */

/** Fails closed on a relative, traversing or empty-segment path. */
fun normalizeFolderPath(raw: String): String {
    require(raw.startsWith("/")) { "\"$raw\" is not an absolute folder path" }
    val segments = raw.split('/').filter { it.isNotEmpty() }
    require(segments.none { it == "." || it == ".." }) {
        "\"$raw\" is not a plain folder path"
    }
    return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
}

/**
 * Same rules as [normalizeFolderPath], but a file path must name something below the root.
 * The volume API accepts `..`; rejecting it here keeps a stale picker value from escaping
 * the mounted partition.
 */
fun normalizeVentoyFilePath(raw: String): String {
    val normalized = normalizeFolderPath(raw)
    require(normalized != "/") { "\"$raw\" does not name a file" }
    return normalized
}

/** Parent folder, or null at the data-partition root. */
fun folderParent(path: String): String? {
    val normalized = normalizeFolderPath(path)
    if (normalized == "/") return null
    return normalized.substringBeforeLast('/').ifEmpty { "/" }
}

/** Last path segment; root keeps `/` so the browser never shows an empty label. */
fun folderName(path: String): String {
    val normalized = normalizeFolderPath(path)
    return if (normalized == "/") "/" else normalized.substringAfterLast('/')
}
