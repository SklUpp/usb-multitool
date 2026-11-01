package usb.image

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
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
        // First two sectors fit and stay committed; overflowing third chunk not written.
        assertEquals(1024, disk.bytesWritten)
        assertEquals(listOf(0UL, 1UL), disk.writes)
        assertEquals(OperationState.Partial, op.state)
        assertEquals(1024L, op.bytesDone)
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

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
