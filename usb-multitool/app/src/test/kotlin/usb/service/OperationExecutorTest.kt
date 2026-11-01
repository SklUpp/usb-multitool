package usb.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
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
        service.awaitStop(1)
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
            service.awaitStop(1)
            assertEquals(1, service.stopCount)

            // Idle stop must not kill controller — later submit still runs.
            val second = DiskController.get().submit(object : DiskOperation() {
                override suspend fun run() = Unit
            })
            assertEquals(OperationState.Completed, second.await())
            service.awaitStop(2)
            assertEquals(2, service.startCount)
            assertEquals(2, service.stopCount)
            assertSame(controller, DiskController.get())
        } finally {
            DiskController.setForTest(null)
            controller.shutdown()
        }
    }

    @Test fun noRecursiveServiceStartFromLifecycle() = runTest {
        var requestStarts = 0
        var promoteCalls = 0
        var stopCalls = 0
        val executor = OperationExecutor(this)
        val lifecycle = DiskServiceLifecycle(
            requestStart = {
                requestStarts++
                // Mimic DiskService.onStartCommand: promote only, never re-enter startForegroundService.
                promoteCalls++
            },
            stopService = { stopCalls++ },
            hasOutstandingWork = { executor.hasOutstandingWork() },
        )
        executor.bind(NoOpWakeLock, lifecycle)

        val done = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                assertEquals(1, requestStarts)
                assertEquals(1, promoteCalls)
                assertTrue(lifecycle.hasRequestedStart())
            }
        })
        assertEquals(OperationState.Completed, done.await())
        assertEquals(1, requestStarts)
        assertEquals(1, promoteCalls)
        assertEquals(1, stopCalls)
        assertFalse(lifecycle.hasRequestedStart())
    }

    @Test fun queuedSecondOperationSurvivesFirstCompletion() = runTest {
        var stopCalls = 0
        val executor = OperationExecutor(this)
        val lifecycle = DiskServiceLifecycle(
            requestStart = {},
            stopService = {
                assertFalse(
                    "stopWhenIdle while work outstanding",
                    executor.hasOutstandingWork(),
                )
                stopCalls++
            },
            hasOutstandingWork = { executor.hasOutstandingWork() },
        )
        executor.bind(NoOpWakeLock, lifecycle)

        val gate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                firstStarted.complete(Unit)
                gate.await()
            }
        })
        firstStarted.await()
        val second = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                // Survived first completion — service must still be up.
                assertEquals(0, stopCalls)
                secondStarted.complete(Unit)
            }
        })
        advanceUntilIdle()
        assertTrue(executor.hasOutstandingWork())
        gate.complete(Unit)
        assertEquals(OperationState.Completed, first.await())
        assertEquals(OperationState.Completed, second.await())
        secondStarted.await()
        assertEquals(1, stopCalls)
        assertFalse(executor.hasOutstandingWork())
    }

    @Test fun controllerBindIsolatesFromPublicFieldClobber() = runTest {
        val testService = RecordingServiceControl()
        val controller = DiskController(
            serviceControl = testService,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )
        // Public fields gone — only bind API can rebind. Service cannot clobber via field write.
        val clobber = RecordingServiceControl()
        // Attempting field assignment must not compile; verify bind isolation instead:
        // after service-like bind, original RecordingServiceControl is replaced only via bind.
        controller.bind(NoOpWakeLock, clobber)
        val done = controller.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, done.await())
        clobber.awaitStop(1)
        assertEquals(0, testService.startCount)
        assertEquals(0, testService.stopCount)
        assertEquals(1, clobber.startCount)
        assertEquals(1, clobber.stopCount)

        // Rebind back to test controller — service cannot leave stale control unexpectedly.
        controller.bind(NoOpWakeLock, testService)
        val again = controller.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, again.await())
        testService.awaitStop(1)
        assertEquals(1, testService.startCount)
        assertEquals(1, testService.stopCount)
        assertEquals(1, clobber.startCount)
        controller.shutdown()
    }

    @Test fun destroyedServiceUnbindLeavesNewerBindingActive() = runTest {
        // Mimic DiskService: old instance destroys after newer instance already bound.
        val oldWake = RecordingWakeLock()
        val oldLifecycle = RecordingServiceControl()
        val newWake = RecordingWakeLock()
        val newLifecycle = RecordingServiceControl()
        val controller = DiskController(
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )

        controller.bind(oldWake, oldLifecycle)
        controller.bind(newWake, newLifecycle)

        // Stale onDestroy must not clobber the active binding.
        controller.unbind(oldLifecycle)

        val done = controller.submit(object : DiskOperation() {
            override suspend fun run() {
                assertTrue(newWake.held)
            }
        })
        assertEquals(OperationState.Completed, done.await())
        newLifecycle.awaitStop(1)
        assertEquals(1, newLifecycle.startCount)
        assertEquals(1, newLifecycle.stopCount)
        assertEquals(0, oldLifecycle.startCount)
        assertEquals(0, oldLifecycle.stopCount)
        assertEquals(1, newWake.acquireCount)
        assertEquals(1, newWake.releaseCount)
        assertEquals(0, oldWake.acquireCount)

        // Own unbind still clears when identity matches.
        controller.unbind(newLifecycle)
        val after = controller.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, after.await())
        // NoOpServiceControl after matching unbind — counts stay frozen on newLifecycle.
        assertEquals(1, newLifecycle.startCount)
        assertEquals(1, newLifecycle.stopCount)
        controller.shutdown()
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

    suspend fun awaitStop(expected: Int = 1) {
        withTimeout(2000) {
            while (stopCount < expected) {
                delay(10)
            }
        }
    }
}
