package usb.ventoy

/**
 * Parsed `ventoy.json` control entries. Pure Kotlin: [VentoySetup] produces these and the UI
 * renders them, so they never live in usb.ui.
 */

/** One global ventoy.json `control` entry, shown with its raw value. */
data class VentoySetting(
    val key: String,
    val rawValue: String,
    val status: SettingStatus,
)

sealed class SettingStatus {
    data class Known(val valid: Boolean, val expected: String) : SettingStatus()
    data object UnknownKey : SettingStatus()
    /** The key exists, but its value is not a scalar this app can edit safely. */
    data object Unreadable : SettingStatus()
}

/**
 * Result of reading `/ventoy/ventoy.json`. Missing, empty, and unreadable are three
 * different states: an unreadable file must never be reported as "no settings".
 */
sealed interface VentoyGlobalSettings {
    /** No `/ventoy/ventoy.json` on the data partition. */
    data object Missing : VentoyGlobalSettings

    /** The file exists and parsed, but has no `control` entries. */
    data object Empty : VentoyGlobalSettings

    data class Present(val entries: List<VentoySetting>) : VentoyGlobalSettings

    /**
     * The file or the volume could not be read/parsed; [message] is the cause.
     * [damagedLayout] means Ventoy signatures exist but the partition layout is invalid, so
     * the UI must not suggest installing Ventoy again.
     */
    data class ReadError(
        val message: String,
        val damagedLayout: Boolean = false,
    ) : VentoyGlobalSettings
}

/**
 * One explicit control write. A null [value] removes the key; the JSON remains owned by
 * [VentoySetup], so unknown sections and unrelated keys are preserved.
 */
data class VentoyControlSetting(val key: String, val value: String?)

/** One control key this app understands and can offer explicit choices for. */
data class VentoyKnownControl(
    val key: String,
    val expected: String,
    val allowed: List<String>,
)

/**
 * Why one data-partition folder level could not be read. The host produces it from the
 * real exception; the UI owns the localized text, so no raw engine message is displayed.
 */
enum class FolderReadProblem {
    /** The folder (or a path component) does not exist any more. */
    Missing,
    /** The path exists but is a regular file. */
    NotDirectory,
    /** Mount/IO failure; the folder may still exist. */
    Unreadable,
}

/**
 * Validation shared by the reducer, the host operation, and the JSON writer. Unknown keys
 * stay writable as explicit key/value entries; known keys are restricted to their options.
 */
object VentoyControlCatalog {
    const val MAX_KEY_LENGTH = 128
    const val MAX_VALUE_LENGTH = 1024

    val known: List<VentoyKnownControl> =
        listOf(
            VentoyKnownControl(
                key = "VTOY_WIN11_BYPASS_CHECK",
                expected = "0 or 1",
                allowed = listOf("0", "1"),
            ),
            VentoyKnownControl(
                key = "VTOY_WIN11_BYPASS_NRO",
                expected = "0 or 1",
                allowed = listOf("0", "1"),
            ),
        )

    private val keyPattern = Regex("[A-Za-z0-9_.-]+")

    fun known(key: String): VentoyKnownControl? = known.firstOrNull { it.key == key }

    /** Null when [key] is writable; otherwise a stable human-readable problem. */
    fun keyProblem(key: String): String? =
        when {
            key.isBlank() -> "key is empty"
            key.length > MAX_KEY_LENGTH -> "key is longer than $MAX_KEY_LENGTH characters"
            !keyPattern.matches(key) ->
                "key may contain only letters, digits, '_', '.', and '-'"
            else -> null
        }

    /** Null when [value] is writable for [key]; unknown keys accept any bounded text. */
    fun valueProblem(key: String, value: String?): String? {
        if (value == null) return null
        val knownControl = known(key)
        if (knownControl != null) {
            return if (value in knownControl.allowed) {
                null
            } else {
                "$key expects ${knownControl.expected}, got \"$value\""
            }
        }
        return when {
            value.length > MAX_VALUE_LENGTH -> "value is longer than $MAX_VALUE_LENGTH characters"
            value.any { it.code < 0x20 } -> "value must not contain control characters"
            else -> null
        }
    }

    /** First problem in [settings], including duplicate keys; null when all are writable. */
    fun problem(settings: List<VentoyControlSetting>): String? {
        val seen = HashSet<String>()
        for (setting in settings) {
            // Removal only matches an existing key; it never writes the key itself, so keys
            // with spaces or non-ASCII characters stay removable.
            if (setting.value != null) {
                keyProblem(setting.key)?.let { return it }
                valueProblem(setting.key, setting.value)?.let { return it }
            }
            if (!seen.add(setting.key)) return "duplicate key \"${setting.key}\""
        }
        return null
    }
}
