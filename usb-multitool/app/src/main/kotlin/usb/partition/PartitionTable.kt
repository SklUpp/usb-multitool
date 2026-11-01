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
) {
    companion object {
        private const val MIB_BYTES = 1024L * 1024L

        fun read(disk: BlockDevice): PartitionTable {
            val mbr = MbrTable.read(disk)
            if (mbr.protective) {
                val gpt = GptTable.read(disk)
                return fromGpt(disk, gpt)
            }
            if (mbr.partitions.isNotEmpty()) {
                // Classic MBR. Still probe GPT — hybrid layouts are ambiguous.
                val gpt = runCatching { GptTable.read(disk) }.getOrNull()
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
                    freeRegions = listOf(FreeRegion(0UL, disk.sectorCount)),
                    diskSectorCount = disk.sectorCount,
                    sectorSize = disk.sectorSize,
                )
            }
        }

        private fun fromGpt(disk: BlockDevice, gpt: GptTable.Result): PartitionTable {
            val entrySectors = (
                (gpt.header.entryCount.toLong() * gpt.header.entrySize.toLong() +
                    disk.sectorSize - 1) / disk.sectorSize
                ).toULong()
            // Backup entries sit immediately before the backup header at the disk end.
            val backupEntriesLba = gpt.header.backupLba - entrySectors + 1UL
            return PartitionTable(
                scheme = TableScheme.GPT,
                partitions = gpt.partitions,
                freeRegions = recomputeFree(
                    gpt.partitions,
                    disk.sectorCount,
                    alignmentSectors(disk.sectorSize),
                    firstUsable = gpt.header.firstUsable,
                    lastUsableExclusive = gpt.header.lastUsable + 1UL,
                ),
                diskSectorCount = disk.sectorCount,
                sectorSize = disk.sectorSize,
                gptBackupHeaderLba = gpt.header.backupLba,
                gptBackupEntriesLba = backupEntriesLba,
                gptPrimaryEntryLba = gpt.header.entryLba,
                gptHeader = gpt.header,
                gptEntriesRaw = gpt.entriesRaw,
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
