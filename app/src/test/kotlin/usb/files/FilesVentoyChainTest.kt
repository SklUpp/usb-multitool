package usb.files

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.operation.DiskOperation
import usb.testutil.InMemoryUsbVolume
import usb.ventoy.VentoyIsoLibrary

/**
 * JVM integration chains over the existing volume seam. InMemoryUsbVolume models UsbFsVolume
 * operations only; it is not a block-device or filesystem-engine substitute.
 */
class FilesVentoyChainTest {
    @Test
    fun filesUploadReadRenameDeleteChainSyncsEachDurableMutation() {
        val volume = InMemoryUsbVolume()
        val payload = byteArrayOf(4, 8, 15, 16, 23, 42)

        FilesOperations.upload(
            ByteArrayInputStream(payload),
            "/ISO/source.img",
            volume,
            DiskOperation("files-upload"),
            chunkBytes = 2,
        )
        assertArrayEquals(payload, volume.read("/ISO/source.img", length = payload.size))

        FilesOperations.rename(volume, "/ISO/source.img", "/ISO/renamed.img")
        assertNull(volume.statOrNull("/ISO/source.img"))
        assertArrayEquals(payload, volume.read("/ISO/renamed.img", length = payload.size))

        FilesOperations.delete(volume, "/ISO/renamed.img")
        assertNull(volume.statOrNull("/ISO/renamed.img"))
        assertEquals(3, volume.syncCount)
    }

    @Test
    fun ventoyImportThenFilesRenameReadDeleteChainKeepsImportedBytes() {
        val volume = InMemoryUsbVolume()
        val payload = byteArrayOf(1, 3, 3, 7)
        val imported =
            VentoyIsoLibrary.import(
                volume = volume,
                input = ByteArrayInputStream(payload),
                rawName = "linux.iso",
                operation = DiskOperation("ventoy-import"),
                overwrite = false,
                chunkBytes = 2,
            )

        assertEquals("/ISO/linux.iso", imported.targetPath)
        FilesOperations.rename(volume, imported.targetPath, "/ISO/renamed-linux.iso")
        assertArrayEquals(
            payload,
            volume.read("/ISO/renamed-linux.iso", length = payload.size),
        )
        FilesOperations.delete(volume, "/ISO/renamed-linux.iso")

        assertNull(volume.statOrNull(imported.targetPath))
        assertNull(volume.statOrNull("/ISO/renamed-linux.iso"))
        // Import syncs staged bytes and commit; Files rename/delete each sync once.
        assertEquals(4, volume.syncCount)
    }

    @Test
    fun cancelledFilesUploadPreservesCommittedPeerAndRemovesPartialTarget() {
        val kept = byteArrayOf(9, 9, 9)
        val volume = InMemoryUsbVolume(mapOf("/ISO/kept.iso" to kept))
        var active = true
        val input =
            object : InputStream() {
                private var sent = false

                override fun read(): Int = error("single-byte read not expected")

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (sent) return -1
                    sent = true
                    buffer[offset] = 5
                    active = false
                    return 1
                }
            }

        assertThrows(CancellationException::class.java) {
            FilesOperations.upload(
                input,
                "/ISO/interrupted.iso",
                volume,
                DiskOperation("files-cancel", active = { active }),
                chunkBytes = 4,
            )
        }

        assertArrayEquals(kept, volume.read("/ISO/kept.iso", length = kept.size))
        assertNull(volume.statOrNull("/ISO/interrupted.iso"))
    }

    @Test
    fun failedVentoyReplacementAfterBackupMoveRestoresPreviousBytesAndCleansStaging() {
        val original = byteArrayOf(7, 7)
        val backing = InMemoryUsbVolume(mapOf("/ISO/windows.iso" to original))
        var renameCount = 0
        val volume =
            object : usb.fs.UsbFsVolume by backing {
                override fun rename(from: String, to: String) {
                    renameCount++
                    if (renameCount == 2) throw IOException("install rename failed")
                    backing.rename(from, to)
                }
            }
        val beforeFailure = backing.syncCount
        val operation = DiskOperation("ventoy-replace")

        assertThrows(IOException::class.java) {
            VentoyIsoLibrary.import(
                volume = volume,
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                rawName = "windows.iso",
                operation = operation,
                overwrite = true,
                chunkBytes = 2,
            )
        }

        assertEquals(3, renameCount)
        assertArrayEquals(original, backing.read("/ISO/windows.iso", length = original.size))
        assertTrue(backing.paths().none { it.contains(".xi-") || it.contains(".xb-") || it.contains(".import-") })
        assertTrue(operation.mutationAttempted)
        assertFalse(operation.commitReached)
        assertTrue(backing.syncCount > beforeFailure)
    }
}
