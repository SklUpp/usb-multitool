package usb.ventoy

/**
 * Pure path rules for unattend XML targets on the Ventoy data volume.
 * Stdlib only — safe for host UI scripts (no USB / Android / VentoySetup).
 *
 * Semantics match the historical [VentoySetup] writer:
 * bare name → `/ventoy/<name>`; path with `/` kept; `\` → `/`; collapse empty
 * segments; reject `.` / `..` / empty; refuse the Ventoy config path.
 */
object XmlPath {
    const val VENTOY_DIR = "ventoy"
    const val CONFIG_PATH = "/ventoy/ventoy.json"

    /** FAT LFN limit for a single name component (UTF-16 code units). */
    const val MAX_LFN_UNITS: Int = 255

    fun resolve(input: String): String {
        val cleaned = input.trim().replace('\\', '/')
        require(cleaned.isNotEmpty()) { "invalid XML path \"$input\"" }
        val withLeading = if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        val segments = withLeading.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty() && segments.none { it == "." || it == ".." }) {
            "invalid XML path \"$input\""
        }
        val path = "/" + segments.joinToString("/")
        val resolved =
            if (cleaned.contains('/')) path else "/$VENTOY_DIR/${path.drop(1)}"
        require(!resolved.equals(CONFIG_PATH, ignoreCase = true)) {
            "XML target must not be the Ventoy config file $CONFIG_PATH"
        }
        return resolved
    }

    /**
     * Image path → XML basename stem for WIN-06 naming.
     * `/ISO/Win11.iso` → `Win11`; bare `setup` → `setup`; trailing dots stripped.
     * Empty after strip → `autounattend`.
     */
    fun imageStem(imagePath: String): String {
        val file =
            imagePath
                .trim()
                .replace('\\', '/')
                .substringAfterLast('/')
                .trim()
        require(file.isNotEmpty() && file != "." && file != "..") {
            "invalid image path \"$imagePath\""
        }
        require(!file.contains('\u0000')) { "image path contains NUL" }
        val stem =
            if (file.contains('.')) {
                file.substringBeforeLast('.').trimEnd('.')
            } else {
                file
            }.trim()
        return stem.ifEmpty { "autounattend" }
    }

    /**
     * Candidate file names: `<stem>.xml`, then `<stem>_1.xml`, `<stem>_2.xml`, …
     * Stops when the next name would exceed [MAX_LFN_UNITS] (filesystem namespace bound).
     * Optional [maxSuffix] truncates earlier for tests; null = no artificial cap.
     */
    fun numberedXmlNames(stem: String, maxSuffix: Int? = null): Sequence<String> {
        val base = stem.trim()
        require(base.isNotEmpty() && base != "." && base != "..") { "invalid XML stem \"$stem\"" }
        require(!base.contains('/') && !base.contains('\\') && !base.contains('\u0000')) {
            "invalid XML stem \"$stem\""
        }
        require(maxSuffix == null || maxSuffix >= 0) { "maxSuffix must be >= 0" }
        return sequence {
            val first = "$base.xml"
            if (first.length <= MAX_LFN_UNITS) yield(first)
            var n = 1
            while (maxSuffix == null || n <= maxSuffix) {
                val name = "${base}_$n.xml"
                if (name.length > MAX_LFN_UNITS) break
                yield(name)
                if (n == Int.MAX_VALUE) break
                n++
            }
        }
    }

    /** Resolved `/ventoy/...` paths for [numberedXmlNames]. */
    fun numberedXmlPaths(stem: String, maxSuffix: Int? = null): Sequence<String> =
        numberedXmlNames(stem, maxSuffix).map(::resolve)
}
