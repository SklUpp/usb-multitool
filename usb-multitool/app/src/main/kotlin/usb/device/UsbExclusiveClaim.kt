package usb.device

/**
 * Exclusive process-wide ownership of the single BOT mass-storage device.
 *
 * `AndroidUsbBulkChannel.open` claims the interface with `force = true`, so a second live
 * session silently steals the first one's endpoint instead of failing. Every disk open
 * therefore takes a [Lease]; the lease lives exactly as long as that [UsbDisk] and is
 * released by [UsbDisk.close], success or failure.
 *
 * There is exactly one instance per process: [usb.service.DiskController.usbClaim] owns it,
 * and every [usb.files.FilesVolumeSession] (one per ViewModel) uses that instance, so two
 * Activity/ViewModel instances in one process cannot claim the stick at the same time.
 *
 * Acquisition is non-blocking on purpose. A raced opener must fail fast and be reported
 * (the usage probe maps it to "unavailable"), never wait inside a lock for an unrelated
 * long operation: waiting would put a mount in the close→open window it is meant to close.
 *
 * Owners: [usb.files.FilesVolumeSession] acquires for a mounted volume and for a raw
 * session ([usb.files.FilesVolumeSession.openAfterCloseClaimed]) under its single lock,
 * which is what makes "close the mount, then claim" atomic against a concurrent mount.
 */
class UsbExclusiveClaim {
    /** A live session still owns the device; the caller must not open it. */
    class BusyException : IllegalStateException("USB device is claimed by another session")

    /**
     * Ownership token. [close] is idempotent and identity-checked, so a disk closed twice
     * (or closed after a failed session close) can never free a successor's lease.
     * The released check and the claim clear happen in one monitor hold: a concurrent
     * [tryAcquire] sees either the old lease or no lease, never a half-released one.
     */
    inner class Lease : AutoCloseable {
        private var released = false

        override fun close() {
            synchronized(this@UsbExclusiveClaim) {
                if (released) return
                released = true
                if (held === this) held = null
            }
        }
    }

    private var held: Lease? = null

    /** @throws BusyException when another session holds the device. */
    fun tryAcquire(): Lease =
        synchronized(this) {
            if (held != null) throw BusyException()
            Lease().also { held = it }
        }

    fun isHeld(): Boolean = synchronized(this) { held != null }
}
