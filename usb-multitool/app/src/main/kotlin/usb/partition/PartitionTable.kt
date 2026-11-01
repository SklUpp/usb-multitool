package usb.partition

import usb.device.UsbDisk

data class Partition(
    val startLba: ULong,
    val sectorCount: ULong,
    val type: String = "",
    val flags: Long = 0L,
)

data class FreeRegion(
    val startLba: ULong,
    val sectorCount: ULong,
)

object PartitionTable {
    // Filled in Task 5 (MBR/GPT parsing).
    fun read(disk: UsbDisk): List<Partition> = emptyList()
}
