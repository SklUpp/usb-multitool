package usb.service

/** Progress/result carrier for long disk work. ponytail: Task 6 owns executor wiring. */
data class DiskOperation(
    val name: String,
    @Volatile var bytesDone: Long = 0L,
    @Volatile var bytesTotal: Long = -1L,
    private val active: () -> Boolean = { true },
) {
    fun isActive(): Boolean = active()

    fun report(bytes: Long) {
        bytesDone = bytes
    }
}

sealed class OperationState {
    data object Queued : OperationState()
    data object Running : OperationState()
    data object Completed : OperationState()
    data object Cancelled : OperationState()
    data class Failed(val error: Throwable) : OperationState()
}
