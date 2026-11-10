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
    companion object {
        private const val MIB_BYTES = 1024L * 1024L

        fun read(disk: BlockDevice): PartitionTable {
            val mbr = MbrTable.read(disk)
            if (mbr.protective) {
                // 0xEE plus any other real MBR partition is hybrid-ambiguous.
                if (mbr.partitions.isNotEmpty()) {
                    return PartitionTable(
                        scheme = TableScheme.HYBRID,
                        partitions = mbr.partitions,
                        freeRegions = emptyList(),
                        diskSectorCount = disk.sectorCount,
                        sectorSize = disk.sectorSize,
                        hybridAmbiguous = true,
                    )
                }
                return fromGpt(disk, readGptPreferPrimary(disk))
            }
            if (mbr.partitions.isNotEmpty()) {
                // Classic MBR. Still probe GPT — hybrid layouts are ambiguous.
                // Parse failures mean "no GPT"; transport failures must surface.
                val gpt = try {
                    GptTable.read(disk)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (gpt != null && gpt.partitions.isNotEmpty()) {
                    return PartitionTable(
                        scheme = TableScheme.HYBRID,
                        partitions = mbr.partitions,
                        freeRegions = emptyList(),
                        diskSectorCount = disk.sectorCount,
                        sectorSize = disk.sectorSize,
                        hybridAmbiguous = true,
                    )
                }
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
                fromGpt(disk, GptTable.read(disk))
            } catch (_: IllegalArgumentException) {
                PartitionTable(
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
            }
        }

        /**
         * Read the primary GPT; on validation failure recover the table from the backup
         * copy at the last disk sector. The returned result is flagged as backup-sourced
         * so write paths can refuse the ambiguous primary entry-array location.
         */
        private fun readGptPreferPrimary(disk: BlockDevice): GptTable.Result {
            val primaryFailure = try {
                return GptTable.read(disk)
            } catch (failure: IllegalArgumentException) {
                failure
            }
            // A backup header needs its own sector below the primary header at LBA 1.
            if (disk.sectorCount < 3UL) throw primaryFailure
            val backupHeaderLba = disk.sectorCount - 1UL
            val backup = try {
                GptTable.read(disk, backupHeaderLba)
            } catch (failure: IllegalArgumentException) {
                primaryFailure.addSuppressed(failure)
                throw primaryFailure
            }
            val header = backup.header
            val entrySectors = (
                (header.entryCount.toLong() * header.entrySize.toLong() +
                    disk.sectorSize - 1) / disk.sectorSize
                ).toULong()
            // A backup header must point back at the primary header at LBA 1;
            // anything else means two different layouts are being mixed.
            require(header.backupLba == 1UL) {
                "backup GPT header at LBA $backupHeaderLba points at alternate LBA " +
                    "${header.backupLba}, expected 1; refusing ambiguous primary recovery"
            }
            require(header.entryLba > 0UL && entrySectors > 0UL &&
                header.entryLba < backupHeaderLba &&
                entrySectors <= backupHeaderLba - header.entryLba) {
                "backup GPT entries at LBA ${header.entryLba} overlap the backup header " +
                    "at LBA $backupHeaderLba"
            }
            require(header.lastUsable < header.entryLba) {
                "backup GPT usable range ends at LBA ${header.lastUsable}, " +
                    "past its entries at LBA ${header.entryLba}"
            }
            return backup.copy(fromBackup = true)
        }

        private fun fromGpt(disk: BlockDevice, gpt: GptTable.Result): PartitionTable {
            val header = gpt.header
            val entrySectors = (
                (header.entryCount.toLong() * header.entrySize.toLong() +
                    disk.sectorSize - 1) / disk.sectorSize
                ).toULong()
            // Backup entries occupy [backupLba-entrySectors, backupLba-1]; header is exclusive.
            // A backup-sourced header records its own LBA and entry LBA instead.
            val backupHeaderLba = if (gpt.fromBackup) header.currentLba else header.backupLba
            val backupEntriesLba =
                if (gpt.fromBackup) header.entryLba else header.backupLba - entrySectors
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
            var cursor = firstUsable
            for (p in ordered) {
                if (p.startLba > cursor) {
                    free += FreeRegion(cursor, p.startLba - cursor)
                }
                cursor = maxOf(cursor, p.startLba + p.sectorCount)
            }
            if (cursor < lastUsableExclusive) {
                free += FreeRegion(cursor, lastUsableExclusive - cursor)
            }
            return free.filter { it.sectorCount > 0UL }
        }
    }
}
