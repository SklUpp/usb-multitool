package usb.image

import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation

class RawImageStagingTransactionTest {
    private fun root() = Files.createTempDirectory("raw-stage-tx").toFile().also { it.deleteOnExit() }

    @Test
    fun copiesChunksAndReportsProgress() {
        val root = root()
        val bytes = "abcdefghij".encodeToByteArray()
        val volume = FakeVolume(bytes)
        val operation = DiskOperation()
        val result = stageRawImageChunks(root, volume, "/image", bytes.size.toLong(), "x.img", operation, 3)
        assertArrayEquals(bytes, result.readBytes())
        assertEquals(bytes.size.toLong(), operation.bytesDone)
    }

    @Test
    fun stagesOneMiBChunksAndReportsFinalProgress() {
        val root = root()
        val chunk = 1024 * 1024
        val bytes = ByteArray(chunk + 100) { (it % 251).toByte() }
        val volume = FakeVolume(bytes)
        val operation = DiskOperation()

        val result =
            stageRawImageChunks(root, volume, "/image", bytes.size.toLong(), "x.img", operation, chunk)

        assertArrayEquals(bytes, result.readBytes())
        assertEquals(listOf(chunk, 100), volume.readSizes)
        assertEquals(bytes.size.toLong(), operation.bytesDone)
    }

    @Test
    fun cancelAfterFirstChunkRemovesPartialFile() {
        val root = root()
        val bytes = ByteArray(10) { 1 }
        val volume = FakeVolume(bytes)
        var cancelled = false
        volume.readHook = { cancelled = true }
        val operation = DiskOperation(active = { !cancelled })

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            stageRawImageChunks(root, volume, "/image", bytes.size.toLong(), "x.img", operation, 3)
        }
        assertEquals(1, volume.readSizes.size)
        assertTrue(ImageStaging.listStagingFiles(root).isEmpty())
    }

    @Test
    fun shortReadFailsAndRemovesOwnedPartialFile() {
        val root = root()
        val operation = DiskOperation()
        assertThrows(IllegalStateException::class.java) {
            stageRawImageChunks(root, FakeVolume(byteArrayOf(1, 2)), "/image", 3, "x.img", operation, 3)
        }
        assertTrue(ImageStaging.listStagingFiles(root).isEmpty())
    }

    @Test
    fun inactiveOperationCancelsAndCleansPartialFile() {
        val root = root()
        val operation = DiskOperation(active = { false })
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            stageRawImageChunks(root, FakeVolume(byteArrayOf(1)), "/image", 1, "x.img", operation)
        }
        assertTrue(ImageStaging.listStagingFiles(root).isEmpty())
    }

    @Test
    fun cleanupFailureDoesNotReplacePrimaryFailure() {
        val root = root()
        val bytes = ByteArray(10) { 1 }
        val volume = FakeVolume(bytes)
        var staged: File? = null
        volume.readHook = {
            val file = ImageStaging.listStagingFiles(root).single()
            file.delete()
            file.mkdir()
            File(file, "child").writeText("kept")
            staged = file
            throw IllegalStateException("primary staging failure")
        }
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                stageRawImageChunks(root, volume, "/image", bytes.size.toLong(), "x.img", DiskOperation(), 3)
            }
            assertEquals("primary staging failure", error.message)
            assertEquals(1, error.suppressed.size)
            assertTrue(error.suppressed[0] is java.io.IOException)
        } finally {
            staged?.let { dir ->
                File(dir, "child").delete()
                dir.delete()
            }
            root.delete()
        }
    }

    private class FakeVolume(private val bytes: ByteArray) : UsbFsVolume {
        val readSizes = mutableListOf<Int>()
        var readHook: (() -> Unit)? = null

        override fun stat(path: String) = UsbFsDirEntry("image", false, bytes.size.toLong())
        override fun read(path: String, offset: Long, length: Int): ByteArray {
            val from = offset.toInt()
            val data = bytes.copyOfRange(from, minOf(bytes.size, from + length))
            readSizes += data.size
            readHook?.invoke()
            return data
        }
        override fun sync() = Unit
        override fun close() = Unit
        override fun list(path: String) = emptyList<UsbFsDirEntry>()
        override fun statOrNull(path: String) = null
        override fun mkdir(path: String) = Unit
        override fun delete(path: String) = Unit
        override fun rename(from: String, to: String) = Unit
        override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long, exclusiveCreate: Boolean) = Unit
    }
}
