package usb.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout

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
    private val requestStart: () -> Deferred<Unit>,
    private val stopService: () -> Unit,
    private val hasOutstandingWork: () -> Boolean,
    private val isServiceForeground: () -> Boolean = { false },
) : ServiceControl {
    private val lock = Any()
    @Volatile
    private var startRequested = false
    private var pendingStart: CompletableDeferred<Unit>? = null

    override suspend fun startForeground() {
        var owner = false
        val ready = synchronized(lock) {
            if (isServiceForeground()) {
                startRequested = true
                return
            }
            startRequested = true
            pendingStart ?: CompletableDeferred<Unit>().also {
                pendingStart = it
                owner = true
            }
        }
        try {
            withTimeout(5_000L) {
                if (owner) {
                    requestStart().await()
                    ready.complete(Unit)
                } else {
                    ready.await()
                }
            }
        } catch (t: Throwable) {
            if (owner) {
                ready.completeExceptionally(t)
                synchronized(lock) {
                    if (pendingStart === ready) {
                        pendingStart = null
                        startRequested = false
                    }
                }
            }
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
                pendingStart = null
                followUp = { stopService() }
            }
        }
        followUp?.invoke()
    }

    /**
     * Service instance is gone: clear the one-shot start latch so a later
     * [startForeground] can request start again instead of no-oping.
     * Clear pending readiness so a subsequent start does not reuse a stale signal.
     */
    fun onServiceDestroyed() {
        synchronized(lock) {
            startRequested = false
            pendingStart = null
        }
    }

    /** Test hook: whether a start has already been requested. */
    fun hasRequestedStart(): Boolean = startRequested
}
