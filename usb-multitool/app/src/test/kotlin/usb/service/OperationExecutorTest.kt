package usb.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test fun firstOperationStartsForegroundAndIdleStops() = runTest {
        val service = RecordingServiceControl()
        val executor = OperationExecutor(this, serviceControl = service)
        val done = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                assertEquals(1, service.startCount)
            }
        })
        assertEquals(OperationState.Completed, done.await())
        assertEquals(1, service.startCount)
        assertEquals(1, service.stopCount)
    }

    @Test fun cancellationExceptionNotWrappedAsIoFailure() = runTest {
        val executor = OperationExecutor(this)
        val handle = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                throw CancellationException("scope cancelled")
            }
        })
        assertEquals(OperationState.Cancelled, handle.await())
    }

    @Test fun controllerSurvivesIdleStopAndAcceptsLaterSubmit() = runTest {
        val service = RecordingServiceControl()
        val controller = DiskController(
            serviceControl = service,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )
        DiskController.setForTest(controller)
        try {
            val first = DiskController.get().submit(object : DiskOperation() {
                override suspend fun run() = Unit
            })
            assertEquals(OperationState.Completed, first.await())
            assertEquals(1, service.stopCount)

            // Idle stop must not kill controller — later submit still runs.
            val second = DiskController.get().submit(object : DiskOperation() {
                override suspend fun run() = Unit
            })
            assertEquals(OperationState.Completed, second.await())
            assertEquals(2, service.startCount)
            assertEquals(2, service.stopCount)
            assertSame(controller, DiskController.get())
        } finally {
            DiskController.setForTest(null)
            controller.shutdown()
        }
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

class RecordingServiceControl : ServiceControl {
    var startCount = 0
        private set
    var stopCount = 0
        private set

    override fun startForeground() {
        startCount++
    }

    override fun stopWhenIdle() {
        stopCount++
    }
}
