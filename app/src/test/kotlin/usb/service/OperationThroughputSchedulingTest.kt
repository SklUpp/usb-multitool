package usb.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import usb.operation.DiskOperation
import usb.operation.OperationState

class OperationThroughputSchedulingTest {
    @Test fun scheduledReportsProduceMonotonicSpeed() = runTest {
        val executor = OperationExecutor(this)
        var observed = -1L
        val operation = object : DiskOperation("scheduled") {
            override suspend fun run() {
                report(0L, 0L)
                report(2_000L, 1_000_000_000L)
                observed = bytesPerSecond
            }
        }
        val handle = executor.submit(operation)
        assertEquals(OperationState.Completed, handle.await())
        assertEquals(2_000L, observed)
        assertEquals(2_000L, operation.bytesPerSecond)
        assertEquals(2_000L, operation.bytesDone)
    }

    @Test fun cancelKeepsLastSpeedWithoutFabrication() = runTest {
        val executor = OperationExecutor(this)
        var observed = -1L
        val operation = object : DiskOperation("cancelled") {
            override suspend fun run() {
                report(0L, 0L)
                report(1_000L, 1_000_000_000L)
                observed = bytesPerSecond
                throw kotlinx.coroutines.CancellationException("user cancel")
            }
        }
        val handle = executor.submit(operation)
        assertEquals(OperationState.Cancelled, handle.await())
        assertEquals(1_000L, observed)
        assertEquals(1_000L, operation.bytesPerSecond)
        assertEquals(1_000L, operation.bytesDone)
    }

    @Test fun stalledSampleExpiresThroughMapper() = runTest {
        val executor = OperationExecutor(this)
        val operation = object : DiskOperation("stalled") {
            override suspend fun run() {
                report(0L, 0L)
                report(2_000L, 1_000_000_000L)
            }
        }
        val handle = executor.submit(operation)
        assertEquals(OperationState.Completed, handle.await())
        val freshAt = 1_000_000_000L + DiskOperation.THROUGHPUT_STALE_NANOS - 1L
        assertEquals(2_000L, operation.currentBytesPerSecond(freshAt))
        val stalledAt = 1_000_000_000L + DiskOperation.THROUGHPUT_STALE_NANOS + 1L
        assertEquals(0L, operation.currentBytesPerSecond(stalledAt))
        val token = OperationTokenGenerator.next()
        val fresh = usb.ui.readOnlyOperationProgress(
            token = token,
            outcome = OperationState.Running,
            title = usb.ui.OperationTitle.Upload,
            bytesDone = operation.bytesDone,
            bytesTotal = 4_000L,
            resultMessage = null,
            failureCause = null,
            cancelable = true,
            bytesPerSecond = operation.currentBytesPerSecond(freshAt),
        )
        val stalled = usb.ui.readOnlyOperationProgress(
            token = token,
            outcome = OperationState.Running,
            title = usb.ui.OperationTitle.Upload,
            bytesDone = operation.bytesDone,
            bytesTotal = 4_000L,
            resultMessage = null,
            failureCause = null,
            cancelable = true,
            bytesPerSecond = operation.currentBytesPerSecond(stalledAt),
        )
        assertEquals(2_000L, fresh.bytesPerSecond)
        assertEquals(1L, usb.remainingSeconds(fresh.bytesDone, fresh.bytesTotal, fresh.bytesPerSecond))
        assertEquals(0L, stalled.bytesPerSecond)
        assertEquals(null, usb.remainingSeconds(stalled.bytesDone, stalled.bytesTotal, stalled.bytesPerSecond))
    }
}
