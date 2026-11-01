package usb.partition

import usb.device.UsbDisk

data class Partition(
    val startLba: ULong,
    val sectorCount: ULong,
    val type: String = "",
    val name: String = "",
    val flags: Long = 0L,
)

data class FreeRegion(
    val startLba: ULong,
    val sectorCount: ULong,
)

object PartitionTable {
    fun read(disk: UsbDisk): List<Partition> {
        val mbr = MbrTable.read(disk)
        if (mbr.protective) {
            return GptTable.read(disk)
        }
        if (mbr.partitions.isNotEmpty()) {
            return mbr.partitions
        }
        // Empty MBR: try GPT anyway (some images omit protective flag).
        return try {
            GptTable.read(disk)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}
