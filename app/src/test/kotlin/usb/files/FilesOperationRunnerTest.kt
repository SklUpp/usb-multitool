package usb.files

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertSame
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk
import usb.device.UsbExclusiveClaim
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.service.OperationExecutor

class FilesOperationRunnerTest {
    @Test
    fun failedBodyClosesAcquiredMountBeforeNextOperationEntersSession() = runTest {
        val pin = AttachmentPin(1L, "1:stick")
        val events = mutableListOf<String>()
        lateinit var session: FilesVolumeSession
        session = FilesVolumeSession(
            currentAttachment = { pin },
            openMounted = { partitionId, writable, _, lease ->
                val disk = UsbDisk(FakeBotSession(), BlockGeometry(512, 64UL), lease = lease)
                val volume = object : UsbFsVolume {
                    override fun list(path: String) = emptyList<UsbFsDirEntry>()
                    override fun stat(path: String) = error("unused")
                    override fun statOrNull(path: String) = null
                    override fun mkdir(path: String) = Unit
                    override fun delete(path: String) = Unit
                    override fun rename(from: String, to: String) = Unit
                    override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
                    override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long, exclusiveCreate: Boolean) = Unit
                    override fun sync() = Unit
                    override fun close() { events += "volume-close" }
                }
                MountedFilesVolume(partitionId, disk, volume, writable).also {
                    it.bindLease(lease)
                }
            },
            claim = UsbExclusiveClaim(),
        )
        val executor = OperationExecutor(this)
        val runner = FilesOperationRunner(session)
        val first = runner.create("first") { _ ->
            session.volumeForLocked(pin, "1")
            events += "first-body"
            throw IOException("body failed")
        }
        val second = runner.create("second") { _ ->
            events += "second-body"
            session.volumeForLocked(pin, "1")
        }

        val executorFirst = executor.submit(first.operation, usb.service.OperationTokenGenerator.next())
        val executorSecond = executor.submit(second.operation, usb.service.OperationTokenGenerator.next())
        assertEquals(OperationState.Failed::class, executorFirst.await()::class)
        assertEquals(OperationState.Completed, executorSecond.await())
        assertTrue(events.indexOf("volume-close") < events.indexOf("second-body"))
        session.close()
    }

    @Test
    fun bodyFailureAndCloseFailureKeepPrimaryAndBlockNextMount() = runTest {
        val pin = AttachmentPin(1L, "1:stick")
        val bodyFailure = IOException("body failed")
        val closeFailure = IOException("close unconfirmed")
        val session = FilesVolumeSession(
            currentAttachment = { pin },
            openMounted = { partitionId, writable, _, lease ->
                val disk = UsbDisk(FakeBotSession(), BlockGeometry(512, 64UL), lease = lease)
                val volume = object : UsbFsVolume {
                    override fun list(path: String) = emptyList<UsbFsDirEntry>()
                    override fun stat(path: String) = error("unused")
                    override fun statOrNull(path: String) = null
                    override fun mkdir(path: String) = Unit
                    override fun delete(path: String) = Unit
                    override fun rename(from: String, to: String) = Unit
                    override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
                    override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long, exclusiveCreate: Boolean) = Unit
                    override fun sync() = Unit
                    override fun close() = throw closeFailure
                }
                MountedFilesVolume(partitionId, disk, volume, writable).also { it.bindLease(lease) }
            },
            claim = UsbExclusiveClaim(),
        )
        val executor = OperationExecutor(this)
        val runner = FilesOperationRunner(session)
        val first = runner.create("first") {
            session.volumeForLocked(pin, "1")
            throw bodyFailure
        }
        val second = runner.create("second") {
            session.volumeForLocked(pin, "1")
        }

        val firstResult = executor.submit(first.operation, usb.service.OperationTokenGenerator.next()).await()
        val secondResult = executor.submit(second.operation, usb.service.OperationTokenGenerator.next()).await()

        assertTrue(firstResult is OperationState.Failed)
        val firstFailure = (firstResult as OperationState.Failed).cause
        assertTrue(firstFailure is usb.device.DiskException.IoFailure)
        val normalizedPrimary = requireNotNull(firstFailure.cause)
        assertSame(bodyFailure, normalizedPrimary)
        assertSame(closeFailure, normalizedPrimary.suppressed.single())
        assertSame(closeFailure, first.closeFailure)
        assertTrue(secondResult is OperationState.Failed)
        assertTrue(second.operation.failureCause!!.message!!.contains("refusing remount"))
    }

    @Test
    fun readOnlyCancellationKeepsCancelledTerminalAndExposesCloseFailure() = runTest {
        val pin = AttachmentPin(1L, "1:stick")
        val closeFailure = IOException("close unconfirmed")
        val session = FilesVolumeSession(
            currentAttachment = { pin },
            openMounted = { partitionId, writable, _, lease ->
                val disk = UsbDisk(FakeBotSession(), BlockGeometry(512, 64UL), lease = lease)
                val volume = object : UsbFsVolume {
                    override fun list(path: String) = emptyList<UsbFsDirEntry>()
                    override fun stat(path: String) = error("unused")
                    override fun statOrNull(path: String) = null
                    override fun mkdir(path: String) = Unit
                    override fun delete(path: String) = Unit
                    override fun rename(from: String, to: String) = Unit
                    override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
                    override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long, exclusiveCreate: Boolean) = Unit
                    override fun sync() = Unit
                    override fun close() { throw closeFailure }
                }
                MountedFilesVolume(partitionId, disk, volume, writable).also { it.bindLease(lease) }
            },
            claim = UsbExclusiveClaim(),
        )
        val executor = OperationExecutor(this)
        val submission = FilesOperationRunner(session).create("cancel") {
            session.volumeForLocked(pin, "1")
            throw CancellationException("cancelled")
        }
        val handle = executor.submit(submission.operation, usb.service.OperationTokenGenerator.next())

        assertEquals(OperationState.Cancelled, handle.await())
        assertSame(closeFailure, submission.closeFailure)
    }
}
