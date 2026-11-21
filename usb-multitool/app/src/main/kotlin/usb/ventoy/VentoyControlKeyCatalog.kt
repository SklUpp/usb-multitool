package usb.ventoy

import java.security.MessageDigest

/**
 * One official Ventoy `control` key discovered from Plugson HTML for a pinned release.
 *
 * This is a display/catalog hint only. [VentoyControlCatalog] remains the write validator;
 * a network entry never writes `ventoy.json` by itself and never invents default values.
 */
enum class VentoyControlKeyKind {
    /** Explicit 0/1 radios. */
    FLAG,
    /** Fixed option list (radios with more than 0/1, or a populated select). */
    CHOICE,
    /** Free-form integer text such as menu timeouts. */
    INTEGER,
    /** Free-form text; used when Plugson leaves options empty (e.g. menu language). */
    TEXT,
    /** Free-form path text (search root / default image). */
    PATH,
}

data class VentoyControlKeyDef(
    val key: String,
    val kind: VentoyControlKeyKind,
    /** Non-empty for [VentoyControlKeyKind.FLAG] and [VentoyControlKeyKind.CHOICE]. */
    val allowed: List<String> = emptyList(),
)

/**
 * Verified catalog of official control keys for one Ventoy release.
 *
 * [keys] contains only names found under Plugson `h3.box-title` that match `VTOY_*`.
 * Top-level sections such as `theme` are never included.
 */
data class VentoyControlKeyCatalog(
    val ventoyRelease: String,
    val upstreamRef: String,
    val upstreamSha256: String,
    val fetchedAtEpochMs: Long,
    val keys: List<VentoyControlKeyDef>,
) {
    fun key(name: String): VentoyControlKeyDef? = keys.firstOrNull { it.key == name }
}

/** Fail-closed outcome of a manual catalog refresh. Never mutates user settings. */
sealed interface VentoyControlKeyCatalogResult {
    data class Ok(val catalog: VentoyControlKeyCatalog) : VentoyControlKeyCatalogResult

    data class Failed(val message: String) : VentoyControlKeyCatalogResult
}

/**
 * Parses official Plugson `plugson_control.html` into a [VentoyControlKeyCatalog].
 *
 * HTML is untrusted input: only `h3.box-title` key names and in-section radio/select values
 * are read. Descriptions, scripts and empty language lists are ignored so no undocumented
 * default is invented. Duplicate or malformed catalogs are rejected entirely.
 */
