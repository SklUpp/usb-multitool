package usb.multitool

import kotlinx.coroutines.Job
import usb.service.OperationHandle

/** Immutable ownership ticket for one phone-import inspection (SAF and USB phases). */
internal data class InspectionTicket(
    val generation: Long,
    val attachGeneration: Long,
    val deviceKey: String?,
    val identity: String?,
)

/**
 * Single-slot ownership for phone-import inspection.
 *
 * Contract, all transitions under one lock:
 * - [begin] admits exactly one ticket while the slot is free.
 * - [trackSafJob] is ticket-bound: registering a job for a ticket that no longer owns the
 *   slot cancels that job instead of storing it. A cancel racing `begin`/launch therefore
 *   neither leaks a job nobody can cancel nor lets an old job displace the new ticket's job.
 * - [cancel] disowns the slot once, returning the work to cancel outside the lock; a second
 *   cancel sees an empty slot.
 * - [release] frees the slot only for the owning ticket; a late release after a cancel or a
 *   newer ticket is a no-op, so it can never disown a newer inspection.
 * - [trackUsbHandle] ignores a stale ticket for the same reason.
 *
 * Identity (`===`) is intentional: workers hold the exact ticket instance [begin] accepted.
 */
internal class InspectionOwnership {
    /** The one inspection allowed to open USB, or null. Written only under [lock]. */
    @Volatile
    var active: InspectionTicket? = null
        private set

    private val lock = Any()
    private var safJob: Job? = null
    private var usbHandle: OperationHandle? = null

    /** Work captured by [cancel]; cancelled by the caller after the slot is already free. */
    class Cancelled(val safJob: Job?, val usbHandle: OperationHandle?)

    fun begin(ticket: InspectionTicket): Boolean =
        synchronized(lock) {
            if (active != null) {
                false
            } else {
                active = ticket
                true
            }
        }

    /** Ownership check for workers: a disowned/cancelled ticket never proceeds to USB. */
    fun owned(ticket: InspectionTicket): Boolean = active === ticket

    /**
     * Registers the SAF job for [ticket]. A job whose ticket lost the slot between [begin]
     * and this call is cancelled instead of becoming an untracked orphan.
     */
    fun trackSafJob(ticket: InspectionTicket, job: Job) {
        val orphan =
            synchronized(lock) {
                if (active === ticket) {
                    safJob = job
                    false
                } else {
                    true
                }
            }
        if (orphan) job.cancel()
    }

    fun trackUsbHandle(ticket: InspectionTicket, handle: OperationHandle?) {
        synchronized(lock) {
            if (active === ticket) usbHandle = handle
        }
    }

    fun release(ticket: InspectionTicket) {
        synchronized(lock) {
            if (active === ticket) clearLocked()
        }
    }

    /** Disowns the slot; null when it was already free. */
    fun cancel(): Cancelled? {
        synchronized(lock) {
            if (active == null && safJob == null && usbHandle == null) return null
            val cancelled = Cancelled(safJob, usbHandle)
            clearLocked()
            return cancelled
        }
    }

    private fun clearLocked() {
        active = null
        safJob = null
        usbHandle = null
    }
}
