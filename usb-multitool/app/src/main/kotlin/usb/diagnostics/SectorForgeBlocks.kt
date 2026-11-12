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
        uncertainWrites: List<ULongRange> = emptyList(),
        unresolvedAsFailed: Boolean = false,
    ) {
        require(triples.size % 3 == 0) { "block snapshot is not a triple list" }
        val spans = reconstruct(triples, target.sectorCount, sectorSize, unresolvedAsFailed)
        target.replaceRuns(overlayInheritedFailed(spans, inheritedFailed, uncertainWrites))
    }

    /** Maps the native triples to app runs, coalescing adjacent equal statuses. */
    private fun reconstruct(
        triples: LongArray,
        sectorCount: ULong,
        sectorSize: Int,
        unresolvedAsFailed: Boolean,
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
                    NON_TRIED, NON_TRIMMED, NON_SCRAPED ->
                        if (unresolvedAsFailed) RescueStatus.FAILED else RescueStatus.NON_TRIED
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
        uncertainWrites: List<ULongRange>,
    ): List<RescueMap.Span> {
        if (inheritedFailed.isEmpty() && uncertainWrites.isEmpty()) return spans
        val out = ArrayList<RescueMap.Span>(spans.size + (inheritedFailed.size + uncertainWrites.size) * 2)
        for (span in spans) {
            var pieces = listOf(span)
            if (span.status == RescueStatus.NON_TRIED) {
                for (range in inheritedFailed) pieces = paintRange(pieces, range, RescueStatus.FAILED)
            }
            for (range in uncertainWrites) pieces = paintRange(pieces, range, RescueStatus.NON_TRIED)
            out += pieces
        }
        return out
    }

    private fun paintRange(
        spans: List<RescueMap.Span>,
        range: ULongRange,
        status: RescueStatus,
    ): List<RescueMap.Span> {
        val out = ArrayList<RescueMap.Span>(spans.size + 2)
        for (span in spans) {
            val end = span.start + span.count
            val from = maxOf(span.start, range.first)
            val to = minOf(end, range.last + 1UL)
            if (from >= to) {
                out += span
                continue
            }
            if (span.start < from) out += RescueMap.Span(span.start, from - span.start, span.status)
            out += RescueMap.Span(from, to - from, status)
            if (to < end) out += RescueMap.Span(to, end - to, span.status)
        }
        return coalesce(out)
    }

    private fun coalesce(spans: List<RescueMap.Span>): List<RescueMap.Span> {
        val out = ArrayList<RescueMap.Span>(spans.size)
        for (span in spans) {
            val previous = out.lastOrNull()
            if (previous != null && previous.status == span.status && previous.start + previous.count == span.start) {
                out[out.lastIndex] = RescueMap.Span(previous.start, previous.count + span.count, span.status)
            } else {
                out += span
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
