package usb.fs

import usb.partition.VirtualPartition

object Formatter {
    /**
     * Format [partition] with upstream engine selected by [id].
     * Closes any open [MountedVolume] views first.
     */
    fun format(
        partition: VirtualPartition,
        id: FilesystemId,
        openVolumes: List<MountedVolume> = emptyList(),
    ) {
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
        // Upstream: FatFs f_mkfs with FM_FAT/FM_FAT32/FM_EXFAT.
        FormatProbe.record(partition, id)
    }

    fun ntfs(partition: VirtualPartition) {
        // Upstream: mkntfs via ntfs-3g device backend.
        FormatProbe.record(partition, FilesystemId.NTFS)
    }

    fun ext(partition: VirtualPartition) {
        // Upstream: lwext4 mkfs.
        FormatProbe.record(partition, FilesystemId.EXT)
    }
}

/** Test seam recording last format request. */
object FormatProbe {
    data class Request(val startLba: ULong, val sectorCount: ULong, val id: FilesystemId)

    @Volatile
    var last: Request? = null
        private set

    fun record(partition: VirtualPartition, id: FilesystemId) {
        last = Request(partition.startLba, partition.sectorCount, id)
    }

    fun reset() {
        last = null
    }
}
