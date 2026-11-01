package usb.partition

import usb.device.UsbDisk

enum class TableScheme {
    MBR,
    GPT,
    HYBRID,
}

/**
 * In-memory partition table view used by editors and Ventoy.
 * ponytail: Task 5 parsers fill this; native pipeline supplies mutable model for Task 9+.
 */
data class PartitionTable(
    val scheme: TableScheme,
    val partitions: List<Partition>,
    val freeRegions: List<FreeRegion>,
    val diskSectorCount: ULong,
    val sectorSize: Int = 512,
    val hybridAmbiguous: Boolean = false,
) {
    companion object {
        fun read(disk: UsbDisk): PartitionTable {
            // Task 5 owns on-disk decode. Empty table until parsers land.
            return PartitionTable(
                scheme = TableScheme.GPT,
                partitions = emptyList(),
                freeRegions = listOf(FreeRegion(0UL, disk.geometry.sectorCount)),
                diskSectorCount = disk.geometry.sectorCount,
                sectorSize = disk.geometry.sectorSize,
            )
        }
    }
}
