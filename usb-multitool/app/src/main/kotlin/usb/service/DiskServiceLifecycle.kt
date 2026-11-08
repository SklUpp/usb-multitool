package usb.service

/**
 * Pure service-lifecycle policy (no Android types).
 * [startForeground] only requests service start once — never promotes the
 * notification. [DiskService.onStartCommand] owns synchronous [Service.startForeground].
 * [stopWhenIdle] stops only when [hasOutstandingWork] is false.
 */
class DiskServiceLifecycle(
    private val requestStart: () -> Unit,
    private val stopService: () -> Unit,
    private val hasOutstandingWork: () -> Boolean,
    private val isServiceForeground: () -> Boolean = { false },
) : ServiceControl {
    private val lock = Any()
    @Volatile
    private var startRequested = false

    override fun startForeground() {
        synchronized(lock) {
            if (isServiceForeground()) {
                startRequested = true
                return
            }
            if (startRequested) return
            startRequested = true
            try {
                requestStart()
            } catch (t: Throwable) {
                startRequested = false
                throw t
            }
        }
    }

    override fun stopWhenIdle() {
        synchronized(lock) {
            if (hasOutstandingWork()) return
            startRequested = false
            if (hasOutstandingWork()) {
                startRequested = true
                runCatching { requestStart() }.onFailure { startRequested = false }
                return
            }
            stopService()
        }
    }

    /** Test hook: whether a start has already been requested. */
    fun hasRequestedStart(): Boolean = startRequested
}
