package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the `/ventoy/ventoy.json` merge contract used by VentoySetup. FatFs is native,
 * so the raw volume I/O path cannot run on the JVM; the merge half is exercised here.
 */
class VentoySetupTest {
    @Test
    fun configPathIsAbsoluteAndKeyIsVolumeRelative() {
        assertEquals("ventoy/ventoy.json", VentoySetup.CONFIG_KEY)
        assertEquals("/" + VentoySetup.CONFIG_KEY, VentoySetup.CONFIG_PATH)
    }

    @Test
    fun xmlPathRejectsVentoyConfigCaseInsensitively() {
        assertThrows(IllegalArgumentException::class.java) {
            VentoySetup.xmlPath("ventoy.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VentoySetup.xmlPath("/ventoy/VENTOY.JSON")
        }
        assertEquals("/ventoy/Win.xml", VentoySetup.xmlPath("Win.xml"))
        assertEquals("/Win/foo.xml", VentoySetup.xmlPath("/Win/foo.xml"))
        assertEquals("/Win/foo.xml", VentoySetup.xmlPath("Win/foo.xml"))
    }

    @Test
    fun unknownSearchLevelIsNotTreatedAsUnlimited() {
        assertNull(
            VentoySetup.pathInSearchScope(
                "/dir/a.iso",
                listOf("/"),
                "VTOY_MAX_SEARCH_LEVEL=unknown",
            ),
        )
        assertEquals(
            false,
            VentoySetup.pathInSearchScope(
                "/dir/a.iso",
                listOf("/"),
                "VTOY_MAX_SEARCH_LEVEL=0",
            ),
        )
    }

    @Test
    fun linkingASecondIsoKeepsTheFirstLinkAndControlBlock() {
        val firstJson =
            WindowsSetup.mergeAutoInstall(
                null,
                listOf("Windows11_Pro.iso"),
                "/ventoy/Windows11_Pro.xml",
                SetupOptions(bypassChecks = true),
                overwrite = false,
            )
        val merged =
            WindowsSetup.mergeAutoInstall(
                firstJson,
                listOf("Ubuntu.iso"),
                "/ventoy/Ubuntu.xml",
                SetupOptions(),
                overwrite = false,
            )

        val root = MiniJson.parse(merged) as Map<*, *>
        val images = (root["auto_install"] as List<*>).map { (it as Map<*, *>)["image"] }
        assertEquals(listOf("/ISO/Windows11_Pro.iso", "/ISO/Ubuntu.iso"), images)

        val controlKeys = (root["control"] as List<*>)
            .flatMap { (it as Map<*, *>).keys }
            .map { it.toString() }
        assertFalse(
            "binding must not invent a search root: $controlKeys",
            controlKeys.contains("VTOY_DEFAULT_SEARCH_ROOT"),
        )
        assertTrue("control block lost: $controlKeys", controlKeys.contains("VTOY_WIN11_BYPASS_CHECK"))
    }

    @Test
    fun relinkingTheSameIsoStillRequiresOverwrite() {
        val firstJson =
            WindowsSetup.mergeAutoInstall(
                null,
                listOf("Win11.iso"),
                "/ventoy/Win11.xml",
                SetupOptions(),
                overwrite = false,
            )
        try {
            WindowsSetup.mergeAutoInstall(
                firstJson,
                listOf("Win11.iso"),
                "/ventoy/Win11.xml",
                SetupOptions(),
                overwrite = false,
            )
            fail("expected VentoyOverwriteRequiredException")
        } catch (_: VentoyOverwriteRequiredException) {
        }
    }
}
