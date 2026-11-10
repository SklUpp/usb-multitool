package usb.partition

import usb.device.BlockDevice

data class PartitionTable(
    val scheme: TableScheme,
    val partitions: List<Partition>,
    val freeRegions: List<FreeRegion>,
    val diskSectorCount: ULong,
    val sectorSize: Int = 512,
    val hybridAmbiguous: Boolean = false,
    val gptBackupHeaderLba: ULong? = null,
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
            val backupEntryEnd = backupHeader.entryLba +
                GptTable.entrySectors(backupHeader, disk.sectorSize)
            require(header.lastUsable < backupHeader.entryLba || header.firstUsable >= backupEntryEnd) {
                "GPT usable range ${header.firstUsable}..${header.lastUsable} overlaps backup " +
                    "metadata at LBA ${backupHeader.entryLba}..${backupEntryEnd - 1UL}"
            }
            return primary
        }

        /** A recovered backup may not sit on the real primary metadata range either. */
        private fun validateBackup(disk: BlockDevice, backup: GptTable.Result): GptTable.Result {
            val header = backup.header
            val primaryHeader = GptTable.readHeaderOrNull(disk, 1UL) ?: return backup
            requireConsistent(primaryHeader, header)
            val primaryEntryEnd = primaryHeader.entryLba +
                GptTable.entrySectors(primaryHeader, disk.sectorSize)
            require(header.lastUsable < primaryHeader.entryLba || header.firstUsable >= primaryEntryEnd) {
                "GPT usable range ${header.firstUsable}..${header.lastUsable} overlaps primary " +
                    "metadata at LBA ${primaryHeader.entryLba}..${primaryEntryEnd - 1UL}"
            }
            return backup
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
            val entrySectors = GptTable.entrySectors(header, disk.sectorSize)
            // Backup entries occupy [backupLba-entrySectors, backupLba-1]; header is exclusive.
            // A backup-sourced header records its own LBA and entry LBA instead.
            val backupHeaderLba = if (gpt.fromBackup) header.currentLba else header.backupLba
            val backupEntriesLba =
                if (gpt.fromBackup) header.entryLba else header.backupLba - entrySectors
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
