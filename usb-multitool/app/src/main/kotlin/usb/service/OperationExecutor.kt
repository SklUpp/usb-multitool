package usb.service

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import usb.device.DiskException

sealed class OperationState {
    data object Queued : OperationState()
    data object Running : OperationState()
    data object Completed : OperationState()
    data object Cancelled : OperationState()
    data class Failed(val cause: DiskException) : OperationState()
}

abstract class DiskOperation {
    private val active = AtomicBoolean(true)
    private val inactive = CompletableDeferred<Unit>()

    fun isActive(): Boolean = active.get()

    /** Suspend until cancel/disconnect marks this operation inactive. */
    protected suspend fun awaitInactive() {
        inactive.await()
    }

    internal fun markInactive() {
        if (active.getAndSet(false)) {
            inactive.complete(Unit)
        }
    }

    abstract suspend fun run()
}

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
    private val wakeLock: OperationWakeLock = NoOpWakeLock,
    private val serviceControl: ServiceControl = NoOpServiceControl,
) {
    private val mutex = Mutex()
    private val queue = ArrayDeque<QueuedOp>()
    private var disconnected = false
    private var running = false
    @Volatile private var current: QueuedOp? = null

    fun submit(operation: DiskOperation): OperationHandle {
        val handle = QueuedOp(operation)
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
                val next = queue.pollFirst() ?: run {
                    serviceControl.stopWhenIdle()
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
                    serviceControl.stopWhenIdle()
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

    private class QueuedOp(
        val operation: DiskOperation,
    ) : OperationHandle {
        private val result = CompletableDeferred<OperationState>()
        val cancelled = AtomicBoolean(false)
        @Volatile var state: OperationState = OperationState.Queued

        fun isDone(): Boolean = result.isCompleted

        fun complete(state: OperationState) {
            if (result.complete(state)) {
                this.state = state
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
