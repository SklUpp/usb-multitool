package usb.image

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.test.runTest
import usb.device.TransferPlan
import usb.device.capacityBytes
import usb.diagnostics.ConfirmationMismatchException
import usb.diagnostics.DestructiveConfirmation
import usb.partition.RawWriteRange
import usb.operation.DiskOperation
import usb.service.OperationExecutor
import usb.operation.OperationState
import usb.testutil.RecordingDisk

class RawWriterTest {
    private val identity = "RAW-TEST"

    private fun conf(disk: RecordingDisk) =
        DestructiveConfirmation(identity, disk.geometry.capacityBytes)

    private fun wholeDisk(disk: RecordingDisk) = RawWriteRange(0UL, disk.sectorCount)

    private fun rawSource(bytes: ByteArray, decodedSize: Long? = bytes.size.toLong()) =
        ImageSource.fromBytes(bytes, format = ImageFormat.IMG, decodedSize = decodedSize)

    @Test fun verifyIsSkippedWithoutItsOwnAdmission() {
        awaitFreshBudget()
        val held = mutableListOf<MeasurementHandle>()
        try {
            repeat(3) { held.add(MeasurementHandle()) }
            val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
            var opens = 0
            val payload = ByteArray(512) { 4 }
            val source =
                ImageSource.fromContent(
                    openRaw = {
                        opens++
                        java.io.ByteArrayInputStream(payload)
                    },
                    displayName = "verify.img",
                    size = payload.size.toLong(),
                )
            val result =
                RawWriter.write(
                    source,
                    disk,
                    identity,
                    conf(disk),
                    overwrite = true,
                    range = wholeDisk(disk),
                )
            assertEquals("verify must not open a second unadmitted stream", 1, opens)
            assertTrue(result.readback is ReadbackVerdict.Incomplete)
        } finally {
            held.forEach {
                it.cancel()
                it.abandon()
            }
        }
        awaitFreshBudget()
    }

    @Test fun snapshotWithKnownSizeAndDigestIsNotReadTwice() {
        awaitFreshBudget()
        val payload = ByteArray(1024) { 8 }
        var opens = 0
        val source =
            ImageSource.fromContent(
                openRaw = {
                    opens++
                    java.io.ByteArrayInputStream(payload)
                },
                displayName = "staged.img",
                size = payload.size.toLong(),
                snapshot = true,
            )
        val digest = source.measureDecoded().sha256
        opens = 0
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val result =
            RawWriter.write(
                source,
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = wholeDisk(disk),
                expectedSha256 = digest,
            )
        assertEquals("snapshot size+digest must skip measure (write + readback only)", 2, opens)
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        awaitFreshBudget()
    }

    @Test fun writerRefusesBeforeOpeningWhenAdmissionIsExhausted() {
        awaitFreshBudget()
        val held = mutableListOf<MeasurementHandle>()
        try {
            repeat(4) { held.add(MeasurementHandle()) }
            val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
            var opens = 0
            val source =
                ImageSource.fromContent(
                    openRaw = {
                        opens++
                        java.io.ByteArrayInputStream(ByteArray(512))
                    },
                    displayName = "late.img",
                    size = 512L,
                )
            val failure =
                try {
                    RawWriter.write(
                        source,
                        disk,
                        identity,
                        conf(disk),
                        overwrite = true,
                        range = wholeDisk(disk),
                    )
                    null
                } catch (expected: java.io.IOException) {
                    expected
                }
            assertTrue(failure != null)
            assertEquals("source must not be opened without admission", 0, opens)
        } finally {
            held.forEach {
                it.cancel()
                it.abandon()
            }
        }
        awaitFreshBudget()
    }

