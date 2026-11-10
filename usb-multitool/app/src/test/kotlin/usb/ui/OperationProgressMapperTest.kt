package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import usb.operation.OperationState as ServiceOperationState

class OperationProgressMapperTest {
    private val scan = ReadScanOutcome(
        sectorsRead = 3UL,
        totalSectors = 16UL,
        unreadableSectors = 0UL,
        failedRangeCount = 0,
        averageBytesPerSecond = 512L,
    )

    @Test fun partialPreservesProgressAndMessages() {
        val progress = readOnlyOperationProgress(
            ServiceOperationState.Partial, "scan", 4L, 10L, "saved", IllegalStateException("incomplete"), false,
        )
        assertEquals(OperationState.Partial, progress.state)
        assertEquals(4L, progress.bytesDone)
        assertEquals("saved", progress.resultMessage)
        assertEquals("incomplete", progress.errorMessage)
        assertEquals(false, progress.cancelable)
        assertNull(progress.readScanResult)
    }

    @Test fun failedUsesOutcomeCauseMessage() {
        val progress = readOnlyOperationProgress(
            ServiceOperationState.Failed(IllegalArgumentException("bad disk")), "scan", 4L, 10L, null, null, true,
        )
        assertEquals(OperationState.Failed, progress.state)
        assertEquals("bad disk", progress.errorMessage)
        assertNull(progress.readScanResult)
    }

    @Test fun queuedAndRunningKeepTheirState() {
        val queued = readOnlyOperationProgress(
            ServiceOperationState.Queued, "scan", 0L, 10L, "stale", null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Queued, queued.state)
        assertEquals(0L, queued.bytesDone)
        assertEquals(10L, queued.bytesTotal)
        assertNull(queued.resultMessage)
        // Non-terminal must not carry a finished scan summary.
        assertNull(queued.readScanResult)

        val running = readOnlyOperationProgress(
            ServiceOperationState.Running, "scan", 4L, 10L, "stale", null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Running, running.state)
        assertEquals(4L, running.bytesDone)
        assertNull(running.resultMessage)
        assertNull(running.readScanResult)
    }

    @Test fun cancelledKeepsProgressAndStructuredScan() {
        val progress = readOnlyOperationProgress(
            ServiceOperationState.Cancelled, "scan", 4L, 10L, null, null, false, readScanResult = scan,
        )
        assertEquals(OperationState.Cancelled, progress.state)
        assertEquals(false, progress.cancelable)
        assertEquals(4L, progress.bytesDone)
        assertEquals(10L, progress.bytesTotal)
        assertEquals(scan, progress.readScanResult)
    }

    @Test fun completedPreservesResultProgressAndScan() {
        val progress = readOnlyOperationProgress(
            ServiceOperationState.Completed, "scan", 10L, 10L, null, null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Completed, progress.state)
        assertEquals(10L, progress.bytesDone)
        assertNull(progress.resultMessage)
        assertEquals(scan, progress.readScanResult)
    }
}
