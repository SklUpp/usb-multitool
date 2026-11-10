package usb.partition

/** Absolute sector range for a raw write: [sectorCount] sectors starting at [startLba]. */
data class RawWriteRange(
    val startLba: ULong,
    val sectorCount: ULong,
) {
    init {
        require(sectorCount > 0UL) { "raw range must cover at least one sector" }
    }

    /**
     * Exclusive end. Only meaningful for a range already accepted by [RawRangePlan];
     * validation guarantees that the sum cannot wrap.
     */
    val endLbaExclusive: ULong get() = startLba + sectorCount

    fun byteLength(sectorSize: Int): ULong {
        require(sectorSize > 0) { "sector size must be positive" }
        return sectorCount * sectorSize.toULong()
    }
}

/** What a raw write can destroy; partitions carry a name, metadata zones do not. */
enum class RawObjectKind {
    Partition,
    BootArea,
    TableArea,
}

data class RawRangeObject(
    val startLba: ULong,
    val sectorCount: ULong,
    val name: String?,
    val kind: RawObjectKind,
) {
    /** Exclusive end, saturated instead of wrapping for a malformed claim. */
    val endLbaExclusive: ULong
        get() = if (startLba > ULong.MAX_VALUE - sectorCount) ULong.MAX_VALUE else startLba + sectorCount
}

/** Why the on-disk scheme cannot be trusted; rendered by the UI, never guessed here. */
enum class RawSchemeProblem {
    TableUnreadable,
    HybridAmbiguous,
    GptPrimaryDamaged,
    GptBackupMetadataUnknown,
}

sealed interface SchemeTrust {
    data class Confirmed(val scheme: TableScheme) : SchemeTrust
    data class Damaged(val reason: RawSchemeProblem) : SchemeTrust
    data class Unreadable(val reason: RawSchemeProblem) : SchemeTrust
}

data class RawRangeOverlap(
    val target: RawRangeObject,
    val overlapSectors: ULong,
    val fullyCovered: Boolean,
)

/**
 * Disk geometry and the objects a raw range may overwrite. Built once per partition
 * refresh; the UI never re-derives sector counts from byte capacities.
 */
