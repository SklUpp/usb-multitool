package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Global `control` workflow without a mounted volume: read-state distinction, explicit
 * key/value writes, and preservation of unknown JSON sections.
 */
class VentoySettingsWorkflowTest {
    @Test
    fun blankOrMissingControlIsEmptyNotAnError() {
        assertEquals(VentoyGlobalSettings.Empty, VentoySetup.parseGlobalSettings(""))
        assertEquals(VentoyGlobalSettings.Empty, VentoySetup.parseGlobalSettings("  \n"))
        assertEquals(VentoyGlobalSettings.Empty, VentoySetup.parseGlobalSettings("{}"))
        assertEquals(
            VentoyGlobalSettings.Empty,
            VentoySetup.parseGlobalSettings("""{"control": []}"""),
        )
    }

    @Test
    fun invalidJsonIsAReadErrorNotEmpty() {
        assertTrue(VentoySetup.parseGlobalSettings("{oops") is VentoyGlobalSettings.ReadError)
        assertTrue(VentoySetup.parseGlobalSettings("[]") is VentoyGlobalSettings.ReadError)
        assertTrue(
            VentoySetup.parseGlobalSettings("""{"control": {}}""") is
                VentoyGlobalSettings.ReadError,
        )
    }

    @Test
    fun entriesKeepRawValuesAndKnownValidation() {
        val parsed =
            VentoySetup.parseGlobalSettings(
                """
                {
                  "control": [
                    {"VTOY_WIN11_BYPASS_CHECK": "2"},
                    {"UNKNOWN_KEY": "keep"},
                    {"NOT_A_SCALAR": {"nested": true}}
                  ]
                }
                """.trimIndent(),
            )
        val entries = (parsed as VentoyGlobalSettings.Present).entries
        assertEquals(3, entries.size)
        val known = entries.first { it.key == "VTOY_WIN11_BYPASS_CHECK" }
        assertEquals("2", known.rawValue)
        assertEquals(SettingStatus.Known(valid = false, expected = "0 or 1"), known.status)
        assertEquals(SettingStatus.UnknownKey, entries.first { it.key == "UNKNOWN_KEY" }.status)
        assertEquals(SettingStatus.Unreadable, entries.first { it.key == "NOT_A_SCALAR" }.status)
    }

    @Test
    fun applyControlsPreservesUnknownSectionsAndRemovesOnlyChosenKey() {
        val existing =
            """
            {
              "control": [
                {"VTOY_WIN11_BYPASS_CHECK": "0"},
                {"CUSTOM_KEY": 7},
                {"REMOVE_ME": "x"}
              ],
              "auto_install": [{"image": "/ISO/Win.iso", "template": "/ventoy/Win.xml"}],
              "custom_section": {"keep": true}
            }
            """.trimIndent()
        val updated =
            WindowsSetup.applyControls(
                existing,
                listOf(
                    VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"),
                    VentoyControlSetting("NEW_KEY", "value"),
                    VentoyControlSetting("REMOVE_ME", null),
                ),
            ) ?: error("expected a changed JSON document")
        val root = MiniJson.parse(updated) as Map<*, *>
        val control = root["control"] as List<*>
        val flattened = control.flatMap { (it as Map<*, *>).entries }.associate {
            it.key.toString() to it.value
        }
        assertEquals("1", flattened["VTOY_WIN11_BYPASS_CHECK"])
        assertEquals(7L, flattened["CUSTOM_KEY"])
        assertEquals("value", flattened["NEW_KEY"])
        assertTrue("removed key must be gone", "REMOVE_ME" !in flattened)
        assertEquals(
            "unknown sections stay byte-equivalent in value",
            true,
            (root["custom_section"] as Map<*, *>)["keep"],
        )
        assertTrue(updated.contains("/ISO/Win.iso"))
        assertTrue(updated.contains("/ventoy/Win.xml"))
    }

    @Test
    fun catalogRejectsInvalidKnownValuesButAllowsAdditionalKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            WindowsSetup.applyControls(
                null,
                listOf(VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "2")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WindowsSetup.applyControls(
                null,
                listOf(VentoyControlSetting("bad key", "1")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WindowsSetup.applyControls(
                null,
                listOf(
                    VentoyControlSetting("X", "1"),
                    VentoyControlSetting("X", "0"),
                ),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            WindowsSetup.applyControls(
                """{"control": {}}""",
                listOf(VentoyControlSetting("X", "1")),
            )
        }
        val json =
            WindowsSetup.applyControls(
                null,
                listOf(VentoyControlSetting("VTOY_DEFAULT_SEARCH_ROOT", "/ISO")),
            ) ?: error("expected a changed JSON document")
        assertTrue(json.contains("VTOY_DEFAULT_SEARCH_ROOT"))
        assertTrue(json.contains("/ISO"))
    }

    @Test
    fun removalAllowsKeysOutsideTheCreationPattern() {
        val existing = """{"control":[{"MY KEY":"1"},{"CTRL\u0001":"2"}]}"""
        assertEquals(
            null,
            VentoyControlCatalog.problem(listOf(VentoyControlSetting("MY KEY", null))),
        )
        val updated =
            WindowsSetup.applyControls(
                existing,
                listOf(
                    VentoyControlSetting("MY KEY", null),
                    VentoyControlSetting("CTRL\u0001", null),
                ),
            ) ?: error("expected a changed JSON document")
        assertTrue("odd keys must be removable", !updated.contains("MY KEY"))
        assertTrue("control-char keys must be removable", !updated.contains("CTRL"))
    }

    @Test
    fun removalOnlySnapshotOnFileWithoutControlIsANoOp() {
        assertNull(
            WindowsSetup.applyControls(
                """{"auto_install":[]}""",
                listOf(VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", null)),
            ),
        )
        assertNull(
            WindowsSetup.applyControls(
                null,
                listOf(VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", null)),
            ),
        )
    }

    @Test
    fun writingTheSameValueIsANoOp() {
        assertNull(
            WindowsSetup.applyControls(
                """{"control":[{"VTOY_WIN11_BYPASS_CHECK":"1"}]}""",
                listOf(VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1")),
            ),
        )
    }
}
