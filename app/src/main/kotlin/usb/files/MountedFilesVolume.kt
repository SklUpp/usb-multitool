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

    /**
     * App-scoped safe removal: flush filesystem, close it, then flush and close
     * the SCSI disk (SYNCHRONIZE CACHE(10)). Every required step runs even after
     * an earlier failure; the first failure is primary and later distinct
     * failures are suppressed. Lease release stays fail-closed: no release and
     * no remount unless every step confirmed.
     */
    fun eject() {
        closeFailure?.let { throw it }
        var failure: Throwable? = null
        try {
            try {
                volume.sync()
            } catch (t: Throwable) {
                failure = t
            }
            try {
                volume.close()
            } catch (t: Throwable) {
                if (failure == null) failure = t else if (failure !== t) failure!!.addSuppressed(t)
            }
            try {
                disk.sync()
            } catch (t: Throwable) {
                if (failure == null) failure = t else if (failure !== t) failure!!.addSuppressed(t)
            } finally {
                try {
                    disk.close()
                } catch (t: Throwable) {
                    if (failure == null) failure = t else if (failure !== t) failure!!.addSuppressed(t)
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
