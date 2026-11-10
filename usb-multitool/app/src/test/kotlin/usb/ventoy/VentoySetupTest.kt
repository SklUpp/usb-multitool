package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the `/ventoy/ventoy.json` seeding contract [VentoySetup.linkXml] relies on.
 * FatFs is native, so the raw volume I/O path cannot run on the JVM; the merge
 * half that already ate other ISOs' links is exercised here directly.
 */
class VentoySetupTest {
    @Test
    fun configKeyMatchesWhatWindowsSetupReads() {
        // A leading slash hides the seed from WindowsSetup and drops every other link.
        assertEquals("ventoy/ventoy.json", VentoySetup.CONFIG_KEY)
        assertEquals("/" + VentoySetup.CONFIG_KEY, VentoySetup.CONFIG_PATH)
    }

    @Test
    fun linkingASecondIsoKeepsTheFirstLinkAndControlBlock() {
        val firstRoot = VentoySetup.configRoot(existingJson = null)
        val firstJson = WindowsSetup.write(
            firstRoot,
            "Windows11_Pro.iso",
            SetupOptions(bypassChecks = true),
            overwrite = false,
        )

        // Second link reads the config back from the volume and reseeds the root.
        val secondRoot = VentoySetup.configRoot(firstJson)
        val merged = WindowsSetup.write(secondRoot, "Ubuntu.iso", SetupOptions(), overwrite = false)

        val root = MiniJson.parse(merged) as Map<*, *>
        val images = (root["auto_install"] as List<*>).map { (it as Map<*, *>)["image"] }
        assertEquals(listOf("/ISO/Windows11_Pro.iso", "/ISO/Ubuntu.iso"), images)

        val controlKeys = (root["control"] as List<*>)
            .flatMap { (it as Map<*, *>).keys }
            .map { it.toString() }
        assertTrue("control block lost: $controlKeys", controlKeys.contains("VTOY_DEFAULT_SEARCH_ROOT"))
        assertTrue("control block lost: $controlKeys", controlKeys.contains("VTOY_WIN11_BYPASS_CHECK"))
    }

    @Test
    fun relinkingTheSameIsoStillRequiresOverwrite() {
        val firstJson =
            WindowsSetup.write(VentoySetup.configRoot(null), "Win11.iso", SetupOptions(), overwrite = false)
        try {
            WindowsSetup.write(VentoySetup.configRoot(firstJson), "Win11.iso", SetupOptions(), overwrite = false)
            fail("expected VentoyOverwriteRequiredException")
        } catch (_: VentoyOverwriteRequiredException) {
        }
    }
}
