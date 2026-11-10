package usb.partition

import java.nio.ByteBuffer
import usb.device.BlockDevice

/**
 * Creates an empty MBR or GPT table on a disk, or replaces the current table with one.
 *
 * Safety rules (fail closed, no guessing):
 * - HYBRID / ambiguous layouts and a GPT recovered from its backup are refused.
 * - GPT metadata is only erased where the parsed header proves it lives. When the current
 *   table is a valid GPT its own header/entry LBAs (including the backup header's recorded
 *   entry LBA) are reused; a missing/bit-rotted backup header leaves the location unknown and
 *   such an initialization is refused instead of guessing.
 *   When the current table is MBR the read already proved that no valid GPT copy exists, so
 *   the new GPT is written directly at its standard positions.
 * - Writing never invents a "clear the first 33 sectors" range: every sector touched is
 *   derived from the parsed table or from the target layout computed for this disk.
 * - MBR is refused when the disk exceeds 32-bit LBA addressing.
 *
 * Ordering keeps every crash point readable by [PartitionTable.read] without a hybrid layout:
 * - to GPT from MBR/blank: empty MBR, sync, backup entries/header, sync, protective MBR,
 *   sync, primary entries/header, sync. The old MBR entries are gone before any GPT metadata
 *   exists, so a non-empty MBR plus a valid GPT can never be observed; after the backup
 *   lands the reader uses it until the primary header is written.
 * - to GPT from GPT: the old primary stays valid until the new backup is durable; only proven
 *   backup/entry LBAs are overwritten.
 * - to MBR: empty MBR first, sync, then both provable GPT headers, sync, then both entry
 *   arrays, sync. A crash still exposes either the old GPT (both headers intact), a valid
 *   backup-sourced GPT (one header gone), or an empty MBR (both headers gone) - never a
 *   CRC-valid header pointing at an erased array.
 * - after the target table is written the result is re-read completely: for GPT both the
 *   primary and the backup header/entry copies must parse and agree.

 */
object TableInitializer {
    private const val GPT_ENTRY_COUNT = 128
    private const val GPT_ENTRY_SIZE = 128
    private const val GPT_HEADER_SIZE = 92
    private const val MBR_SLOTS = 4
    private const val MBR_MAX_LBA = 0xFFFF_FFFFUL
    private const val MBR_TYPE_PROTECTIVE = 0xEE
    private const val MBR_SIGNATURE = 0xAA55

    /** Exact sectors this plan will touch, all proven from [table] or the target layout. */
    data class Plan(
        val target: TableScheme,
        val primaryHeaderLba: ULong,
        val primaryEntriesLba: ULong,
        val backupHeaderLba: ULong,
        val backupEntriesLba: ULong,
        val entryCount: Int,
        val entrySize: Int,
        val firstUsable: ULong,
        val lastUsable: ULong,
        val diskGuid: ByteArray,
        /**
         * GPT header sectors zeroed before the target table is written. Headers are always
         * erased (and synced) before any entry array, so a crash cannot leave a CRC-valid
         * header pointing at erased entries.
         */
        val eraseHeaders: List<FreeRegion>,
        /** GPT entry-array ranges erased after [eraseHeaders] (old metadata only). */
        val eraseArrays: List<FreeRegion>,
        /**
         * True when the current table is MBR: the old MBR entries are cleared before any GPT
         * metadata is published, so a reader can never observe a non-empty MBR plus a valid
         * GPT (hybrid) at an intermediate stage.
         */
        val writeEmptyMbrFirst: Boolean,
    )

    fun plan(table: PartitionTable, target: TableScheme): Plan {
        if (table.scheme == TableScheme.HYBRID || table.hybridAmbiguous) {
            throw HybridAmbiguityException(
                "hybrid/ambiguous layout: refusing to initialize a table over it",
            )
        }
        require(target == TableScheme.GPT || target == TableScheme.MBR) {
            "only MBR and GPT can be initialized"
        }
        val diskSectors = table.diskSectorCount
        val sectorSize = table.sectorSize
        return when (target) {
            TableScheme.GPT -> gptPlan(table, diskSectors, sectorSize)
            TableScheme.MBR -> mbrPlan(table, diskSectors)
            TableScheme.HYBRID -> error("unreachable")
        }
    }

    /**
     * Writes the planned empty table, syncing between the crash-visible stages, and verifies
     * by re-reading. Throws after the first write, so the caller reports `Partial`.
     */
    fun initialize(disk: BlockDevice, table: PartitionTable, target: TableScheme): String {
        require(disk.sectorCount == table.diskSectorCount) { "live disk geometry changed" }
        require(disk.sectorSize == table.sectorSize) { "live sector size changed" }
        val plan = plan(table, target)
        return when (target) {
            TableScheme.GPT -> initializeGpt(disk, plan)
            TableScheme.MBR -> initializeMbr(disk, plan)
            TableScheme.HYBRID -> error("unreachable")
        }
    }

