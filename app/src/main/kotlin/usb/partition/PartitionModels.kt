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

/** Why a typed GPT partition name cannot be stored in the 72-byte GPT name field. */
enum class PartitionNameProblem {
    Blank,
    TooLong,
    ControlCharacter,
    /** A surrogate pair would be split by the 36-unit ceiling or is already unpaired. */
    UnpairedSurrogate,
}

/**
 * GPT entry names: 36 UTF-16 code units (72 bytes, not NUL-terminated by the writer).
 * MBR entries have no name field at all, so a non-null name for an MBR table is refused
 * instead of being dropped silently on the next write.
 */
object PartitionName {
    const val MAX_UTF16_UNITS = 36

    fun normalize(raw: String): String = raw.trim()

    fun problem(raw: String): PartitionNameProblem? {
        val name = normalize(raw)
        return when {
            name.isEmpty() -> PartitionNameProblem.Blank
            name.any { it.isISOControl() } -> PartitionNameProblem.ControlCharacter
            !wellFormedUtf16(name) -> PartitionNameProblem.UnpairedSurrogate
            name.length > MAX_UTF16_UNITS -> PartitionNameProblem.TooLong
            else -> null
        }
    }

    /** Only a plain GPT entry stores a name; MBR has no field and HYBRID is never rewritten. */
    fun supported(scheme: TableScheme): Boolean = scheme == TableScheme.GPT

    private fun wellFormedUtf16(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return false
                    i += 2
                }
                c.isLowSurrogate() -> return false
                else -> i++
            }
        }
        return true
    }
}

class HybridAmbiguityException(message: String) : Exception(message)
class NoFreeSpaceException(message: String) : Exception(message)
