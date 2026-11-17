package usb.files

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import usb.fs.UsbFsVolume
import usb.testutil.RecordingDisk

class MountedFilesVolumeTest {
    private class EventDisk(
        val events: MutableList<String>,
        session: usb.device.FakeBotSession = usb.device.FakeBotSession(),
    ) : RecordingDisk(session = session) {
        override fun sync() {
            events += "disk-sync"
            try {
                super.sync()
            } finally {
                events += "disk-sync-done"
            }
        }
    }

    private open class EventCloseSession(val events: MutableList<String>) : usb.device.FakeBotSession() {
        override fun close() {
            events += "disk"
            super.close()
        }
    }

    private class FailingCloseSession(events: MutableList<String>, val failure: Throwable) :
        EventCloseSession(events) {
        override fun close() {
            super.close()
            throw failure
        }
    }

    private fun volume(
        events: MutableList<String>,
        volumeSync: Throwable? = null,
        volumeClose: Throwable? = null,
        diskSync: Throwable? = null,
        diskClose: Throwable? = null,
    ): MountedFilesVolume {
        val session =
            if (diskClose == null) EventCloseSession(events) else FailingCloseSession(events, diskClose)
        val disk = EventDisk(events, session).apply {
            if (diskSync != null) syncHook = { throw diskSync }
        }
        val fsVolume = object : UsbFsVolume {
            override fun list(path: String) = emptyList<usb.fs.UsbFsDirEntry>()
            override fun stat(path: String) = error("unused")
            override fun statOrNull(path: String) = null
            override fun mkdir(path: String) = Unit
            override fun delete(path: String) = Unit
            override fun rename(from: String, to: String) = Unit
            override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
            override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long, exclusiveCreate: Boolean) = Unit
            override fun sync() {
                events += "volume-sync"
                if (volumeSync != null) throw volumeSync
            }
            override fun close() {
                events += "volume"
                if (volumeClose != null) throw volumeClose
            }
        }
        return MountedFilesVolume("1", disk, fsVolume, false)
    }

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
            override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long, exclusiveCreate: Boolean) = Unit
            override fun sync() = Unit
            override fun close() = Unit
        }
        MountedFilesVolume("1", disk, volume, false).close()
    }

    @Test fun ejectSyncsThenClosesVolumeThenDisk() {
        val events = mutableListOf<String>()
        volume(events).eject()
        assertEquals(listOf("volume-sync", "volume", "disk-sync", "disk-sync-done", "disk"), events.takeLast(5))
    }

    @Test fun ejectRunsCleanupAfterVolumeSyncFailureAndSuppressesRest() {
        val events = mutableListOf<String>()
        val primary = IOException("volume sync failed")
        val closeErr = IOException("volume close failed")
        val diskErr = IOException("disk close failed")
        val thrown = assertThrows(IOException::class.java) {
            volume(events, volumeSync = primary, volumeClose = closeErr, diskClose = diskErr).eject()
        }
        assertSame(primary, thrown)
        assertEquals(listOf("volume-sync", "volume", "disk-sync", "disk-sync-done", "disk"), events)
        assertEquals(2, thrown.suppressed.size)
        assertSame(closeErr, thrown.suppressed[0])
        assertSame(diskErr, thrown.suppressed[1])
    }

    @Test fun ejectUnsupportedDiskSyncFailsClosed() {
        val events = mutableListOf<String>()
        val unsupported = usb.device.DiskException.UnsupportedCommand("SYNCHRONIZE CACHE(10) unsupported")
        val thrown = assertThrows(usb.device.DiskException.UnsupportedCommand::class.java) {
            volume(events, diskSync = unsupported).eject()
        }
        assertSame(unsupported, thrown)
        assertEquals(listOf("volume-sync", "volume", "disk-sync", "disk-sync-done", "disk"), events)
    }
}
