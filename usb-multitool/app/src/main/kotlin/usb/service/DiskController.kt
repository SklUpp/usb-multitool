package usb.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Process-safe disk work accessor. Owns the single-thread scope and [OperationExecutor].
 * Idle [ServiceControl.stopWhenIdle] must not cancel this controller — later [submit] still runs.
 */
class DiskController(
    wakeLock: OperationWakeLock = NoOpWakeLock,
    serviceControl: ServiceControl = NoOpServiceControl,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    val executor = OperationExecutor(scope, wakeLock = wakeLock, serviceControl = serviceControl)

    fun submit(operation: DiskOperation): OperationHandle = executor.submit(operation)

    fun onDisconnected() = executor.onDisconnected()

    /** Rebind Android wake/service control without replacing the executor. */
    fun bind(wakeLock: OperationWakeLock, serviceControl: ServiceControl) {
        executor.wakeLock = wakeLock
        executor.serviceControl = serviceControl
    }

    /** Terminal teardown only (process/app shutdown). Not used on idle service stop. */
    fun shutdown() {
        scope.cancel()
    }

    companion object {
        @Volatile
        private var shared: DiskController? = null

        fun get(): DiskController {
            val existing = shared
            if (existing != null) return existing
            return synchronized(this) {
                shared ?: DiskController().also { shared = it }
            }
        }

        fun setForTest(controller: DiskController?) {
            synchronized(this) {
                shared = controller
            }
        }
    }
}
