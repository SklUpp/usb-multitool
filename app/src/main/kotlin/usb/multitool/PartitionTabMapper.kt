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
        readOnly = isProtectedSystemPartition(partition),
        isEfiType = isEfiSystemType(partition),
        startLba = partition.startLba,
        sectorCount = partition.sectorCount,
        filesystemId = filesystemId,
        volumeLabel = volumeLabelProbe(partition, filesystemId),
        bootable = partition.bootable,
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
