package usb.ventoy

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.operation.DiskOperation
import usb.testutil.InMemoryUsbVolume

class VentoyImageLibraryTest {
    @Test
    fun newFileIsWrittenAndNoTempOrBackupRemains() {
        val volume = InMemoryUsbVolume()
        val operation = DiskOperation("import")
        val outcome = VentoyIsoLibrary.import(
            volume = volume,
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            rawName = "win.img",
            operation = operation,
            overwrite = false,
        )
        assertEquals("/ISO/win.img", outcome.targetPath)
        assertEquals(4L, outcome.bytes)
        assertFalse(outcome.replaced)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), volume.fileBytes("/ISO/win.img"))
        assertTrue(volume.paths().none { it.contains(".import-") || it.contains(".bak-") })
        assertTrue(operation.mutationAttempted)
        assertTrue(operation.commitReached)
    }

    @Test
    fun existingFileIsNotTouchedWithoutOverwrite() {
        val volume = InMemoryUsbVolume(mapOf("/ISO/win.img" to byteArrayOf(9, 9)))
        val operation = DiskOperation("import")
        assertThrows(ImageAlreadyExistsException::class.java) {
            VentoyIsoLibrary.import(
                volume,
                ByteArrayInputStream(byteArrayOf(1)),
                "win.img",
                operation,
                overwrite = false,
            )
        }
        assertArrayEquals(byteArrayOf(9, 9), volume.fileBytes("/ISO/win.img"))
        assertFalse(operation.mutationAttempted)
        assertTrue(volume.paths().none { it.contains(".import-") || it.contains(".bak-") })
    }

    @Test
    fun overwriteReplacesOnlyAfterTheNewFileIsComplete() {
        val volume = InMemoryUsbVolume(mapOf("/ISO/win.img" to byteArrayOf(9, 9)))
        val outcome = VentoyIsoLibrary.import(
            volume,
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            "win.img",
            DiskOperation("import"),
            overwrite = true,
        )
        assertTrue(outcome.replaced)
        assertArrayEquals(byteArrayOf(1, 2, 3), volume.fileBytes("/ISO/win.img"))
        assertTrue(volume.paths().none { it.contains(".import-") || it.contains(".bak-") })
    }

    @Test
    fun importDirectoryNormalizesAndRejectsTraversal() {
        assertEquals("/Images", VentoyIsoLibrary.normalizeDirectory("Images"))
        assertEquals("/A/B", VentoyIsoLibrary.normalizeDirectory("/A//B/"))
        assertThrows(IllegalArgumentException::class.java) {
            VentoyIsoLibrary.normalizeDirectory("../etc")
        }
    }

    @Test
    fun chosenImportDirectoryIsUsed() {
        val volume = InMemoryUsbVolume()
        volume.mkdir("/Images")
        val operation = DiskOperation("import")
        val outcome =
            VentoyIsoLibrary.import(
                volume = volume,
                input = ByteArrayInputStream(byteArrayOf(7)),
                rawName = "win.img",
                operation = operation,
                overwrite = false,
                importDirectory = "/Images",
            )
        assertEquals("/Images/win.img", outcome.targetPath)
        assertArrayEquals(byteArrayOf(7), volume.fileBytes("/Images/win.img"))
    }

    @Test
    fun rootImportUsesSingleSlash() {
        val volume = InMemoryUsbVolume()
        val outcome =
            VentoyIsoLibrary.import(
                volume = volume,
                input = ByteArrayInputStream(byteArrayOf(3)),
                rawName = "win.img",
                operation = DiskOperation("import"),
                overwrite = false,
                importDirectory = "/",
            )
        assertEquals("/win.img", outcome.targetPath)
        assertArrayEquals(byteArrayOf(3), volume.fileBytes("/win.img"))
    }

    @Test
    fun importCannotOverwriteVentoyConfig() {
        val volume = InMemoryUsbVolume(mapOf("/ventoy/ventoy.json" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) {
            VentoyIsoLibrary.import(
                volume = volume,
                input = ByteArrayInputStream(byteArrayOf(2)),
                rawName = "ventoy.json",
                operation = DiskOperation("import"),
                overwrite = true,
                importDirectory = "/ventoy",
            )
        }
        assertArrayEquals(byteArrayOf(1), volume.fileBytes("/ventoy/ventoy.json"))
    }

    @Test
    fun cancelledImportPreservesPreviousFileAndRemovesTemp() {
        val volume = InMemoryUsbVolume(mapOf("/ISO/win.img" to byteArrayOf(9, 9)))
        var active = true
        val stream =
            object : InputStream() {
                private var sent = false
                override fun read(): Int = throw AssertionError("single-byte read not expected")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent) return -1
                    sent = true
                    b[off] = 5
                    active = false // cancel after this chunk
                    return 1
                }
            }
        val operation = DiskOperation("import", active = { active })
        assertThrows(CancellationException::class.java) {
            VentoyIsoLibrary.import(volume, stream, "win.img", operation, overwrite = true)
        }
        assertArrayEquals(byteArrayOf(9, 9), volume.fileBytes("/ISO/win.img"))
        assertTrue(volume.paths().none { it.contains(".import-") || it.contains(".bak-") })
    }

    @Test
    fun streamFailureBeforeRenamePreservesPreviousFile() {
        val volume = InMemoryUsbVolume(mapOf("/ISO/win.img" to byteArrayOf(9, 9)))
        val failing =
            object : InputStream() {
                private var reads = 0
                override fun read(): Int = throw IOException("boom")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (reads++ == 0) {
                        b[off] = 1
                        return 1
                    }
                    throw IOException("boom")
                }
            }
        assertThrows(IOException::class.java) {
            VentoyIsoLibrary.import(
                volume,
                failing,
                "win.img",
                DiskOperation("import"),
                overwrite = true,
            )
        }
        assertArrayEquals(byteArrayOf(9, 9), volume.fileBytes("/ISO/win.img"))
        assertTrue(volume.paths().none { it.contains(".import-") || it.contains(".bak-") })
    }

    @Test
    fun providerSizeMismatchAbortsWithoutReplacing() {
        val volume = InMemoryUsbVolume(mapOf("/ISO/win.img" to byteArrayOf(9, 9)))
        assertThrows(ImageSizeMismatchException::class.java) {
            VentoyIsoLibrary.import(
                volume,
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                "win.img",
                DiskOperation("import"),
                overwrite = true,
                expectedSize = 10,
            )
        }
        assertArrayEquals(byteArrayOf(9, 9), volume.fileBytes("/ISO/win.img"))
    }

    @Test
    fun capacityCeilingRejectsBeforeWriting() {
        val volume = InMemoryUsbVolume(mapOf("/ISO/win.img" to byteArrayOf(9, 9)))
        val operation = DiskOperation("import")
        assertThrows(ImageTooLargeException::class.java) {
            VentoyIsoLibrary.import(
                volume,
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                "win.img",
                operation,
                overwrite = true,
                maxBytes = 3,
            )
        }
        assertArrayEquals(byteArrayOf(9, 9), volume.fileBytes("/ISO/win.img"))
        assertTrue(operation.mutationAttempted)
    }

    @Test
    fun emptyFileIsRefused() {
        val volume = InMemoryUsbVolume()
        assertThrows(ImageEmptyException::class.java) {
            VentoyIsoLibrary.import(
                volume,
                ByteArrayInputStream(ByteArray(0)),
                "empty.xml",
                DiskOperation("import"),
                overwrite = false,
            )
        }
        assertFalse(volume.paths().contains("/ISO/empty.xml"))
    }

    @Test
    fun templateImportCreatesVentoyDirectoryAndKeepsBytes() {
        val volume = InMemoryUsbVolume()
        val xml = "<unattend/>".toByteArray()
        val outcome = VentoyIsoLibrary.import(
            volume = volume,
            input = ByteArrayInputStream(xml),
            rawName = "my.xml",
            operation = DiskOperation("import"),
            overwrite = false,
            importDirectory = "/ventoy",
        )
        assertEquals("/ventoy/my.xml", outcome.targetPath)
        assertArrayEquals(xml, volume.fileBytes("/ventoy/my.xml"))
    }

    @Test
    fun namesCannotEscapeTheDestinationDirectory() {
        // Only the basename is used; no segment can reach outside the destination.
        assertEquals("passwd", VentoyIsoLibrary.requireImportName("../etc/passwd"))
        assertEquals("archive.tar.gz", VentoyIsoLibrary.requireImportName("archive.tar.gz"))
        assertThrows(InvalidImageNameException::class.java) {
            VentoyIsoLibrary.requireImportName("..")
        }
        assertThrows(InvalidImageNameException::class.java) {
            VentoyIsoLibrary.requireImportName("")
        }
    }
}
