package usb.diagnostics

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.DiskException
import usb.image.ImageCancelledException
import usb.image.ImageFormat
import usb.image.ImageSource
import usb.image.ImageVerifyException
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.testutil.RecordingDisk

class RescueRestoreFaultTest {
    private fun confirm(disk: RecordingDisk, identity: String = "TEST-DISK") =
        DestructiveConfirmation(identity, disk.geometry.capacityBytes)

    private fun countedSource(
        bytes: ByteArray,
        declaredSize: Long? = bytes.size.toLong(),
        onOpen: (Int) -> ByteArray = { bytes },
    ): Pair<ImageSource, () -> Int> {
        var opens = 0
        val source =
            ImageSource.fromContent(
                openRaw = {
                    opens++
                    ByteArrayInputStream(onOpen(opens))
                },
                displayName = "image.img",
                size = declaredSize,
            )
        return source to { opens }
    }

    private fun assertNoIoAndUntouchedOperation(
        disk: RecordingDisk,
        opens: Int,
        checkpoints: Int,
        op: DiskOperation,
    ) {
        assertEquals(0, opens)
        assertEquals(0L, disk.readCount)
        assertEquals(0L, disk.writeCount)
        assertEquals(0L, disk.syncCount)
        assertEquals(0, checkpoints)
        assertEquals(OperationState.Queued, op.state)
        assertFalse(op.mutationAttempted)
        assertFalse(op.commitReached)
        assertNull(op.failureCause)
    }

    // region Preflight (no writes, no reads, no source opens)

    @Test fun preflightRefusesCapacityMismatchWithoutIoOrMutation() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val map = RescueMap.forRestore(4UL, sectorSize = 512, identity = "TEST-DISK")
        val (source, opens) = countedSource(ByteArray(4 * 512) { 1 })
        val op = DiskOperation("preflight-capacity")
        var checkpoints = 0

        try {
            Rescue.restore(
                source,
                disk,
                map,
                identity = "TEST-DISK",
                confirmation = DestructiveConfirmation("TEST-DISK", 2UL * 512UL),
                checkpoint = { checkpoints++ },
                operation = op,
            )
            fail("expected ConfirmationMismatchException")
        } catch (e: ConfirmationMismatchException) {
            assertTrue(e.message!!.contains("capacity"))
        }

