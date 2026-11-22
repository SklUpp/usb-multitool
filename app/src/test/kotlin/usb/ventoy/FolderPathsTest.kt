package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Path rules for the lazy folder browser; they gate both navigation and host reads. */
class FolderPathsTest {
    @Test
    fun normalizesAbsoluteFolders() {
        assertEquals("/", normalizeFolderPath("/"))
        assertEquals("/", normalizeFolderPath("//"))
        assertEquals("/ISO", normalizeFolderPath("/ISO/"))
        assertEquals("/ISO/Windows", normalizeFolderPath("//ISO///Windows/"))
    }

    @Test
    fun rejectsRelativeAndTraversingPaths() {
        for (path in listOf("", "ISO", "../ISO", "/..", "/ISO/../ventoy", "/./ISO")) {
            assertThrows(IllegalArgumentException::class.java) { normalizeFolderPath(path) }
        }
    }

    @Test
    fun parentAndNameFollowThePath() {
        assertNull(folderParent("/"))
        assertEquals("/", folderParent("/ISO"))
        assertEquals("/ISO", folderParent("/ISO/Windows"))
        assertEquals("/", folderName("/"))
        assertEquals("ISO", folderName("/ISO"))
        assertEquals("Windows", folderName("/ISO/Windows/"))
    }
}
