package usb.ui

import usb.diagnostics.RescueMapSummary
import usb.image.ImageFormat

/**
 * Facts read from a selected SAF document without touching the disk. These types are
 * snapshotted into [DestructiveConfirmation] so the dialog shows exactly what was
 * inspected, never a later re-read.
 */
data class RescueImageInspection(
    val uri: String,
    val name: String? = null,
    /**
     * SAF `OpenableColumns.SIZE`. For a raw ISO/IMG this is the decoded length and may be
     * compared with the map geometry; extra trailing bytes are harmless because restore
     * reads only the mapped sectors. For gzip/xz it is the encoded length and is never
     * compared with the decoded requirement. Never a proof of physical media integrity.
     */
    val sizeBytes: Long? = null,
    /**
     * Format detected from the content magic (compressed magic wins over the name), or
     * null when the header could not be read. Only [ImageFormat.IMG]/[ImageFormat.ISO]
     * permit a stored-size comparison.
     */
    val format: ImageFormat? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: DestructiveConfirmation.displayLabel(uri)
            ?: uri
}

/** Outcome of comparing a known stored size with the map's required raw length. */
enum class RescueImageLengthIssue { SHORT, OVERFLOW }

/**
 * Single policy shared by the picker and the reducer. Compressed sources are decoded by
 * the live stream, so their stored size must never be compared with the map requirement.
 * A raw source is a stream prefix: short means the restore must be blocked before any
 * write; extra trailing bytes are ignored by [usb.diagnostics.Rescue.restore]. An
 * unreadable header (null format) is not compared; the engine stream still enforces the
 * length contract and reports Partial/Failed instead of Completed.
 */
fun rescueImageLengthIssue(
    sizeBytes: Long?,
    sectorCount: ULong,
    sectorSize: Int,
    format: ImageFormat?,
): RescueImageLengthIssue? {
    val expected = usb.diagnostics.BackupArchive.expectedImageBytes(sectorCount, sectorSize)
        ?: return RescueImageLengthIssue.OVERFLOW
    if (format != ImageFormat.IMG && format != ImageFormat.ISO) return null
    val actual = sizeBytes?.takeIf { it >= 0L } ?: return null
    return if (actual.toULong() < expected) RescueImageLengthIssue.SHORT else null
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
