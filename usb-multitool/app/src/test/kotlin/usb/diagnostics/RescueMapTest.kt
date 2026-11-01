package usb.diagnostics

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.image.ImageFormat
import usb.image.ImageSource
import usb.testutil.RecordingDisk

class RescueMapTest {
    @Test fun failedRangeRemainsPartialAfterResume() {
        val map = RescueMap.whole(100UL)
        map.markFailed(10UL, 2UL)
        map.markCopied(0UL, 10UL)
        assertTrue(map.partial)
        assertEquals(listOf(10UL until 12UL), map.failed)
    }

    @Test fun fullyCopiedIsNotPartial() {
        val map = RescueMap.whole(16UL)
        map.markCopied(0UL, 16UL)
        assertFalse(map.partial)
        assertTrue(map.failed.isEmpty())
        assertEquals(listOf(0UL until 16UL), map.copied)
    }

    @Test fun encodeDecodeRoundTrip() {
        val map = RescueMap.whole(32UL)
        map.markCopied(0UL, 8UL)
        map.markFailed(8UL, 4UL)
        val restored = RescueMap.decode(map.encode())
        assertEquals(map.copied, restored.copied)
        assertEquals(map.failed, restored.failed)
        assertTrue(restored.partial)
    }

    @Test fun sparseMapSupportsDisksAbove512MiB() {
        // 1 GiB / 512 = 2_097_152 sectors — previously capped at 1_048_576.
        val sectors = 2_097_152UL
        val map = RescueMap.whole(sectors)
        map.markCopied(0UL, 8UL)
        map.markFailed(1_048_576UL, 4UL)
        assertEquals(listOf(0UL until 8UL), map.copied)
        assertEquals(listOf(1_048_576UL until 1_048_580UL), map.failed)
        assertTrue(map.partial)
        assertEquals(RescueStatus.NON_TRIED, map.statusAt(100UL))
        val roundTrip = RescueMap.decode(map.encode())
        assertEquals(map.copied, roundTrip.copied)
        assertEquals(map.failed, roundTrip.failed)
        assertEquals(sectors, roundTrip.sectorCount)
    }

    @Test fun wipeRequiresDestructiveConfirmation() {
        val disk = RecordingDisk(sectorCount = 4UL)
        val identity = "TEST-DISK-SERIAL"
        try {
            Diagnostics.wipe(
                disk,
                identity = identity,
                confirmation = DestructiveConfirmation("WRONG", disk.geometry.capacityBytes),
            )
            fail("expected ConfirmationMismatchException")
        } catch (_: ConfirmationMismatchException) {
        }
        assertEquals(0, disk.bytesWritten)

        Diagnostics.wipe(
            disk,
            identity = identity,
            confirmation = DestructiveConfirmation(identity, disk.geometry.capacityBytes),
        )
        assertEquals(4 * 512, disk.bytesWritten)
    }

    @Test fun confirmationMismatchWritesNothing() {
        val disk = RecordingDisk(sectorCount = 8UL)
        try {
            Diagnostics.wipe(
                disk,
                identity = "A",
                confirmation = DestructiveConfirmation("A", capacityBytes = 1UL),
            )
            fail("expected ConfirmationMismatchException")
        } catch (_: ConfirmationMismatchException) {
        }
        assertEquals(emptyList<ULong>(), disk.writes)
        assertEquals(0, disk.bytesWritten)
    }

    @Test fun writeBenchmarkRejectsMetadataRange() {
        val disk = RecordingDisk(sectorCount = 4096UL)
        val identity = "BENCH"
        val conf = DestructiveConfirmation(identity, disk.geometry.capacityBytes)
        try {
            Diagnostics.writeBenchmark(
                disk,
                identity = identity,
                confirmation = conf,
                startLba = 0UL,
                sectors = 8UL,
            )
            fail("expected UnsafeBenchmarkTargetException")
        } catch (_: UnsafeBenchmarkTargetException) {
        }
        assertEquals(0, disk.bytesWritten)

        try {
            Diagnostics.writeBenchmark(
                disk,
                identity = identity,
                confirmation = conf,
                startLba = 2047UL,
                sectors = 1UL,
            )
            fail("expected UnsafeBenchmarkTargetException")
        } catch (_: UnsafeBenchmarkTargetException) {
        }
        assertEquals(0, disk.bytesWritten)
    }

    @Test fun writeBenchmarkRequiresMatchAndWritesSafeRange() {
        val disk = RecordingDisk(sectorCount = 4096UL)
        val identity = "BENCH"
        try {
            Diagnostics.writeBenchmark(
                disk,
                identity = identity,
                confirmation = DestructiveConfirmation("NOPE", disk.geometry.capacityBytes),
                startLba = 2048UL,
                sectors = 4UL,
            )
            fail("expected ConfirmationMismatchException")
        } catch (_: ConfirmationMismatchException) {
        }
        assertEquals(0, disk.bytesWritten)

        val report = Diagnostics.writeBenchmark(
            disk,
            identity = identity,
            confirmation = DestructiveConfirmation(identity, disk.geometry.capacityBytes),
            startLba = 2048UL,
            sectors = 4UL,
        )
        assertEquals(4 * 512L, report.bytesWritten)
        assertEquals(2048UL, report.startLba)
        assertEquals(listOf(2048UL, 2049UL, 2050UL, 2051UL), disk.writes)
        assertFalse(disk.writes.any { it < PARTITION_METADATA_SECTORS })
    }

    @Test fun readScanNeverWrites() {
        val disk = RecordingDisk(sectorCount = 8UL)
        val report = Diagnostics.readScan(disk)
        assertEquals(8UL, report.sectorsRead)
        assertEquals(0, disk.bytesWritten)
        assertTrue(report.failed.isEmpty())
    }

    @Test fun rescueCopyStreamsToSinkNotByteArray() {
        val payload = ByteArray(4 * 512) { i -> (i % 251).toByte() }
        val disk = RecordingDisk.fromBytes(payload)
        val map = RescueMap.whole(4UL)
        val out = ByteArrayOutputStream()
        val sink = out.asRescueSink(512)
        Rescue.copy(disk, sink, map)
        assertFalse(map.partial)
        assertTrue(out.toByteArray().contentEquals(payload))
    }

    @Test fun restoreEndsCleanlyOnShortEofWithoutZeroFill() {
        val seed = ByteArray(4 * 512) { 0x5A }
        val seeded = RecordingDisk.fromBytes(seed)
        val map = RescueMap.whole(4UL)
        val short = ByteArray(100) { 7 }
        val source = ImageSource.fromBytes(short, format = ImageFormat.IMG, decodedSize = 100L)
        Rescue.restore(source, seeded, map)
        // No sector written; existing data preserved; map stays non-tried.
        assertEquals(0, seeded.bytesWritten)
        assertEquals(listOf(0UL until 4UL), map.nonTried)
        assertEquals(0x5A, seeded.bytes()[0].toInt() and 0xFF)
    }

    @Test fun restoreFullSectorReadLoopHandlesChunkedStream() {
        val payload = ByteArray(1024) { 9 }
        val source = ImageSource.fromBytes(
            payload,
            format = ImageFormat.IMG,
            decodedSize = payload.size.toLong(),
        )
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val map = RescueMap.whole(4UL)
        Rescue.restore(source, disk, map)
        assertEquals(listOf(0UL until 2UL), map.copied)
        assertEquals(1024, disk.bytesWritten)
        assertEquals(9, disk.bytes()[0].toInt())
    }
}
