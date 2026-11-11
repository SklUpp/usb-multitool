package usb.diagnostics

enum class RescueStatus {
    NON_TRIED,
    COPIED,
    FAILED,
}

/**
 * Domain of a rescue map. COPY maps track a disk→image backup; RESTORE maps track
 * an image→disk write. Passing a COPY map into [Rescue.restore] would skip every
 * already-copied sector and leave only zero gaps on the stick — refuse that.
 */
enum class MapKind {
    COPY,
    RESTORE,
}

/**
 * ddrescue-style map over sector ranges using run-length encoding.
 * Supports disks of any size without a per-sector array or 512 MiB cap.
 * Partial output stays partial while any sector is failed or non-tried.
 */
class RescueMap private constructor(
    val sectorCount: ULong,
    val kind: MapKind,
    val sectorSize: Int?,
    /** Target disk identity (serial/label). Never binds COPIED runs to image content. */
    val identity: String?,
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

    /** Number of RLE runs — must stay ≤ [MAX_RUN_LINES] for encode/decode parity. */
    val runCount: Int get() = runs.size

    fun markFailed(start: ULong, count: ULong) = paint(start, count, RescueStatus.FAILED)

    fun markCopied(start: ULong, count: ULong) = paint(start, count, RescueStatus.COPIED)

    fun markNonTried(start: ULong, count: ULong) = paint(start, count, RescueStatus.NON_TRIED)

    /** Discard inherited progress (COPIED/FAILED) and restart from NON_TRIED. */
    fun resetProgress() {
        runs.clear()
        runs += Run(0UL, sectorCount, RescueStatus.NON_TRIED)
    }

    fun statusAt(lba: ULong): RescueStatus {
        require(lba < sectorCount)
        return runAt(lba).status
    }

    /** Immutable snapshot for a checkpoint writer (runs copied). */
    fun snapshot(): RescueMap =
        RescueMap(
            sectorCount = sectorCount,
            kind = kind,
            sectorSize = sectorSize,
            identity = identity,
            runs = runs.map { Run(it.start, it.count, it.status) }.toMutableList(),
        )

    /**
     * Serialize beside a partial image. Always emits v2 so restore can refuse a
     * COPY map; [decode] still accepts legacy v1 (`sectorCount=` first line).
     */
    fun encode(): String {
        require(runs.size <= MAX_RUN_LINES) {
            "rescue map has ${runs.size} runs; max $MAX_RUN_LINES — refuse encode of undecodable map"
        }
        return buildString {
            append("usb-rescue-map 2\n")
            append("kind=${kind.name}\n")
            if (sectorSize != null) append("sectorSize=$sectorSize\n")
            append("sectorCount=$sectorCount\n")
            if (identity != null) append("identity=$identity\n")
            for (run in runs) {
                append(run.start)
                append(' ')
                append(run.count)
                append(' ')
                append(run.status.name)
                append('\n')
            }
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
        require(count <= sectorCount - start)
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

    /**
     * Read-only facts for pre-restore inspection. Built from a successfully decoded map;
     * never used to admit a restore (the restore path still decodes and revalidates).
     */
    fun summary(): RescueMapSummary =
        RescueMapSummary(
            kind = kind,
            sectorCount = sectorCount,
            sectorSize = sectorSize,
            identity = identity,
            copiedRanges = copied,
            failedRanges = failed,
            nonTriedRanges = nonTried,
        )

    companion object {
        /** Soft cap against OOM from hostile SAF maps (~8 MiB UTF-8). */
        const val MAX_ENCODED_CHARS: Int = 8 * 1024 * 1024
        /** Cap run lines so decode cannot paint millions of fragments. */
        const val MAX_RUN_LINES: Int = 10_000

        /** @deprecated Prefer [forCopy]; kept for SelfTestRunner / older call sites. */
        fun whole(sectorCount: ULong): RescueMap = forCopy(sectorCount)

        fun forCopy(
            sectorCount: ULong,
            sectorSize: Int? = null,
            identity: String? = null,
        ): RescueMap {
            require(sectorCount > 0UL)
            if (sectorSize != null) requireValidSectorSize(sectorSize)
            return RescueMap(
                sectorCount = sectorCount,
                kind = MapKind.COPY,
                sectorSize = sectorSize,
                identity = identity,
                runs = mutableListOf(Run(0UL, sectorCount, RescueStatus.NON_TRIED)),
            )
        }

        fun forRestore(
            sectorCount: ULong,
            sectorSize: Int? = null,
            identity: String? = null,
        ): RescueMap {
            require(sectorCount > 0UL)
            if (sectorSize != null) requireValidSectorSize(sectorSize)
            return RescueMap(
                sectorCount = sectorCount,
                kind = MapKind.RESTORE,
                sectorSize = sectorSize,
                identity = identity,
                runs = mutableListOf(Run(0UL, sectorCount, RescueStatus.NON_TRIED)),
            )
        }

        fun decode(text: String): RescueMap {
            require(text.length <= MAX_ENCODED_CHARS) {
                "rescue map too large (${text.length} chars; max $MAX_ENCODED_CHARS)"
            }
            // Cap before materializing every line. Use the same separators as
            // lineSequence() (LF / CR / CRLF) — counting only '\\n' lets CR-only
            // maps bypass the guard and OOM in toList().
            val lineBudget = MAX_RUN_LINES + 17
            val boundedLineCount = text.lineSequence().take(lineBudget).count()
            require(boundedLineCount < lineBudget) {
                "rescue map has too many lines (≥$lineBudget); max runs $MAX_RUN_LINES"
            }
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            require(lines.isNotEmpty()) { "empty rescue map" }

            var kind = MapKind.COPY
            var sectorSize: Int? = null
            var identity: String? = null
            var sectorCount: ULong? = null
            var index = 0

            if (lines[0].startsWith("usb-rescue-map ")) {
                val ver = lines[0].removePrefix("usb-rescue-map ").trim()
                require(ver == "2") { "unsupported rescue map version: $ver" }
                index = 1
                while (index < lines.size) {
                    val line = lines[index]
                    when {
                        line.startsWith("kind=") ->
                            kind = MapKind.valueOf(line.removePrefix("kind="))
                        line.startsWith("sectorSize=") -> {
                            sectorSize = line.removePrefix("sectorSize=").toInt()
                            requireValidSectorSize(sectorSize)
                        }
                        line.startsWith("sectorCount=") ->
                            sectorCount = line.removePrefix("sectorCount=").toULong()
                        line.startsWith("identity=") ->
                            identity = line.removePrefix("identity=")
                        line[0].isDigit() -> break
                        else -> error("unknown rescue map header: $line")
                    }
                    index++
                }
            } else {
                // Legacy v1: first line sectorCount=N, implied kind=COPY.
                require(lines[0].startsWith("sectorCount=")) {
                    "rescue map must start with sectorCount= or usb-rescue-map 2"
                }
                sectorCount = lines[0].removePrefix("sectorCount=").toULong()
                index = 1
            }

            val count = requireNotNull(sectorCount) { "rescue map missing sectorCount" }
            val map =
                RescueMap(
                    sectorCount = count,
                    kind = kind,
                    sectorSize = sectorSize,
                    identity = identity,
                    runs = mutableListOf(Run(0UL, count, RescueStatus.NON_TRIED)),
                )
            val runLines = lines.drop(index)
            require(runLines.size <= MAX_RUN_LINES) {
                "rescue map has ${runLines.size} run lines; max $MAX_RUN_LINES"
            }
            for (line in runLines) {
                val parts = line.split(' ')
                require(parts.size == 3) { "bad rescue map run: $line" }
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

        private fun requireValidSectorSize(sectorSize: Int) {
            require(sectorSize in 512..4096 && sectorSize and (sectorSize - 1) == 0) {
                "invalid sectorSize $sectorSize"
            }
        }
    }
}

/**
 * Pre-restore snapshot of a decoded map. Counts and ranges are copied so a later
 * checkpoint mutation cannot change what the confirmation showed.
 */
data class RescueMapSummary(
    val kind: MapKind,
    val sectorCount: ULong,
    val sectorSize: Int?,
    /** Source disk identity recorded by the backup; never content proof. */
    val identity: String?,
    val copiedRanges: List<ULongRange>,
    val failedRanges: List<ULongRange>,
    val nonTriedRanges: List<ULongRange>,
) {
    val copiedSectors: ULong get() = copiedRanges.sumOf { it.last - it.first + 1UL }
    val failedSectors: ULong get() = failedRanges.sumOf { it.last - it.first + 1UL }
    val nonTriedSectors: ULong get() = nonTriedRanges.sumOf { it.last - it.first + 1UL }
}
