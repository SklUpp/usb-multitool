package usb.multitool

import usb.device.UsbDisk
import usb.partition.Partition
import usb.partition.VirtualPartition
import usb.ui.PartitionTab

internal fun mapPartitionTabs(
    partitions: List<Partition>,
    filesystemProbe: (Partition) -> usb.fs.FilesystemId? = { null },
): List<PartitionTab> = partitions.mapIndexed { index, partition ->
    val protected = isProtectedSystemPartition(partition)
    val efiType = partition.esp ||
        partition.type.equals("0xEF", ignoreCase = true) ||
        partition.type.equals("EFI", ignoreCase = true) ||
        partition.type.equals("C12A7328-F81F-11D2-BA4B-00A0C93EC93B", ignoreCase = true)
    PartitionTab(
        id = (index + 1).toString(),
        name = partition.name.ifBlank { "Partition ${index + 1}" },
        readOnly = protected,
        isEfiType = efiType,
        startLba = partition.startLba,
        sectorCount = partition.sectorCount,
        filesystemId = filesystemProbe(partition),
        bootable = partition.bootable,
    )
}

internal fun mapPartitionTabs(disk: UsbDisk, partitions: List<Partition>): List<PartitionTab> =
    mapPartitionTabs(partitions) { partition ->
        runCatching {
            usb.fs.Filesystem.probe(VirtualPartition(partition.startLba, partition.sectorCount, disk))
        }.getOrNull()
    }
