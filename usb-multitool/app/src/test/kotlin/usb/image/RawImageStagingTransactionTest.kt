package usb.image

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.service.DiskOperation

class RawImageStagingTransactionTest {
    private fun root() = Files.createTempDirectory("raw-stage-tx").toFile().also { it.deleteOnExit() }

    @Test
    fun copiesChunksReportsProgressAndSyncsDurableOutput() {
        val root = root()
        val bytes = "abcdefghij".encodeToByteArray()
        val volume = FakeVolume(bytes)
        val operation = DiskOperation()
        val result = stageRawImageChunks(root, volume, "/image", bytes.size.toLong(), "x.img", operation, 3)
        assertContentEquals(bytes, result.readBytes())
        assertEquals(bytes.size.toLong(), operation.bytesDone)
    }

    @Test
    fun shortReadFailsAndRemovesOwnedPartialFile() {
        val root = root()
        val operation = DiskOperation()
        assertFailsWith<IllegalStateException> {
            stageRawImageChunks(root, FakeVolume(byteArrayOf(1, 2)), "/image", 3, "x.img", operation, 3)
        }
        assertTrue(ImageStaging.listStagingFiles(root).isEmpty())
    }

    @Test
    fun inactiveOperationCancelsAndCleansPartialFile() {
        val root = root()
        val operation = DiskOperation(active = { false })
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            stageRawImageChunks(root, FakeVolume(byteArrayOf(1)), "/image", 1, "x.img", operation)
        }
        assertTrue(ImageStaging.listStagingFiles(root).isEmpty())
    }

    private class FakeVolume(private val bytes: ByteArray) : UsbFsVolume {
        override fun stat(path: String) = UsbFsDirEntry("image", false, bytes.size.toLong())
        override fun read(path: String, offset: Long, length: Int) =
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
        override fun sync() = Unit
        override fun close() = Unit
        override fun list(path: String) = emptyList<UsbFsDirEntry>()
        override fun statOrNull(path: String) = null
        override fun mkdir(path: String) = Unit
        override fun delete(path: String) = Unit
        override fun rename(from: String, to: String) = Unit
        override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long) = Unit
    }
}
