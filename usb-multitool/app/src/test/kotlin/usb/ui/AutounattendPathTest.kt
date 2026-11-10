package usb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutounattendPathTest {
    @Test
    fun sameTargetMatchesXmlTargetForRelativeDirectories() {
        assertTrue(UiReducer.sameXmlTarget("/Win/foo.xml", "Win/foo.xml"))
        assertTrue(UiReducer.sameXmlTarget("/Win/foo.xml", "/Win/foo.xml"))
        assertFalse(UiReducer.sameXmlTarget("/ventoy/Win/foo.xml", "Win/foo.xml"))
        assertTrue(UiReducer.sameXmlTarget("/ventoy/foo.xml", "foo.xml"))
    }
}
