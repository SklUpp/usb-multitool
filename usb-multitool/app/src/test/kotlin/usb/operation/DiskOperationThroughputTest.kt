package usb.operation

import org.junit.Assert.assertEquals
import org.junit.Test

class DiskOperationThroughputTest {
    @Test fun firstSampleKeepsSpeedZero() {
        val op = DiskOperation("t")
        op.report(100L, 1_000_000_000L)
        assertEquals(0L, op.bytesPerSecond)
        assertEquals(100L, op.bytesDone)
    }

    @Test fun steadySamplesYieldRate() {
        val op = DiskOperation("t")
        op.report(0L, 0L)
        op.report(1_000L, 1_000_000_000L)
        assertEquals(1_000L, op.bytesPerSecond)
    }

    @Test fun zeroElapsedKeepsSpeedZero() {
        val op = DiskOperation("t")
        op.report(0L, 5L)
        op.report(100L, 5L)
        assertEquals(0L, op.bytesPerSecond)
        assertEquals(100L, op.bytesDone)
    }

    @Test fun stallResetsToZero() {
        val op = DiskOperation("t")
        op.report(0L, 0L)
        op.report(1_000L, 1_000_000_000L)
        assertEquals(1_000L, op.bytesPerSecond)
        op.report(1_000L, 2_000_000_000L)
        assertEquals(0L, op.bytesPerSecond)
    }

    @Test fun newFileResetsWindow() {
        val op = DiskOperation("t")
        op.report(0L, 0L)
        op.report(2_000L, 1_000_000_000L)
        assertEquals(2_000L, op.bytesPerSecond)
        op.beginCurrentFile(500L)
        assertEquals(0L, op.bytesPerSecond)
        assertEquals(0L, op.bytesDone)
        assertEquals(500L, op.bytesTotal)
        op.report(250L, 9_000_000_000L)
        assertEquals(0L, op.bytesPerSecond)
        op.report(500L, 10_000_000_000L)
        assertEquals(250L, op.bytesPerSecond)
    }

    @Test fun phaseSnapshotResetsCountersTogether() {
        val op = DiskOperation("t")
        op.beginCurrentFile(1_000L)
        op.report(1_000L, 1_000_000_000L)
        op.beginPhase(DiskOperationPhase.Verify, 2_000L)
        val snapshot = op.progressSnapshot()
        assertEquals(0L, snapshot.bytesDone)
        assertEquals(2_000L, snapshot.bytesTotal)
        assertEquals(DiskOperationPhase.Verify, snapshot.phase)
        assertEquals(0L, snapshot.bytesPerSecond)
    }

    @Test fun largeDeltasDoNotOverflow() {
        val op = DiskOperation("t")
        op.report(0L, 0L)
        op.report(Long.MAX_VALUE, 1_000_000_000L)
        assertEquals(Long.MAX_VALUE, op.bytesPerSecond)
    }
}
