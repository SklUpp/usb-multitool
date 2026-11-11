package usb.files

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import usb.fs.FilesystemSpace
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsUsageVolume
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation

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

    @Test fun uploadWritesChunkSizedBlocksAndPreservesContent() {
        val chunk = 1024 * 1024
        val payload = ByteArray(2 * chunk + 5) { (it % 251).toByte() }
        val volume = RecordingVolume()
        val operation = DiskOperation("upload-chunks")

        FilesOperations.upload(
            java.io.ByteArrayInputStream(payload),
            "/x",
            volume,
            operation,
            chunk,
        )

        assertEquals(listOf(0L, chunk.toLong(), 2L * chunk), volume.writes.map { it.offset })
        assertEquals(listOf(chunk, chunk, 5), volume.writes.map { it.data.size })
        assertTrue(volume.writes.first().create)
        assertTrue(volume.writes.drop(1).none { it.create })
        assertArrayEquals(payload, volume.content())
        assertEquals(payload.size.toLong(), operation.bytesDone)
        assertEquals(1, volume.syncCount)
    }

    @Test fun uploadShortStreamReadsDoNotShiftOffsets() {
        val payload = ByteArray(40) { it.toByte() }
        val volume = RecordingVolume()
        val stream =
            object : java.io.InputStream() {
                private var pos = 0

                override fun read(): Int = if (pos < payload.size) payload[pos++].toInt() and 0xFF else -1

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= payload.size) return -1
                    val n = minOf(len, 7, payload.size - pos)
                    System.arraycopy(payload, pos, b, off, n)
                    pos += n
                    return n
                }
            }

        FilesOperations.upload(stream, "/x", volume, DiskOperation("upload-short"), 16)

        assertEquals(listOf(0L, 7L, 14L, 21L, 28L, 35L), volume.writes.map { it.offset })
        assertEquals(listOf(7, 7, 7, 7, 7, 5), volume.writes.map { it.data.size })
        assertArrayEquals(payload, volume.content())
    }

    @Test fun uploadExactlyOneChunkDoesNotAppendEmptyWrite() {
        val payload = ByteArray(16) { 3 }
        val volume = RecordingVolume()

        FilesOperations.upload(java.io.ByteArrayInputStream(payload), "/x", volume, DiskOperation("upload-one"), 16)

        assertEquals(1, volume.writes.size)
        assertEquals(16, volume.writes.single().data.size)
        assertArrayEquals(payload, volume.content())
        assertEquals(1, volume.syncCount)
    }

    @Test fun uploadEmptyInputCreatesEmptyFileOnce() {
        val volume = RecordingVolume()

        FilesOperations.upload(java.io.ByteArrayInputStream(ByteArray(0)), "/x", volume, DiskOperation("upload-empty"), 16)

        assertEquals(1, volume.writes.size)
        assertEquals(0, volume.writes.single().data.size)
        assertTrue(volume.writes.single().create)
        assertEquals(1, volume.syncCount)
    }

    @Test fun uploadCancellationAfterFirstChunkDeletesTarget() {
        val volume = RecordingVolume()
        var cancelled = false
        // Hook fires after the first write is recorded; the next loop check cancels.
        volume.writeHook = { cancelled = true }
        val payload = ByteArray(40) { 1 }
        val operation = DiskOperation("upload-cancel", active = { !cancelled })

        val failure =
            runCatching {
                FilesOperations.upload(
                    java.io.ByteArrayInputStream(payload),
                    "/x",
                    volume,
                    operation,
                    16,
                )
            }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(1, volume.writes.size)
        assertEquals(1, volume.deleteCount)
    }

    @Test fun knownSizeLargerThanFreeSpaceRejectsBeforeWrite() {
        val volume = SpaceAwareVolume(freeBytes = 3uL, totalBytes = 10uL)
        val failure =
            runCatching {
                FilesOperations.upload(
                    ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                    "/x",
                    volume,
                    DiskOperation("upload-space"),
                    4,
                    knownSizeBytes = 4L,
                )
            }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(
            FilesOperationPolicy.insufficientSpaceMessage(3uL, 4L),
            failure!!.message,
        )
        assertEquals(0, volume.writeCount)
        assertEquals(1, volume.spaceCalls)
    }

    @Test fun knownSizeFittingFreeSpaceWritesAndPassesPlannedTotal() {
        val volume = SpaceAwareVolume(freeBytes = 10uL, totalBytes = 10uL)
        FilesOperations.upload(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            "/x",
            volume,
            DiskOperation("upload-fit"),
            2,
            knownSizeBytes = 4L,
        )
        assertEquals(2, volume.writeCount)
        assertEquals(listOf(4L, 4L), volume.plannedTotals)
        assertEquals(1, volume.spaceCalls)
    }

    @Test fun unknownSizeSkipsFreeSpaceQuery() {
        val volume = SpaceAwareVolume(freeBytes = 0uL, totalBytes = 0uL)
        FilesOperations.upload(
            ByteArrayInputStream(byteArrayOf(1, 2)),
            "/x",
            volume,
            DiskOperation("upload-unknown"),
            2,
            knownSizeBytes = null,
        )
        assertEquals(1, volume.writeCount)
        assertEquals(0, volume.spaceCalls)
    }

    @Test fun spaceQueryFailureDoesNotBlockUpload() {
        val volume =
            SpaceAwareVolume(freeBytes = 0uL, totalBytes = 0uL, spaceFailure = IOException("space"))
        FilesOperations.upload(
            ByteArrayInputStream(byteArrayOf(9)),
            "/x",
            volume,
            DiskOperation("upload-space-fail"),
            4,
            knownSizeBytes = 1L,
        )
        assertEquals(1, volume.writeCount)
        assertEquals(1, volume.spaceCalls)
    }

    @Test fun volumeWithoutUsageIgnoresKnownSize() {
        val volume = RecordingVolume()
        FilesOperations.upload(
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            "/x",
            volume,
            DiskOperation("upload-no-usage"),
            4,
            knownSizeBytes = 3L,
        )
        assertEquals(1, volume.writes.size)
    }

    @Test fun requireFreeSpaceHelperRejectsOnlyWhenBothKnown() {
        val volume = SpaceAwareVolume(freeBytes = 1uL, totalBytes = 2uL)
        runCatching { FilesOperations.requireFreeSpaceBeforeWrite(volume, 2L) }
            .exceptionOrNull()
            ?.let { assertTrue(it is IOException) }
            ?: fail("expected IOException")
        FilesOperations.requireFreeSpaceBeforeWrite(volume, null)
        FilesOperations.requireFreeSpaceBeforeWrite(volume, 0L)
        FilesOperations.requireFreeSpaceBeforeWrite(RecordingVolume(), 99L)
    }

    private class RecordingVolume : UsbFsVolume {
        data class Write(val offset: Long, val data: ByteArray, val create: Boolean)

        val writes = mutableListOf<Write>()
        var deleteCount = 0
        var syncCount = 0
        var writeHook: (() -> Unit)? = null
        private var written = false

        fun content(): ByteArray {
            val out = ByteArray(writes.sumOf { it.data.size })
            var offset = 0
            for (write in writes) {
                System.arraycopy(write.data, 0, out, offset, write.data.size)
                offset += write.data.size
            }
            return out
        }

        override fun list(path: String) = emptyList<UsbFsDirEntry>()
        override fun stat(path: String) = UsbFsDirEntry(path, false, 0)
        override fun statOrNull(path: String) =
            if (written) UsbFsDirEntry(path, false, 0) else null
        override fun mkdir(path: String) = Unit
        override fun delete(path: String) { deleteCount++ }
        override fun rename(from: String, to: String) = Unit
        override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
        override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long) {
            writes += Write(offset, data.copyOf(), create)
            written = true
            writeHook?.invoke()
        }
        override fun sync() { syncCount++ }
        override fun close() = Unit
    }

    private class SpaceAwareVolume(
        private val freeBytes: ULong,
        private val totalBytes: ULong,
        private val spaceFailure: Throwable? = null,
    ) : UsbFsVolume, UsbFsUsageVolume {
        var writeCount = 0
        var spaceCalls = 0
        val plannedTotals = mutableListOf<Long>()
        private var written = false

        override fun space(): FilesystemSpace {
            spaceCalls++
            spaceFailure?.let { throw it }
            return FilesystemSpace(totalBytes = totalBytes, freeBytes = freeBytes)
        }

        override fun list(path: String) = emptyList<UsbFsDirEntry>()
        override fun stat(path: String) = UsbFsDirEntry(path, false, 0)
        override fun statOrNull(path: String) =
            if (written) UsbFsDirEntry(path, false, 0) else null
        override fun mkdir(path: String) = Unit
        override fun delete(path: String) = Unit
        override fun rename(from: String, to: String) = Unit
        override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
        override fun write(path: String, data: ByteArray, offset: Long, create: Boolean, plannedTotalSize: Long) {
            writeCount++
            plannedTotals += plannedTotalSize
            written = true
        }
        override fun sync() = Unit
        override fun close() = Unit
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
