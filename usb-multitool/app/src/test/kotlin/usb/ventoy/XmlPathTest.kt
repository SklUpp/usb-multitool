package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
