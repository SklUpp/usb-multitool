package usb.service

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import usb.device.DiskException
import usb.operation.DiskOperation
import usb.operation.OperationState


interface OperationWakeLock {
    fun acquire()
    fun release()
}

object NoOpWakeLock : OperationWakeLock {
    override fun acquire() = Unit
    override fun release() = Unit
}

interface ServiceControl {
    fun startForeground()
    fun stopWhenIdle()
}

object NoOpServiceControl : ServiceControl {
    override fun startForeground() = Unit
    override fun stopWhenIdle() = Unit
}

interface OperationHandle {
    suspend fun await(): OperationState
    fun cancel()
}

class OperationExecutor(
    private val scope: CoroutineScope,
    @Volatile private var wakeLock: OperationWakeLock = NoOpWakeLock,
    @Volatile private var serviceControl: ServiceControl = NoOpServiceControl,
) {
    private val mutex = Mutex()
    private val queue = ArrayDeque<QueuedOp>()
    private val disconnected = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0)
    private var running = false
    @Volatile private var current: QueuedOp? = null
    @Volatile private var currentDeviceId: String? = null
    private val outstanding = AtomicInteger(0)
    /** Set under [mutex] by [stopIfIdleLocked]; cleared on bind/unbind and on enqueue. */
    @Volatile private var idleStopSignalled = false

    /** Rebind wake/service control. Not a public field write. */
    fun bind(wakeLock: OperationWakeLock, serviceControl: ServiceControl) {
        this.wakeLock = wakeLock
        this.serviceControl = serviceControl
        idleStopSignalled = false
    }

    /**
     * Drop wake/service binding only when [expectedServiceControl] is still active.
     * A destroyed service must not reset a newer service's binding.
     */
    fun unbind(expectedServiceControl: ServiceControl) {
        if (this.serviceControl === expectedServiceControl) {
            this.wakeLock = NoOpWakeLock
            this.serviceControl = NoOpServiceControl
            idleStopSignalled = false
        }
    }


    /** Queued or running operations remain. Safe for stopWhenIdle re-entry. */
    fun hasOutstandingWork(): Boolean = outstanding.get() > 0

    /**
     * Must hold [mutex]. Signals idle stop only when no queued, running, or
     * counted-but-not-yet-enqueued operation exists. Every path that drops the
     * last outstanding count (including rejected submits) must call this, or the
     * foreground service stays up with no work.
     */
    private fun stopIfIdleLocked(force: Boolean = false) {
        if (running || queue.isNotEmpty() || outstanding.get() > 0) return
        // A rejected submit can race an idle stop that the service control answered
        // by restarting, so it re-signals instead of trusting the flag.
        if (idleStopSignalled && !force) return
        serviceControl.stopWhenIdle()
        idleStopSignalled = true
    }

    fun submit(operation: DiskOperation): OperationHandle {
        val submitGen = connectionGeneration.get()
        val handle = QueuedOp(operation, generation = submitGen)
        // Count before async enqueue so a second submit cannot lose a race
        // against the first op's idle stopWhenIdle check.
        handle.markCounted()
        outstanding.incrementAndGet()

        scope.launch {
            val startWorker = mutex.withLock {
                if (disconnected.get() || connectionGeneration.get() != submitGen) {
                    handle.complete(OperationState.Failed(DiskException.Disconnected()))
                    stopIfIdleLocked(force = true)
                    return@withLock false
                }
                if (handle.cancelled.get() || !handle.operation.isActive()) {
                    handle.complete(OperationState.Cancelled)
                    stopIfIdleLocked(force = true)
                    return@withLock false
                }
                val wasEmpty = queue.isEmpty() && !running
                if (wasEmpty) {
                    try {
                        serviceControl.startForeground()
                    } catch (t: Throwable) {
                        handle.complete(
                            OperationState.Failed(
                                DiskException.IoFailure(
                                    "Failed to start foreground service: ${t.message ?: t.javaClass.simpleName}",
                                    t,
                                ),
                            ),
                        )
                        stopIfIdleLocked(force = true)
                        return@withLock false
                    }
                }
                // Serialized with stopIfIdleLocked: work queued after a concurrent
                // idle attempt must be able to stop the (re)started service later.
                idleStopSignalled = false
                queue.addLast(handle)
                !running
            }
            if (startWorker) drain()
        }
        return handle
    }

    fun onDisconnected() {
        val gen = connectionGeneration.incrementAndGet()
        disconnected.set(true)
        currentDeviceId = null
        scope.launch {
            var runningOp: QueuedOp? = null
            val toFail = mutableListOf<QueuedOp>()
            mutex.withLock {
                runningOp = current?.takeIf { it.generation <= gen }
                val iter = queue.iterator()
                while (iter.hasNext()) {
                    val op = iter.next()
                    if (op.generation <= gen) {
                        iter.remove()
                        toFail.add(op)
                    }
                }
            }
            runningOp?.recordDisconnect()
            for (op in toFail) {
                op.failDisconnected()
            }
            mutex.withLock {
                stopIfIdleLocked()
            }
        }
    }
    fun onConnected(deviceId: String? = null) {
        val same = !disconnected.get() &&
            ((deviceId != null && currentDeviceId == deviceId) || (deviceId == null && currentDeviceId == null))
        if (same) return
        currentDeviceId = deviceId
        val gen = connectionGeneration.incrementAndGet()
        disconnected.set(false)
        scope.launch {
            var runningOp: QueuedOp? = null
            val toFail = mutableListOf<QueuedOp>()
            mutex.withLock {
                runningOp = current?.takeIf { it.generation < gen }
                val iter = queue.iterator()
                while (iter.hasNext()) {
                    val op = iter.next()
                    if (op.generation < gen) {
                        iter.remove()
                        toFail.add(op)
                    }
                }
            }
            runningOp?.recordDisconnect()
            for (op in toFail) {
                op.failDisconnected()
            }
            mutex.withLock {
                stopIfIdleLocked()
            }
        }
    }


    private suspend fun drain() {
        while (true) {
            val op = mutex.withLock {
                if (running) return
                val next = queue.pollFirst()
                if (next == null) {
                    // Outstanding counted but not yet enqueued: leave worker stopped;
                    // that submit's launch will restart drain after enqueue.
                    stopIfIdleLocked()
                    return
                }
                if (disconnected.get() || next.generation != connectionGeneration.get()) {
                    next.failDisconnected()
                    return@withLock null
                }
                if (next.cancelled.get() || !next.operation.isActive()) {
                    next.complete(OperationState.Cancelled)
                    return@withLock null
                }
                running = true
                current = next
                next
            } ?: continue

            try {
                runOne(op)
            } finally {
                mutex.withLock {
                    running = false
                    current = null
                    if (queue.isEmpty()) {
                        stopIfIdleLocked()
                        // Counted submit still pending enqueue, if any — exit; it restarts drain.
                        return
                    }
                }
            }
        }
    }
    private suspend fun runOne(op: QueuedOp) {
        if (op.isDone()) return
        if (op.cancelled.get() || !op.operation.isActive()) {
            op.operation.markInactive()
            op.complete(OperationState.Cancelled)
            return
        }
        if (disconnected.get() || op.generation != connectionGeneration.get()) {
            op.failDisconnected()
            return
        }

        op.state = OperationState.Running
        try {
            wakeLock.acquire()
        } catch (t: Throwable) {
            op.complete(
                OperationState.Failed(
                    DiskException.IoFailure("Wake lock acquire failed: ${t.message ?: t.javaClass.simpleName}", t),
                ),
            )
            return
        }
        var terminal: OperationState = OperationState.Completed
        try {
            try {
                op.operation.run()
                terminal = classifyNormalReturn(op)
            } catch (e: DiskException) {
                attachSecondaryFailure(op, e)
                terminal = classifyThrown(op, cancelled = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Do not wrap cooperative/cancellation as IoFailure.
                // Still attach as suppressed when a primary failure already exists so close/
                // disconnect after Failed is not silent.
                if (op.operation.failureCause != null ||
                    op.operation.state is OperationState.Failed
                ) {
                    attachSecondaryFailure(op, e)
                } else if (op.operation.mutationAttempted &&
                    !op.operation.commitReached &&
                    op.operation.state != OperationState.Completed
                ) {
                    op.operation.failureCause = e
                }
                terminal = classifyThrown(op, cancelled = true)
            } catch (t: Throwable) {
                attachSecondaryFailure(op, t)
                terminal = classifyThrown(op, cancelled = false)
            }
        } finally {
            try {
                wakeLock.release()
            } catch (_: Throwable) {}
            clearSuccessNoiseCause(op, terminal)
            if (!op.isDone()) op.complete(terminal)
        }
    }

    /**
     * Keep the first failure as [DiskOperation.failureCause] / Failed.cause and attach any
     * later throw (close, disconnect) via [Throwable.addSuppressed] — never drop it.
     */
    private fun attachSecondaryFailure(op: QueuedOp, thrown: Throwable) {
        val existing = op.operation.failureCause
        if (existing == null) {
            op.operation.failureCause = thrown
        } else if (existing !== thrown) {
            existing.addSuppressed(thrown)
        }
        val stated = op.operation.state
        if (stated is OperationState.Failed) {
            val primary = stated.cause
            if (primary !== thrown) {
                // Avoid double-add when failureCause === Failed.cause (already suppressed above).
                val already = primary === existing || primary.suppressed.any { it === thrown }
                if (!already) primary.addSuppressed(thrown)
            }
        }
    }

    /**
     * Success must not keep a late-race Disconnected/cancel cause — callers feed
     * [DiskOperation.failureCause] into UI error lines even for Completed.
     */
    private fun clearSuccessNoiseCause(op: QueuedOp, terminal: OperationState) {
        if (terminal != OperationState.Completed) return
        when (val cause = op.operation.failureCause) {
            is DiskException.Disconnected -> op.operation.failureCause = null
            is kotlinx.coroutines.CancellationException -> op.operation.failureCause = null
            else -> Unit
        }
    }

    /**
     * Normal `run()` return. Explicit terminal states and [DiskOperation.commitReached]
     * beat late cancel/disconnect races; cancel/disconnect never invent Completed without
     * a durable commit boundary.
     */
    private fun classifyNormalReturn(op: QueuedOp): OperationState {
        if (op.isDone()) return op.state
        when (val stated = op.operation.state) {
            OperationState.Partial -> return OperationState.Partial
            is OperationState.Failed -> return stated
            OperationState.Completed -> return OperationState.Completed
            else -> Unit
        }
        if (op.operation.commitReached) return OperationState.Completed
        if (op.generation != connectionGeneration.get() || disconnected.get()) {
            val cause = DiskException.Disconnected()
            if (op.operation.failureCause == null) op.operation.failureCause = cause
            return if (op.operation.mutationAttempted) OperationState.Partial else OperationState.Failed(cause)
        }
        if (op.cancelled.get() || !op.operation.isActive()) {
            return if (op.operation.mutationAttempted) {
                val cause = kotlinx.coroutines.CancellationException("Operation cancelled")
                if (op.operation.failureCause == null) op.operation.failureCause = cause
                OperationState.Partial
            } else {
                OperationState.Cancelled
            }
        }
        return OperationState.Completed
    }

    /**
     * Exception path. Close/disconnect errors stay visible (Partial after mutation).
     * Only a proven commit + pure cancel may still report Completed.
     */
    private fun classifyThrown(op: QueuedOp, cancelled: Boolean): OperationState {
        if (op.isDone()) return op.state
        when (val stated = op.operation.state) {
            OperationState.Partial -> return OperationState.Partial
            is OperationState.Failed -> return stated
            OperationState.Completed -> {
                // Close/disconnect after a claimed Completed is ambiguous → not success.
                if (!cancelled) {
                    return if (op.operation.mutationAttempted) {
                        OperationState.Partial
                    } else if (op.generation != connectionGeneration.get() || disconnected.get()) {
                        OperationState.Failed(DiskException.Disconnected())
                    } else {
                        OperationState.Failed(
                            op.operation.failureCause
                                ?: DiskException.IoFailure("operation failed after completion mark"),
                        )
                    }
                }
                return OperationState.Completed
            }
            else -> Unit
        }
        if (op.operation.commitReached && cancelled) return OperationState.Completed
        if (op.operation.mutationAttempted) return OperationState.Partial
        if (op.generation != connectionGeneration.get() || disconnected.get()) {
            return OperationState.Failed(DiskException.Disconnected())
        }
        return if (cancelled) {
            OperationState.Cancelled
        } else {
            val cause = op.operation.failureCause
            if (cause is DiskException) OperationState.Failed(cause)
            else OperationState.Failed(
                DiskException.IoFailure(
                    cause?.message ?: cause?.javaClass?.name ?: "operation failed",
                    cause,
                ),
            )
        }
    }

    private inner class QueuedOp(
        val operation: DiskOperation,
        val generation: Long,
    ) : OperationHandle {
        private val result = CompletableDeferred<OperationState>()
        val cancelled = AtomicBoolean(false)
        @Volatile var state: OperationState = OperationState.Queued
        private val counted = AtomicBoolean(false)

        fun isDone(): Boolean = result.isCompleted

        fun markCounted() {
            counted.set(true)
        }

        fun complete(state: OperationState) {
            if (result.complete(state)) {
                this.state = state
                this.operation.state = state
                if (counted.get()) outstanding.decrementAndGet()
            }
        }

        fun recordDisconnect() {
            // Late disconnect after durable success must not invent an error cause;
            // classify still returns Completed via state/commitReached.
            val success = operation.commitReached ||
                operation.state == OperationState.Completed
            if (!success && operation.failureCause == null) {
                operation.failureCause = DiskException.Disconnected()
            }
            operation.markInactive()
        }

        fun failDisconnected() {
            complete(OperationState.Failed(DiskException.Disconnected()))
            operation.markInactive()
        }

        override suspend fun await(): OperationState = result.await()

        override fun cancel() {
            cancelled.set(true)
            operation.markInactive()
        }
    }
}
