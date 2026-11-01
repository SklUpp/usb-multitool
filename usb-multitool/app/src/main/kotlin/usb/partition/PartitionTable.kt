package usb.partition

enum class TableScheme {
    MBR,
    GPT,
}

enum class PartitionType {
    BASIC,
    VTOYEFI,
    ESP,
    OTHER,
}

data class Partition(
    val startLba: ULong,
    val sectorCount: ULong,
    val type: PartitionType = PartitionType.BASIC,
    val name: String = "",
    val esp: Boolean = false,
) {
    val lastLba: ULong get() = startLba + sectorCount - 1UL
}

data class FreeRegion(
    val startLba: ULong,
    val sectorCount: ULong,
)

data class PartitionTable(
    val scheme: TableScheme,
    val partitions: List<Partition>,
    val freeRegions: List<FreeRegion> = emptyList(),
    val diskSectorCount: ULong = 0UL,
    val sectorSize: Int = 512,
)
