package usb.files

import org.junit.Test
import usb.fs.UsbFsVolume
import usb.testutil.RecordingDisk

class MountedFilesVolumeTest {
    @Test fun closesVolumeAndDiskWithoutThrowing() {
        val disk = RecordingDisk()
        val volume = object : UsbFsVolume {
            override fun list(path: String) = emptyList<usb.fs.UsbFsDirEntry>()
            override fun stat(path: String) = error("unused")
            override fun statOrNull(path: String) = null
            override fun mkdir(path: String) = Unit
            override fun delete(path: String) = Unit
            override fun rename(from: String, to: String) = Unit
            override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
            override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long) = Unit
            override fun sync() = Unit
            override fun close() = Unit
        }
        MountedFilesVolume("1", disk, volume, false).close()
    }
}