object VentoyControlKeyCatalogParser {
    const val MAX_KEYS = 64
    const val MAX_HTML_CHARS = 256 * 1024
    private val titleKeyPattern = Regex("""^\s*(VTOY_[A-Z0-9_]+)\b""")
    private val boxTitlePattern =
        Regex(
            """<h3\s+[^>]*class\s*=\s*["']box-title["'][^>]*>(.*?)</h3>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val radioValuePattern =
        Regex(
            """<input\b[^>]*\btype\s*=\s*["']radio["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val selectBlockPattern =
        Regex(
            """<select\b[^>]*>(.*?)</select>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val optionPattern =
        Regex(
            """<option\b([^>]*)>(.*?)</option>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val textInputPattern =
        Regex(
            """<input\b[^>]*\btype\s*=\s*["']text["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val attrPattern = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)')""")

    fun parse(
        html: String,
        ventoyRelease: String,
        upstreamRef: String,
        upstreamSha256: String,
        fetchedAtEpochMs: Long,
    ): VentoyControlKeyCatalog {
        require(html.length <= MAX_HTML_CHARS) {
            "Plugson control HTML is ${html.length} characters; limit is $MAX_HTML_CHARS"
        }
        require(ventoyRelease.isNotBlank()) { "ventoyRelease must not be blank" }
        require(upstreamRef.isNotBlank()) { "upstreamRef must not be blank" }
        require(upstreamSha256.matches(Regex("[a-f0-9]{64}"))) {
            "upstreamSha256 must be a lowercase hex SHA-256 digest"
        }

        val titles = boxTitlePattern.findAll(html).toList()
        if (titles.isEmpty()) {
            throw VentoyControlKeyCatalogException(
                "Plugson control HTML has no h3.box-title control keys; refusing the catalog",
            )
        }

        val keys = ArrayList<VentoyControlKeyDef>(titles.size)
        val seen = HashSet<String>()
        for (index in titles.indices) {
            val match = titles[index]
            val rawTitle = stripTags(match.groupValues[1])
            val keyMatch =
                titleKeyPattern.find(rawTitle)
                    ?: throw VentoyControlKeyCatalogException(
                        "Plugson control title is not a VTOY key: \"${rawTitle.take(64)}\"",
                    )
            val key = keyMatch.groupValues[1]
            VentoyControlCatalog.keyIssue(key)?.let { issue ->
                throw VentoyControlKeyCatalogException(
                    "Plugson control key \"$key\" is invalid: ${issueText(issue)}",
                )
            }
            if (!seen.add(key)) {
                throw VentoyControlKeyCatalogException(
                    "Plugson control HTML lists duplicate key \"$key\"; refusing the catalog",
                )
            }
            if (keys.size >= MAX_KEYS) {
                throw VentoyControlKeyCatalogException(
                    "Plugson control HTML lists more than $MAX_KEYS keys; refusing the catalog",
                )
            }
            val bodyStart = match.range.last + 1
            val bodyEnd = titles.getOrNull(index + 1)?.range?.first ?: html.length
            val body = html.substring(bodyStart, bodyEnd)
            keys += parseSection(key, body)
        }

        return VentoyControlKeyCatalog(
            ventoyRelease = ventoyRelease,
            upstreamRef = upstreamRef,
            upstreamSha256 = upstreamSha256,
            fetchedAtEpochMs = fetchedAtEpochMs,
            keys = keys,
        )
    }

    private fun parseSection(key: String, body: String): VentoyControlKeyDef {
        val radios = linkedSetOf<String>()
        for (input in radioValuePattern.findAll(body)) {
            val value = attribute(input.value, "value") ?: continue
            validateOption(key, value)
            radios += value
        }
        val options = linkedSetOf<String>()
        for (select in selectBlockPattern.findAll(body)) {
            for (option in optionPattern.findAll(select.groupValues[1])) {
                val attrs = option.groupValues[1]
                val labeled = stripTags(option.groupValues[2]).trim()
                val value =
                    attribute(attrs, "value")
                        ?: attribute(attrs, "title")
                        ?: labeled.takeIf { it.isNotEmpty() }
                        ?: continue
                validateOption(key, value)
                options += value
            }
        }
        val hasText = textInputPattern.containsMatchIn(body)

        return when {
            radios.isNotEmpty() -> {
                val allowed = radios.toList()
                val kind =
                    if (allowed.toSet() == setOf("0", "1")) {
                        VentoyControlKeyKind.FLAG
                    } else {
                        VentoyControlKeyKind.CHOICE
                    }
                VentoyControlKeyDef(key = key, kind = kind, allowed = allowed)
            }
            options.isNotEmpty() ->
                VentoyControlKeyDef(
                    key = key,
                    kind = VentoyControlKeyKind.CHOICE,
                    allowed = options.toList(),
                )
            hasText ->
                VentoyControlKeyDef(
                    key = key,
                    kind = kindForFreeText(key),
                    allowed = emptyList(),
                )
            // Empty <select> (menu language is filled by Plugson JS): keep the key, invent nothing.
            selectBlockPattern.containsMatchIn(body) ->
                VentoyControlKeyDef(
                    key = key,
                    kind = VentoyControlKeyKind.TEXT,
                    allowed = emptyList(),
                )
            else ->
                throw VentoyControlKeyCatalogException(
                    "Plugson control key \"$key\" has no radio, select, or text control; format may have changed",
                )
        }
    }

    private fun kindForFreeText(key: String): VentoyControlKeyKind =
        when (key) {
            "VTOY_DEFAULT_SEARCH_ROOT", "VTOY_DEFAULT_IMAGE" -> VentoyControlKeyKind.PATH
            "VTOY_MENU_TIMEOUT", "VTOY_SECONDARY_TIMEOUT" -> VentoyControlKeyKind.INTEGER
            else -> VentoyControlKeyKind.TEXT
        }

    private fun validateOption(key: String, value: String) {
        if (value.length > VentoyControlCatalog.MAX_VALUE_LENGTH) {
            throw VentoyControlKeyCatalogException(
                "Plugson control key \"$key\" has a value longer than ${VentoyControlCatalog.MAX_VALUE_LENGTH}",
            )
        }
        if (value.any { it.code < 0x20 }) {
            throw VentoyControlKeyCatalogException(
                "Plugson control key \"$key\" has a value with control characters",
            )
        }
    }

    private fun attribute(tagOrAttrs: String, name: String): String? {
        for (match in attrPattern.findAll(tagOrAttrs)) {
            if (!match.groupValues[1].equals(name, ignoreCase = true)) continue
            return match.groupValues[3].ifEmpty { match.groupValues[4] }
        }
        return null
    }

    private fun stripTags(value: String): String =
        value.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun issueText(issue: VentoyControlCatalog.Issue): String =
        when (issue) {
            VentoyControlCatalog.Issue.EmptyKey -> "key is empty"
            is VentoyControlCatalog.Issue.KeyTooLong -> "key is longer than ${issue.max} characters"
            VentoyControlCatalog.Issue.KeyCharset ->
                "key may contain only letters, digits, '_', '.', and '-'"
            is VentoyControlCatalog.Issue.ValueTooLong -> "value is longer than ${issue.max} characters"
            VentoyControlCatalog.Issue.ValueControlChars -> "value must not contain control characters"
            is VentoyControlCatalog.Issue.ValueNotAllowed ->
                "${issue.key} expects ${issue.expected}, got \"${issue.actual}\""
            is VentoyControlCatalog.Issue.DuplicateKey -> "duplicate key \"${issue.key}\""
        }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Official Plugson control-key catalog could not be trusted or understood. */
class VentoyControlKeyCatalogException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
