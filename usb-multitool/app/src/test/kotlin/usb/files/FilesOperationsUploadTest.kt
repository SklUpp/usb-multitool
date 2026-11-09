package usb.files

import java.io.ByteArrayInputStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.service.DiskOperation

class FilesOperationsUploadTest {
    @Test fun firstWriteFailureStillCleansCreatedTarget() {
        val v = FakeVolume(writeFailure = IllegalStateException("write"))
        val primary = kotlin.runCatching {
            FilesOperations.upload(ByteArrayInputStream(byteArrayOf(1)), "/x", v, DiskOperation("u"), 4)
        }.exceptionOrNull()!!
        assertEquals("write", primary.message)
        assertEquals(1, v.deleteCount)
    }

    @Test fun emptyWriteFailureStillCleansCreatedTarget() {
        val v = FakeVolume(writeFailure = IllegalStateException("empty"))
        val primary = kotlin.runCatching {
            FilesOperations.upload(ByteArrayInputStream(ByteArray(0)), "/x", v, DiskOperation("u"), 4)
        }.exceptionOrNull()!!
        assertEquals("empty", primary.message)
        assertEquals(1, v.deleteCount)
    }

    @Test fun cleanupStatFailureKeepsPrimaryAndReportsWarning() {
        val cleanup = IllegalStateException("stat")
        val primary = IllegalStateException("write")
        val v = FakeVolume(writeFailure = primary, statFailureAfterWrite = cleanup)
        var warning: Throwable? = null
        val thrown = kotlin.runCatching {
            FilesOperations.upload(ByteArrayInputStream(byteArrayOf(1)), "/x", v, DiskOperation("u"), 4) { warning = it }
        }.exceptionOrNull()!!
        assertSame(primary, thrown)
        assertSame(cleanup, warning)
        assertSame(cleanup, thrown.suppressed.single())
    }

    private class FakeVolume(
        private val writeFailure: Throwable? = null,
        private val statFailureAfterWrite: Throwable? = null,
    ) : UsbFsVolume {
        var deleteCount = 0
        var writeAttempted = false
        override fun list(path: String) = emptyList<UsbFsDirEntry>()
        override fun stat(path: String) = UsbFsDirEntry(path, false, 1)
        override fun statOrNull(path: String): UsbFsDirEntry? {
            if (!writeAttempted) return null
            statFailureAfterWrite?.let { throw it }
            return UsbFsDirEntry(path, false, 1)
        }
        override fun mkdir(path: String) = Unit
        override fun delete(path: String) { deleteCount++ }
        override fun rename(from: String, to: String) = Unit
        override fun read(path: String, offset: Long, length: Int) = byteArrayOf(1)
        override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long) {
            writeAttempted = true
            writeFailure?.let { throw it }
        }
        override fun sync() = Unit
        override fun close() = Unit
    }
}