    private fun awaitFreshBudget() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (MeasurementHandle.availableAdmissions() < 4 &&
            System.nanoTime() < deadline
        ) {
            MeasurementHandle.retryDeferredCloses()
            Thread.sleep(5)
        }
        assertEquals(4, MeasurementHandle.availableAdmissions())
    }

    @Test fun rangeWriteUsesAbsoluteLbasAndLeavesOtherSectorsAlone() {
        val payload = ByteArray(2048) { 3 }
        val disk = RecordingDisk(sectorCount = 64UL, sectorSize = 512)
        val result = RawWriter.write(
            rawSource(payload),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(8UL, 4UL),
        )
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(2048L, result.writtenBytes)
        assertEquals(listOf(8UL), disk.writes)
        assertEquals(listOf(8L to 4), disk.writtenRanges)
        // Readback starts at the same absolute LBA, never at 0.
        assertEquals(8UL, disk.reads.first())
        val bytes = disk.bytes()
        assertTrue(bytes.copyOfRange(8 * 512, 12 * 512).contentEquals(payload))
        assertTrue(bytes.copyOfRange(0, 8 * 512).all { it == 0.toByte() })
        assertTrue(bytes.copyOfRange(12 * 512, 16 * 512).all { it == 0.toByte() })
        assertEquals(1L, disk.syncCount)
    }

    @Test fun rangeEndingOnTheLastSectorIsAccepted() {
        val payload = ByteArray(1024) { 5 }
        val disk = RecordingDisk(sectorCount = 10UL, sectorSize = 512)
        val result = RawWriter.write(
            rawSource(payload),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(8UL, 2UL),
        )
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(listOf(8L to 2), disk.writtenRanges)
    }

    @Test fun rangePastTheDiskIsRejectedBeforeAnyWrite() {
        val disk = RecordingDisk(sectorCount = 10UL, sectorSize = 512)
        try {
            RawWriter.write(
                rawSource(ByteArray(512) { 1 }),
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(9UL, 2UL),
            )
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0L, disk.writeCount)
        assertEquals(0L, disk.syncCount)
    }

    @Test fun rangeWithWrappingStartIsRejectedBeforeAnyWrite() {
        val disk = RecordingDisk(sectorCount = 10UL, sectorSize = 512)
        try {
            RawWriter.write(
                rawSource(ByteArray(512) { 1 }),
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(ULong.MAX_VALUE, 2UL),
            )
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0L, disk.writeCount)
    }

    @Test fun emptyRangeIsRejectedByConstruction() {
        try {
            RawWriteRange(0UL, 0UL)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun knownSourceLargerThanRangeWritesNoSector() {
        val source = rawSource(ByteArray(1024) { 1 }, decodedSize = 1024L)
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        try {
            RawWriter.write(
                source,
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(0UL, 1UL),
            )
            fail("expected ImageTooLargeException")
        } catch (_: ImageTooLargeException) {
        }
        assertEquals(0L, disk.writeCount)
        assertEquals(emptyList<ULong>(), disk.writes)
    }

    @Test fun stagedGzipMagicInIsoNameIsWrittenDecoded() {
        val payload = ByteArray(6000) { 5 }
        val file = java.io.File.createTempFile("staged-backup", ".iso")
        try {
            file.writeBytes(gzip(payload))
            val disk = RecordingDisk(sectorCount = 20UL, sectorSize = 512)
            val result = RawWriter.write(
                ImageSource.fromFile(file),
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(0UL, 12UL),
                expectedDecodedBytes = 6000L,
            )
            assertEquals(RawWriteStatus.Completed, result.state)
            assertEquals(ReadbackVerdict.Verified, result.readback)
            assertTrue(disk.bytes().copyOfRange(0, payload.size).contentEquals(payload))
        } finally {
            file.delete()
        }
    }

    @Test fun safDigestMismatchAfterConfirmationWritesNothing() {
        var bytes = ByteArray(1024) { 1 }
        val source = ImageSource.fromContent(
            openRaw = { java.io.ByteArrayInputStream(bytes) },
            displayName = "disk.img",
            size = bytes.size.toLong(),
        )
        val confirmedDigest = source.measureDecoded().sha256
        // Equal-size content swap between confirmation and write.
        bytes = ByteArray(1024) { 9 }
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        try {
            RawWriter.write(
                source,
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(0UL, 2UL),
                expectedDecodedBytes = 1024L,
                expectedSha256 = confirmedDigest,
            )
            fail("expected SourceChangedException")
        } catch (_: SourceChangedException) {
        }
        assertEquals(0L, disk.writeCount)
        assertEquals(0L, disk.syncCount)
        assertEquals(emptyList<ULong>(), disk.reads)
    }

    @Test fun safDigestMatchWritesAndReadsBack() {
        val bytes = ByteArray(1024) { 4 }
        val source = ImageSource.fromContent(
            openRaw = { java.io.ByteArrayInputStream(bytes) },
            displayName = "disk.img",
            size = bytes.size.toLong(),
        )
        val digest = source.measureDecoded().sha256
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(0UL, 2UL),
            expectedDecodedBytes = 1024L,
            expectedSha256 = digest,
        )
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(ReadbackVerdict.Verified, result.readback)
    }

    @Test fun zeroProviderSizeIsMeasuredNotTreatedAsEmpty() {
        // SAF providers may report SIZE = 0 for a stream with real content. The writer must
        // measure the decoded bytes instead of trusting the declared size.
        val bytes = ByteArray(1024) { 6 }
        val source = ImageSource.fromContent(
            openRaw = { java.io.ByteArrayInputStream(bytes) },
            displayName = "disk.img",
            size = 0L,
        )
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = wholeDisk(disk),
        )
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(1024L, result.writtenBytes)
        assertEquals(ReadbackVerdict.Verified, result.readback)
    }

    @Test fun compressedSourceIsDecodedCountedThenWrittenStreamingly() {
        val payload = ByteArray(6000) { 7 }
        val source = ImageSource.fromBytes(gzip(payload))
        val disk = RecordingDisk(sectorCount = 20UL, sectorSize = 512)
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(1UL, 12UL),
            expectedDecodedBytes = 6000L,
        )
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(6000L, result.writtenBytes)
        assertEquals(listOf(1L to 12), disk.writtenRanges)
        assertTrue(disk.bytes().copyOfRange(512, 512 + payload.size).contentEquals(payload))
    }

    @Test fun gzipDecodedSizeIsUnknownSoOverflowIsDetectedWhileStreaming() {
        // The gzip footer is not a decoded length (ISIZE is mod 2^32 and only covers the
        // last member), so the stream is the authority: the range fills, the extra byte is
        // refused, and the result is Partial rather than a pre-write rejection.
        val source = ImageSource.fromBytes(gzip(ByteArray(6000) { 7 }))
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val op = DiskOperation("raw-gzip-overflow")
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = wholeDisk(disk),
            operation = op,
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(4096L, result.writtenBytes)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertTrue(result.failure is ImageTooLargeException)
        assertEquals(1L, disk.syncCount)
        assertEquals(-1L, op.bytesTotal)
        assertEquals(OperationState.Partial, op.state)
    }

    @Test fun compressedSourceLargerThanRangeIsRefusedBeforeAnyWrite() {
        val source = ImageSource.fromBytes(gzip(ByteArray(6000) { 7 }))
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        try {
            RawWriter.write(
                source,
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = wholeDisk(disk),
            )
            fail("expected ImageTooLargeException")
        } catch (_: ImageTooLargeException) {
        }
        assertEquals(0L, disk.writeCount)
    }

    @Test fun gzipFooterIsNeverTakenAsDecodedSize() {
        // Trailing non-gzip bytes whose last 4 bytes look like 0xFFFFFFFF must not be
        // mistaken for a 4 GiB ISIZE on preflight; GZIPInputStream stops cleanly after the
        // member trailer when the next bytes do not start with GZIP_MAGIC.
        val payload = ByteArray(1024) { 5 }
        val padded = gzip(payload) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val op = DiskOperation("raw-gzip-footer")
        val result = RawWriter.write(
            ImageSource.fromBytes(padded),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = wholeDisk(disk),
            operation = op,
        )
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(1024L, result.writtenBytes)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(-1L, op.bytesTotal)
    }

    @Test fun gzipMembersAreCountedTogetherNotFromTheLastFooter() {
        // GZIPInputStream concatenates members; the final ISIZE only describes the tail.
        val first = ByteArray(700) { 1 }
        val second = ByteArray(400) { 2 }
        val source = ImageSource.fromBytes(gzip(first) + gzip(second))
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = wholeDisk(disk),
        )
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(1100L, result.writtenBytes)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(2, disk.bytes()[700].toInt())
    }

    @Test fun partialFinalSectorIsPaddedInsideTheRange() {
        val payload = ByteArray(100) { 9 }
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val result = RawWriter.write(
            rawSource(payload),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(1UL, 1UL),
        )
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(512, disk.bytesWritten)
        val bytes = disk.bytes()
        assertEquals(9, bytes[512].toInt())
        assertEquals(0, bytes[512 + 100].toInt())
        assertEquals(0.toByte(), bytes[0])
    }

    @Test fun declaredSizeOverflowWritesOnlyTheRangeThenSyncsAndReadsBack() {
        // A source that declares 1024 bytes but delivers 1536: the extra sector must not be
        // truncated silently — exactly the requested range is written, the tail is refused.
        val payload = ByteArray(3 * 512) { 7 }
        val source = rawSource(payload, decodedSize = 1024L)
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val op = DiskOperation("raw-range-overflow")
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(2UL, 2UL),
            operation = op,
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(1024L, result.writtenBytes)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(listOf(2L to 2), disk.writtenRanges)
        // Overflow is detected only after commut, and the medium is synced + read back.
        assertEquals(1L, disk.syncCount)
        assertTrue(disk.reads.contains(2UL))
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.mutationAttempted)
        // The declared size is still known, so progress advertises it.
        assertEquals(1024L, op.bytesTotal)
        assertTrue(result.failure is ImageTooLargeException)
    }

    @Test fun prematureEofSyncsAndReadsBackWrittenPrefix() {
        val source = rawSource(ByteArray(512) { 4 }, decodedSize = 1024L)
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val op = DiskOperation("raw-write-eof")
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = wholeDisk(disk),
            operation = op,
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(512L, result.writtenBytes)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(1L, disk.syncCount)
        assertTrue(result.failure is java.io.EOFException)
        assertEquals(OperationState.Partial, op.state)
        assertEquals(512L, op.bytesDone)
    }

    @Test fun transportFailureAfterFirstCommitIsSyncedAndReportedPartial() = runTest {
        val inner = RecordingDisk(sectorCount = 130UL, sectorSize = 512)
        val payload = ByteArray(129 * 512) { 3 }
        val source = rawSource(payload)
        val executor = OperationExecutor(this)
        var captured: RawWriteResult? = null
        val handle = executor.submit(object : DiskOperation("raw-partial") {
            override suspend fun run() {
                var attempts = 0
                val flaky = object : usb.device.BlockDevice {
                    override val sectorSize: Int get() = inner.sectorSize
                    override val sectorCount: ULong get() = inner.sectorCount

                    override fun read(lba: ULong, destination: ByteBuffer) =
                        inner.read(lba, destination)

                    override fun write(lba: ULong, source: ByteBuffer) {
                        attempts++
                        // The request may reach the medium before the CSW/residue error.
                        inner.write(lba, source)
                        if (attempts >= 2) throw java.io.IOException("simulated transport failure")
                    }

                    override fun sync() = inner.sync()
                }
                captured = RawWriter.write(
                    source = source,
                    disk = flaky,
                    identity = identity,
                    confirmation = DestructiveConfirmation(identity, inner.capacityBytes),
                    overwrite = true,
                    range = RawWriteRange(0UL, inner.sectorCount),
                    operation = this,
                )
                assertTrue(mutationAttempted)
            }
        })
        assertEquals(OperationState.Partial, handle.await())
        val result = captured!!
        assertEquals(RawWriteStatus.Partial, result.state)
        // The failed second request may have reached the medium, so it is not Verified.
        val verdict = result.readback as ReadbackVerdict.Unverified
        assertEquals(RawWriteRange(128UL, 1UL), verdict.range)
        assertEquals(TransferPlan.MAX_CHUNK.toLong(), result.writtenBytes)
        assertEquals(1L, inner.syncCount)
        assertTrue(inner.bytesWritten > 0)
    }

    @Test fun readbackMismatchCountsSectorsAndNamesAbsoluteLba() {
        val payload = ByteArray(2048) { 5 }
        val disk =
            object : RecordingDisk(sectorCount = 16UL, sectorSize = 512) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    super.write(lba, source)
                    // Drop the sector after the first one of this range.
                    bytes.remove(lba + 1UL)
                }
            }
        val result = RawWriter.write(
            rawSource(payload),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(4UL, 4UL),
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(1L, disk.syncCount)
        val verdict = result.readback as ReadbackVerdict.Mismatch
        assertEquals(1UL, verdict.mismatchedSectors)
        assertEquals(5UL, verdict.firstMismatchLba)
        assertTrue(result.failure!!.message!!.contains("readback mismatch"))
        assertTrue(result.failure!!.message!!.contains("LBA 5"))
    }

    @Test fun readbackIoFailureIsIncompleteNotVerified() {
        val disk =
            object : RecordingDisk(sectorCount = 16UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    throw java.io.IOException("USB disconnect mid-readback")
                }
            }
        val result = RawWriter.write(
            rawSource(ByteArray(512) { 9 }),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(2UL, 1UL),
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        val verdict = result.readback as ReadbackVerdict.Incomplete
        assertEquals(ReadbackProblem.IoError, verdict.reason)
        assertEquals(1L, disk.syncCount)
        assertEquals(1L, disk.writeCount)
    }

    @Test fun readbackSourceShortIsReportedAsSourceChanged() {
        var opens = 0
        val source =
            ImageSource.fromContent(
                openRaw = {
                    opens++
                    if (opens > 1) java.io.ByteArrayInputStream(ByteArray(128))
                    else java.io.ByteArrayInputStream(ByteArray(512) { 1 })
                },
                displayName = "stick.img",
                size = 512L,
            )
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(0UL, 1UL),
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        val verdict = result.readback as ReadbackVerdict.Incomplete
        assertEquals(ReadbackProblem.SourceChanged, verdict.reason)
        assertEquals(1L, disk.syncCount)
    }

    @Test fun cancelAfterFirstCommitIsPartialWithSyncAndReadback() {
        val payload = ByteArray(TransferPlan.MAX_CHUNK + 512) { 2 }
        val stillActive = AtomicBoolean(true)
        val op = DiskOperation(name = "raw-cancel", active = { stillActive.get() })
        val disk =
            object : RecordingDisk(sectorCount = 256UL, sectorSize = 512) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    // Commit the whole first chunk, then simulate the user cancelling.
                    val count = source.remaining() / sectorSize
                    for (i in 0 until count) {
                        val one = ByteArray(sectorSize)
                        source.get(one)
                        bytes[lba + i.toULong()] = one
                    }
                    writtenRanges.add(Pair(lba.toLong(), count))
                    bytesWritten += count * sectorSize
                    stillActive.set(false)
                }
            }
        val result = RawWriter.write(
            rawSource(payload),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(0UL, 129UL),
            operation = op,
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        // The operation is already cancelled, so the partial readback still runs on the
        // committed prefix instead of silently skipping verification.
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(1L, disk.syncCount)
        assertTrue(result.failure is ImageCancelledException)
        assertTrue(op.mutationAttempted)
    }

    @Test fun cancelDuringFullReadbackIsIncomplete() {
        val payload = ByteArray(TransferPlan.MAX_CHUNK + 512) { 4 }
        val stillActive = AtomicBoolean(true)
        val op = DiskOperation(name = "raw-verify-cancel", active = { stillActive.get() })
        val disk =
            object : RecordingDisk(sectorCount = 256UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    stillActive.set(false)
                    super.read(lba, destination)
                }
            }
        val result = RawWriter.write(
            rawSource(payload),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = wholeDisk(disk),
            operation = op,
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        val verdict = result.readback as ReadbackVerdict.Incomplete
        assertEquals(ReadbackProblem.Cancelled, verdict.reason)
        assertEquals(1L, disk.syncCount)
    }

    @Test fun emptySourceFailsWithoutWrites() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        try {
            RawWriter.write(
                rawSource(ByteArray(0)),
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = wholeDisk(disk),
            )
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0L, disk.writeCount)
        assertEquals(0L, disk.syncCount)
    }

    @Test fun overwriteFalseWritesNothing() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        try {
            RawWriter.write(
                rawSource(ByteArray(512) { 1 }),
                disk,
                identity,
                conf(disk),
                overwrite = false,
                range = wholeDisk(disk),
            )
            fail("expected OverwriteRequiredException")
        } catch (_: OverwriteRequiredException) {
        }
        assertEquals(0L, disk.writeCount)
    }

    @Test fun confirmationMismatchWritesNothing() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        try {
            RawWriter.write(
                rawSource(ByteArray(512) { 1 }),
                disk,
                identity,
                DestructiveConfirmation("OTHER", disk.geometry.capacityBytes),
                overwrite = true,
                range = wholeDisk(disk),
            )
            fail("expected ConfirmationMismatchException")
        } catch (_: ConfirmationMismatchException) {
        }
        assertEquals(0L, disk.writeCount)
    }

    @Test fun progressTotalIsKnownAfterMeasurement() {
        val knownDisk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val knownOp = DiskOperation("known")
        val known = RawWriter.write(
            rawSource(ByteArray(1024) { 1 }),
            knownDisk,
            identity,
            conf(knownDisk),
            overwrite = true,
            range = wholeDisk(knownDisk),
            operation = knownOp,
        )
        assertEquals(RawWriteStatus.Completed, known.state)
        assertEquals(1024L, knownOp.bytesTotal)

        val unknownDisk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val unknownOp = DiskOperation("unknown")
        val unknown = RawWriter.write(
            rawSource(ByteArray(1024) { 1 }, decodedSize = null),
            unknownDisk,
            identity,
            conf(unknownDisk),
            overwrite = true,
            range = wholeDisk(unknownDisk),
            operation = unknownOp,
        )
        assertEquals(RawWriteStatus.Completed, unknown.state)
        assertEquals(1024L, unknownOp.bytesTotal)
        assertEquals(1024L, unknownOp.bytesDone)
    }

    @Test fun unknownDecodedSizeIsMeasuredAndWrittenBeforeCommit() {
        val payload = ByteArray(1024) { 3 }
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val result = RawWriter.write(
            rawSource(payload, decodedSize = null),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(1UL, 2UL),
            expectedDecodedBytes = 1024L,
        )
        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(listOf(1L to 2), disk.writtenRanges)
    }

    @Test fun unknownDecodedSizeLargerThanRangeIsRefusedBeforeAnyWrite() {
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        try {
            RawWriter.write(
                rawSource(ByteArray(1024) { 1 }, decodedSize = null),
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(1UL, 1UL),
            )
            fail("expected ImageTooLargeException")
        } catch (_: ImageTooLargeException) {
        }
        assertEquals(0L, disk.writeCount)
        assertEquals(0L, disk.syncCount)
        assertEquals(emptyList<ULong>(), disk.reads)
    }

    @Test fun sourceChangedAfterConfirmationIsRefusedBeforeAnyWrite() {
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        try {
            RawWriter.write(
                rawSource(ByteArray(1024) { 1 }),
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(1UL, 2UL),
                expectedDecodedBytes = 512L,
            )
            fail("expected SourceChangedException")
        } catch (_: SourceChangedException) {
        }
        assertEquals(0L, disk.writeCount)
        assertEquals(0L, disk.syncCount)
        assertEquals(emptyList<ULong>(), disk.reads)
    }

    @Test fun uncompressedFileSourceKeepsDeclaredSize() {
        val tempFile = java.io.File.createTempFile("test-image", ".iso")
        try {
            tempFile.writeBytes(ByteArray(2048) { 0x5A })
            val source = ImageSource.fromFile(tempFile)
            assertEquals(2048L, source.declaredDecodedSize)
            val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
            val result = RawWriter.write(
                source,
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(2UL, 4UL),
            )
            assertEquals(RawWriteStatus.Completed, result.state)
            assertEquals(listOf(2L to 4), disk.writtenRanges)
        } finally {
            tempFile.delete()
        }
    }

    @Test fun failedFirstWriteAttemptIsUnverifiedNotVerified() {
        var calls = 0
        val disk =
            object : RecordingDisk(sectorCount = 8UL, sectorSize = 512) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    super.write(lba, source)
                    calls++
                    throw java.io.IOException("CSW failure after transfer")
                }
            }
        val op = DiskOperation("raw-unverified-first")
        val result = RawWriter.write(
            rawSource(ByteArray(512) { 6 }),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(0UL, 1UL),
            operation = op,
        )
        assertEquals(1, calls)
        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(0L, result.writtenBytes)
        val verdict = result.readback as ReadbackVerdict.Unverified
        assertEquals(RawWriteRange(0UL, 1UL), verdict.range)
        assertEquals(1L, disk.syncCount)
        assertTrue(result.failure is java.io.IOException)
        assertEquals(OperationState.Partial, op.state)
    }

    @Test fun failedLaterWriteAttemptLeavesAttemptUnverified() {
        var calls = 0
        val disk =
            object : RecordingDisk(sectorCount = 130UL, sectorSize = 512) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    super.write(lba, source)
                    calls++
                    if (calls >= 2) throw java.io.IOException("residue after transfer")
                }
            }
        val payload = ByteArray(129 * 512) { 3 }
        val result = RawWriter.write(
            rawSource(payload),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(0UL, 129UL),
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(TransferPlan.MAX_CHUNK.toLong(), result.writtenBytes)
        val verdict = result.readback as ReadbackVerdict.Unverified
        assertEquals(RawWriteRange(128UL, 1UL), verdict.range)
        assertTrue(result.failure is java.io.IOException)
        assertEquals(1L, disk.syncCount)
    }

    @Test fun corruptFailedAttemptReportsMismatchNotVerified() {
        var calls = 0
        val disk =
            object : RecordingDisk(sectorCount = 130UL, sectorSize = 512) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    calls++
                    if (calls >= 2) {
                        // Commit zeroes instead of the requested sector, then fail.
                        val zero = ByteArray(sectorSize)
                        bytes[lba] = zero
                        writtenRanges.add(Pair(lba.toLong(), 1))
                        bytesWritten += sectorSize
                        throw java.io.IOException("corrupted transfer")
                    }
                    super.write(lba, source)
                }
            }
        val payload = ByteArray(129 * 512) { 4 }
        val result = RawWriter.write(
            rawSource(payload),
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(0UL, 129UL),
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        val verdict = result.readback as ReadbackVerdict.Mismatch
        assertEquals(128UL, verdict.firstMismatchLba)
        assertEquals(1UL, verdict.mismatchedSectors)
    }

    @Test fun sourceCloseFailureDowngradesCompletedButKeepsResult() {
        var opens = 0
        val payload = ByteArray(512) { 7 }
        val source =
            ImageSource.fromContent(
                openRaw = {
                    opens++
                    // Only the write stream fails to close; readback reopens normally.
                    ClosingStream(payload, failClose = opens == 1)
                },
                displayName = "stick.img",
                size = payload.size.toLong(),
            )
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val op = DiskOperation("raw-close-fail")
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(0UL, 1UL),
            operation = op,
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(512L, result.writtenBytes)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertTrue(result.failure is java.io.IOException)
        assertTrue(result.failure!!.message!!.contains("close failed"))
        assertEquals(1L, disk.syncCount)
        assertEquals(OperationState.Partial, op.state)
    }

    @Test fun sourceCloseFailureKeepsPrimaryWriteFailure() {
        val writeError = java.io.IOException("write failed")
        var opens = 0
        val payload = ByteArray(512) { 8 }
        val source =
            ImageSource.fromContent(
                openRaw = {
                    opens++
                    ClosingStream(payload, failClose = opens == 1)
                },
                displayName = "stick.img",
                size = payload.size.toLong(),
            )
        val disk =
            object : RecordingDisk(sectorCount = 8UL, sectorSize = 512) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    super.write(lba, source)
                    throw writeError
                }
            }
        val result = RawWriter.write(
            source,
            disk,
            identity,
            conf(disk),
            overwrite = true,
            range = RawWriteRange(0UL, 1UL),
        )
        assertEquals(RawWriteStatus.Partial, result.state)
        assertTrue(result.failure === writeError)
        assertEquals(1, writeError.suppressed.size)
        assertTrue(writeError.suppressed[0].message!!.contains("close failed"))
        assertTrue(result.readback is ReadbackVerdict.Unverified)
    }

    private class ClosingStream(
        data: ByteArray,
        private val failClose: Boolean,
    ) : java.io.ByteArrayInputStream(data) {
        override fun close() {
            if (failClose) throw java.io.IOException("close failed")
        }
    }

    @Test fun zeroReadStreamFailsInsteadOfHangingTheWriter() {
        val source = ImageSource.fromContent(
            openRaw = { ZeroAfterPrefix(ByteArray(6)) },
            displayName = "disk.img",
            size = 6L,
        )
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        try {
            RawWriter.write(
                source,
                disk,
                identity,
                conf(disk),
                overwrite = true,
                range = RawWriteRange(0UL, 1UL),
            )
            fail("expected IOException")
        } catch (_: java.io.IOException) {
        }
        assertEquals(0L, disk.writeCount)
    }

    private class ZeroAfterPrefix(private val prefix: ByteArray) : java.io.InputStream() {
        private var index = 0
        override fun read(): Int =
            if (index < prefix.size) prefix[index++].toInt() and 0xFF else 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (index < prefix.size && len > 0) {
                b[off] = prefix[index++]
                return 1
            }
            return 0
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
