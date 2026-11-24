package usb.files

import usb.fs.UsbFsVolume
import usb.operation.DiskOperation

/**
 * Read/write volume surface for files coordinators. [FilesVolumeSession] implements it;
 * callers never lock the session themselves.
 */
interface UsbVolume {
    fun read(pin: AttachmentPin?, partitionId: String): UsbFsVolume

    fun <T> write(
        pin: AttachmentPin?,
        partitionId: String,
        operation: DiskOperation,
        body: (UsbFsVolume) -> T,
    ): T
}
