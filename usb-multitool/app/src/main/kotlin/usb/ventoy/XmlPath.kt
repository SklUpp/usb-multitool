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
}
