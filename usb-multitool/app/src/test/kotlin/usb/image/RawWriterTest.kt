package usb.image

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.DiskException
import usb.device.TransferPlan
import usb.diagnostics.ConfirmationMismatchException
import usb.diagnostics.DestructiveConfirmation
import usb.service.DiskOperation
import usb.service.OperationState
import usb.testutil.RecordingDisk

class RawWriterTest {
    private val identity = "RAW-TEST"

    private fun conf(disk: RecordingDisk) =
        DestructiveConfirmation(identity, disk.geometry.capacityBytes)

    @Test fun compressedSourceWritesDecodedBytesAndStopsAtCapacity() {
        val source = ImageSource.fromBytes(gzip(ByteArray(6000) { 7 }))
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true)
            fail("expected ImageTooLargeException")
        } catch (_: ImageTooLargeException) {
        }
        assertEquals(0, disk.bytesWritten)
    }

    @Test fun knownSizeRejectionWritesNoSector() {
        val source = ImageSource.fromBytes(
            ByteArray(1024) { 1 },
            format = ImageFormat.IMG,
            decodedSize = 10_000L,
        )
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true)
            fail("expected ImageTooLargeException")
        } catch (_: ImageTooLargeException) {
        }
        assertEquals(0, disk.bytesWritten)
        assertEquals(emptyList<ULong>(), disk.writes)
    }

    @Test fun rawImageWritesExactSectors() {
        val payload = ByteArray(1024) { 3 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 1024L)
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        RawWriter.write(source, disk, identity, conf(disk), overwrite = true)
        assertEquals(1024, disk.bytesWritten)
        assertTrue(disk.bytes().copyOf(1024).contentEquals(payload))
        assertEquals(1L, disk.syncCount)
        // One multi-sector BOT write covering both sectors, then verify re-reads them.
        assertEquals(listOf(0UL), disk.writes)
        assertEquals(listOf(0L to 2), disk.writtenRanges)
        assertTrue(disk.reads.isNotEmpty())
    }

    @Test fun partialFinalSectorIsPadded() {
        val payload = ByteArray(100) { 9 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 100L)
        val disk = RecordingDisk(sectorCount = 2UL, sectorSize = 512)
        RawWriter.write(source, disk, identity, conf(disk), overwrite = true)
        assertEquals(512, disk.bytesWritten)
        assertEquals(9, disk.bytes()[0].toInt())
        assertEquals(0, disk.bytes()[100].toInt())
    }
    @Test fun prematureEofFailsOperationAndSkipsSync() {
        val payload = ByteArray(512) { 4 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 1024L)
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val op = DiskOperation("raw-write-eof")
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected EOFException")
        } catch (e: java.io.EOFException) {
            assertEquals("Premature EOF: wrote 512 of 1024 bytes", e.message)
        }

        assertEquals(0L, disk.syncCount)
        assertTrue(op.state is OperationState.Failed)
        val failed = op.state as OperationState.Failed
        assertTrue(failed.cause is java.io.EOFException)
        assertEquals("Premature EOF: wrote 512 of 1024 bytes", failed.cause.message)
    }

    @Test fun rawFileSourcePopulatesDeclaredSize() {
        val tempFile = java.io.File.createTempFile("test-image", ".iso")
        try {
            tempFile.writeBytes(ByteArray(2048) { 0x5A })
            val source = ImageSource.fromFile(tempFile)
            assertEquals(2048L, source.declaredDecodedSize)

            val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true)
            assertEquals(2048, disk.bytesWritten)
            assertEquals(1L, disk.syncCount)
        } finally {
            tempFile.delete()
        }
    }

    @Test fun overwriteFalseWritesNothing() {
        val payload = ByteArray(512) { 1 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 512L)
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = false)
            fail("expected OverwriteRequiredException")
        } catch (_: OverwriteRequiredException) {
        }
        assertEquals(0, disk.bytesWritten)
    }

    @Test fun confirmationMismatchWritesNothing() {
        val payload = ByteArray(512) { 1 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 512L)
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        try {
            RawWriter.write(
                source,
                disk,
                identity,
                DestructiveConfirmation("OTHER", disk.geometry.capacityBytes),
                overwrite = true,
            )
            fail("expected ConfirmationMismatchException")
        } catch (_: ConfirmationMismatchException) {
        }
        assertEquals(0, disk.bytesWritten)
    }

    @Test fun unknownLengthOversizeKeepsCommittedSectorsAndReportsPartial() {
        // 3 full sectors = 1536 bytes; disk holds 2 sectors = 1024.
        // decodedSize unknown so early reject cannot fire.
        val payload = ByteArray(3 * 512) { 7 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = null)
        val disk = RecordingDisk(sectorCount = 2UL, sectorSize = 512)
        val op = DiskOperation("raw-write-partial")
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageTooLargeException")
        } catch (_: ImageTooLargeException) {
        }
        // First two sectors fit in one chunk and stay committed; overflow detected after.
        assertEquals(1024, disk.bytesWritten)
        assertEquals(listOf(0UL), disk.writes)
        assertEquals(listOf(0L to 2), disk.writtenRanges)
        assertEquals(OperationState.Partial, op.state)
        assertEquals(1024L, op.bytesDone)
        // Overflow aborts before sync/verify.
        assertEquals(0L, disk.syncCount)
    }

    @Test fun unknownLengthOversizeBeforeAnyCommitStaysNonPartial() {
        // No room on disk: first chunk already overflows. Throw, but never claim Partial.
        val payload = ByteArray(512) { 7 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = null)
        val disk = RecordingDisk(sectorCount = 0UL, sectorSize = 512)
        val op = DiskOperation("raw-write-zero-overflow")
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageTooLargeException")
        } catch (_: ImageTooLargeException) {
        }
        assertEquals(0, disk.bytesWritten)
        assertEquals(emptyList<ULong>(), disk.writes)
        assertTrue(op.state != OperationState.Partial)
    }

    @Test fun multiSectorChunkThenVerifyCatchesSilentDrop() {
        val payload = ByteArray(2048) { 5 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 2048L)
        val op = DiskOperation("raw-write-verify-mismatch")
        val disk =
            object : RecordingDisk(sectorCount = 8UL, sectorSize = 512) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    super.write(lba, source)
                    // Drop the second sector of the multi-sector write after recording it.
                    bytes.remove(lba + 1UL)
                }
            }
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageVerifyException")
        } catch (e: ImageVerifyException) {
            assertTrue(e.message!!.contains("проверка выявила ошибку"))
            assertTrue(e.message!!.contains("mismatch"))
        }
        assertEquals(1L, disk.syncCount)
        assertEquals(OperationState.Partial, op.state)
    }

    @Test fun cancelBetweenChunksAbortsBeforeVerify() {
        val payload = ByteArray(TransferPlan.MAX_CHUNK + 512) { 2 }
        val source =
            ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = payload.size.toLong())
        val stillActive = java.util.concurrent.atomic.AtomicBoolean(true)
        val op = DiskOperation(name = "raw-write-cancel", active = { stillActive.get() })
        var chunks = 0
        val cancelling =
            object : RecordingDisk(sectorCount = 256UL, sectorSize = 512) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    super.write(lba, source)
                    chunks++
                    if (chunks == 1) stillActive.set(false)
                }
            }
        try {
            RawWriter.write(
                source,
                cancelling,
                identity,
                conf(cancelling),
                overwrite = true,
                operation = op,
            )
            fail("expected ImageCancelledException")
        } catch (_: ImageCancelledException) {
        }
        assertEquals(1, chunks)
        assertEquals(0L, cancelling.syncCount)
    }

    @Test fun verifyIoFailureMarksPartialIncomplete() {
        val payload = ByteArray(512) { 9 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 512L)
        val op = DiskOperation("raw-write-verify-io")
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    throw java.io.IOException("USB disconnect mid-verify")
                }
            }
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageVerifyException")
        } catch (e: ImageVerifyException) {
            assertTrue(e.message!!.contains("verification unavailable"))
            assertTrue(e.cause is java.io.IOException)
        }
        assertEquals(1L, disk.syncCount)
        assertEquals(OperationState.Partial, op.state)
        assertEquals(512, disk.bytesWritten)
        assertTrue(op.failureCause!!.message!!.contains("verification unavailable"))
    }

    @Test fun cancelDuringVerifyMarksPartialIncomplete() {
        val payload = ByteArray(TransferPlan.MAX_CHUNK + 512) { 4 }
        val source =
            ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = payload.size.toLong())
        val stillActive = java.util.concurrent.atomic.AtomicBoolean(true)
        val op = DiskOperation(name = "raw-write-verify-cancel", active = { stillActive.get() })
        val disk =
            object : RecordingDisk(sectorCount = 256UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    // First verify chunk: cancel after write+sync completed.
                    stillActive.set(false)
                    super.read(lba, destination)
                }
            }
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageCancelledException")
        } catch (e: ImageCancelledException) {
            assertTrue(e.message!!.contains("image written and synced; verification"))
        }
        assertEquals(1L, disk.syncCount)
        assertEquals(OperationState.Partial, op.state)
        assertTrue(disk.bytesWritten >= TransferPlan.MAX_CHUNK)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    @Test fun verifyDiskExceptionMarksPartialIncomplete() {
        val payload = ByteArray(512) { 8 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 512L)
        val op = DiskOperation("raw-write-verify-diskex")
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    throw DiskException.IoFailure("USB stall mid-verify")
                }
            }
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageVerifyException")
        } catch (e: ImageVerifyException) {
            assertTrue(e.message!!.contains("image written and synced; verification"))
            assertTrue(e.cause is DiskException.IoFailure)
        }
        assertEquals(1L, disk.syncCount)
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause!!.message!!.contains("image written and synced; verification"))
    }

    @Test fun verifyOpenSecurityExceptionMarksPartialIncomplete() {
        val payload = ByteArray(512) { 6 }
        var opens = 0
        val source =
            ImageSource.fromContent(
                openRaw = {
                    opens++
                    if (opens > 1) throw SecurityException("SAF revoked")
                    java.io.ByteArrayInputStream(payload)
                },
                displayName = "stick.img",
                size = 512L,
            )
        val op = DiskOperation("raw-write-verify-sec")
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageVerifyException")
        } catch (e: ImageVerifyException) {
            assertTrue(e.message!!.contains("verification unavailable"))
            assertTrue(e.message!!.contains("SecurityException"))
            assertTrue(e.cause is SecurityException)
        }
        assertEquals(1L, disk.syncCount)
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause!!.message!!.contains("verification unavailable"))
    }

    @Test fun cancelDuringVerifyFailureCauseIsInformative() {
        val payload = ByteArray(TransferPlan.MAX_CHUNK + 512) { 4 }
        val source =
            ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = payload.size.toLong())
        val stillActive = java.util.concurrent.atomic.AtomicBoolean(true)
        val op = DiskOperation(name = "raw-write-verify-cancel-cause", active = { stillActive.get() })
        val disk =
            object : RecordingDisk(sectorCount = 256UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    stillActive.set(false)
                    super.read(lba, destination)
                }
            }
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageCancelledException")
        } catch (e: ImageCancelledException) {
            assertTrue(e.message!!.contains("image written and synced; verification"))
        }
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause is ImageCancelledException)
        assertTrue(op.failureCause!!.message!!.contains("image written and synced; verification"))
        assertEquals(1L, disk.syncCount)
    }

    @Test fun verifyDiskExceptionDisconnectedMarksPartial() {
        val payload = ByteArray(512) { 7 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 512L)
        val op = DiskOperation("raw-write-verify-disc")
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    throw DiskException.Disconnected()
                }
            }
        try {
            RawWriter.write(source, disk, identity, conf(disk), overwrite = true, operation = op)
            fail("expected ImageVerifyException")
        } catch (e: ImageVerifyException) {
            assertTrue(e.message!!.contains("image written and synced; verification"))
            assertTrue(e.cause is DiskException.Disconnected)
        }
        assertEquals(1L, disk.syncCount)
        assertEquals(OperationState.Partial, op.state)
        assertTrue(disk.bytesWritten > 0)
        assertTrue(op.failureCause!!.message!!.contains("image written and synced; verification"))
    }
}
