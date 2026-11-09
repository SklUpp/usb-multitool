package usb.fs

import usb.partition.VirtualPartition

object Formatter {
    fun supportedFormatIds(): List<FilesystemId> = FormatCatalog.supportedFormatIds()

    fun requireSupported(id: FilesystemId): FilesystemId = FormatCatalog.requireSupported(id)

    fun label(id: FilesystemId): String = FormatCatalog.label(id)

    /**
     * Format [partition] with upstream engine selected by [id].
     * Closes any open [MountedVolume] views first.
     */
    fun format(
        partition: VirtualPartition,
        id: FilesystemId,
        openVolumes: List<MountedVolume> = emptyList(),
    ) {
        requireSupported(id)
        openVolumes.filter { it.partition === partition || sameRange(it.partition, partition) }
            .forEach { volume ->
                volume.sync()
                volume.close()
            }
        when (id) {
            FilesystemId.FAT, FilesystemId.EXFAT -> FormatNative.fat(partition, id)
            FilesystemId.NTFS -> FormatNative.ntfs(partition)
            FilesystemId.EXT -> FormatNative.ext(partition)
        }
    }

    private fun sameRange(a: VirtualPartition, b: VirtualPartition): Boolean =
        a.startLba == b.startLba && a.sectorCount == b.sectorCount
}

internal object FormatNative {
    fun fat(partition: VirtualPartition, id: FilesystemId) {
        FatFs.mkfs(
            callback = BlockDeviceFatCallback(partition),
            sectorSize = partition.sectorSize,
            sectorCount = partition.sectorCount.toLong(),
            type = fatMkfsType(id, partition),
        )
    }

    fun ntfs(partition: VirtualPartition) {
        NtfsFs.mkfs(partition)
    }

    fun ext(partition: VirtualPartition) {
        ExtFs.mkfs(partition)
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
