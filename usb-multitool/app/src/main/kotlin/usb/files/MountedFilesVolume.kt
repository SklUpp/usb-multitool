package usb.files

import usb.device.UsbDisk
import usb.fs.UsbFsVolume

/** Owns USB disk and mounted filesystem; closes volume before disk, preserving both failures. */
class MountedFilesVolume(
    val partitionId: String,
    private val disk: UsbDisk,
    val volume: UsbFsVolume,
    val writable: Boolean,
) : AutoCloseable {
    override fun close() {
        var failure: Throwable? = null
        disk.beginHigherLevelClose()
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
            if (failure != null) throw failure!!
            disk.confirmHigherLevelClose()
        } catch (t: Throwable) {
            throw t
        }
    }
}
