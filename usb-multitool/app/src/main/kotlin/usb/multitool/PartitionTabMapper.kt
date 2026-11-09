package usb.multitool

import usb.device.UsbDisk
import usb.partition.Partition
import usb.partition.VirtualPartition
import usb.ui.PartitionTab

internal fun mapPartitionTabs(
    partitions: List<Partition>,
    filesystemProbe: (Partition) -> usb.fs.FilesystemId? = { null },
): List<PartitionTab> = partitions.mapIndexed { index, partition ->
    val isEfi = isProtectedSystemPartition(partition)
    PartitionTab(
        id = (index + 1).toString(),
        name = partition.name.ifBlank { "Partition ${index + 1}" },
        readOnly = isEfi,
        isVtoyEfi = isEfi,
        startLba = partition.startLba,
        sectorCount = partition.sectorCount,
        filesystemId = filesystemProbe(partition),
    )
}

internal fun mapPartitionTabs(disk: UsbDisk, partitions: List<Partition>): List<PartitionTab> =
    mapPartitionTabs(partitions) { partition ->
        runCatching {
            usb.fs.Filesystem.probe(VirtualPartition(partition.startLba, partition.sectorCount, disk))
        }.getOrNull()
    }
