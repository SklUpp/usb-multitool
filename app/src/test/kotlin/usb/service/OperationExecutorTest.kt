package usb.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.DiskException
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.operation.syncDestructiveDisk
import usb.files.FilesOperations
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume

class OperationExecutorTest {
    @Test fun eachSubmitGetsUniqueTokenAndQueuedCancelIsAddressed() = runTest {
        val executor = OperationExecutor(this)
        val gate = CompletableDeferred<Unit>()
        val first = executor.submit(object : DiskOperation("first") {
            override suspend fun run() = gate.await()
        })
        val second = executor.submit(object : DiskOperation("second") {
            override suspend fun run() = Unit
        })
        assertTrue(first.token != second.token)
        second.cancel()
        assertEquals(OperationState.Cancelled, second.await())
        gate.complete(Unit)
        assertEquals(OperationState.Completed, first.await())
    }

    @Test fun metadataReadDoesNotRaiseForegroundServiceButLongIoDoes() = runTest {
        var starts = 0
        var wakeAcquires = 0
        val control = object : ServiceControl {
            override suspend fun startForeground() { starts++ }
            override fun stopWhenIdle() = Unit
        }
        val wake = object : OperationWakeLock {
            override fun acquire() { wakeAcquires++ }
            override fun release() = Unit
        }
        val executor = OperationExecutor(this, wake, control)
        executor
            .submit(
                object : DiskOperation("probe") {
                    override val foregroundService: Boolean = false
                    override suspend fun run() = Unit
                },
            )
            .await()
        assertEquals("a short metadata read must not post the service notification", 0, starts)
        assertEquals(0, wakeAcquires)
        executor.submit(object : DiskOperation("write") { override suspend fun run() = Unit }).await()
        assertEquals("long I/O still owns the foreground service", 1, starts)
        assertEquals(1, wakeAcquires)
    }

    @Test fun terminalHandleIgnoresLateCancel() = runTest {
        val executor = OperationExecutor(this)
        val handle = executor.submit(object : DiskOperation("done") {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, handle.await())
        handle.cancel()
        assertEquals(OperationState.Completed, handle.state)
    }
    @Test fun renameSyncFailureAfterMarkedMutationIsPartial() = runTest {
        val volume = object : UsbFsVolume {
            var renamed = false

            override fun list(path: String) = emptyList<UsbFsDirEntry>()
            override fun stat(path: String): UsbFsDirEntry = error("unused")
            override fun statOrNull(path: String): UsbFsDirEntry? = null
            override fun mkdir(path: String) = Unit
            override fun delete(path: String) = Unit
            override fun rename(from: String, to: String) { renamed = true }
            override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
            override fun write(
                path: String,
                data: ByteArray,
                offset: Long,
                create: Boolean,
                plannedTotalSize: Long,
                exclusiveCreate: Boolean,
            ) = Unit
            // rename may create both links and still fail; post-rename sync failure
            // leaves the change on disk.
            override fun sync(): Unit = throw java.io.IOException("sync after rename failed")
            override fun close() = Unit
        }
        val executor = OperationExecutor(this)
        val operation = object : DiskOperation("Rename ubuntu.iso") {
            override suspend fun run() {
                // Same ordering as MainActivity.renameEntry: mark after binding checks,
                // immediately before the engine rename.
                mutationAttempted = true
                FilesOperations.rename(volume, "/ubuntu.iso", "/linux.iso")
            }
        }
        assertEquals(OperationState.Partial, executor.submit(operation).await())
        assertTrue(volume.renamed)
        assertTrue(operation.failureCause is java.io.IOException)
    }

    @Test fun cancelAfterCommitReportsCompletedNotCancelled() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = object : DiskOperation("Import image") {
            override suspend fun run() {
                started.complete(Unit)
                release.await()
                mutationAttempted = true
                commitReached = true
            }
        }
        val handle = executor.submit(operation)
        started.await()
        handle.cancel()
        release.complete(Unit)
        assertEquals(OperationState.Completed, handle.await())
    }

