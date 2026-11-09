package usb.files

import org.junit.Test
import org.junit.Assert.assertEquals
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume

class FilesOperationsTest {
    @Test fun listDeleteRenameSyncDelegateInOrder() {
        val volume = FakeVolume()
        assertEquals(listOf("a"), FilesOperations.list(volume, "/").map { it.name })
        FilesOperations.delete(volume, "/a")
        FilesOperations.rename(volume, "/b", "/c")
        assertEquals(listOf("delete:/a", "sync", "rename:/b:/c", "sync"), volume.events)
    }

    private class FakeVolume : UsbFsVolume {
        val events = mutableListOf<String>()
        override fun list(path: String) = listOf(UsbFsDirEntry("a", false, 1))
        override fun stat(path: String) = UsbFsDirEntry(path, false, 0)
        override fun statOrNull(path: String) = null
        override fun mkdir(path: String) = Unit
        override fun delete(path: String) { events += "delete:$path" }
        override fun rename(from: String, to: String) { events += "rename:$from:$to" }
        override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
        override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long) = Unit
        override fun sync() { events += "sync" }
        override fun close() = Unit
    }
}
