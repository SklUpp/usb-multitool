package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RestorePathTest {
    @Test
    fun normalizesSafDocumentIdAndSeparators() {
        assertEquals(
            "/Download/backup/disk.img",
            normalizeDocumentPath("Download//backup/./disk.img"),
        )
    }

    @Test
    fun resolvesParentWithoutEscapingRoot() {
        assertEquals("/Download/disk.map", normalizeDocumentPath("Download/backup/../disk.map"))
        assertEquals("/disk.map", normalizeDocumentPath("../../disk.map"))
    }

    @Test
    fun keepsEmptyPathAsRoot() {
        assertEquals("/", normalizeDocumentPath(""))
        assertEquals("/", normalizeDocumentPath("///"))
    }
}
