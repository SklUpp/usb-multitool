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
import org.junit.Assert.fail
import org.junit.Test
import usb.device.DiskException

class OperationExecutorTest {
    @Test fun disconnectWaitsForRunningWorkerAndPreservesPartial() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val operation = object : DiskOperation() {
            override suspend fun run() {
                runs++
                started.complete(Unit)
                release.await()
                state = OperationState.Partial
                failureCause = DiskException.Disconnected()
            }
        }
        val first = executor.submit(operation)
        started.await()
        val awaiting = launch { first.await() }
        executor.onDisconnected()
        advanceUntilIdle()
        assertTrue("running handle completed before worker return", awaiting.isActive)
        release.complete(Unit)
        assertEquals(OperationState.Partial, first.await())
        assertEquals(1, runs)
        assertTrue(operation.failureCause is DiskException.Disconnected)
        awaiting.join()
    }

    @Test fun disconnectFailsQueuedOperationWithoutStartingIt() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() { started.complete(Unit); release.await() }
        })
        started.await()
        var queuedRuns = 0
        val queued = executor.submit(object : DiskOperation() {
            override suspend fun run() { queuedRuns++ }
        })
        executor.onDisconnected()
        assertEquals(OperationState.Failed(DiskException.Disconnected()), queued.await())
        assertEquals(0, queuedRuns)
        release.complete(Unit)
        first.await()
    }

    @Test fun secondOperationWaitsAndDisconnectStopsQueuedOnly() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                started.complete(Unit)
                release.await()
                state = OperationState.Partial
            }
        })
        started.await()
        val second = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                fail("queued operation must not start after disconnect")
            }
        })
        executor.onDisconnected()
        assertEquals(OperationState.Failed(DiskException.Disconnected()), second.await())
        release.complete(Unit)
        assertEquals(OperationState.Partial, first.await())
    }
    @Test fun disconnectReconnectNormalReturnFailsWithoutPrematureCompletion() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = object : DiskOperation() {
            override suspend fun run() {
                started.complete(Unit)
                release.await()
            }
        }
        val handle = executor.submit(operation)
        started.await()
        val awaiting = launch { handle.await() }
        executor.onDisconnected()
        executor.onConnected("replacement")
        advanceUntilIdle()
        assertTrue(awaiting.isActive)
        release.complete(Unit)
        assertEquals(OperationState.Failed(DiskException.Disconnected()), handle.await())
        awaiting.join()
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
    @Test fun throwErrorFromRunFailsOperationAndWorkerAcceptsNextSubmit() = runTest {
        val executor = OperationExecutor(this)
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                throw java.lang.NoClassDefFoundError("FatFs native library missing")
            }
        })
        val firstOutcome = first.await()
        assertTrue(firstOutcome is OperationState.Failed)
        val failCause = (firstOutcome as OperationState.Failed).cause
        assertTrue(failCause is DiskException.IoFailure)
        assertTrue(failCause.message?.contains("FatFs native library missing") == true)

        // Worker must not be stuck — next submit must succeed
        val second = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, second.await())
    }

    @Test fun partialStatePreservedOnException() = runTest {
        val executor = OperationExecutor(this)
        val handle = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                state = OperationState.Partial
                throw DiskException.IoFailure("simulated short write")
            }
        })
        assertEquals(OperationState.Partial, handle.await())
    }

    @Test fun foregroundServiceStartFailureFailsOperation() = runTest {
        val failingControl = object : ServiceControl {
            override fun startForeground() {
                throw IllegalStateException("FGS not allowed")
            }
            override fun stopWhenIdle() = Unit
        }
        val executor = OperationExecutor(this, serviceControl = failingControl)
        val handle = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        val outcome = handle.await()
        assertTrue(outcome is OperationState.Failed)
        val cause = (outcome as OperationState.Failed).cause
        assertTrue(cause.message?.contains("Failed to start foreground service") == true)
    }

    @Test fun wakeLockAcquireFailureFailsOperationWithoutRunning() = runTest {
        val failingWakeLock = object : OperationWakeLock {
            override fun acquire() {
                throw SecurityException("WakeLock denied")
            }
            override fun release() = Unit
        }
        val executor = OperationExecutor(this, wakeLock = failingWakeLock)
        var hasRun = false
        val handle = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                hasRun = true
            }
        })
        val outcome = handle.await()
        assertTrue(outcome is OperationState.Failed)
        assertFalse(hasRun)
    }

    @Test fun disconnectRejectsSubsequentSubmitsImmediately() = runTest {
        val executor = OperationExecutor(this)
        val gate = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                gate.await()
            }
        })
        executor.onDisconnected()
        gate.complete(Unit)
        assertEquals(OperationState.Failed(DiskException.Disconnected()), first.await())

        val subsequent = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Failed(DiskException.Disconnected()), subsequent.await())
    }

    @Test fun serviceLifecycleInterleavingDoesNotDropForeground() {
        var starts = 0
        var stops = 0
        var outstandingWork = false
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++ },
            stopService = { stops++ },
            hasOutstandingWork = { outstandingWork },
        )

        // First start
        lifecycle.startForeground()
        assertEquals(1, starts)
        assertTrue(lifecycle.hasRequestedStart())

        // Work finishes and stopWhenIdle starts, but more work arrives
        outstandingWork = true
        lifecycle.stopWhenIdle()
        // Because hasOutstandingWork was true, stopService was not called
        assertEquals(0, stops)
        assertTrue(lifecycle.hasRequestedStart())
    }

    @Test fun serviceAlreadyForegroundDoesNotCallRequestStart() {
        var starts = 0
        var isForeground = true
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++ },
            stopService = {},
            hasOutstandingWork = { false },
            isServiceForeground = { isForeground },
        )
        lifecycle.startForeground()
        assertEquals(0, starts)

        // Second call while still foreground must not request start again
        lifecycle.startForeground()
        assertEquals(0, starts)
    }

    @Test fun quickReconnectDoesNotDiscardSubsequentOperations() = runTest {
        val executor = OperationExecutor(this)
        executor.onDisconnected()
        executor.onConnected()

        val afterReconnect = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, afterReconnect.await())
    }

    @Test fun pendingCancellationCompletesWithoutRunning() = runTest {
        val executor = OperationExecutor(this)
        val op = object : DiskOperation() {
            override suspend fun run() {
                fail("cancelled operation must not run")
            }
        }
        val handle = executor.submit(op)
        handle.cancel()
        assertEquals(OperationState.Cancelled, handle.await())
    }

    @Test fun oldQueuedOperationsDiscardedOnReconnect() = runTest {
        val executor = OperationExecutor(this)
        val firstStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                firstStarted.complete(Unit)
                gate.await()
            }
        })
        firstStarted.await()

        var staleRan = false
        val stale = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                staleRan = true
            }
        })
        kotlinx.coroutines.yield()

        executor.onDisconnected()
        executor.onConnected()
        gate.complete(Unit)

        var newRan = false
        val newOp = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                newRan = true
            }
        })
        assertEquals(OperationState.Completed, newOp.await())
        assertTrue(newRan)
        assertEquals(OperationState.Failed(DiskException.Disconnected()), stale.await())
        assertFalse(staleRan)
        assertEquals(OperationState.Failed(DiskException.Disconnected()), first.await())
    }

    @Test fun repeatedOnConnectedSameDeviceIsIdempotent() = runTest {
        val executor = OperationExecutor(this)
        executor.onConnected("dev1")
        val op1 = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        // Repeated attach/resume for same device must not abort queued or completed operations
        executor.onConnected("dev1")
        val op2 = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, op1.await())
        assertEquals(OperationState.Completed, op2.await())
    }

    @Test fun repeatedGenericOnConnectedIsIdempotent() = runTest {
        val executor = OperationExecutor(this)
        executor.onConnected()
        val op1 = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        executor.onConnected()
        val op2 = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, op1.await())
        assertEquals(OperationState.Completed, op2.await())
    }

    @Test fun deviceSwapWithoutDetachAbortsOldDeviceOperations() = runTest {
        val executor = OperationExecutor(this)
        executor.onConnected("dev1")

        val firstStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                firstStarted.complete(Unit)
                gate.await()
            }
        })
        firstStarted.await()

        var staleRan = false
        val stale = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                staleRan = true
            }
        })
        kotlinx.coroutines.yield()

        // Hot swap to dev2 without explicit onDisconnected
        executor.onConnected("dev2")
        gate.complete(Unit)

        var newRan = false
        val newOp = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                newRan = true
            }
        })
        assertEquals(OperationState.Completed, newOp.await())
        assertTrue(newRan)
        assertEquals(OperationState.Failed(DiskException.Disconnected()), stale.await())
        assertFalse(staleRan)
        assertEquals(OperationState.Failed(DiskException.Disconnected()), first.await())
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
