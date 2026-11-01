package usb.diagnostics

enum class RescueStatus {
    NON_TRIED,
    COPIED,
    FAILED,
}

/**
 * ddrescue-style map over sector ranges using run-length encoding.
 * Supports disks of any size without a per-sector array or 512 MiB cap.
 * Partial output stays partial while any sector is failed or non-tried.
 */
class RescueMap private constructor(
    val sectorCount: ULong,
    private val runs: MutableList<Run>,
) {
    private data class Run(
        var start: ULong,
        var count: ULong,
        var status: RescueStatus,
    ) {
        val endExclusive: ULong get() = start + count
    }

    val partial: Boolean
        get() = runs.any { it.status != RescueStatus.COPIED }

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
        return runAt(lba).status
    }

    /** Serialize beside a partial image for resume. */
    fun encode(): String = buildString {
        append("sectorCount=$sectorCount\n")
        for (run in runs) {
            append(run.start)
            append(' ')
            append(run.count)
            append(' ')
            append(run.status.name)
            append('\n')
        }
    }

    private fun runAt(lba: ULong): Run {
        for (run in runs) {
            if (lba >= run.start && lba < run.endExclusive) return run
        }
        error("no run covers LBA $lba")
    }

    private fun paint(start: ULong, count: ULong, value: RescueStatus) {
        require(count > 0UL)
        require(start < sectorCount)
        require(start + count <= sectorCount)
        val end = start + count
        val rebuilt = ArrayList<Run>(runs.size + 2)
        for (run in runs) {
            val rEnd = run.endExclusive
            if (rEnd <= start || run.start >= end) {
                rebuilt += Run(run.start, run.count, run.status)
                continue
            }
            if (run.start < start) {
                rebuilt += Run(run.start, start - run.start, run.status)
            }
            if (rEnd > end) {
                rebuilt += Run(end, rEnd - end, run.status)
            }
        }
        rebuilt += Run(start, count, value)
        rebuilt.sortBy { it.start }
        runs.clear()
        for (run in rebuilt) {
            val last = runs.lastOrNull()
            if (last != null && last.status == run.status && last.endExclusive == run.start) {
                last.count += run.count
            } else {
                runs += run
            }
        }
    }

    private fun rangesOf(wanted: RescueStatus): List<ULongRange> =
        runs.filter { it.status == wanted }.map { it.start until it.endExclusive }

    companion object {
        fun whole(sectorCount: ULong): RescueMap {
            require(sectorCount > 0UL)
            return RescueMap(
                sectorCount = sectorCount,
                runs = mutableListOf(Run(0UL, sectorCount, RescueStatus.NON_TRIED)),
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
