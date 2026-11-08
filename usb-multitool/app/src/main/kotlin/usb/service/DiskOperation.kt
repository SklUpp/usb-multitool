package usb.service

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
    private val active: () -> Boolean = { true },
) {
    private val explicitActive = AtomicBoolean(true)
    private val inactive = CompletableDeferred<Unit>()

    fun isActive(): Boolean = explicitActive.get() && active()

    fun report(bytes: Long) {
        bytesDone = bytes
    }

    suspend fun awaitInactive() {
        inactive.await()
    }

    internal fun markInactive() {
        if (explicitActive.getAndSet(false)) {
            inactive.complete(Unit)
        }
    }

    open suspend fun run() {}
}
