package usb.service

/**
 * Pure service-lifecycle policy (no Android types).
 * [startForeground] only requests service start once — never promotes the
 * notification. [DiskService.onStartCommand] owns synchronous [Service.startForeground].
 * [stopWhenIdle] stops only when [hasOutstandingWork] is false.
 *
 * Callbacks ([requestStart]/[stopService]) run **outside** [lock] so they may take
 * other monitors (e.g. DiskService.Companion) without AB-BA deadlock against
 * [onServiceDestroyed] / service teardown.
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
        }
        try {
            requestStart()
        } catch (t: Throwable) {
            startRequested = false
            throw t
        }
    }

    override fun stopWhenIdle() {
        val followUp: (() -> Unit)?
        synchronized(lock) {
            if (hasOutstandingWork()) return
            startRequested = false
            if (hasOutstandingWork()) {
                startRequested = true
                followUp = {
                    runCatching { requestStart() }.onFailure { startRequested = false }
                }
            } else {
                followUp = { stopService() }
            }
        }
        followUp?.invoke()
    }

    /**
     * Service instance is gone: clear the one-shot start latch so a later
     * [startForeground] can request start again instead of no-oping.
     * Volatile write only — must not take [lock] (callers may hold Companion).
     */
    fun onServiceDestroyed() {
        startRequested = false
    }

    /** Test hook: whether a start has already been requested. */
    fun hasRequestedStart(): Boolean = startRequested
}
