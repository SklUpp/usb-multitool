package usb.diagnostics

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.DiskException
import usb.image.ImageFormat
import usb.image.ImageSource
import usb.service.DiskOperation
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

    @Test fun restoreConsumesCopiedSectorAtLbaWithoutRewrite() {
        // Dense image: sector i lives at offset i * sectorSize.
        // Map: 0 COPIED (skip write), 1 FAILED, 2 NON_TRIED.
        val sectorSize = 512
        val s0 = ByteArray(sectorSize) { 0xA0.toByte() }
        val s1 = ByteArray(sectorSize) { 0xB1.toByte() }
        val s2 = ByteArray(sectorSize) { 0xC2.toByte() }
        val image = s0 + s1 + s2
        val seed = ByteArray(3 * sectorSize) { 0x11 }
        // Pre-copied sector 0 already matches image; must not be rewritten.
        System.arraycopy(s0, 0, seed, 0, sectorSize)
        val disk = RecordingDisk.fromBytes(seed, sectorSize)
        val map = RescueMap.whole(3UL)
        map.markCopied(0UL, 1UL)
        map.markFailed(1UL, 1UL)
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())

        Rescue.restore(source, disk, map)

        assertEquals(listOf(1UL, 2UL), disk.writes)
        assertFalse(disk.writes.contains(0UL))
        // Sector 1 must come from image offset sectorSize, not stream head after a skip.
        assertTrue(disk.bytes().copyOfRange(sectorSize, sectorSize * 2).contentEquals(s1))
        assertTrue(disk.bytes().copyOfRange(sectorSize * 2, sectorSize * 3).contentEquals(s2))
        assertTrue(disk.bytes().copyOfRange(0, sectorSize).contentEquals(s0))
        assertEquals(listOf(0UL until 3UL), map.copied)
    }

    @Test fun restoreShortEofAfterCopiedSectorLeavesLaterUntouched() {
        // One full sector restored, then mid-sector EOF: later LBAs stay seed, no zero-fill.
        val sectorSize = 512
        val seed = ByteArray(4 * sectorSize) { 0x5A }
        val disk = RecordingDisk.fromBytes(seed)
        val map = RescueMap.whole(4UL)
        val image = ByteArray(sectorSize + 100) { 7 }
        val source = ImageSource.fromBytes(
            image,
            format = ImageFormat.IMG,
            decodedSize = image.size.toLong(),
        )

        Rescue.restore(source, disk, map)

        assertEquals(listOf(0UL), disk.writes)
        assertEquals(listOf(0UL until 1UL), map.copied)
        assertEquals(listOf(1UL until 4UL), map.nonTried)
        assertEquals(7, disk.bytes()[0].toInt() and 0xFF)
        // Sector 1+ never written or zero-filled.
        assertEquals(0x5A, disk.bytes()[sectorSize].toInt() and 0xFF)
        assertEquals(0x5A, disk.bytes()[3 * sectorSize].toInt() and 0xFF)
        assertTrue(map.partial)
    }

    @Test fun restoreCancelMidRestorePreservesMapAndStopsWrites() {
        val sectorSize = 512
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = sectorSize)
        val map = RescueMap.whole(4UL)
        val payload = ByteArray(4 * sectorSize) { 3 }
        val source = ImageSource.fromBytes(
            payload,
            format = ImageFormat.IMG,
            decodedSize = payload.size.toLong(),
        )
        // Become inactive once two sectors committed; third write must not happen.
        val op = DiskOperation("rescue-restore-cancel", active = { disk.writes.size < 2 })

        val returned = Rescue.restore(source, disk, map, operation = op)

        assertEquals(map, returned)
        assertEquals(listOf(0UL, 1UL), disk.writes)
        assertEquals(listOf(0UL until 2UL), map.copied)
        assertEquals(listOf(2UL until 4UL), map.nonTried)
        assertTrue(map.partial)
        assertEquals(2 * sectorSize, disk.bytesWritten)
    }

    /**
     * One unreadable sector in the middle must not shift the sectors after it:
     * the image stays dense with a zero gap, the map records the failure, and
     * restore puts every good sector back at its own LBA.
     */
    @Test fun rescueCopyThenRestoreRoundTripsPastOneFailedSector() {
        val sectorSize = 512
        val sectorCount = 8
        val badLba = 3UL
        val payload = ByteArray(sectorCount * sectorSize) { i -> ((i / sectorSize) + 1).toByte() }
        val source = OneBadSectorDisk(sectorCount.toULong(), sectorSize, badLba)
        for (i in 0 until sectorCount) {
            source.bytes[i.toULong()] = payload.copyOfRange(i * sectorSize, (i + 1) * sectorSize)
        }

        val copyMap = RescueMap.whole(sectorCount.toULong())
        val image = ByteArrayOutputStream()
        val op = DiskOperation("rescue-copy")
        Rescue.copy(source, image.asRescueSink(sectorSize), copyMap, operation = op)
        assertEquals(usb.service.OperationState.Partial, op.state)
        val imageBytes = image.toByteArray()
        assertEquals(sectorCount * sectorSize, imageBytes.size)
        assertEquals(listOf(3UL until 4UL), copyMap.failed)
        assertEquals(listOf(0UL until 3UL, 4UL until 8UL), copyMap.copied)
        assertTrue(copyMap.partial)

        for (i in 0 until sectorCount) {
            val slice = imageBytes.copyOfRange(i * sectorSize, (i + 1) * sectorSize)
            if (i.toULong() == badLba) {
                assertTrue("failed sector must be a zero gap", slice.all { it == 0.toByte() })
            } else {
                assertTrue(
                    "sector $i shifted in the rescue image",
                    slice.contentEquals(payload.copyOfRange(i * sectorSize, (i + 1) * sectorSize)),
                )
            }
        }

        val target = RecordingDisk(sectorCount = sectorCount.toULong(), sectorSize = sectorSize)
        val restoreMap = RescueMap.whole(sectorCount.toULong())
        val restoreSource = ImageSource.fromBytes(
            imageBytes,
            format = ImageFormat.IMG,
            decodedSize = imageBytes.size.toLong(),
        )
        Rescue.restore(restoreSource, target, restoreMap)

        assertEquals((0 until sectorCount).map { it.toULong() }, target.writes)
        assertEquals(listOf(0UL until sectorCount.toULong()), restoreMap.copied)
        val targetBytes = target.bytes()
        for (i in 0 until sectorCount) {
            val slice = targetBytes.copyOfRange(i * sectorSize, (i + 1) * sectorSize)
            if (i.toULong() == badLba) {
                assertTrue(slice.all { it == 0.toByte() })
            } else {
                assertTrue(
                    "restored sector $i landed at the wrong LBA",
                    slice.contentEquals(payload.copyOfRange(i * sectorSize, (i + 1) * sectorSize)),
                )
            }
        }
    }

    @Test fun rescueSinkRejectsGapInsteadOfShifting() {
        val sink = ByteArrayOutputStream().asRescueSink(512)
        sink.writeSector(0UL, ByteArray(512))
        try {
            sink.writeSector(2UL, ByteArray(512))
            fail("expected IllegalStateException for a skipped LBA")
        } catch (_: IllegalStateException) {
        }
    }

    private class OneBadSectorDisk(
        sectorCount: ULong,
        sectorSize: Int,
        private val badLba: ULong,
    ) : RecordingDisk(sectorCount = sectorCount, sectorSize = sectorSize) {
        override fun read(lba: ULong, destination: ByteBuffer) {
            if (lba == badLba) throw java.io.IOException("simulated medium error at LBA $lba")
            super.read(lba, destination)
        }
    }
}
