package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolver-only — safe for run-ventoy-junit (all ventoy tests, no UI classpath).
 * UI gate / displayLabel covered in ConfirmationTest.
 */
class XmlPathTest {
    @Test
    fun bareNameLandsUnderVentoy() {
        assertEquals("/ventoy/autounattend.xml", XmlPath.resolve("autounattend.xml"))
    }

    @Test
    fun pathWithSlashKeptAndDoubleSlashCollapsed() {
        assertEquals("/ventoy/autounattend.xml", XmlPath.resolve("/ventoy//autounattend.xml"))
        assertEquals("/custom/setup.xml", XmlPath.resolve("/custom//setup.xml"))
        assertEquals("/path/to.xml", XmlPath.resolve("\\path\\to.xml"))
    }

    @Test
    fun rejectsDotDotEmptyAndConfigPath() {
        assertThrows(IllegalArgumentException::class.java) { XmlPath.resolve("") }
        assertThrows(IllegalArgumentException::class.java) { XmlPath.resolve("/ventoy/../x.xml") }
        assertThrows(IllegalArgumentException::class.java) { XmlPath.resolve("/ventoy/./x.xml") }
        assertThrows(IllegalArgumentException::class.java) {
            XmlPath.resolve(XmlPath.CONFIG_PATH)
        }
        assertThrows(IllegalArgumentException::class.java) {
            XmlPath.resolve("/ventoy/ventoy.json")
        }
    }

    @Test
    fun imageStemStripsPathAndExtension() {
        assertEquals("Win11_23H2_English_x64", XmlPath.imageStem("/ISO/Win11_23H2_English_x64.iso"))
        assertEquals("setup", XmlPath.imageStem("setup.wim"))
        assertEquals("noext", XmlPath.imageStem("/ISO/noext"))
        assertEquals("autounattend", XmlPath.imageStem("/ISO/.iso"))
        assertEquals("a.b", XmlPath.imageStem("a.b.img"))
    }

    @Test
    fun numberedNamesAreConservativeSequence() {
        assertEquals(
            listOf("Win11.xml", "Win11_1.xml", "Win11_2.xml"),
            XmlPath.numberedXmlNames("Win11", maxSuffix = 2).toList(),
        )
        assertEquals(
            listOf("/ventoy/Win11.xml", "/ventoy/Win11_1.xml"),
            XmlPath.numberedXmlPaths("Win11", maxSuffix = 1).toList(),
        )
    }

    @Test
    fun resolveStillRefusesConfigEvenNearStem() {
        assertEquals("/ventoy/ventoy.xml", XmlPath.resolve("ventoy.xml"))
        assertThrows(IllegalArgumentException::class.java) {
            XmlPath.resolve("ventoy.json")
        }
    }

    @Test
    fun numberedNamesBoundByLfnNotArbitrary999() {
        val names = XmlPath.numberedXmlNames("Long").take(1005).toList()
        assertEquals("Long.xml", names.first())
        assertEquals("Long_1000.xml", names[1000])
        assertEquals("Long_1004.xml", names.last())
    }

    @Test
    fun numberedNamesStopWhenLfnExceeded() {
        // stem of 250 leaves room for `.xml` (254) but not `_1.xml` (254? 250+2+1+4=257).
        val stem = "N".repeat(250)
        val names = XmlPath.numberedXmlNames(stem).toList()
        assertEquals(listOf("${stem}.xml"), names)
        assertTrue(names.single().length <= XmlPath.MAX_LFN_UNITS)
    }
}
