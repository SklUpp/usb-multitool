package usb.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.DiskException

class OperationExecutorTest {
    @Test fun secondOperationWaitsAndDisconnectStopsBoth() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                started.complete(Unit)
                awaitInactive()
            }
        })
        started.await()
        val second = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                // queued behind first; disconnect should fail it before run
            }
        })
        executor.onDisconnected()
        assertEquals(OperationState.Failed(DiskException.Disconnected()), first.await())
        assertEquals(OperationState.Failed(DiskException.Disconnected()), second.await())
    }

    @Test fun operationsRunSerially() = runTest {
        val executor = OperationExecutor(this)
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                order.add("first-start")
                firstStarted.complete(Unit)
                gate.await()
                order.add("first-end")
            }
        })
        val second = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                order.add("second")
            }
        })
        firstStarted.await()
        assertEquals(listOf("first-start"), order)
        gate.complete(Unit)
        assertEquals(OperationState.Completed, first.await())
        assertEquals(OperationState.Completed, second.await())
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test fun cancelMarksCooperativeCancel() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val handle = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                started.complete(Unit)
                awaitInactive()
            }
        })
        started.await()
        handle.cancel()
        assertEquals(OperationState.Cancelled, handle.await())
    }

    @Test fun wakeLockReleasedAfterCompletion() = runTest {
        val locks = RecordingWakeLock()
        val executor = OperationExecutor(this, wakeLock = locks)
        val done = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                assertTrue(locks.held)
            }
        })
        assertEquals(OperationState.Completed, done.await())
        assertFalse(locks.held)
        assertEquals(1, locks.acquireCount)
        assertEquals(1, locks.releaseCount)
    }
}

class RecordingWakeLock : OperationWakeLock {
    var held = false
        private set
    var acquireCount = 0
        private set
    var releaseCount = 0
        private set

    override fun acquire() {
        acquireCount++
        held = true
    }

    override fun release() {
        releaseCount++
        held = false
    }
}
