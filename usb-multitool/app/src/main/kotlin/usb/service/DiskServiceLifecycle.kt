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
) : ServiceControl {
    @Volatile
    private var startRequested = false

    override fun startForeground() {
        if (startRequested) return
        startRequested = true
        requestStart()
    }

    override fun stopWhenIdle() {
        if (hasOutstandingWork()) return
        startRequested = false
        stopService()
    }

    /** Test hook: whether a start has already been requested. */
    fun hasRequestedStart(): Boolean = startRequested
}
