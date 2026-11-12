package usb.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.DiskException
import usb.device.UsbDisk
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.testutil.RecordingDisk

/**
 * Folder-backed archive must use the same durable boundary as the file backup:
 * after image bytes exist, a sync/checkpoint/close failure is Partial, never Completed.
 */
class RescueArchiveOperationsTest {
    private class FakeTarget(
        sectorSize: Int,
        private val failCheckpointAt: Int = -1,
        private val failClose: Boolean = false,
        private val failCanonical: Boolean = false,
    ) : RescueArchiveTarget {
        val image = ByteArrayOutputStream()
        override val sink: RescueSink = image.asRescueSink(sectorSize)
        val events = mutableListOf<String>()
        var syncs = 0
        var checkpoints = 0
        var closes = 0
        var canonicals = 0
        var lastCheckpoint: RescueMap? = null

        override fun syncImage() {
            syncs++
            events += "sync"
        }

        override fun writeCheckpoint(map: RescueMap) {
            checkpoints++
            events += "checkpoint"
            lastCheckpoint = map.snapshot()
            if (checkpoints == failCheckpointAt) {
                throw IOException("checkpoint publish failed")
            }
        }

        override fun closeArchive() {
            closes++
            events += "close"
            if (failClose) throw IOException("archive close failed")
        }

        override fun publishCanonical() {
            canonicals++
            events += "canonical"
            if (failCanonical) throw IOException("canonical publish failed")
        }
    }

    @Test
    fun safCheckpointIntervalKeepsDocumentChurnRare() {
        val interval512 = RescueArchiveOperations.checkpointEverySectors(512)
        val interval4096 = RescueArchiveOperations.checkpointEverySectors(4096)
        assertEquals(524_288UL, interval512)
        assertEquals(65_536UL, interval4096)
        // Sanity: 64 GiB ≈ 256 publications, 1 TiB ≈ 4096 (not tens of thousands).
        assertEquals(256L, (64L * 1024 * 1024 * 1024) / (interval512.toLong() * 512))
        assertEquals(4096L, (1024L * 1024 * 1024 * 1024) / (interval512.toLong() * 512))
    }

    /**
     * Host replacement for the native engine: reads the real disk in LBA order, writes
     * through the real sink/checkpoint callbacks, and stops on cancellation. `null` in
     * production keeps [Rescue.copy]; the SAF sink gate is exercised for real.
     */
    private fun hostEngine(
        disk: RecordingDisk,
    ): (UsbDisk, RescueSink, RescueMap, (RescueMap) -> Unit, DiskOperation) -> RescueMap =
        { _, sink, map, checkpoint, operation ->
            val sectorSize = disk.geometry.sectorSize
            val buffer = ByteBuffer.allocate(sectorSize)
            var wrote = false
            try {
                for (i in 0 until disk.geometry.sectorCount.toInt()) {
                    if (!operation.isActive()) {
                        operation.state = OperationState.Partial
                        break
                    }
                    buffer.clear()
                    disk.read(i.toULong(), buffer)
                    sink.writeBatch(i.toULong(), buffer.array(), sectorSize, sectorSize)
                    wrote = true
                    map.markCopied(i.toULong(), 1UL)
                    checkpoint(map.snapshot())
                }
            } finally {
                // Only an image that already has bytes is grown; a pre-image abort
                // must not fabricate a durable side-effect.
                if (wrote) sink.ensureLength(disk.geometry.capacityBytes.toLong())
            }
            // Production engine always publishes a final checkpoint, even on cancel.
            checkpoint(map.snapshot())
            map
        }

    private fun tinyMap(disk: RecordingDisk): RescueMap =
        RescueMap.forCopy(
            sectorCount = disk.geometry.sectorCount,
            sectorSize = disk.geometry.sectorSize,
            identity = "test-stick",
        )

    @Test
    fun cleanArchiveCompletesAfterFinalCheckpointAndClose() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val target = FakeTarget(512)
        val op = DiskOperation(name = "rescue-archive")

        RescueArchiveOperations.copy(disk, target, tinyMap(disk), op, engine = hostEngine(disk))

        assertEquals(OperationState.Completed, op.state)
        assertTrue(op.commitReached)
        assertTrue(op.mutationAttempted)
        assertEquals(4 * 512, target.image.size())
        assertTrue(target.syncs >= 1)
        assertTrue(target.checkpoints >= 1)
        assertEquals(1, target.closes)
        assertEquals(1, target.canonicals)
        assertFalse(target.lastCheckpoint?.partial ?: true)
        assertTrue(target.events.indexOf("sync") < target.events.indexOf("checkpoint"))
        assertTrue(target.events.indexOf("close") < target.events.indexOf("canonical"))
        assertEquals("canonical", target.events.last())
    }

    @Test
    fun checkpointFailureAfterImageBytesIsPartial() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val target = FakeTarget(512, failCheckpointAt = 1)
        val op = DiskOperation(name = "rescue-archive")

        try {
            RescueArchiveOperations.copy(disk, target, tinyMap(disk), op, engine = hostEngine(disk))
            fail("expected checkpoint failure")
        } catch (expected: IOException) {
            assertEquals("checkpoint publish failed", expected.message)
        }

        assertTrue(op.mutationAttempted)
        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
        assertTrue(target.closes >= 1)
        assertEquals(0, target.canonicals)
    }

    @Test
    fun closeFailureAfterImageBytesIsPartialAndNotCommitted() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val target = FakeTarget(512, failClose = true)
        val op = DiskOperation(name = "rescue-archive")

        try {
            RescueArchiveOperations.copy(disk, target, tinyMap(disk), op, engine = hostEngine(disk))
            fail("expected close failure")
        } catch (expected: IOException) {
            assertEquals("archive close failed", expected.message)
        }

        assertTrue(op.mutationAttempted)
        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
        assertEquals(0, target.canonicals)
    }

    @Test
    fun cancelLeavesCheckpointButNoCanonical() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val target = FakeTarget(512)
        val op = DiskOperation(name = "rescue-archive", active = { false })

        val map = RescueArchiveOperations.copy(disk, target, tinyMap(disk), op, engine = hostEngine(disk))

        assertTrue(map.partial)
        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
        assertTrue(target.checkpoints >= 1)
        assertEquals(0, target.canonicals)
    }

    @Test
    fun canonicalFailureIsPartialAndNotCommitted() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val target = FakeTarget(512, failCanonical = true)
        val op = DiskOperation(name = "rescue-archive")

        try {
            RescueArchiveOperations.copy(disk, target, tinyMap(disk), op, engine = hostEngine(disk))
            fail("expected canonical failure")
        } catch (expected: IOException) {
            assertEquals("canonical publish failed", expected.message)
        }

        assertEquals(1, target.canonicals)
        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
    }

    @Test
    fun preImageDisconnectDoesNotCommit() {
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: java.nio.ByteBuffer) {
                    throw DiskException.Disconnected()
                }
            }
        val target = FakeTarget(512)
        val op = DiskOperation(name = "rescue-archive")

        try {
            RescueArchiveOperations.copy(disk, target, tinyMap(disk), op, engine = hostEngine(disk))
        } catch (_: DiskException.Disconnected) {
            // Expected: no image byte reached the folder.
        }

        assertFalse(op.mutationAttempted)
        assertFalse(op.commitReached)
        assertEquals(0, target.canonicals)
    }
}
