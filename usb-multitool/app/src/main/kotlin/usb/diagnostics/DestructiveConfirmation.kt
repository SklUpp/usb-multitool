package usb.diagnostics

import usb.device.UsbDisk
import usb.fs.MountedVolume

/** User-confirmed disk identity and capacity for destructive mutations. */
data class DestructiveConfirmation(
    val identity: String,
    val capacityBytes: ULong,
) {
    /**
     * Throws [ConfirmationMismatchException] before any sector write when the
     * live disk does not match the identity/capacity the user confirmed.
     */
    fun requireMatch(identity: String, disk: UsbDisk) {
        requireMatch(identity, disk.geometry.capacityBytes)
    }

    fun requireMatch(identity: String, liveCapacityBytes: ULong) {
        if (this.identity != identity || capacityBytes != liveCapacityBytes) {
            throw ConfirmationMismatchException(
                "confirmed identity=${this.identity} capacity=$capacityBytes " +
                    "but live identity=$identity capacity=$liveCapacityBytes",
            )
        }
    }
}

class ConfirmationMismatchException(message: String) : Exception(message)

/** First [PARTITION_METADATA_SECTORS] LBAs hold MBR/GPT/alignment gap. */
const val PARTITION_METADATA_SECTORS: ULong = 2048UL

internal fun closeMountedVolumes(openVolumes: List<MountedVolume>) {
    for (volume in openVolumes) {
        if (volume.isOpen()) {
            volume.sync()
            volume.close()
        }
    }
}