    @Test
    fun cancelDuringNativeFormatWaitsForEngineAndReportsCommittedSync() = runTest {
        val executor = OperationExecutor(this)
        val disk = usb.testutil.RecordingDisk(sectorCount = 16UL)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = object : DiskOperation("Format partition") {
            override suspend fun run() {
                // Write tracking marks mutation before the blocking native mkfs call.
                mutationAttempted = true
                started.complete(Unit)
                // Stand in for native mkfs: handle.cancel() cannot interrupt this call.
                release.await()
                syncDestructiveDisk(disk)
            }
        }
        val handle = executor.submit(operation)
        started.await()
        handle.cancel()
        val awaiting = launch { handle.await() }
        advanceUntilIdle()
        assertTrue("cancel cannot complete while native mkfs is blocked", awaiting.isActive)
        release.complete(Unit)

        assertEquals(OperationState.Completed, handle.await())
        awaiting.join()
        assertTrue(operation.commitReached)
        assertEquals(1L, disk.syncCount)
    }

    @Test
    fun cancelDuringNativeFormatFailureReportsPartialAfterMutation() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = object : DiskOperation("Format partition") {
            override suspend fun run() {
                mutationAttempted = true
                started.complete(Unit)
                release.await()
                error("native mkfs failed after writing metadata")
            }
        }
        val handle = executor.submit(operation)
        started.await()
        handle.cancel()
        release.complete(Unit)

