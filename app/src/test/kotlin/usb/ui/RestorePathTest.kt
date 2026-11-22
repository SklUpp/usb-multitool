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
    fun stripsProviderAndVolumePrefixes() {
        assertEquals(
            "/Download/backup/disk.img",
            normalizeDocumentPath("/document/primary:Download%2Fbackup%2Fdisk.img"),
        )
        assertEquals(
            "/Download/disk.map",
            normalizeDocumentPath("tree/primary:Download/disk.map"),
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