data class RawRangeGeometry(
    val diskSectorCount: ULong,
    val sectorSize: Int,
    val trust: SchemeTrust,
    val objects: List<RawRangeObject> = emptyList(),
    /**
     * False when some partition-table metadata has no known sector location, so
     * [overlaps] cannot promise that every byte of the table would be reported.
     */
    val metadataComplete: Boolean = true,
    val metadataProblem: RawSchemeProblem? = null,
) {
    /** Dial positions that land exactly on a partition edge; empty for an untrusted scheme. */
    val snapPoints: List<ULong>
        get() {
            if (trust !is SchemeTrust.Confirmed) return emptyList()
            val points = sortedSetOf<ULong>()
            points += 0UL
            if (diskSectorCount > 0UL) points += diskSectorCount
            for (objectInDisk in objects) {
                if (objectInDisk.kind != RawObjectKind.Partition) continue
                if (objectInDisk.startLba > diskSectorCount) continue
                points += objectInDisk.startLba
                val end = objectInDisk.endLbaExclusive
                if (end <= diskSectorCount) points += end
            }
            return points.toList()
        }

    /**
     * Objects intersected by `[startLba, startLba + sectorCount)`, sorted by start.
     * An empty or wrapping range reports nothing instead of inventing an overlap.
     */
    fun overlaps(startLba: ULong, sectorCount: ULong): List<RawRangeOverlap> {
        if (sectorCount == 0UL || startLba > ULong.MAX_VALUE - sectorCount) return emptyList()
        val end = startLba + sectorCount
        val hits = mutableListOf<RawRangeOverlap>()
        for (objectInDisk in objects) {
            if (objectInDisk.sectorCount == 0UL) continue
            val objectEnd = objectInDisk.endLbaExclusive
            val from = maxOf(startLba, objectInDisk.startLba)
            val to = minOf(end, objectEnd)
            if (to <= from) continue
            hits += RawRangeOverlap(
                target = objectInDisk,
                overlapSectors = to - from,
                // True when the query range lies entirely inside the object (not when
                // the query swallows the object). A one-sector stab into a table zone
                // is fully covered by that zone.
                fullyCovered = objectInDisk.startLba <= startLba && objectEnd >= end,
            )
        }
        return hits.sortedBy { it.target.startLba }
    }

    companion object {
        fun unreadable(diskSectorCount: ULong, sectorSize: Int): RawRangeGeometry =
            RawRangeGeometry(
                diskSectorCount = diskSectorCount,
                sectorSize = sectorSize,
                trust = SchemeTrust.Unreadable(RawSchemeProblem.TableUnreadable),
                metadataComplete = false,
                metadataProblem = RawSchemeProblem.TableUnreadable,
            )

        /**
         * Objects for [table]; null means the table read failed, which is reported as
         * unreadable rather than as "no partitions". Metadata zones are real sector
         * ranges: GPT primary region and GPT backup entries+header are separate objects.
         */
        fun from(table: PartitionTable?, diskSectorCount: ULong, sectorSize: Int): RawRangeGeometry {
            if (table == null) return unreadable(diskSectorCount, sectorSize)
            val objects = mutableListOf<RawRangeObject>()
            val header = table.gptHeader
            var metadataProblem: RawSchemeProblem? = null
            if (header != null) {
                val entrySectors = GptTable.entrySectors(header, sectorSize)
                // A table recovered from the backup does not know where the primary
                // entries live; the whole [0, firstUsable) window is the honest bound.
                val primaryEnd = table.gptPrimaryEntryLba?.let { it + entrySectors } ?: header.firstUsable
                addZone(objects, 0UL, primaryEnd, RawObjectKind.TableArea, diskSectorCount)
                val backupHeaderLba = table.gptBackupHeaderLba
                val backupEntriesLba = table.gptBackupEntriesLba
                when {
                    backupHeaderLba == null ->
                        metadataProblem = RawSchemeProblem.GptBackupMetadataUnknown
                    // A truncated copy declares its backup beyond the medium: there is no
                    // backup metadata here to hide, so nothing is claimed incomplete.
                    backupHeaderLba >= diskSectorCount -> Unit
                    backupEntriesLba == null ->
                        // The validated backup header did not describe its entry array, so
                        // its location is unknown; never predict or hide it.
                        metadataProblem = RawSchemeProblem.GptBackupMetadataUnknown
                    else -> {
                        // A displaced array is legal. The envelope from the array start to
                        // the later of the array end and the header covers both, so no
                        // real backup metadata sector can fall outside the reported zone.
                        val arrayEnd = backupEntriesLba + entrySectors
                        addZone(
                            objects,
                            backupEntriesLba,
                            maxOf(backupHeaderLba + 1UL, arrayEnd),
                            RawObjectKind.TableArea,
                            diskSectorCount,
                        )
                    }
                }
            } else {
                val bootEnd = PartitionTable.alignmentSectors(sectorSize)
                addZone(objects, 0UL, bootEnd, RawObjectKind.BootArea, diskSectorCount)
            }
            for (partition in table.partitions) {
                objects += RawRangeObject(
                    startLba = partition.startLba,
                    sectorCount = partition.sectorCount,
                    name = partition.name.takeIf { it.isNotBlank() },
                    kind = RawObjectKind.Partition,
                )
            }
            val hybrid =
                table.hybridAmbiguous || table.scheme == TableScheme.HYBRID
            val trust = when {
                hybrid -> SchemeTrust.Damaged(RawSchemeProblem.HybridAmbiguous)
                table.gptPrimaryDamaged -> SchemeTrust.Damaged(RawSchemeProblem.GptPrimaryDamaged)
                else -> SchemeTrust.Confirmed(table.scheme)
            }
            if (hybrid) {
                // MBR view hides the GPT metadata that also lives on the medium, so an
                // overlap list cannot be claimed complete.
                metadataProblem = RawSchemeProblem.HybridAmbiguous
            }
            return RawRangeGeometry(
                diskSectorCount = diskSectorCount,
                sectorSize = sectorSize,
                trust = trust,
                objects = objects,
                metadataComplete = metadataProblem == null,
                metadataProblem = metadataProblem,
            )
        }

        private fun addZone(
            out: MutableList<RawRangeObject>,
            startLba: ULong,
            endLbaExclusive: ULong,
            kind: RawObjectKind,
            diskSectorCount: ULong,
        ) {
            if (startLba >= diskSectorCount || endLbaExclusive <= startLba) return
            val end = minOf(endLbaExclusive, diskSectorCount)
            out += RawRangeObject(
                startLba = startLba,
                sectorCount = end - startLba,
                name = null,
                kind = kind,
            )
        }
    }
}

