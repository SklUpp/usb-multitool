package usb.fs

/**
 * Space inside one filesystem, not free ranges in the partition table.
 * Both values come from the mounted filesystem's own metadata.
 */
data class FilesystemSpace(
    val totalBytes: ULong,
    val freeBytes: ULong,
) {
    init {
        require(freeBytes <= totalBytes) {
            "freeBytes $freeBytes exceeds totalBytes $totalBytes"
        }
    }

    val usedBytes: ULong get() = totalBytes - freeBytes
}

/** Driver-reported mount access for one live filesystem handle. */
enum class FilesystemAccess {
    ReadOnly,
    ReadWrite,
    Unknown,
}

/** A mounted filesystem whose driver exposes its actual mount mode. */
interface UsbFsAccessVolume {
    val access: FilesystemAccess
}

/**
 * A mounted volume that can report its own free/total space read-only.
 * FAT/exFAT, EXT and NTFS implement it; other volumes simply are not queried.
 */
interface UsbFsUsageVolume {
    /**
     * Reads free/total bytes from filesystem metadata. Never mounts, allocates or
     * writes. Throws when the query fails (closed volume, engine error, I/O error).
     */
    fun space(): FilesystemSpace
}


enum class FilesystemUsageReason {
    /** Volume type has no space query wired. */
    UnsupportedFilesystem,

    /** Native engine is not linked in this build. */
    EngineMissing,

    /** Volume was closed before the query reached the engine. */
    Closed,

    /** Engine/disk reported a failure; the value is unknown, not zero. */
    IoFailure,
}

sealed interface FilesystemUsage {
    data class Known(
        val space: FilesystemSpace,
        val measuredAtMillis: Long,
    ) : FilesystemUsage

    data class Unavailable(val reason: FilesystemUsageReason) : FilesystemUsage
}

/**
 * Everything the measurement is valid for: attachment generation, device identity,
 * partition geometry and filesystem type. A snapshot whose key no longer matches
 * the selected partition must be rendered as unknown, never as a stale value.
 */
data class FilesystemUsageKey(
    val attachGeneration: Long,
    val identity: String,
    val partitionStartLba: ULong,
    val partitionSectorCount: ULong,
    val sectorSize: Int,
    val filesystem: FilesystemId,
) {
    init {
        require(partitionSectorCount > 0uL) { "partitionSectorCount must be positive" }
        require(sectorSize > 0) { "sectorSize must be positive" }
    }
}

data class FilesystemUsageSnapshot(
    val key: FilesystemUsageKey,
    val usage: FilesystemUsage,
    /** Driver-reported mode for same mounted handle; unknown when no handle fact exists. */
    val access: FilesystemAccess = FilesystemAccess.Unknown,
) {
    fun matches(other: FilesystemUsageKey): Boolean = key == other
}
