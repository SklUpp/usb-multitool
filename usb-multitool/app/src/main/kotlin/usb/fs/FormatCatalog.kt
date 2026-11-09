package usb.fs

/**
 * Format-picker catalog shared by UI (host-checkable) and [Formatter].
 * No partition/engine types — keeps scripts/run-ui-host-check.sh free of JNI deps.
 */
object FormatCatalog {
    /** Engines the app can actually format today (all four are real engines). */
    fun supportedFormatIds(): List<FilesystemId> =
        listOf(FilesystemId.FAT, FilesystemId.EXFAT, FilesystemId.EXT, FilesystemId.NTFS)

    fun requireSupported(id: FilesystemId): FilesystemId {
        require(id in supportedFormatIds()) {
            "filesystem $id is not offered by the format picker"
        }
        return id
    }

    fun label(id: FilesystemId): String =
        when (id) {
            FilesystemId.FAT -> "FAT/FAT32"
            FilesystemId.EXFAT -> "exFAT"
            FilesystemId.EXT -> "EXT4"
            FilesystemId.NTFS -> "NTFS"
        }
}