/** Hard rejections: the write cannot be performed as asked, so nothing may be written. */
enum class RawRangeProblem {
    EmptyRange,
    BeyondEndOfDisk,
    RangeTooLarge,
    SourceLargerThanRange,
}

sealed interface RawRangeWarning {
    data class OverlapsObjects(val overlaps: List<RawRangeOverlap>) : RawRangeWarning
    data class UnalignedStart(val startLba: ULong, val alignmentSectors: ULong) : RawRangeWarning
    data class SchemeNotTrusted(val trust: SchemeTrust) : RawRangeWarning

    /** Some table metadata has no known location; the overlap list is not exhaustive. */
    data class MetadataCoverageUnknown(val reason: RawSchemeProblem) : RawRangeWarning
}

sealed interface RawRangeCheck {
    data class Ok(val warnings: List<RawRangeWarning>) : RawRangeCheck
    data class Rejected(val problems: Set<RawRangeProblem>) : RawRangeCheck
}

/**
 * Range arithmetic shared by the UI draft, the confirmation path and the writer.
 * Overlaps are warnings: raw writing over a partition is a legitimate, confirmed action.
 */
object RawRangePlan {
    /**
     * Problems that make the range impossible regardless of the partition table.
     * Used by the raw writer before the first sector write.
     */
    private fun boundProblems(
        startLba: ULong,
        sectorCount: ULong,
        diskSectorCount: ULong,
        sectorSize: Int,
    ): Set<RawRangeProblem> {
        val problems = linkedSetOf<RawRangeProblem>()
        if (sectorCount == 0UL) {
            problems += RawRangeProblem.EmptyRange
            return problems
        }
        if (startLba >= diskSectorCount || sectorCount > diskSectorCount - startLba) {
            problems += RawRangeProblem.BeyondEndOfDisk
        }
        if (sectorCount > maxRepresentableSectors(sectorSize)) {
            problems += RawRangeProblem.RangeTooLarge
        }
        return problems
    }

    fun check(
        startLba: ULong,
        sectorCount: ULong,
        geometry: RawRangeGeometry,
        sourceBytes: ULong? = null,
    ): RawRangeCheck {
        val problems =
            boundProblems(startLba, sectorCount, geometry.diskSectorCount, geometry.sectorSize)
                .toMutableSet()
        if (sourceBytes != null &&
            sectorCount > 0UL &&
            sectorCount <= maxRepresentableSectors(geometry.sectorSize)
        ) {
            val rangeBytes = sectorCount * geometry.sectorSize.toULong()
            if (sourceBytes > rangeBytes) problems += RawRangeProblem.SourceLargerThanRange
        }
        if (problems.isNotEmpty()) return RawRangeCheck.Rejected(problems)

        val warnings = mutableListOf<RawRangeWarning>()
        if (geometry.trust !is SchemeTrust.Confirmed) {
            warnings += RawRangeWarning.SchemeNotTrusted(geometry.trust)
        }
        if (!geometry.metadataComplete) {
            warnings += RawRangeWarning.MetadataCoverageUnknown(
                geometry.metadataProblem ?: RawSchemeProblem.GptBackupMetadataUnknown,
            )
        }
        val alignment = PartitionTable.alignmentSectors(geometry.sectorSize)
        if (startLba % alignment != 0UL) {
            warnings += RawRangeWarning.UnalignedStart(startLba, alignment)
        }
        val overlaps = geometry.overlaps(startLba, sectorCount)
        if (overlaps.isNotEmpty()) {
            warnings += RawRangeWarning.OverlapsObjects(overlaps)
        }
        return RawRangeCheck.Ok(warnings)
    }

    /** Throws [IllegalArgumentException] for a range that cannot be written at all. */
    fun requireWritable(range: RawWriteRange, diskSectorCount: ULong, sectorSize: Int) {
        val problems = boundProblems(range.startLba, range.sectorCount, diskSectorCount, sectorSize)
        require(problems.isEmpty()) {
            "raw range ${range.startLba}+${range.sectorCount} rejected: " +
                problems.joinToString { it.name }
        }
    }

    /**
     * Largest sector count whose byte length plus the final sector pad still fits
     * the signed progress/readback arithmetic.
     */
    private fun maxRepresentableSectors(sectorSize: Int): ULong {
        require(sectorSize > 0) { "sector size must be positive" }
        val maxBytes = Long.MAX_VALUE - sectorSize.toLong() + 1L
        return maxBytes.toULong() / sectorSize.toULong()
    }
}