    private fun gptPlan(table: PartitionTable, diskSectors: ULong, sectorSize: Int): Plan {
        require(!table.gptPrimaryDamaged) {
            "GPT primary is damaged and the table came from the backup; restore a matching " +
                "GPT backup first instead of initializing over it"
        }
        val existing = table.gptHeader
        if (table.scheme == TableScheme.GPT && existing != null) {
            // Reuse the geometry the parsed header proves, never a guessed 33-sector span.
            require(existing.entrySize == GPT_ENTRY_SIZE) {
                "cannot initialize over a GPT with entry size ${existing.entrySize}"
            }
            val entrySectors = GptTable.entrySectors(existing, sectorSize)
            require(entrySectors > 0UL) { "empty GPT entry array" }
            val primaryEntries = table.gptPrimaryEntryLba ?: existing.entryLba
            val backupHeader = table.gptBackupHeaderLba
                ?: throw IllegalArgumentException(
                    "backup GPT header is missing or invalid; restore a matching GPT backup " +
                        "first instead of guessing its location",
                )
            val backupEntries = table.gptBackupEntriesLba
                ?: throw IllegalArgumentException(
                    "backup GPT entry location is unknown; refusing to guess it",
                )
            require(primaryEntries >= 2UL && primaryEntries + entrySectors <= diskSectors) {
                "primary GPT entries outside the disk"
            }
            require(backupHeader > entrySectors) { "backup GPT header overlaps the disk start" }
            require(backupHeader < diskSectors) { "backup GPT header outside the disk" }
            require(backupEntries + entrySectors <= diskSectors) {
                "backup GPT entries outside the disk"
            }
            // Re-prove the layout: the writer must never place one copy's array on top of the
            // other copy's header/array, even if the table was built programmatically.
            require(primaryEntries + entrySectors <= backupHeader) {
                "primary GPT entries overlap the backup header"
            }
            require(backupEntries + entrySectors <= backupHeader) {
                "backup GPT entries overlap the backup header"
            }
            require(primaryEntries + entrySectors <= backupEntries ||
                backupEntries + entrySectors <= primaryEntries) {
                "GPT primary and backup entry arrays overlap"
            }
            require(primaryEntries + entrySectors <= existing.firstUsable) {
                "GPT entries overlap the usable range"
            }
            require(backupEntries > existing.lastUsable) {
                "backup GPT entries overlap the usable range"
            }
            return Plan(
                target = TableScheme.GPT,
                primaryHeaderLba = existing.currentLba,
                primaryEntriesLba = primaryEntries,
                backupHeaderLba = backupHeader,
                backupEntriesLba = backupEntries,
                entryCount = existing.entryCount,
                entrySize = existing.entrySize,
                firstUsable = existing.firstUsable,
                lastUsable = existing.lastUsable,
                // Keep the disk GUID so boot entries that reference it stay meaningful.
                diskGuid = existing.diskGuid,
                eraseHeaders = emptyList(),
                eraseArrays = emptyList(),
                writeEmptyMbrFirst = false,
            )
        }
        // Blank or MBR disk: read proved no valid GPT copy exists, so standard geometry is safe.
        val entrySectors = ((GPT_ENTRY_COUNT * GPT_ENTRY_SIZE + sectorSize - 1) / sectorSize).toULong()
        val primaryEntries = 2UL
        val backupHeader = diskSectors - 1UL
        val backupEntries = backupHeader - entrySectors
        val firstUsable = primaryEntries + entrySectors
        val lastUsable = backupEntries - 1UL
        require(diskSectors > entrySectors * 2UL + 3UL && lastUsable >= firstUsable) {
            "disk is too small for a GPT table"
        }
        return Plan(
            target = TableScheme.GPT,
            primaryHeaderLba = 1UL,
            primaryEntriesLba = primaryEntries,
            backupHeaderLba = backupHeader,
            backupEntriesLba = backupEntries,
            entryCount = GPT_ENTRY_COUNT,
            entrySize = GPT_ENTRY_SIZE,
            firstUsable = firstUsable,
            lastUsable = lastUsable,
            diskGuid = randomDiskGuid(),
            eraseHeaders = emptyList(),
            eraseArrays = emptyList(),
            // Blank and MBR disks must lose their old entries before any GPT metadata exists.
            writeEmptyMbrFirst = table.scheme == TableScheme.MBR,
        )
    }

