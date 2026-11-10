package usb.files

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk
import usb.fs.CloseAfterSuccessException
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.operation.OperationState

/**
 * Session owns one mounted volume + coarse lock. Behavioral contracts:
 * close failure refuses remount and fail-closed gate; volume closes before disk;
 * writable mutation + close fail → Partial; body fail keeps primary.
 */
class FilesVolumeSessionTest {
    @Test
    fun closeReturnsNullWhenNothingOpen() {
        val session = FilesVolumeSession { _, _ -> error("must not open") }
        assertNull(session.close())
    }

    @Test
    fun closeOrderIsVolumeThenDisk() {
        val events = mutableListOf<String>()
        val session =
            FilesVolumeSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
            }
        session.withLock { session.volumeForLocked("1") }
        assertNull(session.close())
        assertEquals(listOf("volume", "disk"), events)
    }

    @Test
    fun closeFailureReturnedAndGateRefusesContinuation() {
        val closeErr = IOException("volume sync unconfirmed")
        val session =
            FilesVolumeSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        session.withLock { session.volumeForLocked("1") }
        val err = session.close()
        assertSame(closeErr, err)

        var continued = false
        try {
            requireFilesVolumeClosed(err)
            continued = true
            fail("gate must throw")
        } catch (t: Throwable) {
            assertSame(closeErr, t)
        }
        assertEquals(false, continued)
    }

    @Test
    fun remountRefusedAfterUnconfirmedClose() {
        val closeErr = IOException("umount failed")
        var opens = 0
        val session =
            FilesVolumeSession { partitionId, writable ->
                opens++
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        session.withLock {
            session.volumeForLocked("1", writable = false)
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    // RO → RW forces close of current before reopen.
                    session.volumeForLocked("1", writable = true)
                }
            assertTrue(thrown.message!!.contains("refusing remount"))
            assertSame(closeErr, thrown.cause)
        }
        assertEquals("second open must not run after refuse", 1, opens)
        // Slot cleared; explicit close is a no-op.
        assertNull(session.close())
    }

    @Test
    fun writableSuccessPlusCloseFailMarksPartial() {
        val closeErr = IOException("EXT close/sync failed")
        val session =
            FilesVolumeSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        val op = DiskOperation(name = "mkdir")
        op.state = OperationState.Running
        var bodyRan = false
        session.withLock {
            try {
                session.withWritableVolume("1", op) {
                    bodyRan = true
                }
                fail("expected CloseAfterSuccessException")
            } catch (e: CloseAfterSuccessException) {
                assertSame(closeErr, e.cause)
                assertEquals(OperationState.Partial, op.state)
                assertSame(e, op.failureCause)
                assertTrue(e.message!!.contains("Change applied"))
            }
        }
        assertTrue(bodyRan)
        assertTrue(op.state != OperationState.Completed)
    }

    @Test
    fun writableBodyFailureKeepsPrimaryAndSuppressesClose() {
        val closeErr = IOException("close failed")
        val session =
            FilesVolumeSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        val op = DiskOperation(name = "upload")
        op.state = OperationState.Running
        val primary = IllegalStateException("write failed")
        session.withLock {
            try {
                session.withWritableVolume("1", op) {
                    throw primary
                }
                fail("expected primary")
            } catch (e: IllegalStateException) {
                assertSame(primary, e)
                assertEquals(1, e.suppressed.size)
                assertSame(closeErr, e.suppressed[0])
            }
        }
        // Close path must not flip Running → Partial when body already failed.
        assertEquals(OperationState.Running, op.state)
    }

    @Test
    fun diskCloseFailureSurfacesWhenVolumeCloseOk() {
        val diskErr = IOException("disk close failed")
        val session =
            FilesVolumeSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = null, diskClose = diskErr)
            }
        session.withLock { session.volumeForLocked("2") }
        val err = session.close()
        assertSame(diskErr, err)
    }

    @Test
    fun volumeCloseFailureSuppressesDiskCloseFailure() {
        val volumeErr = IOException("volume close failed")
        val diskErr = IOException("disk close failed")
        val events = mutableListOf<String>()
        val session =
            FilesVolumeSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = volumeErr, diskClose = diskErr)
            }
        session.withLock { session.volumeForLocked("1") }
        val err = session.close()
        assertSame(volumeErr, err)
        assertEquals(1, volumeErr.suppressed.size)
        assertSame(diskErr, volumeErr.suppressed[0])
        assertEquals(listOf("volume", "disk"), events)
    }

    @Test
    fun cachedVolumeReusedForSamePartitionAndMode() {
        var opens = 0
        val session =
            FilesVolumeSession { partitionId, writable ->
                opens++
                mounted(partitionId, writable, volumeClose = null, diskClose = null)
            }
        session.withLock {
            val a = session.volumeForLocked("1", writable = false)
            val b = session.volumeForLocked("1", writable = false)
            assertSame(a, b)
        }
        assertEquals(1, opens)
    }

    private fun mounted(
        partitionId: String,
        writable: Boolean,
        events: MutableList<String> = mutableListOf(),
        volumeClose: Throwable?,
        diskClose: Throwable?,
    ): MountedFilesVolume {
        val bot =
            object : FakeBotSession() {
                override fun close() {
                    events += "disk"
                    if (diskClose != null) throw diskClose
                    super.close()
                }
            }
        val disk = UsbDisk(session = bot, geometry = BlockGeometry(512, 64UL))
        val volume =
            object : UsbFsVolume {
                override fun list(path: String) = emptyList<UsbFsDirEntry>()
                override fun stat(path: String) = error("unused")
                override fun statOrNull(path: String) = null
                override fun mkdir(path: String) = Unit
                override fun delete(path: String) = Unit
                override fun rename(from: String, to: String) = Unit
                override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
                override fun write(
                    path: String,
                    data: ByteArray,
                    offset: Long,
                    create: Boolean,
                    plannedTotalSize: Long,
                ) = Unit
                override fun sync() = Unit
                override fun close() {
                    events += "volume"
                    if (volumeClose != null) throw volumeClose
                }
            }
        return MountedFilesVolume(partitionId, disk, volume, writable)
    }
}
