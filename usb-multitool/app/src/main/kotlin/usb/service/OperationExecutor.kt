package usb.service

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import usb.device.DiskException


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
    private var disconnected = false
    private var running = false
    @Volatile private var current: QueuedOp? = null
    private val outstanding = AtomicInteger(0)

    /** Rebind wake/service control. Not a public field write. */
    fun bind(wakeLock: OperationWakeLock, serviceControl: ServiceControl) {
        this.wakeLock = wakeLock
        this.serviceControl = serviceControl
    }

    /**
     * Drop wake/service binding only when [expectedServiceControl] is still active.
     * A destroyed service must not reset a newer service's binding.
     */
    fun unbind(expectedServiceControl: ServiceControl) {
        if (this.serviceControl === expectedServiceControl) {
            this.wakeLock = NoOpWakeLock
            this.serviceControl = NoOpServiceControl
        }
    }


    /** Queued or running operations remain. Safe for stopWhenIdle re-entry. */
    fun hasOutstandingWork(): Boolean = outstanding.get() > 0
    fun submit(operation: DiskOperation): OperationHandle {
        val handle = QueuedOp(operation)
        // Count before async enqueue so a second submit cannot lose a race
        // against the first op's idle stopWhenIdle check.
        handle.markCounted()
        outstanding.incrementAndGet()
        scope.launch {
            val startWorker = mutex.withLock {
                if (disconnected) {
                    handle.complete(OperationState.Failed(DiskException.Disconnected()))
                    return@withLock false
                }
                val wasEmpty = queue.isEmpty() && !running
                queue.addLast(handle)
                if (wasEmpty) serviceControl.startForeground()
                !running
            }
            if (startWorker) drain()
        }
        return handle
    }

    fun onDisconnected() {
        scope.launch {
            val runningOp: QueuedOp?
            val queued: List<QueuedOp>
            mutex.withLock {
                disconnected = true
                runningOp = current
                queued = queue.toList()
                queue.clear()
            }
            runningOp?.failDisconnected()
            for (op in queued) {
                op.failDisconnected()
            }
            mutex.withLock {
                if (!running) serviceControl.stopWhenIdle()
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
                    if (outstanding.get() == 0) {
                        serviceControl.stopWhenIdle()
                    }
                    return
                }
                if (disconnected) {
                    next.failDisconnected()
                    return@withLock null
                }
                running = true
                current = next
                next
            } ?: continue

            runOne(op)

            mutex.withLock {
                running = false
                current = null
                if (queue.isEmpty()) {
                    if (outstanding.get() == 0) {
                        serviceControl.stopWhenIdle()
                        return
                    }
                    // Counted submit still pending enqueue — exit; it restarts drain.
                    return
                }
            }
        }
    }

    private suspend fun runOne(op: QueuedOp) {
        if (op.isDone()) return
        if (op.cancelled.get()) {
            op.operation.markInactive()
            op.complete(OperationState.Cancelled)
            return
        }
        if (mutex.withLock { disconnected }) {
            op.failDisconnected()
            return
        }

        op.state = OperationState.Running
        wakeLock.acquire()
        var terminal: OperationState = OperationState.Completed
        try {
            try {
                op.operation.run()
                terminal = when {
                    op.isDone() -> op.state
                    mutex.withLock { disconnected } ->
                        OperationState.Failed(DiskException.Disconnected())
                    op.cancelled.get() || !op.operation.isActive() ->
                        OperationState.Cancelled
                    else -> OperationState.Completed
                }
            } catch (e: DiskException) {
                terminal = if (op.isDone()) op.state else OperationState.Failed(e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Do not wrap cooperative/cancellation as IoFailure.
                terminal = if (op.isDone()) op.state else OperationState.Cancelled
            } catch (e: Exception) {
                terminal = if (op.isDone()) {
                    op.state
                } else {
                    OperationState.Failed(DiskException.IoFailure(e.message ?: "io"))
                }
            }
        } finally {
            wakeLock.release()
            if (!op.isDone()) op.complete(terminal)
        }
    }

    private inner class QueuedOp(
        val operation: DiskOperation,
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
                if (counted.get()) outstanding.decrementAndGet()
            }
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