    private fun mbrPlan(table: PartitionTable, diskSectors: ULong): Plan {
        require(diskSectors in 1UL..MBR_MAX_LBA) {
            "disk of $diskSectors sectors exceeds MBR 32-bit LBA addressing"
        }
        val erase = when (table.scheme) {
            TableScheme.GPT -> {
                require(!table.gptPrimaryDamaged) {
                    "GPT primary is damaged; restore a matching GPT backup first"
                }
                val header = table.gptHeader
                    ?: throw IllegalArgumentException("GPT scheme without a parsed header")
                require(header.entrySize == GPT_ENTRY_SIZE) {
                    "cannot remove a GPT with entry size ${header.entrySize} by erasing it"
                }
                val entrySectors = GptTable.entrySectors(header, table.sectorSize)
                val primaryEntries = table.gptPrimaryEntryLba ?: header.entryLba
                val backupHeader = table.gptBackupHeaderLba
                    ?: throw IllegalArgumentException(
                        "backup GPT header is missing or invalid; refusing to erase guessed " +
                            "metadata — restore a matching GPT backup first",
                    )
                val backupEntries = table.gptBackupEntriesLba
                    ?: throw IllegalArgumentException(
                        "backup GPT entry location is unknown; refusing to guess what to erase",
                    )
                require(header.currentLba < diskSectors && backupHeader < diskSectors) {
                    "GPT header outside the disk"
                }
                require(primaryEntries >= 2UL && primaryEntries + entrySectors <= diskSectors) {
                    "primary GPT entries outside the disk"
                }
                require(backupEntries + entrySectors <= diskSectors) {
                    "backup GPT entries outside the disk"
                }
                listOf(
                    FreeRegion(header.currentLba, 1UL) to FreeRegion(primaryEntries, entrySectors),
                    FreeRegion(backupHeader, 1UL) to FreeRegion(backupEntries, entrySectors),
                )
            }
            TableScheme.MBR -> emptyList()
            TableScheme.HYBRID -> error("hybrid refused above")
        }.unzip()
        return Plan(
            target = TableScheme.MBR,
            primaryHeaderLba = 0UL,
            primaryEntriesLba = 0UL,
            backupHeaderLba = 0UL,
            backupEntriesLba = 0UL,
            entryCount = MBR_SLOTS,
            entrySize = 16,
            firstUsable = 1UL,
            lastUsable = diskSectors - 1UL,
            diskGuid = ByteArray(16),
            eraseHeaders = erase.first,
            eraseArrays = erase.second,
            writeEmptyMbrFirst = false,
        )
    }

    private fun initializeGpt(disk: BlockDevice, plan: Plan): String {
        val sectorSize = disk.sectorSize
        val entries = ByteArray(plan.entryCount * plan.entrySize)
        val entriesCrc = GptTable.crc32(entries)
        val entrySectors = ((entries.size.toLong() + sectorSize - 1) / sectorSize).toInt()
        val padded = entries.copyOf(entrySectors * sectorSize)

        if (plan.writeEmptyMbrFirst) {
            // Clear the old MBR entries before any GPT metadata exists: a reader must never
            // see a non-empty MBR together with a valid GPT (hybrid) at a crash point.
            disk.write(0UL, ByteBuffer.wrap(emptyMbr(sectorSize)))
            disk.sync()
        }

        writeSectors(disk, plan.backupEntriesLba, padded, sectorSize)
        disk.write(
            plan.backupHeaderLba,
            ByteBuffer.wrap(
                GptBackup.buildEmptyHeader(
                    diskGuid = plan.diskGuid,
                    entryCount = plan.entryCount,
                    entrySize = plan.entrySize,
                    headerSize = GPT_HEADER_SIZE,
                    currentLba = plan.backupHeaderLba,
                    backupLba = plan.primaryHeaderLba,
                    firstUsable = plan.firstUsable,
                    lastUsable = plan.lastUsable,
                    entryLba = plan.backupEntriesLba,
                    entriesCrc = entriesCrc,
                    sectorSize = sectorSize,
                ),
            ),
        )
        // Durable backup before the primary MBR/header can point at it.
        disk.sync()

        disk.write(0UL, ByteBuffer.wrap(protectiveMbr(disk.sectorCount, sectorSize)))
        disk.sync()

        writeSectors(disk, plan.primaryEntriesLba, padded, sectorSize)
        disk.write(
            plan.primaryHeaderLba,
            ByteBuffer.wrap(
                GptBackup.buildEmptyHeader(
                    diskGuid = plan.diskGuid,
                    entryCount = plan.entryCount,
                    entrySize = plan.entrySize,
                    headerSize = GPT_HEADER_SIZE,
                    currentLba = plan.primaryHeaderLba,
                    backupLba = plan.backupHeaderLba,
                    firstUsable = plan.firstUsable,
                    lastUsable = plan.lastUsable,
                    entryLba = plan.primaryEntriesLba,
                    entriesCrc = entriesCrc,
                    sectorSize = sectorSize,
                ),
            ),
        )
        disk.sync()
        requireScheme(disk, TableScheme.GPT)
        requireMatchingGptCopies(disk)
        return "Initialized empty GPT: ${plan.entryCount} entry slots, usable LBA " +
            "${plan.firstUsable}..${plan.lastUsable}"
    }

