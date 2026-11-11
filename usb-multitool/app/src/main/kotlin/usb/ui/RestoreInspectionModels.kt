package usb.ui

import usb.diagnostics.RescueMapSummary

/**
 * Facts read from a selected SAF document without touching the disk. These types are
 * snapshotted into [DestructiveConfirmation] so the dialog shows exactly what was
 * inspected, never a later re-read.
 */
data class RescueImageInspection(
    val uri: String,
    val name: String? = null,
    /**
     * SAF `OpenableColumns.SIZE`: the encoded source length as reported by the provider.
     * Never treated as a decoded-image size and never used to admit or reject a restore.
     */
    val sizeBytes: Long? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: DestructiveConfirmation.displayLabel(uri)
            ?: uri
}

data class RescueMapInspection(
    val uri: String,
    val name: String? = null,
    val sizeBytes: Long? = null,
    val summary: RescueMapSummary? = null,
    /** Decode/bounded-read failure; a non-null error blocks the destructive confirmation. */
    val error: String? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: DestructiveConfirmation.displayLabel(uri)
            ?: uri
}

data class GptBackupSummary(
    val sectorSize: Int,
    val primarySectors: Int,
    val backupSectors: Int,
    val blobBytes: Int,
    val partitions: Int,
    val firstUsable: ULong,
    val lastUsable: ULong,
    /** Disk size recorded inside the captured headers (primary backupLba + 1). */
    val originalSectorCount: ULong,
)

data class GptBackupInspection(
    val uri: String,
    val name: String? = null,
    /** SAF source size as reported; the actual bounded read is what validation consumes. */
    val sizeBytes: Long? = null,
    val summary: GptBackupSummary? = null,
    /** Size/header/CRC failure; a non-null error blocks the destructive confirmation. */
    val error: String? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: DestructiveConfirmation.displayLabel(uri)
            ?: uri
}
