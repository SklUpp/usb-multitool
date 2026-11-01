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
) {
    private val active = AtomicBoolean(true)
    private val inactive = CompletableDeferred<Unit>()

    fun isActive(): Boolean = active.get()

    fun report(bytes: Long) {
        bytesDone = bytes
    }

    suspend fun awaitInactive() {
        inactive.await()
    }

    internal fun markInactive() {
        if (active.getAndSet(false)) {
            inactive.complete(Unit)
        }
    }

    open suspend fun run() {}
}
