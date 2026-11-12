package usb.operation

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

sealed class OperationState {
    data object Queued : OperationState()
    data object Running : OperationState()
    data object Completed : OperationState()
    data object Cancelled : OperationState()
    data object Partial : OperationState()
    data class Failed(val cause: Throwable) : OperationState()
}

open class DiskOperation(
    open val name: String = "",
    @Volatile var bytesDone: Long = 0L,
    @Volatile var bytesTotal: Long = -1L,
    @Volatile var state: OperationState = OperationState.Queued,
    @Volatile var failureCause: Throwable? = null,
    @Volatile var mutationAttempted: Boolean = false,
    /** Set once cancellation can no longer undo the operation's committed result. */
    @Volatile var commitReached: Boolean = false,
    private val active: () -> Boolean = { true },
) {
    private val explicitActive = AtomicBoolean(true)
    private val inactive = CompletableDeferred<Unit>()
    private val inactiveListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun isActive(): Boolean = explicitActive.get() && active()

    /** Register a best-effort cleanup (e.g. close an in-flight source stream) on cancel. */
    fun onInactive(listener: () -> Unit) {
        inactiveListeners.add(listener)
        if (!isActive()) runCatching(listener)
    }

    fun removeInactiveListener(listener: () -> Unit) {
        inactiveListeners.remove(listener)
    }

    fun report(bytes: Long) {
        bytesDone = bytes
    }

    suspend fun awaitInactive() {
        inactive.await()
    }

    internal fun markInactive() {
        if (explicitActive.getAndSet(false)) {
            inactive.complete(Unit)
            inactiveListeners.forEach { runCatching(it) }
        }
    }

    open suspend fun run() {}
}
