package usb.diagnostics

/**
 * Translation between the native SectorForge (`sf-core`) block list and the app's
 * `usb-rescue-map 2` map.
 *
 * The engine reports byte ranges, the app map is LBA-based, and sf-core has five
 * statuses while the durable map has three. Mapping is deliberately conservative:
 * only a finished range becomes COPIED, only a confirmed bad sector becomes FAILED,
 * and everything still parked or half-trimmed stays NON_TRIED — so an interrupted
 * run can never claim data it does not have, and restore keeps refusing a partial
 * COPY map.
 */
internal object SectorForgeBlocks {
    const val NON_TRIED = 0L
    const val NON_TRIMMED = 1L
    const val NON_SCRAPED = 2L
    const val FAILED = 3L
    const val COPIED = 4L

    /**
     * Ranges to hand the engine before it runs. COPIED is inherited as finished;
     * FAILED becomes NonTrimmed so the retry pass revisits it (a sector that failed
     * once is not trusted forever).
     */
    fun seeds(map: RescueMap): LongArray {
        val out = LongArray((map.copied.size + map.failed.size) * 3)
        var index = 0
        for (range in map.copied) index = write(out, index, range, COPIED)
        for (range in map.failed) index = write(out, index, range, NON_TRIMMED)
        return out
    }

    /**
     * Repaints [target] from one native snapshot.
     *
     * The snapshot is reconstructed in one linear pass and must be a complete,
     * sector-aligned, ordered, non-overlapping cover of the whole map — the
     * invariant sf-core's `MapFile` guarantees. Everything is validated before
     * [target] is touched, so a truncated or malformed snapshot leaves the map
     * exactly as it was instead of wiping recoverable progress. The reconstructed
     * map must also stay within [RescueMap.MAX_RUN_LINES] to remain encodable.
     *
     * [inheritedFailed] is the FAILED set the target had before the run: a sector
     * that is still non-tried because the run was interrupted keeps its previous
     * FAILED marking instead of silently degrading to "never examined".
     */
    fun apply(
        target: RescueMap,
        triples: LongArray,
        sectorSize: Int,
        inheritedFailed: List<ULongRange>,
    ) {
        require(triples.size % 3 == 0) { "block snapshot is not a triple list" }
        val spans = reconstruct(triples, target.sectorCount, sectorSize)
        target.replaceRuns(overlayInheritedFailed(spans, inheritedFailed))
    }

    /** Maps the native triples to app runs, coalescing adjacent equal statuses. */
    private fun reconstruct(
        triples: LongArray,
        sectorCount: ULong,
        sectorSize: Int,
    ): List<RescueMap.Span> {
        val out = ArrayList<RescueMap.Span>(triples.size / 3)
        var nextLba = 0UL
        var index = 0
        while (index < triples.size) {
            val block = index / 3
            val startByte = triples[index]
            val sizeByte = triples[index + 1]
            require(startByte >= 0 && sizeByte > 0) { "bad native block at $block" }
            require(startByte % sectorSize == 0L && sizeByte % sectorSize == 0L) {
                "native block is not sector aligned: $startByte+$sizeByte"
            }
            val lba = startByte.toULong() / sectorSize.toULong()
            require(lba == nextLba) {
                "native block $block starts at sector $lba, expected $nextLba " +
                    "(snapshot must be ordered, non-overlapping and complete)"
            }
            val sectors = sizeByte.toULong() / sectorSize.toULong()
            require(sectors <= sectorCount - nextLba) {
                "native block $block ends past sectorCount $sectorCount"
            }
            nextLba += sectors
            val status =
                when (triples[index + 2]) {
                    COPIED -> RescueStatus.COPIED
                    FAILED -> RescueStatus.FAILED
                    NON_TRIED, NON_TRIMMED, NON_SCRAPED -> RescueStatus.NON_TRIED
                    else ->
                        throw IllegalArgumentException(
                            "unknown native block status ${triples[index + 2]} at $block",
                        )
                }
            val last = out.lastOrNull()
            if (last != null && last.status == status && last.start + last.count == lba) {
                out[out.lastIndex] = RescueMap.Span(last.start, last.count + sectors, status)
            } else {
                out += RescueMap.Span(lba, sectors, status)
            }
            index += 3
        }
        require(nextLba == sectorCount) {
            "native snapshot covers $nextLba sectors, expected $sectorCount (complete coverage required)"
        }
        return out
    }

    /** Re-marks inherited FAILED only where the snapshot left the sector unresolved. */
    private fun overlayInheritedFailed(
        spans: List<RescueMap.Span>,
        inheritedFailed: List<ULongRange>,
    ): List<RescueMap.Span> {
        if (inheritedFailed.isEmpty()) return spans
        val out = ArrayList<RescueMap.Span>(spans.size + inheritedFailed.size * 2)
        var cursor = 0
        for (span in spans) {
            if (span.status != RescueStatus.NON_TRIED) {
                out += span
                continue
            }
            val spanFirst = span.start
            val spanLast = span.start + span.count - 1UL
            while (cursor < inheritedFailed.size && inheritedFailed[cursor].last < spanFirst) {
                cursor++
            }
            var lba = spanFirst
            var i = cursor
            while (i < inheritedFailed.size) {
                val range = inheritedFailed[i]
                if (range.first > spanLast) break
                val from = maxOf(range.first, spanFirst, lba)
                if (from > spanLast) break
                if (from > lba) out += RescueMap.Span(lba, from - lba, RescueStatus.NON_TRIED)
                val to = minOf(range.last, spanLast)
                out += RescueMap.Span(from, to - from + 1UL, RescueStatus.FAILED)
                lba = to + 1UL
                if (range.last > spanLast) break
                i++
            }
            cursor = i
            if (lba <= spanLast) {
                out += RescueMap.Span(lba, spanLast - lba + 1UL, RescueStatus.NON_TRIED)
            }
        }
        return out
    }

    private fun write(out: LongArray, index: Int, range: ULongRange, status: Long): Int {
        out[index] = range.first.toLong()
        out[index + 1] = (range.last - range.first + 1UL).toLong()
        out[index + 2] = status
        return index + 3
    }
}