        assertEquals(OperationState.Partial, handle.await())
        assertFalse(operation.commitReached)
    }

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
                CompletableDeferred(Unit)
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
            requestStart = { CompletableDeferred(Unit) },
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
    @Test fun partialStatePreservedOnCancellationAndGenericFailure() = runTest {
        val executor = OperationExecutor(this)
        val cancelled = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                state = OperationState.Partial
                throw CancellationException("after partial")
            }
        })
        assertEquals(OperationState.Partial, cancelled.await())
        val failed = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                state = OperationState.Partial
                throw IllegalStateException("after partial")
            }
        })
        assertEquals(OperationState.Partial, failed.await())
    }


    @Test fun processIoReportsFailureOnce() = runTest {
        val failure = IllegalStateException("close failed")
        val reported = mutableListOf<Throwable>()
        val controller = DiskController(
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            failureReporter = { reported.add(it) },
        )
        try {
            controller.runProcessIo { throw failure }
            advanceUntilIdle()
            assertEquals(listOf(failure), reported)
        } finally {
            controller.shutdown()
        }
    }

    @Test fun foregroundWorkWaitsForReadinessWithoutBlockingOtherWork() = runTest {
        val ready = CompletableDeferred<Unit>()
        var wakeAcquires = 0
        val executor = OperationExecutor(
            this,
            wakeLock = object : OperationWakeLock {
                override fun acquire() { wakeAcquires++ }
                override fun release() = Unit
            },
            serviceControl = object : ServiceControl {
                override suspend fun startForeground() { ready.await() }
                override fun stopWhenIdle() = Unit
            },
        )
        val foreground = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        val metadata = executor.submit(object : DiskOperation() {
            override val foregroundService = false
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, metadata.await())
        assertEquals(0, wakeAcquires)
        ready.complete(Unit)
        assertEquals(OperationState.Completed, foreground.await())
        assertEquals(1, wakeAcquires)
    }

    @Test fun foregroundReadinessRunsWhileDiskWorkerIsBlocked() = runBlocking {
        val diskDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + diskDispatcher)
        val metadataStarted = CountDownLatch(1)
        val releaseMetadata = CountDownLatch(1)
        try {
            val startCalled = CompletableDeferred<Unit>()
            val control = object : ServiceControl {
                override suspend fun startForeground() {
                    startCalled.complete(Unit)
                }

                override fun stopWhenIdle() = Unit
            }
            val executor = OperationExecutor(scope, serviceControl = control)
            val metadata = executor.submit(
                object : DiskOperation("probe") {
                    override val foregroundService: Boolean = false
                    override suspend fun run() {
                        metadataStarted.countDown()
                        check(releaseMetadata.await(10, TimeUnit.SECONDS))
                    }
                },
            )
            assertTrue(metadataStarted.await(2, TimeUnit.SECONDS))
            val foreground = executor.submit(object : DiskOperation("write") {
                override suspend fun run() = Unit
            })
            // The single disk thread is blocked in metadata; readiness must still
            // be requested from its own dispatcher.
            withTimeout(2_000) { startCalled.await() }
            releaseMetadata.countDown()
            assertEquals(OperationState.Completed, metadata.await())
            assertEquals(OperationState.Completed, foreground.await())
        } finally {
            releaseMetadata.countDown()
            scope.cancel()
            diskDispatcher.close()
        }
    }

    @Test fun foregroundReadinessKeepsSubmissionOrder() = runBlocking {
        val diskDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + diskDispatcher)
        val firstStartEntered = CompletableDeferred<Unit>()
        val releaseFirstStart = CompletableDeferred<Unit>()
        val starts = AtomicInteger(0)
        val order = mutableListOf<String>()
        try {
            val control = object : ServiceControl {
                override suspend fun startForeground() {
                    if (starts.getAndIncrement() == 0) {
                        firstStartEntered.complete(Unit)
                        releaseFirstStart.await()
                    }
                }

                override fun stopWhenIdle() = Unit
            }
            val executor = OperationExecutor(scope, serviceControl = control)
            val first = executor.submit(object : DiskOperation("write A") {
                override suspend fun run() { order.add("A") }
            })
            withTimeout(2_000) { firstStartEntered.await() }
            val second = executor.submit(object : DiskOperation("write B") {
                override suspend fun run() { order.add("B") }
            })
            // A's readiness is blocked; B must not overtake it. Give an
            // independent readiness lane time to run before asserting.
            delay(200)
            assertEquals(1, starts.get())
            releaseFirstStart.complete(Unit)
            assertEquals(OperationState.Completed, first.await())
            assertEquals(OperationState.Completed, second.await())
            assertEquals(listOf("A", "B"), order)
        } finally {
            releaseFirstStart.complete(Unit)
            scope.cancel()
            diskDispatcher.close()
        }
    }

    @Test fun foregroundServiceStartFailureFailsOperation() = runTest {
        val failingControl = object : ServiceControl {
            override suspend fun startForeground() {
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

    @Test fun staleDisconnectStopsForegroundAfterLastRejectedSubmit() = runTest {
        val service = RecordingServiceControl()
        val executor = OperationExecutor(this, serviceControl = service)
        var rejected: OperationHandle? = null
        val first = executor.submit(object : DiskOperation() {
            override suspend fun run() {
                // Disconnect lands before this op returns, so the drain idle check
                // sees the counted-but-not-enqueued submit and cannot stop.
                executor.onDisconnected()
                rejected = executor.submit(object : DiskOperation() {
                    override suspend fun run() {
                        fail("rejected submit must not run")
                    }
                })
            }
        })
        assertEquals(OperationState.Failed(DiskException.Disconnected()), first.await())
        assertEquals(OperationState.Failed(DiskException.Disconnected()), rejected!!.await())
        advanceUntilIdle()
        assertFalse(executor.hasOutstandingWork())
        // Stopped exactly once, and only after the rejected submit dropped the count.
        assertEquals(listOf("start", "stop"), service.events)
        assertEquals(1, service.stopCount)

        // Idle stop must not kill later work.
        executor.onConnected("dev1")
        advanceUntilIdle()
        val next = executor.submit(object : DiskOperation() {
            override suspend fun run() = Unit
        })
        assertEquals(OperationState.Completed, next.await())
        service.awaitStop(2)
        assertEquals(listOf("start", "stop", "start", "stop"), service.events)
    }

    @Test fun serviceLifecycleInterleavingDoesNotDropForeground() = runTest {
        var starts = 0
        var stops = 0
        var outstandingWork = false
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++; CompletableDeferred(Unit) },
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

    @Test fun serviceAlreadyForegroundDoesNotCallRequestStart() = runTest {
        var starts = 0
        var isForeground = true
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++; CompletableDeferred(Unit) },
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


    @Test fun mutationReturnsCompletedNormally() = runTest {
        val executor = OperationExecutor(this)
        val op = object : DiskOperation() { override suspend fun run() { mutationAttempted = true } }
        assertEquals(OperationState.Completed, executor.submit(op).await())
    }

    @Test fun lateCancelAfterMutationIsPartialButBeforeMutationIsCancelled() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val op = object : DiskOperation() {
            override suspend fun run() { mutationAttempted = true; started.complete(Unit); gate.await() }
        }
        val handle = executor.submit(op)
        started.await()
        handle.cancel()
        gate.complete(Unit)
        assertEquals(OperationState.Partial, handle.await())
        assertTrue(op.failureCause is CancellationException)

        val clean = object : DiskOperation() { override suspend fun run() { throw CancellationException("before") } }
        assertEquals(OperationState.Cancelled, executor.submit(clean).await())
    }

    @Test fun failureAfterMutationIsPartial() = runTest {
        val executor = OperationExecutor(this)
        val op = object : DiskOperation() { override suspend fun run() { mutationAttempted = true; error("close") } }
        assertEquals(OperationState.Partial, executor.submit(op).await())
    }
    @Test fun lateDisconnectAfterMutationIsPartialWithCause() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val operation = object : DiskOperation() {
            override suspend fun run() { mutationAttempted = true; started.complete(Unit); gate.await() }
        }
        val handle = executor.submit(operation)
        started.await()
        executor.onDisconnected()
        executor.onConnected("new-device")
        gate.complete(Unit)
        assertEquals(OperationState.Partial, handle.await())
        assertTrue(operation.failureCause is DiskException.Disconnected)
    }
    @Test fun lateDisconnectPreservesExistingCause() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val original = IllegalStateException("original mutation failure")
        val operation = object : DiskOperation() {
            override suspend fun run() {
                mutationAttempted = true
                failureCause = original
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(operation)
        started.await()
        executor.onDisconnected()
        gate.complete(Unit)
        assertEquals(OperationState.Partial, handle.await())
        assertSame(original, operation.failureCause)
    }

@Test fun cancelThrowAfterCommitReachedIsCompleted() = runTest {
        val executor = OperationExecutor(this)
        val op = object : DiskOperation() {
            override suspend fun run() {
                mutationAttempted = true
                commitReached = true
                throw CancellationException("late cancel after commit")
            }
        }
        assertEquals(OperationState.Completed, executor.submit(op).await())
    }

@Test fun closeFailureAfterCompletedIsPartialNotCompleted() = runTest {
        val executor = OperationExecutor(this)
        val closeErr = DiskException.IoFailure("close failed")
        val op = object : DiskOperation() {
            override suspend fun run() {
                mutationAttempted = true
                state = OperationState.Completed
                throw closeErr
            }
        }
        assertEquals(OperationState.Partial, executor.submit(op).await())
        assertSame(closeErr, op.failureCause)
    }

@Test fun closeThrowAfterExplicitFailedPreservesPrimaryAndSuppressesClose() = runTest {
        val executor = OperationExecutor(this)
        val primary = IllegalStateException("table read failed")
        val closeErr = DiskException.IoFailure("close failed")
        val op = object : DiskOperation() {
            override suspend fun run() {
                state = OperationState.Failed(primary)
                failureCause = primary
                throw closeErr
            }
        }
        val outcome = executor.submit(op).await()
        assertTrue(outcome is OperationState.Failed)
        assertSame(primary, (outcome as OperationState.Failed).cause)
        assertSame(primary, op.failureCause)
        assertTrue(
            "close must be suppressed on primary, not dropped",
            primary.suppressed.any { it === closeErr } ||
                primary.suppressed.any { it.message == closeErr.message },
        )
        // Close must not become the only visible cause.
        assertFalse(outcome.cause === closeErr)
    }

@Test fun closeThrowAfterFailedWithMatchingCauseStillSuppresses() = runTest {
        val executor = OperationExecutor(this)
        val primary = DiskException.IoFailure("partition table unreadable")
        val closeErr = DiskException.Disconnected()
        val op = object : DiskOperation() {
            override suspend fun run() {
                state = OperationState.Failed(primary)
                failureCause = primary
                throw closeErr
            }
        }
        val outcome = executor.submit(op).await()
        assertTrue(outcome is OperationState.Failed)
        assertSame(primary, (outcome as OperationState.Failed).cause)
        assertTrue(primary.suppressed.any { it is DiskException.Disconnected })
    }

@Test fun commitReachedLateDisconnectClearsDisconnectFailureCause() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val op = object : DiskOperation() {
            override suspend fun run() {
                mutationAttempted = true
                commitReached = true
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(op)
        started.await()
        executor.onDisconnected()
        gate.complete(Unit)
        assertEquals(OperationState.Completed, handle.await())
        assertNull(op.failureCause)
    }

@Test fun commitReachedSurvivesLateDisconnect() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val op = object : DiskOperation() {
            override suspend fun run() {
                mutationAttempted = true
                commitReached = true
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(op)
        started.await()
        executor.onDisconnected()
        gate.complete(Unit)
        assertEquals(OperationState.Completed, handle.await())
    }

@Test fun explicitFailedNotMaskedByLateCancel() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val cause = IllegalStateException("table read failed")
        val op = object : DiskOperation() {
            override suspend fun run() {
                state = OperationState.Failed(cause)
                failureCause = cause
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(op)
        started.await()
        handle.cancel()
        gate.complete(Unit)
        val outcome = handle.await()
        assertTrue(outcome is OperationState.Failed)
        assertSame(cause, (outcome as OperationState.Failed).cause)
    }

@Test fun lateCancelAfterCompletedIsCompletedEvenWithMutation() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val op = object : DiskOperation() {
            override suspend fun run() {
                mutationAttempted = true
                state = OperationState.Completed
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(op)
        started.await()
        handle.cancel()
        gate.complete(Unit)
        assertEquals(OperationState.Completed, handle.await())
    }

@Test fun lateCancelAfterReadOnlyCompletedIsCompleted() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val op = object : DiskOperation() {
            override suspend fun run() {
                state = OperationState.Completed
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(op)
        started.await()
        handle.cancel()
        gate.complete(Unit)
        assertEquals(OperationState.Completed, handle.await())
    }

@Test fun lateDisconnectAfterCompletedClearsDisconnectFailureCause() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val op = object : DiskOperation() {
            override suspend fun run() {
                mutationAttempted = true
                state = OperationState.Completed
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(op)
        started.await()
        executor.onDisconnected()
        gate.complete(Unit)
        assertEquals(OperationState.Completed, handle.await())
        // UI feeds failureCause into Completed log/status — must not keep Disconnected noise.
        assertNull(op.failureCause)
    }

@Test fun lateDisconnectAfterCompletedIsCompleted() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val op = object : DiskOperation() {
            override suspend fun run() {
                mutationAttempted = true
                state = OperationState.Completed
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(op)
        started.await()
        executor.onDisconnected()
        gate.complete(Unit)
        assertEquals(OperationState.Completed, handle.await())
    }

@Test fun lateDisconnectAfterReadOnlyCompletedIsCompleted() = runTest {
        val executor = OperationExecutor(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val op = object : DiskOperation() {
            override suspend fun run() {
                state = OperationState.Completed
                started.complete(Unit)
                gate.await()
            }
        }
        val handle = executor.submit(op)
        started.await()
        executor.onDisconnected()
        gate.complete(Unit)
        assertEquals(OperationState.Completed, handle.await())
    }

    @Test fun eachSubmitGetsDistinctTokenIncludingSameOperation() = runTest {
        val executor = OperationExecutor(this)
        val gate = CompletableDeferred<Unit>()
        val operation = object : DiskOperation() {
            override suspend fun run() { gate.await() }
        }
        val first = executor.submit(operation)
        val second = executor.submit(operation)
        assertFalse(first.token == second.token)
        first.cancel()
        second.cancel()
        gate.complete(Unit)
        assertEquals(OperationState.Cancelled, first.await())
        assertEquals(OperationState.Cancelled, second.await())
    }

    @Test fun cancelTerminalRaceKeepsTerminalResult() = runTest {
        val executor = OperationExecutor(this)
        val returned = CompletableDeferred<Unit>()
        val operation = object : DiskOperation() {
            override suspend fun run() { returned.complete(Unit) }
        }
        val handle = executor.submit(operation)
        returned.await()
        handle.cancel()
        assertEquals(OperationState.Completed, handle.await())
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

    /** Start/stop order across the executor's lifetime. */
    val events = mutableListOf<String>()

    override suspend fun startForeground() {
        startCount++
        events.add("start")
    }

    override fun stopWhenIdle() {
        stopCount++
        events.add("stop")
    }

    suspend fun awaitStop(expected: Int = 1) {
        withTimeout(2000) {
            while (stopCount < expected) {
                delay(10)
            }
        }
    }
}
