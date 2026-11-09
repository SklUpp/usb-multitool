package usb.partition

data class Partition(
    val startLba: ULong,
    val sectorCount: ULong,
    val type: String = "",
    val name: String = "",
    val flags: Long = 0L,
    val bootable: Boolean = false,
    val esp: Boolean = false,
    /** Original MBR/GPT entry slot; -1 when the record has no on-disk slot yet. */
    val slot: Int = -1,
)

/** GPT attribute bit 2: legacy BIOS bootable. */
internal const val GPT_LEGACY_BOOTABLE_FLAG = 4L

data class FreeRegion(
    val startLba: ULong,
    val sectorCount: ULong,
)

enum class TableScheme {
    MBR,
    GPT,
    HYBRID,
}

enum class PartitionType {
    BASIC,
    EFI,
    VTOYEFI,
}

class HybridAmbiguityException(message: String) : Exception(message)
class NoFreeSpaceException(message: String) : Exception(message)
