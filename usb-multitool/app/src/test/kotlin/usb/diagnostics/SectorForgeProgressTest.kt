package usb.diagnostics
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.operation.DiskOperation

class SectorForgeProgressTest {
    @Test fun checkpointFailureIsRetainedInsteadOfCancellation() {
        val failure = IOException("checkpoint sync failed")
        val map = RescueMap.forCopy(2UL, sectorSize = 512)
        val idleSink = object : RescueSink { override fun writeSector(lba: ULong, sector: ByteArray) = Unit }
        val progress = SectorForgeEngine.Progress(map, DiskOperation("copy"), 512, emptyList(), { throw failure }, idleSink, 1024)
        assertFalse(progress.onProgress(512, 0, 512, 512, longArrayOf(0, 512, 4, 512, 512, 0)))
        assertSame(failure, progress.failure)
    }

    @Test fun malformedSnapshotFailureIsRetained() {
        val map = RescueMap.forCopy(2UL, sectorSize = 512)
        val idleSink = object : RescueSink { override fun writeSector(lba: ULong, sector: ByteArray) = Unit }
        val progress = SectorForgeEngine.Progress(map, DiskOperation("copy"), 512, emptyList(), {}, idleSink, 1024)
        assertFalse(progress.onProgress(0, 0, 1024, 0, longArrayOf(1, 512, 4)))
        org.junit.Assert.assertTrue(progress.failure is IllegalArgumentException)
    }

    @Test fun resumePublishesUntriedBeforeAnyRetry() {
        val map = RescueMap.forCopy(3UL, sectorSize = 512, identity = "disk")
        map.markCopied(0UL, 1UL)
        map.markFailed(1UL, 1UL)
        val events = mutableListOf<String>()
        val sink = object : RescueSink {
            override fun writeSector(lba: ULong, sector: ByteArray) { events += "write" }
            override fun ensureLength(bytes: Long) { events += "extent" }
        }
        SectorForgeEngine.prepareResume(map, map.failed, sink, 1536) { snapshot ->
            events += "checkpoint"
            org.junit.Assert.assertEquals(listOf(1UL until 3UL), snapshot.nonTried)
            org.junit.Assert.assertEquals(listOf(0UL until 1UL), snapshot.copied)
        }
        org.junit.Assert.assertEquals(listOf("extent", "checkpoint"), events)
        org.junit.Assert.assertEquals("disk", map.identity)
    }

    @Test fun failedResumeCheckpointStopsBeforeRetry() {
        val map = RescueMap.forCopy(2UL, sectorSize = 512)
        map.markFailed(1UL, 1UL)
        val failure = IOException("sync failed")
        val sink = object : RescueSink { override fun writeSector(lba: ULong, sector: ByteArray) { error("unexpected write") } }
        try {
            SectorForgeEngine.prepareResume(map, map.failed, sink, 1024) { throw failure }
            org.junit.Assert.fail("expected checkpoint failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        org.junit.Assert.assertEquals(listOf(0UL until 2UL), map.nonTried)
    }

    @Test fun writerFailureMarksEntirePossiblyPartialRangeUncertain() {
        val uncertain = mutableListOf<ULongRange>()
        val sink = object : RescueSink {
            override fun writeSector(lba: ULong, sector: ByteArray) {
                throw IOException("short write after first sector")
            }
            override val positional = true
            override fun writeBatch(lba: ULong, bytes: ByteArray, length: Int, sectorSize: Int) {
                throw IOException("short write after first sector")
            }
        }
        val writer = SectorForgeEngine.Writer(sink, 512, uncertain)
        val buffer = ByteBuffer.allocate(1024)
        assertEquals(SectorForgeNative.STATUS_FATAL, writer.writeAt(512, buffer))
        assertEquals(listOf(1UL until 3UL), uncertain)
    }

    @Test fun cancelSnapshotKeepsUnreadRangesNonTried() {
        val map = RescueMap.forCopy(4UL, sectorSize = 512)
        val op = DiskOperation(name = "copy", active = { false })
        val sink = object : RescueSink {
            override fun writeSector(lba: ULong, sector: ByteArray) = Unit
        }
        val progress = SectorForgeEngine.Progress(map, op, 512, emptyList(), {}, sink, 2048)
        assertFalse(progress.onProgress(512, 0, 1536, 512, longArrayOf(0, 512, 4, 512, 1536, 0)))
        assertEquals(listOf(0UL until 4UL), map.nonTried)
        assertTrue(map.failed.isEmpty())
    }

    @Test fun fatalSnapshotKeepsMapNonTriedAndSkipsCheckpoint() {
        val map = RescueMap.forCopy(4UL, sectorSize = 512)
        val op = DiskOperation("copy")
        var checkpoints = 0
        val progress = SectorForgeEngine.Progress(
            map,
            op,
            512,
            emptyList(),
            { checkpoints++ },
            object : RescueSink {
                override fun writeSector(lba: ULong, sector: ByteArray) = Unit
            },
            2048,
        )
        assertFalse(progress.onProgress(0, 0, 2048, 0, longArrayOf(1, 512, SectorForgeBlocks.COPIED)))
        assertEquals(listOf(0UL until 4UL), map.nonTried)
        assertTrue(map.failed.isEmpty())
        assertEquals(0, checkpoints)
    }
}
