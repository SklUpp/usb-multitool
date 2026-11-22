package usb.multitool

import usb.device.UsbDisk
import usb.partition.Partition
import usb.partition.VirtualPartition
import usb.partition.isEfiSystemType
import usb.partition.isProtectedSystemPartition
import usb.ui.PartitionTab

internal fun mapPartitionTabs(
    partitions: List<Partition>,
    volumeLabelProbe: (Partition, usb.fs.FilesystemId?) -> String? = { _, _ -> null },
    filesystemProbe: (Partition) -> usb.fs.FilesystemId? = { null },
): List<PartitionTab> = partitions.mapIndexed { index, partition ->
    val filesystemId = filesystemProbe(partition)
    PartitionTab(
        id = (index + 1).toString(),
        name = partition.name.ifBlank { "Partition ${index + 1}" },
        readOnly = isProtectedSystemPartition(partition) ||
            filesystemId == usb.fs.FilesystemId.ISO,
        isEfiType = isEfiSystemType(partition),
        startLba = partition.startLba,
        sectorCount = partition.sectorCount,
        filesystemId = filesystemId,
        volumeLabel = volumeLabelProbe(partition, filesystemId),
        bootable = partition.bootable,
    )
}

/** Synthetic Files-tab id; never a partition-table slot and never a mutation target. */
internal const val WHOLE_DISK_VOLUME_ID = "whole"

/**
 * Read-only Files-tab view for a Rufus/DD image with no usable partition table. Probe is
 * metadata-only and returns null when no supported filesystem starts at LBA 0.
 */
internal fun mapWholeDiskTab(disk: UsbDisk): PartitionTab? {
    // Hybrid Rufus images can carry a partition table and an ISO filesystem at LBA 0 at
    // the same time. Probe LBA 0 independently; the synthetic tab is read-only.
    val volume = VirtualPartition(0UL, disk.sectorCount, disk)
    val filesystem = runCatching { usb.fs.Filesystem.probe(volume) }.getOrNull() ?: return null
    val label = runCatching { usb.fs.Filesystem.readLabel(volume, filesystem) }.getOrNull()
    return PartitionTab(
        id = WHOLE_DISK_VOLUME_ID,
        name = label.orEmpty().ifBlank { "ISO/IMG" },
        readOnly = true,
        filesystemId = filesystem,
        volumeLabel = label,
        startLba = 0UL,
        sectorCount = disk.sectorCount,
        wholeDisk = true,
    )
}

internal fun mapPartitionTabs(disk: UsbDisk, partitions: List<Partition>): List<PartitionTab> =
    mapPartitionTabs(
        partitions,
        volumeLabelProbe = { partition, id ->
            id?.let {
                runCatching {
                    usb.fs.Filesystem.readLabel(
                        VirtualPartition(partition.startLba, partition.sectorCount, disk),
                        it,
                    )
                }.getOrNull()
            }
        },
        filesystemProbe = { partition ->
            runCatching {
                usb.fs.Filesystem.probe(
                    VirtualPartition(partition.startLba, partition.sectorCount, disk),
                )
            }.getOrNull()
        },
    )
