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
    data object Unreadable : SettingStatus()
}

data class VentoyGlobalSettings(
    val entries: List<VentoySetting>,
    val error: String? = null,
)
