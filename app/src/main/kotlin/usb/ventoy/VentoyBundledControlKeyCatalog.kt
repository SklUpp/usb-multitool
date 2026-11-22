package usb.ventoy

/**
 * Official Plugson control keys shipped with the pinned Ventoy release, so the key picker
 * is useful offline. This is a display hint only: [VentoyControlCatalog] remains the write
 * validator. The manual refresh replaces it with the exact page parsed from upstream.
 *
 * Source: ventoy/Ventoy v1.1.18 Plugson/www/plugson_control.html (78,595 bytes), SHA-256
 * bb5f1f9ce4a05983557de431024578cf854dfea141d6209f674fb76976c66920.
 */
object VentoyBundledControlKeyCatalog {
    /** Kept local so this pure catalog does not pull in Android asset code. */
    private const val PINNED_RELEASE = "1.1.18"

    private fun flag(key: String) =
        VentoyControlKeyDef(key, VentoyControlKeyKind.FLAG, listOf("0", "1"))

    private fun choice(key: String, vararg allowed: String) =
        VentoyControlKeyDef(key, VentoyControlKeyKind.CHOICE, allowed.toList())

    private fun text(key: String, kind: VentoyControlKeyKind = VentoyControlKeyKind.TEXT) =
        VentoyControlKeyDef(key, kind)

    val catalog: VentoyControlKeyCatalog =
        VentoyControlKeyCatalog(
            ventoyRelease = PINNED_RELEASE,
            upstreamRef = "ventoy/Ventoy@v$PINNED_RELEASE:Plugson/www/plugson_control.html",
            upstreamSha256 = "bb5f1f9ce4a05983557de431024578cf854dfea141d6209f674fb76976c66920",
            // Zero marks a catalog compiled into the app rather than fetched from the network.
            fetchedAtEpochMs = 0L,
            keys = listOf(
                text("VTOY_MENU_LANGUAGE"),
                text("VTOY_DEFAULT_SEARCH_ROOT", VentoyControlKeyKind.PATH),
                flag("VTOY_SECURE_BOOT_POLICY"),
                choice("VTOY_WIN_UEFI_RES_LOCK", "0", "1", "2", "3"),
                flag("VTOY_WIN11_BYPASS_CHECK"),
                flag("VTOY_WIN11_BYPASS_NRO"),
                flag("VTOY_DEFAULT_MENU_MODE"),
                text("VTOY_MENU_TIMEOUT", VentoyControlKeyKind.INTEGER),
                text("VTOY_DEFAULT_IMAGE", VentoyControlKeyKind.PATH),
                choice(
                    "VTOY_MAX_SEARCH_LEVEL",
                    "max",
                    "0",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5",
                    "6",
                    "7",
                    "8",
                    "9",
                    "10",
                ),
                flag("VTOY_SECONDARY_BOOT_MENU"),
                flag("VTOY_SHOW_PASSWORD_ASTERISK"),
                text("VTOY_SECONDARY_TIMEOUT", VentoyControlKeyKind.INTEGER),
                choice(
                    "VTOY_DEFAULT_KBD_LAYOUT",
                    "QWERTY_USA",
                    "AZERTY",
                    "CZECH_QWERTY",
                    "CZECH_QWERTZ",
                    "DANISH",
                    "DVORAK_USA",
                    "FRENCH",
                    "GERMAN",
                    "ITALIANO",
                    "JAPAN_106",
                    "LATIN_USA",
                    "PORTU_BRAZIL",
                    "QWERTY_UK",
                    "QWERTZ",
                    "QWERTZ_HUN",
                    "QWERTZ_SLOV_CROAT",
                    "SPANISH",
                    "SWEDISH",
                    "TURKISH_Q",
                    "VIETNAMESE",
                ),
                flag("VTOY_TREE_VIEW_MENU_STYLE"),
                flag("VTOY_FILT_DOT_UNDERSCORE_FILE"),
                flag("VTOY_SORT_CASE_SENSITIVE"),
                flag("VTOY_VHD_NO_WARNING"),
                flag("VTOY_FILE_FLT_ISO"),
                flag("VTOY_FILE_FLT_WIM"),
                flag("VTOY_FILE_FLT_EFI"),
                flag("VTOY_FILE_FLT_IMG"),
                flag("VTOY_FILE_FLT_VHD"),
                flag("VTOY_FILE_FLT_VTOY"),
            ),
        )
}
