package usb.files

import usb.device.UsbDisk
import usb.fs.UsbFsVolume

/** Owns USB disk and mounted filesystem; lease barrier starts before publication. */
class MountedFilesVolume(
    val partitionId: String,
    private val disk: UsbDisk,
    val volume: UsbFsVolume,
    val writable: Boolean,
    /** Exact geometry and filesystem identity used for this mount; null means unverified. */
    val startLba: ULong? = null,
    val sectorCount: ULong? = null,
    val sectorSize: Int? = null,
    val filesystemId: usb.fs.FilesystemId? = null,
): AutoCloseable {
    private var closeFailure: Throwable? = null
    init {
        disk.beginHigherLevelClose()
    }

    internal fun bindLease(lease: usb.device.UsbExclusiveClaim.Lease) {
        disk.bindLease(lease)
        disk.beginHigherLevelClose()
    }

    override fun close() {
        closeFailure?.let { throw it }
        var failure: Throwable? = null
        try {
            try {
                volume.close()
            } catch (t: Throwable) {
                failure = t
            } finally {
                try {
                    disk.close()
                } catch (t: Throwable) {
                    if (failure == null) failure = t else failure!!.addSuppressed(t)
                }
            }
            if (failure != null) {
                closeFailure = failure
                throw failure
            }
            disk.confirmHigherLevelClose()
        } catch (t: Throwable) {
            if (closeFailure == null) closeFailure = t
            throw t
        }
    }
}
