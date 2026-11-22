package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source identity rules for the global-settings block. */
class VentoySettingsSourceTest {
    @Test
    fun defaultPathIsTheOnlyPathThatMayBeCreated() {
        assertTrue(VentoySettingsSource.Default.isDefaultPath())
        assertEquals(VentoySetup.CONFIG_PATH, VentoySettingsSource.Default.label())
        assertFalse(VentoySettingsSource.UsbFile("/custom/config.json").isDefaultPath())
        assertFalse(
            VentoySettingsSource.Document(
                "content://provider/config.json",
                displayName = "config.json",
            ).isDefaultPath(),
        )
    }

    @Test
    fun theStandardPathInAnotherCaseIsStillDefault() {
        assertTrue(VentoySettingsSource.UsbFile("/VENTOY/Ventoy.JSON").isDefaultPath())
    }

    @Test
    fun documentLabelNeverInventsAnAbsolutePhonePath() {
        val document = VentoySettingsSource.Document(
            uri = "content://provider/tree/document",
            displayName = "my-config.json",
        )
        assertEquals("my-config.json", document.label())
        assertEquals(
            "content://provider/tree/document",
            document.copy(displayName = null).label(),
        )
    }

    @Test
    fun targetIdentityIgnoresDocumentNamesButNotUris() {
        val document =
            VentoySettingsSource.Document(
                uri = "content://provider/config.json",
                displayName = "one.json",
            )
        assertTrue(
            document.sameTarget(
                VentoySettingsSource.Document(
                    uri = "content://provider/config.json",
                    displayName = "two.json",
                ),
            ),
        )
        assertFalse(
            document.sameTarget(
                VentoySettingsSource.Document("content://provider/other.json"),
            ),
        )
        assertTrue(
            VentoySettingsSource.UsbFile("/A.json")
                .sameTarget(VentoySettingsSource.UsbFile("/a.json")),
        )
        assertTrue(
            VentoySettingsSource.Default
                .sameTarget(VentoySettingsSource.UsbFile("/ventoy/ventoy.json")),
        )
        assertFalse(
            VentoySettingsSource.UsbFile("/custom/a.json")
                .sameTarget(VentoySettingsSource.UsbFile("/custom/b.json")),
        )
    }

    @Test
    fun aChosenFileMustBeAbsoluteWithoutTraversal() {
        assertEquals(
            "/custom/config.json",
            normalizeVentoyFilePath("/custom/config.json"),
        )
        assertEquals("/config.json", normalizeVentoyFilePath("//config.json"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeVentoyFilePath("/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeVentoyFilePath("../config.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeVentoyFilePath("/custom/../config.json")
        }
    }
}
