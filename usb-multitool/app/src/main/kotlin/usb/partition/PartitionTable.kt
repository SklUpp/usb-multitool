package usb.partition

import usb.device.BlockDevice

data class PartitionTable(
    val scheme: TableScheme,
    val partitions: List<Partition>,
    val freeRegions: List<FreeRegion>,
    val diskSectorCount: ULong,
    val sectorSize: Int = 512,
    val hybridAmbiguous: Boolean = false,
    /** Backup GPT header LBA from the CRC-valid backup header; null when unknown/invalid. */
    val gptBackupHeaderLba: ULong? = null,
    /** Backup entry-array LBA as recorded by that header; never guessed, null when unknown. */
    val gptBackupEntriesLba: ULong? = null,
    val gptPrimaryEntryLba: ULong? = null,
    val gptHeader: GptHeader? = null,
    val gptEntriesRaw: ByteArray? = null,
    /**
     * True when the primary header or entry array failed validation and this table was
     * recovered from the backup copy at the last disk sector. Read-only by contract:
     * the backup header does not record the primary entry-array location, so
     * [PartitionWriter.commit] and [GptBackup.relocateBackup] refuse such a table.
     */
    val gptPrimaryDamaged: Boolean = false,
) {
    /**
     * Ambiguous layouts — hybrid MBR/GPT, or a GPT surfaced past a malformed MBR — must not be
     * rewritten or formatted: either layout may own the sectors under the other's records.
     */
    fun requireUnambiguousLayout() {
        if (scheme == TableScheme.HYBRID || hybridAmbiguous) {
            throw HybridAmbiguityException(
                "partition layout is ambiguous; repair the table before changing partitions",
            )
        }
    }

    companion object {
        private const val MIB_BYTES = 1024L * 1024L

        fun read(disk: BlockDevice): PartitionTable {
            val mbr = try {
                MbrTable.read(disk)
            } catch (mbrFailure: IllegalArgumentException) {
                // A malformed MBR leaves two possible layouts: surface the GPT records as
                // ambiguous instead of publishing a writable GPT that could overwrite the
                // data the surviving MBR record still describes.
                val gpt = gptBehindDamagedMbr(disk, mbrFailure)
                return hybridAmbiguous(disk, gpt.partitions)
            }
            if (mbr.protective) {
                // 0xEE plus any other real MBR partition is hybrid-ambiguous.
                if (mbr.partitions.isNotEmpty()) return hybridAmbiguous(disk, mbr.partitions)
                return fromGpt(disk, readGptPreferPrimary(disk))
            }
            if (mbr.partitions.isNotEmpty()) {
                // Classic MBR. Only a fully validated GPT copy proves a hybrid layout; GPT
                // magic bytes inside ordinary data are not evidence of one.
                // Transport failures must surface.
                val gpt = try {
                    readGptPreferPrimary(disk)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (gpt != null) return hybridAmbiguous(disk, mbr.partitions)
                return PartitionTable(
                    scheme = TableScheme.MBR,
                    partitions = mbr.partitions,
                    freeRegions = recomputeFree(
                        mbr.partitions,
                        disk.sectorCount,
                        alignmentSectors(disk.sectorSize),
                    ),
                    diskSectorCount = disk.sectorCount,
                    sectorSize = disk.sectorSize,
                )
            }
            return try {
                fromGpt(disk, readGptPreferPrimary(disk))
            } catch (_: GptTable.MissingSignatureException) {
                emptyMbr(disk)
            }
        }

        private fun gptBehindDamagedMbr(
            disk: BlockDevice,
            mbrFailure: IllegalArgumentException,
        ): GptTable.Result = try {
            readGptPreferPrimary(disk)
        } catch (failure: IllegalArgumentException) {
            mbrFailure.addSuppressed(failure)
            throw mbrFailure
        }

        private fun hybridAmbiguous(disk: BlockDevice, mbrPartitions: List<Partition>) =
            PartitionTable(
                scheme = TableScheme.HYBRID,
                partitions = mbrPartitions,
                freeRegions = emptyList(),
                diskSectorCount = disk.sectorCount,
                sectorSize = disk.sectorSize,
                hybridAmbiguous = true,
            )

        private fun emptyMbr(disk: BlockDevice) = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = emptyList(),
            // Never offer LBA 0: a partition there would overwrite the MBR/GPT
            // metadata written by commit as soon as its filesystem is formatted.
            freeRegions = recomputeFree(
                emptyList(),
                disk.sectorCount,
                alignmentSectors(disk.sectorSize),
            ),
            diskSectorCount = disk.sectorCount,
            sectorSize = disk.sectorSize,
        )

        /**
         * Candidate selection for the primary copy: it must validate against its own metadata
         * *and* against the real metadata ranges of a backup copy that also exists on the
         * medium. A failure here is what moves the read onto the backup.
         */
        private fun readGptPreferPrimary(disk: BlockDevice): GptTable.Result {
            val primaryFailure = try {
                return validatePrimary(disk)
            } catch (failure: IllegalArgumentException) {
                failure
            }
            // A backup header needs its own sector below the primary header at LBA 1.
            if (disk.sectorCount < 3UL) throw primaryFailure
            val backup = try {
                GptTable.read(disk, disk.sectorCount - 1UL)
            } catch (failure: IllegalArgumentException) {
                primaryFailure.addSuppressed(failure)
                if (failure !is GptTable.MissingSignatureException) throw failure
                throw primaryFailure
            }
            return validateBackup(disk, backup).copy(fromBackup = true)
        }

        /**
         * Validate the primary copy together with the backup copy it points at. The backup
         * entry-array range comes from the backup header itself, never predicted from primary
         * geometry, so a crafted primary cannot hide an overlap with the real array.
         */
        private fun validatePrimary(disk: BlockDevice): GptTable.Result {
            val primary = GptTable.read(disk)
            val header = primary.header
            if (header.backupLba >= disk.sectorCount) {
                // Truncated copy: no backup lives on this medium; relocate runs before writes.
                return primary
            }
            // Only a CRC-valid header carries usable geometry; stray magic or a bit-rotted
            // header in that sector says nothing about a real backup copy.
            val backupHeader = GptTable.readHeaderOrNull(disk, header.backupLba) ?: return primary
            requireConsistent(header, backupHeader)
            requireDisjointCopies(disk, header, backupHeader)
            // The alternate header is the only authority on its entry array location; publish
            // it so raw range maps never predict a position from primary geometry.
            return primary.copy(backupEntriesLba = backupHeader.entryLba)
        }

        /** A recovered backup may not sit on the real primary metadata range either. */
        private fun validateBackup(disk: BlockDevice, backup: GptTable.Result): GptTable.Result {
            val header = backup.header
            val primaryHeader = GptTable.readHeaderOrNull(disk, 1UL) ?: return backup
            requireConsistent(primaryHeader, header)
            requireDisjointCopies(disk, primaryHeader, header, usable = header)
            return backup
        }

        private data class MetadataRange(
            val label: String,
            val startLba: ULong,
            val sectorCount: ULong,
        ) {
            val endExclusive: ULong get() = startLba + sectorCount
            fun overlaps(other: MetadataRange) =
                startLba < other.endExclusive && other.startLba < endExclusive
        }

        /**
         * Independent validation of both copies: the two header sectors, the two entry arrays
         * and the usable range must all be pairwise disjoint. A CRC and the disk GUID only
         * prove the bytes are self-consistent; a crafted backup header can still record an
         * entry array on top of its own sector (or of the primary metadata), which would be
         * wiped/rewritten over itself by initialization.
         */
        private fun requireDisjointCopies(
            disk: BlockDevice,
            primary: GptHeader,
            backup: GptHeader,
            usable: GptHeader = primary,
        ) {
            val sectorSize = disk.sectorSize
            val ranges = listOf(
                MetadataRange("primary header", primary.currentLba, 1UL),
                MetadataRange(
                    "primary entry array",
                    primary.entryLba,
                    GptTable.entrySectors(primary, sectorSize),
                ),
                MetadataRange("backup header", backup.currentLba, 1UL),
                MetadataRange(
                    "backup entry array",
                    backup.entryLba,
                    GptTable.entrySectors(backup, sectorSize),
                ),
            )
            for ((index, range) in ranges.withIndex()) {
                require(range.sectorCount > 0UL && range.endExclusive <= disk.sectorCount) {
                    "GPT ${range.label} at LBA ${range.startLba} lies outside the disk"
                }
                require(usable.lastUsable < range.startLba || usable.firstUsable >= range.endExclusive) {
                    "GPT usable range ${usable.firstUsable}..${usable.lastUsable} overlaps " +
                        "${range.label} at LBA ${range.startLba}..${range.endExclusive - 1UL}"
                }
                for (other in ranges.drop(index + 1)) {
                    require(!range.overlaps(other)) {
                        "GPT ${range.label} at LBA ${range.startLba}..${range.endExclusive - 1UL} " +
                            "overlaps ${other.label} at LBA ${other.startLba}.." +
                            "${other.endExclusive - 1UL}"
                    }
                }
            }
        }

        private fun requireConsistent(primary: GptHeader, backup: GptHeader) {
            require(backup.backupLba == 1UL) {
                "backup GPT header at LBA ${backup.currentLba} points at alternate LBA " +
                    "${backup.backupLba}, expected 1"
            }
            require(backup.diskGuid.contentEquals(primary.diskGuid)) {
                "primary and backup GPT describe different disks"
            }
            require(backup.entryCount == primary.entryCount &&
                backup.entrySize == primary.entrySize) {
                "primary and backup GPT disagree on entry geometry"
            }
        }

        private fun fromGpt(disk: BlockDevice, gpt: GptTable.Result): PartitionTable {
            val header = gpt.header
            // A CRC-valid backup header is the only authority on the backup copy: the array
            // position comes from it (never predicted as `backupLba - entrySectors`), and an
            // unreadable/bit-rotted header keeps both positions null so callers that rewrite
            // or erase GPT metadata refuse instead of guessing.
            val backupHeaderLba =
                when {
                    gpt.fromBackup -> header.currentLba
                    gpt.backupEntriesLba == null -> null
                    else -> header.backupLba
                }
            val backupEntriesLba =
                if (gpt.fromBackup) header.entryLba else gpt.backupEntriesLba
            // A shrunk copy can declare a usable end past the medium; recomputeFree clamps it.
            return PartitionTable(
                scheme = TableScheme.GPT,
                partitions = gpt.partitions,
                freeRegions = recomputeFree(
                    gpt.partitions,
                    disk.sectorCount,
                    alignmentSectors(disk.sectorSize),
                    firstUsable = header.firstUsable,
                    lastUsableExclusive = header.lastUsable + 1UL,
                ),
                diskSectorCount = disk.sectorCount,
                sectorSize = disk.sectorSize,
                gptBackupHeaderLba = backupHeaderLba,
                gptBackupEntriesLba = backupEntriesLba,
                gptPrimaryEntryLba = if (gpt.fromBackup) null else header.entryLba,
                gptHeader = header,
                gptEntriesRaw = gpt.entriesRaw,
                gptPrimaryDamaged = gpt.fromBackup,
            )
        }

        fun alignmentSectors(sectorSize: Int): ULong {
            require(MIB_BYTES % sectorSize == 0L) { "sector size does not divide 1 MiB" }
            return (MIB_BYTES / sectorSize).toULong()
        }

        fun recomputeFree(
            partitions: List<Partition>,
            diskSectorCount: ULong,
            align: ULong,
            firstUsable: ULong = align,
            lastUsableExclusive: ULong = diskSectorCount,
        ): List<FreeRegion> {
            val ordered = partitions.sortedBy { it.startLba }
            val free = mutableListOf<FreeRegion>()
            // A declared usable end may exceed the medium (shrunk copy awaiting relocate);
            // never advertise free space the disk cannot hold.
            val limit = minOf(lastUsableExclusive, diskSectorCount)
            var cursor = firstUsable
            for (p in ordered) {
                val gapEnd = minOf(p.startLba, limit)
                if (gapEnd > cursor) {
                    free += FreeRegion(cursor, gapEnd - cursor)
                }
                cursor = maxOf(cursor, p.startLba + p.sectorCount)
            }
            if (cursor < limit) {
                free += FreeRegion(cursor, limit - cursor)
            }
            return free
        }
    }
}
