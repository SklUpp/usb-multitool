package usb.fs

import usb.EngineMissingException
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
        if (FormatProbe.testMode) {
            FormatProbe.record(partition, id)
            return
        }
        FatFs.mkfs(
            callback = BlockDeviceFatCallback(partition),
            sectorSize = partition.sectorSize,
            sectorCount = partition.sectorCount.toLong(),
            type = fatMkfsType(id),
        )
    }

    fun ntfs(partition: VirtualPartition) {
        if (FormatProbe.testMode) {
            FormatProbe.record(partition, FilesystemId.NTFS)
            return
        }
        throw EngineMissingException("ntfs-3g")
    }

    fun ext(partition: VirtualPartition) {
        if (FormatProbe.testMode) {
            FormatProbe.record(partition, FilesystemId.EXT)
            return
        }
        throw EngineMissingException("lwext4")
    }
}

/** FatFs mkfs flavour for a FAT-family [FilesystemId]. */
internal fun fatMkfsType(id: FilesystemId): FatMkfsType = when (id) {
    FilesystemId.FAT -> FatMkfsType.FAT
    FilesystemId.EXFAT -> FatMkfsType.EXFAT
    else -> throw IllegalArgumentException("$id is not a FAT-family filesystem")
}

/** Test seam recording last format request. Production keeps testMode=false. */
object FormatProbe {
    data class Request(val startLba: ULong, val sectorCount: ULong, val id: FilesystemId)

    @Volatile
    var testMode: Boolean = false

    @Volatile
    var last: Request? = null
        private set

    fun record(partition: VirtualPartition, id: FilesystemId) {
        last = Request(partition.startLba, partition.sectorCount, id)
    }

    fun reset() {
        last = null
        testMode = false
    }
}