        assertNoIoAndUntouchedOperation(disk, opens(), checkpoints, op)
        assertEquals(listOf(0UL until 4UL), map.nonTried)
    }

    @Test fun preflightRefusesMapLargerThanDiskWithoutIo() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val map = RescueMap.forRestore(8UL, sectorSize = 512, identity = "TEST-DISK")
        val (source, opens) = countedSource(ByteArray(8 * 512) { 1 })
        val op = DiskOperation("preflight-sector-count")
        var checkpoints = 0

        try {
            Rescue.restore(
                source,
                disk,
                map,
                identity = "TEST-DISK",
                confirmation = confirm(disk),
                checkpoint = { checkpoints++ },
                operation = op,
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sectors"))
        }

        assertNoIoAndUntouchedOperation(disk, opens(), checkpoints, op)
        assertEquals(listOf(0UL until 8UL), map.nonTried)
    }

    @Test fun preflightRefusesSectorSizeMismatchWithoutIo() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val map = RescueMap.forRestore(4UL, sectorSize = 4096, identity = "TEST-DISK")
        val (source, opens) = countedSource(ByteArray(4 * 4096) { 1 })
        val op = DiskOperation("preflight-sector-size")
        var checkpoints = 0

        try {
            Rescue.restore(
                source,
                disk,
                map,
                identity = "TEST-DISK",
                confirmation = confirm(disk),
                checkpoint = { checkpoints++ },
                operation = op,
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sectorSize"))
        }

        assertNoIoAndUntouchedOperation(disk, opens(), checkpoints, op)
    }

    @Test fun preflightRefusesByteCountOverflowWithoutIo() {
        val sectorSize = 512
        val overflowSectors = (Long.MAX_VALUE / sectorSize.toLong()).toULong() + 1UL
        val disk = RecordingDisk(sectorCount = overflowSectors, sectorSize = sectorSize)
        val map = RescueMap.forRestore(overflowSectors, sectorSize = sectorSize, identity = "TEST-DISK")
        val (source, opens) = countedSource(ByteArray(sectorSize) { 1 }, declaredSize = null)
        val op = DiskOperation("preflight-overflow")
        var checkpoints = 0

        try {
            Rescue.restore(
                source,
                disk,
                map,
                identity = "TEST-DISK",
                confirmation = confirm(disk),
                checkpoint = { checkpoints++ },
                operation = op,
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("overflows Long"))
        }

        assertNoIoAndUntouchedOperation(disk, opens(), checkpoints, op)
    }

    @Test fun preflightRefusesInsufficientDeclaredSizeWithoutOpeningSource() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val map = RescueMap.forRestore(4UL, sectorSize = 512, identity = "TEST-DISK")
        val (source, opens) = countedSource(ByteArray(512) { 2 }, declaredSize = 512L)
        val op = DiskOperation("preflight-declared-size")
        var checkpoints = 0

        try {
            Rescue.restore(
                source,
                disk,
                map,
                identity = "TEST-DISK",
                confirmation = confirm(disk),
                checkpoint = { checkpoints++ },
                operation = op,
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("need at least"))
        }

        assertNoIoAndUntouchedOperation(disk, opens(), checkpoints, op)
    }

    // endregion

    // region Readback corruption & verification semantics

    @Test fun copiedReadbackMediumErrorFallsThroughRewritesAndCompletes() {
        val sectorSize = 512
        val image = ByteArray(4 * sectorSize) { i -> (i / sectorSize + 1).toByte() }
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = sectorSize) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    if (readCount == 0L) {
                        super.read(lba, ByteBuffer.allocate(destination.remaining()))
                        throw DiskException.SenseError(
                            responseCode = 0x70,
                            senseKey = 0x03, // medium error
                            asc = 0x11,
                            ascq = 0x00,
                        )
                    }
                    super.read(lba, destination)
                }
            }
        val map = RescueMap.forRestore(4UL, sectorSize = sectorSize, identity = "TEST-DISK")
        map.markCopied(0UL, 2UL)
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("copied-medium-readback")

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            operation = op,
        )

        assertEquals(listOf(0UL, 2UL), disk.writes)
        assertTrue(disk.bytes().contentEquals(image))
        assertEquals(listOf(0UL until 4UL), map.copied)
        assertEquals(OperationState.Completed, op.state)
        assertTrue(op.commitReached)
        assertNull(op.failureCause)
    }

    @Test fun copiedReadbackNonMediumSenseErrorFailsWithoutWritesAndRethrows() {
        val sectorSize = 512
        val image = ByteArray(4 * sectorSize) { 0x4A }
        val sense =
            DiskException.SenseError(
                responseCode = 0x70,
                senseKey = 0x02, // NOT READY -> non-medium
                asc = 0x3A,
                ascq = 0x00,
            )
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = sectorSize) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    throw sense
                }
            }
        val map = RescueMap.forRestore(4UL, sectorSize = sectorSize, identity = "TEST-DISK")
        map.markCopied(0UL, 2UL)
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("copied-nonmedium-readback")
        var checkpoints = 0

        try {
            Rescue.restore(
                source,
                disk,
                map,
                identity = "TEST-DISK",
                confirmation = confirm(disk),
                checkpoint = { checkpoints++ },
                operation = op,
            )
            fail("expected SenseError to propagate")
        } catch (e: DiskException.SenseError) {
            assertSame(sense, e)
        }

        assertEquals(0L, disk.writeCount)
        assertEquals(0L, disk.syncCount)
        assertFalse(op.mutationAttempted)
        assertFalse(op.commitReached)
        assertEquals(OperationState.Failed(sense), op.state)
        assertSame(sense, op.failureCause)
        assertEquals(1, checkpoints)
    }

    @Test fun silentWriteDropDetectedByVerifyRestoredReportsPartial() {
        val sectorSize = 512
        val image = ByteArray(4 * sectorSize) { 0x77 }
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = sectorSize) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    super.write(lba, source)
                    // Simulate a silent drop of the last byte in LBA 2 after write returns.
                    bytes[2UL]?.set(sectorSize - 1, 0x00)
                }
            }
        val map = RescueMap.forRestore(4UL, sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("silent-write-drop")
        var checkpoints = 0

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            checkpoint = { checkpoints++ },
            operation = op,
        )

        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause is ImageVerifyException)
        assertTrue(op.failureCause!!.message!!.contains("LBA 2"))
        assertFalse(op.commitReached)
        assertTrue(op.mutationAttempted)
        assertEquals(1L, disk.syncCount)
        assertEquals(1L, disk.readCount)
        assertEquals(1, checkpoints)
    }

    @Test fun verifyRestoredDiskReadFailureAfterSyncReportsPartial() {
        val sectorSize = 512
        val image = ByteArray(2 * sectorSize) { 0x3C }
        val disk =
            object : RecordingDisk(sectorCount = 2UL, sectorSize = sectorSize) {
                override fun read(lba: ULong, destination: ByteBuffer) {
                    throw DiskException.IoFailure("verify read boom")
                }
            }
        val map = RescueMap.forRestore(2UL, sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("verify-read-failure")

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            operation = op,
        )

        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause is DiskException.IoFailure)
        assertEquals("verify read boom", op.failureCause?.message)
        assertFalse(op.commitReached)
        assertTrue(op.mutationAttempted)
        assertEquals(1L, disk.syncCount)
    }

    @Test fun verifyRestoredSourceShortReadAfterSyncReportsPartial() {
        val sectorSize = 512
        val fullImage = ByteArray(2 * sectorSize) { 0x55 }
        val truncated = ByteArray(sectorSize) { 0x55 }
        val (source, opens) =
            countedSource(fullImage) { openIndex ->
                if (openIndex == 1) fullImage else truncated
            }
        val disk = RecordingDisk(sectorCount = 2UL, sectorSize = sectorSize)
        val map = RescueMap.forRestore(2UL, sectorSize = sectorSize, identity = "TEST-DISK")
        val op = DiskOperation("verify-source-short")

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            operation = op,
        )

        assertEquals(2, opens())
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause is ImageVerifyException)
        assertTrue(op.failureCause!!.message!!.contains("short"))
        assertFalse(op.commitReached)
    }

    // endregion

    // region Periodic sync & checkpoint failure semantics

    @Test fun periodicAndFinalSyncFailureSkipsAllCheckpointsAndPreservesSuppressed() {
        val sectorSize = 4096
        val sectorCount = 300 // batch 1 = 256 sectors, batch 2 = 44 sectors
        val image = ByteArray(sectorCount * sectorSize) { i -> (i % 251).toByte() }
        val periodicErr = DiskException.IoFailure("periodic sync boom")
        val finalErr = DiskException.IoFailure("final sync boom")
        val disk = RecordingDisk(sectorCount = sectorCount.toULong(), sectorSize = sectorSize)
        disk.syncHook = {
            if (disk.syncCount == 1L) throw periodicErr else throw finalErr
        }
        val map = RescueMap.forRestore(sectorCount.toULong(), sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val published = mutableListOf<RescueMap>()
        val op = DiskOperation("periodic-and-final-sync-fail")

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            checkpointEverySectors = 128UL,
            checkpoint = { published.add(it.snapshot()) },
            operation = op,
        )

        assertEquals(listOf(0L to 256), disk.writtenRanges)
        assertEquals(2L, disk.syncCount)
        assertEquals(0L, disk.readCount)
        assertTrue(published.isEmpty())
        assertEquals(OperationState.Partial, op.state)
        assertSame(periodicErr, op.failureCause)
        assertEquals(listOf(finalErr), op.failureCause!!.suppressed.toList())
        assertFalse(op.commitReached)
        assertEquals(listOf(0UL until 256UL), map.copied)
        assertEquals(listOf(256UL until 300UL), map.nonTried)
    }

    @Test fun periodicSyncFailureRecoveredByFinalSyncPublishesOnlyFinalCheckpoint() {
        val sectorSize = 4096
        val sectorCount = 300
        val image = ByteArray(sectorCount * sectorSize) { i -> (i % 241).toByte() }
        val disk = RecordingDisk(sectorCount = sectorCount.toULong(), sectorSize = sectorSize)
        disk.syncHook = {
            if (disk.syncCount == 1L) throw DiskException.IoFailure("transient periodic sync")
        }
        val map = RescueMap.forRestore(sectorCount.toULong(), sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val published = mutableListOf<Pair<Long, List<ULongRange>>>()
        val op = DiskOperation("periodic-sync-transient")

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            checkpointEverySectors = 128UL,
            checkpoint = { published.add(disk.syncCount to it.copied) },
            operation = op,
        )

        assertEquals(listOf(0L to 256), disk.writtenRanges)
        assertEquals(2L, disk.syncCount)
        assertEquals(0L, disk.readCount)
        assertEquals(listOf(2L to listOf(0UL until 256UL)), published)
        assertEquals(OperationState.Partial, op.state)
        assertEquals("transient periodic sync", op.failureCause?.message)
        assertFalse(op.commitReached)
    }

    @Test fun periodicCheckpointExceptionRethrowsAfterFinalSyncAndMarksPartial() {
        val sectorSize = 4096
        val sectorCount = 300
        val image = ByteArray(sectorCount * sectorSize) { i -> (i % 239).toByte() }
        val disk = RecordingDisk(sectorCount = sectorCount.toULong(), sectorSize = sectorSize)
        val map = RescueMap.forRestore(sectorCount.toULong(), sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("periodic-cp-boom")
        var cpCalls = 0

        try {
            Rescue.restore(
                source,
                disk,
                map,
                identity = "TEST-DISK",
                confirmation = confirm(disk),
                checkpointEverySectors = 128UL,
                checkpoint = {
                    cpCalls++
                    if (cpCalls == 1) throw IllegalStateException("checkpoint boom")
                },
                operation = op,
            )
            fail("expected periodic checkpoint exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("checkpoint boom", e.message)
        }

        assertEquals(listOf(0L to 256), disk.writtenRanges)
        assertEquals(2L, disk.syncCount)
        assertEquals(2, cpCalls)
        assertEquals(0L, disk.readCount)
        assertEquals(OperationState.Partial, op.state)
        assertEquals("checkpoint boom", op.failureCause?.message)
        assertFalse(op.commitReached)
    }

    @Test fun finalCheckpointFailureAfterVerifiedRestoreMarksPartialWithoutCommit() {
        val sectorSize = 512
        val image = ByteArray(2 * sectorSize) { 0x61 }
        val disk = RecordingDisk(sectorCount = 2UL, sectorSize = sectorSize)
        val map = RescueMap.forRestore(2UL, sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("final-cp-boom")

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            checkpoint = { throw IllegalStateException("final cp boom") },
            operation = op,
        )

        assertEquals(1L, disk.syncCount)
        assertEquals(1L, disk.readCount)
        assertEquals(OperationState.Partial, op.state)
        assertEquals("final cp boom", op.failureCause?.message)
        assertFalse(op.commitReached)
    }

    // endregion

    // region Cancellation semantics

    @Test fun cancelBeforeFirstWriteLeavesZeroIoAndMarksPartialWithCheckpoint() {
        val sectorSize = 512
        val image = ByteArray(4 * sectorSize) { 0x19 }
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = sectorSize)
        val map = RescueMap.forRestore(4UL, sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("pre-cancelled", active = { false })
        val published = mutableListOf<RescueMap>()

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            checkpoint = { published.add(it.snapshot()) },
            operation = op,
        )

        assertEquals(0L, disk.writeCount)
        assertEquals(0L, disk.readCount)
        assertEquals(0L, disk.syncCount)
        assertFalse(op.mutationAttempted)
        assertFalse(op.commitReached)
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause is ImageCancelledException)
        assertEquals(1, published.size)
        assertEquals(listOf(0UL until 4UL), published.single().nonTried)
    }

    @Test fun cancelInsideSubSectorMediumFallbackStopsRemainingSectors() {
        val sectorSize = 512
        val image = ByteArray(4 * sectorSize) { i -> (i / sectorSize + 10).toByte() }
        val writeAttempts = mutableListOf<Pair<ULong, Int>>()
        var cancelled = false
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = sectorSize) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    val count = source.remaining() / sectorSize
                    writeAttempts += lba to count
                    if (count > 1) {
                        throw DiskException.SenseError(
                            responseCode = 0x70,
                            senseKey = 0x03, // medium error triggers per-sector retry
                            asc = 0x11,
                            ascq = 0x00,
                        )
                    }
                    super.write(lba, source)
                }
            }
        disk.writeHook = { cancelled = true }
        val map = RescueMap.forRestore(4UL, sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("cancel-sub-sector") { !cancelled }

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            operation = op,
        )

        assertEquals(listOf(0UL to 4, 0UL to 1), writeAttempts)
        assertEquals(listOf(0UL), disk.writes)
        assertEquals(1L, disk.syncCount)
        assertEquals(0L, disk.readCount)
        assertEquals(listOf(0UL until 1UL), map.copied)
        assertEquals(listOf(1UL until 4UL), map.nonTried)
        assertTrue(map.failed.isEmpty())
        assertTrue(op.mutationAttempted)
        assertFalse(op.commitReached)
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause is ImageCancelledException)
    }

    @Test fun cancelDuringFinalSyncSkipsVerifyRestoredAndMarksPartial() {
        val sectorSize = 512
        val image = ByteArray(2 * sectorSize) { 0x42 }
        var cancelled = false
        val disk = RecordingDisk(sectorCount = 2UL, sectorSize = sectorSize)
        disk.syncHook = { cancelled = true }
        val map = RescueMap.forRestore(2UL, sectorSize = sectorSize, identity = "TEST-DISK")
        val source = ImageSource.fromBytes(image, format = ImageFormat.IMG, decodedSize = image.size.toLong())
        val op = DiskOperation("cancel-during-sync") { !cancelled }
        var checkpoints = 0

        Rescue.restore(
            source,
            disk,
            map,
            identity = "TEST-DISK",
            confirmation = confirm(disk),
            checkpoint = { checkpoints++ },
            operation = op,
        )

        assertEquals(1L, disk.syncCount)
        assertEquals(0L, disk.readCount)
        assertEquals(1, checkpoints)
        assertFalse(map.partial)
        assertFalse(op.commitReached)
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.failureCause is ImageCancelledException)
    }

    // endregion
}
