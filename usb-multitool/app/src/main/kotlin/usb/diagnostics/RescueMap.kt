package usb.diagnostics

enum class RescueStatus {
    NON_TRIED,
    COPIED,
    FAILED,
}

/**
 * ddrescue-style map over sector ranges. Partial output stays partial while any
 * sector is failed or non-tried.
 */
class RescueMap private constructor(
    val sectorCount: ULong,
    private val status: Array<RescueStatus>,
) {
    val partial: Boolean
        get() = status.any { it != RescueStatus.COPIED }

    val failed: List<ULongRange>
        get() = rangesOf(RescueStatus.FAILED)

    val copied: List<ULongRange>
        get() = rangesOf(RescueStatus.COPIED)

    val nonTried: List<ULongRange>
        get() = rangesOf(RescueStatus.NON_TRIED)

    fun markFailed(start: ULong, count: ULong) = paint(start, count, RescueStatus.FAILED)

    fun markCopied(start: ULong, count: ULong) = paint(start, count, RescueStatus.COPIED)

    fun markNonTried(start: ULong, count: ULong) = paint(start, count, RescueStatus.NON_TRIED)

    fun statusAt(lba: ULong): RescueStatus {
        require(lba < sectorCount)
        return status[lba.toInt()]
    }

    /** Serialize beside a partial image for resume. */
    fun encode(): String = buildString {
        append("sectorCount=$sectorCount\n")
        for (range in allRanges()) {
            append(range.first)
            append(' ')
            append(range.last - range.first + 1UL)
            append(' ')
            append(status[range.first.toInt()].name)
            append('\n')
        }
    }

    private fun paint(start: ULong, count: ULong, value: RescueStatus) {
        require(count > 0UL)
        require(start < sectorCount)
        require(start + count <= sectorCount)
        var i = start
        while (i < start + count) {
            status[i.toInt()] = value
            i++
        }
    }

    private fun rangesOf(wanted: RescueStatus): List<ULongRange> =
        allRanges().filter { status[it.first.toInt()] == wanted }

    private fun allRanges(): List<ULongRange> {
        if (sectorCount == 0UL) return emptyList()
        val out = mutableListOf<ULongRange>()
        var start = 0UL
        var current = status[0]
        var i = 1UL
        while (i < sectorCount) {
            val s = status[i.toInt()]
            if (s != current) {
                out += start until i
                start = i
                current = s
            }
            i++
        }
        out += start until sectorCount
        return out
    }

    companion object {
        fun whole(sectorCount: ULong): RescueMap {
            require(sectorCount > 0UL)
            // Cap in-memory map for unit tests; production uses sparse encoding via JNI.
            require(sectorCount <= 1_048_576UL) { "in-memory map limited to 1Mi sectors" }
            return RescueMap(
                sectorCount = sectorCount,
                status = Array(sectorCount.toInt()) { RescueStatus.NON_TRIED },
            )
        }

        fun decode(text: String): RescueMap {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.isNotEmpty() && lines[0].startsWith("sectorCount="))
            val count = lines[0].removePrefix("sectorCount=").toULong()
            val map = whole(count)
            for (line in lines.drop(1)) {
                val parts = line.split(' ')
                require(parts.size == 3)
                val start = parts[0].toULong()
                val n = parts[1].toULong()
                when (RescueStatus.valueOf(parts[2])) {
                    RescueStatus.COPIED -> map.markCopied(start, n)
                    RescueStatus.FAILED -> map.markFailed(start, n)
                    RescueStatus.NON_TRIED -> map.markNonTried(start, n)
                }
            }
            return map
        }
    }
}
