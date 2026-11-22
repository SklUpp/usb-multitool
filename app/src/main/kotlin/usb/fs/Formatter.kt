package usb.fs

import usb.partition.VirtualPartition

object Formatter {
    fun supportedFormatIds(): List<FilesystemId> = FormatCatalog.supportedFormatIds()

    fun requireSupported(id: FilesystemId): FilesystemId = FormatCatalog.requireSupported(id)

    fun label(id: FilesystemId): String = FormatCatalog.label(id)

    /**
     * Format [partition] with upstream engine selected by [id]. Native mkfs calls are blocking
     * and expose no cancellation callback; callers must not advertise interruption while this
     * method runs. Caller must release any [usb.files.MountedFilesVolume] on the disk first
     * (ViewModel closeFilesVolume fail-closed). [label] is written by the same native
     * path as the format where the engine supports it (see [FormatCatalog.supportsVolumeLabel]).
     */
    fun format(
        partition: VirtualPartition,
        id: FilesystemId,
        label: String = "",
        /** Allocation unit size in bytes for FatFs f_mkfs; 0 = library default. */
        auSizeBytes: Int = 0,
    ) {
        requireSupported(id)
        val normalizedLabel = requireWritableLabel(id, label)
        when (id) {
            FilesystemId.FAT, FilesystemId.EXFAT -> {
                FormatNative.fat(partition, id, auSizeBytes)
                if (normalizedLabel.isNotEmpty()) {
                    FatFs.setLabel(
                        callback = BlockDeviceFatCallback(partition),
                        sectorSize = partition.sectorSize,
                        sectorCount = partition.sectorCount.toLong(),
                        label = normalizedLabel,
                    )
                }
            }
            FilesystemId.NTFS -> NtfsFs.mkfs(partition, normalizedLabel)
            FilesystemId.EXT -> ExtFs.mkfs(partition, normalizedLabel)
        }
    }

    /**
     * Replace the volume label of an already formatted partition. Refused for filesystems
     * whose engine has no setter; [FormatCatalog.supportsVolumeLabelRename] is the gate.
     */
    fun setVolumeLabel(partition: VirtualPartition, id: FilesystemId, label: String) {
        requireSupported(id)
        require(FormatCatalog.supportsVolumeLabelRename(id)) {
            "the $id volume label can only be written while formatting"
        }
        val normalizedLabel = requireWritableLabel(id, label)
        when (id) {
            FilesystemId.FAT, FilesystemId.EXFAT ->
                FatFs.setLabel(
                    callback = BlockDeviceFatCallback(partition),
                    sectorSize = partition.sectorSize,
                    sectorCount = partition.sectorCount.toLong(),
                    label = normalizedLabel,
                )
            FilesystemId.NTFS -> NtfsFs.setLabel(partition, normalizedLabel)
            FilesystemId.EXT ->
                throw IllegalArgumentException("the EXT volume label can only be written while formatting")
        }
    }

    /** Trimmed label, validated against the native writer limits; blank means "no label". */
    private fun requireWritableLabel(id: FilesystemId, label: String): String {
        val normalized = VolumeLabel.normalize(label)
        if (normalized.isEmpty()) return normalized
        require(FormatCatalog.supportsVolumeLabel(id)) { "$id cannot store a volume label" }
        val problem = VolumeLabel.problem(normalized, id)
        require(problem == null) { "invalid $id volume label '$normalized': $problem" }
        return normalized
    }

}

internal object FormatNative {
    fun fat(partition: VirtualPartition, id: FilesystemId, auSizeBytes: Int = 0) {
        FatFs.mkfs(
            callback = BlockDeviceFatCallback(partition),
            sectorSize = partition.sectorSize,
            sectorCount = partition.sectorCount.toLong(),
            type = fatMkfsType(id, partition),
            auSizeBytes = auSizeBytes,
        )
    }

    fun ntfs(partition: VirtualPartition, label: String) {
        NtfsFs.mkfs(partition, label)
    }

    fun ext(partition: VirtualPartition, label: String) {
        ExtFs.mkfs(partition, label)
    }
}

/** FatFs mkfs flavour for a FAT-family [FilesystemId]. */
internal fun fatMkfsType(id: FilesystemId, partition: VirtualPartition? = null): FatMkfsType = when (id) {
    FilesystemId.FAT -> {
        val bytes = (partition?.sectorCount ?: 0UL) * (partition?.sectorSize?.toULong() ?: 512UL)
        if (bytes > 2147483648UL) FatMkfsType.FAT32 else FatMkfsType.FAT
    }
    FilesystemId.EXFAT -> FatMkfsType.EXFAT
    else -> throw IllegalArgumentException("$id is not a FAT-family filesystem")
}