    private fun initializeMbr(disk: BlockDevice, plan: Plan): String {
        val sectorSize = disk.sectorSize
        // Replace the old table first; the erased GPT metadata is only unreferenced afterwards.
        disk.write(0UL, ByteBuffer.wrap(emptyMbr(sectorSize)))
        disk.sync()

        // Erase both headers before any entry array: until both are gone the medium still
        // reads as GPT (possibly from the surviving copy), never as an empty MBR with a
        // CRC-valid header over erased entries.
        for (region in plan.eraseHeaders) {
            zeroRange(disk, region, sectorSize)
        }
        disk.sync()
        for (region in plan.eraseArrays) {
            zeroRange(disk, region, sectorSize)
        }
        disk.sync()
        requireScheme(disk, TableScheme.MBR)
        return "Initialized empty MBR: 4 primary slots, " +
            "${plan.eraseHeaders.size + plan.eraseArrays.size} GPT metadata range(s) erased"
    }

    /** Re-read both GPT copies: a working backup must parse and describe the same entries. */
    private fun requireMatchingGptCopies(disk: BlockDevice) {
        val primary = GptTable.read(disk)
        val backup = try {
            GptTable.read(disk, primary.header.backupLba)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException(
                "backup GPT copy is unreadable after initialization: ${failure.message}",
                failure,
            )
        }
        require(
            backup.header.entriesCrc == primary.header.entriesCrc &&
                backup.entriesRaw.contentEquals(primary.entriesRaw) &&
                backup.header.diskGuid.contentEquals(primary.header.diskGuid) &&
                backup.header.entryLba != primary.header.entryLba &&
                backup.header.firstUsable == primary.header.firstUsable &&
                backup.header.lastUsable == primary.header.lastUsable,
        ) {
            "backup GPT copy does not match the primary after initialization"
        }
    }

    private fun requireScheme(disk: BlockDevice, expected: TableScheme) {
        val reread = PartitionTable.read(disk)
        require(reread.scheme == expected) {
            "table initialization did not take effect: read back ${reread.scheme}, expected $expected"
        }
        require(reread.partitions.isEmpty()) {
            "table initialization left ${reread.partitions.size} partition(s)"
        }
    }

    private fun protectiveMbr(diskSectors: ULong, sectorSize: Int): ByteArray {
        val sector = ByteArray(sectorSize)
        val count = minOf(diskSectors - 1UL, MBR_MAX_LBA)
        sector[446 + 4] = MBR_TYPE_PROTECTIVE.toByte()
        putLe32(sector, 446 + 8, 1UL)
        putLe32(sector, 446 + 12, count)
        sector[510] = (MBR_SIGNATURE and 0xFF).toByte()
        sector[511] = ((MBR_SIGNATURE shr 8) and 0xFF).toByte()
        return sector
    }

    private fun emptyMbr(sectorSize: Int): ByteArray {
        val sector = ByteArray(sectorSize)
        sector[510] = (MBR_SIGNATURE and 0xFF).toByte()
        sector[511] = ((MBR_SIGNATURE shr 8) and 0xFF).toByte()
        return sector
    }

    private fun zeroRange(disk: BlockDevice, region: FreeRegion, sectorSize: Int) {
        require(region.sectorCount > 0UL) { "empty erase range" }
        require(region.startLba <= disk.sectorCount - region.sectorCount) {
            "erase range ${region.startLba}+${region.sectorCount} outside the disk"
        }
        val zero = ByteArray(sectorSize)
        var lba = region.startLba
        var remaining = region.sectorCount
        while (remaining > 0UL) {
            disk.write(lba, ByteBuffer.wrap(zero))
            lba += 1UL
            remaining -= 1UL
        }
    }

    private fun writeSectors(disk: BlockDevice, startLba: ULong, raw: ByteArray, sectorSize: Int) {
        require(raw.isNotEmpty() && raw.size % sectorSize == 0) {
            "payload must be a positive multiple of the sector size"
        }
        disk.write(startLba, ByteBuffer.wrap(raw))
    }

    private fun randomDiskGuid(): ByteArray {
        val uuid = java.util.UUID.randomUUID()
        val hex = uuid.toString().replace("-", "")
        val raw = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // GPT stores the first three GUID fields little-endian, like the partition GUIDs.
        raw.copyOfRange(0, 4).reversedArray().copyInto(raw, 0)
        raw.copyOfRange(4, 6).reversedArray().copyInto(raw, 4)
        raw.copyOfRange(6, 8).reversedArray().copyInto(raw, 6)
        return raw
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
        }
    }
}
