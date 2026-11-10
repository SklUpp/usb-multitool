package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsAutoInstallTest {
    private val existing =
        """
        {
          "control": [{"VTOY_MENU_LANGUAGE": "en_US"}],
          "theme": {"file": "/ventoy/theme/theme.txt"},
          "auto_install": [
            {
              "image": "/Other/old.iso",
              "template": ["/ventoy/a.xml", "/ventoy/b.xml"],
              "autosel": 2,
              "timeout": 10
            }
          ]
        }
        """.trimIndent()

    @Test
    fun multipleImagesCanShareOneTemplateAndUnrelatedDataSurvives() {
        val json =
            WindowsSetup.mergeAutoInstall(
                existingJson = existing,
                imagePaths = listOf("/Win/one.img", "/Win/two.iso"),
                templatePath = "/ventoy/win.xml",
                options = SetupOptions(),
                overwrite = false,
            )
        val root = MiniJson.parse(json) as Map<*, *>
        assertEquals(
            mapOf("file" to "/ventoy/theme/theme.txt"),
            root["theme"],
        )
        val control = (root["control"] as List<*>).first() as Map<*, *>
        assertEquals("en_US", control["VTOY_MENU_LANGUAGE"])
        val links = WindowsSetup.parseLinks(json)
        assertEquals(
            listOf("/Other/old.iso", "/Win/one.img", "/Win/two.iso"),
            links.map { it.image },
        )
        assertEquals(listOf("/ventoy/a.xml", "/ventoy/b.xml"), links.first().templates)
        assertEquals("/ventoy/win.xml", links[1].templates.single())
    }

    @Test
    fun existingTemplateArrayIsShownWithoutFlattening() {
        val links = WindowsSetup.parseLinks(existing)
        assertEquals(listOf("/ventoy/a.xml", "/ventoy/b.xml"), links.single().templates)
    }

    @Test
    fun conflictWithoutOverwriteChangesNothing() {
        val json = """{"auto_install":[{"image":"/Win/one.img","template":"/ventoy/old.xml"}]}"""
        assertThrows(VentoyOverwriteRequiredException::class.java) {
            WindowsSetup.mergeAutoInstall(
                json,
                listOf("/Win/one.img", "/Win/two.img"),
                "/ventoy/new.xml",
                SetupOptions(),
                overwrite = false,
            )
        }
    }

    @Test
    fun overwriteUpdatesOnlyTheMatchingEntry() {
        val json = """{"auto_install":[{"image":"/Win/one.img","template":"/ventoy/old.xml","autosel":1}]}"""
        val merged =
            WindowsSetup.mergeAutoInstall(
                json,
                listOf("/Win/one.img"),
                "/ventoy/new.xml",
                SetupOptions(),
                overwrite = true,
            )
        val link = WindowsSetup.parseLinks(merged).single()
        assertEquals("/ventoy/new.xml", link.templates.single())
        val entry = ((MiniJson.parse(merged) as Map<*, *>)["auto_install"] as List<*>).single() as Map<*, *>
        // MiniJson integers are Long; value must stay 1 across overwrite.
        assertEquals(1L, entry["autosel"])
    }

    @Test
    fun duplicateEntriesForOneImageAreNotSilentlyOverwritten() {
        val json =
            """
            {"auto_install":[
              {"image":"/Win/one.img","template":"/ventoy/a.xml"},
              {"image":"/Win/one.img","template":"/ventoy/b.xml"}
            ]}
            """.trimIndent()
        assertThrows(AmbiguousAutoInstallEntryException::class.java) {
            WindowsSetup.mergeAutoInstall(
                json,
                listOf("/Win/one.img"),
                "/ventoy/new.xml",
                SetupOptions(),
                overwrite = true,
            )
        }
    }

    @Test
    fun duplicateEntriesCannotBeRemovedAmbiguously() {
        val json =
            """
            {"auto_install":[
              {"image":"/Win/one.img","template":"/ventoy/a.xml"},
              {"image":"/Win/one.img","template":"/ventoy/b.xml"}
            ]}
            """.trimIndent()
        assertThrows(AmbiguousAutoInstallEntryException::class.java) {
            WindowsSetup.removeLinks(json, listOf("/Win/one.img"))
        }
    }

    @Test
    fun removalDropsOnlyTheBindingAndKeepsEverythingElse() {
        val json =
            """
            {
              "theme": {"file": "/theme.txt"},
              "auto_install": [
                {"image":"/Win/one.img","template":"/ventoy/win.xml"},
                {"image":"/Linux/linux.iso","template":["/ventoy/a.cfg","/ventoy/b.cfg"]}
              ]
            }
            """.trimIndent()
        val updated = WindowsSetup.removeLinks(json, listOf("/Win/one.img"))
        assertNotNull(updated)
        val root = MiniJson.parse(updated!!) as Map<*, *>
        assertEquals(mapOf("file" to "/theme.txt"), root["theme"])
        val links = WindowsSetup.parseLinks(updated)
        assertEquals(listOf("/Linux/linux.iso"), links.map { it.image })
        assertEquals(listOf("/ventoy/a.cfg", "/ventoy/b.cfg"), links.single().templates)
        assertNull(WindowsSetup.removeLinks(json, listOf("/Not/Present.img")))
    }

    @Test
    fun bareNameStaysLegacyIsoPathWhileArbitraryPathIsHonoured() {
        assertEquals("/ISO/win.iso", WindowsSetup.normalizeImagePath("win.iso"))
        assertEquals("/Win/win.iso", WindowsSetup.normalizeImagePath("/Win/win.iso"))
        assertEquals("/Win/win.iso", WindowsSetup.normalizeImagePath("Win\\win.iso"))
        assertThrows(IllegalArgumentException::class.java) {
            WindowsSetup.normalizeImagePath("../escape.iso")
        }
    }

    @Test
    fun quotedPathRoundTripsThroughJson() {
        val json =
            WindowsSetup.mergeAutoInstall(
                null,
                listOf("/Win/\"quoted\" image.iso"),
                "/ventoy/\"q\".xml",
                SetupOptions(),
                overwrite = false,
            )
        val link = WindowsSetup.parseLinks(json).single()
        assertEquals("/Win/\"quoted\" image.iso", link.image)
        assertEquals(listOf("/ventoy/\"q\".xml"), link.templates)
    }

    @Test
    fun controlOnlyMergeKeepsAutoInstallAndUnrelatedKeys() {
        val json =
            WindowsSetup.mergeControlOptions(
                existing,
                SetupOptions(bypassChecks = false, localAccount = true),
            )
        val root = MiniJson.parse(json) as Map<*, *>
        val controls =
            (root["control"] as List<*>).map { (it as Map<*, *>).entries.first() }
        assertTrue(controls.any { it.key == "VTOY_WIN11_BYPASS_CHECK" && it.value == "0" })
        assertTrue(controls.any { it.key == "VTOY_WIN11_BYPASS_NRO" && it.value == "1" })
        assertEquals(1, (root["auto_install"] as List<*>).size)
        assertEquals(mapOf("file" to "/ventoy/theme/theme.txt"), root["theme"])
    }
}
