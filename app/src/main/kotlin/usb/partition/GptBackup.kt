package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import usb.device.BlockDevice

/**
 * Critical-sector backup and GPT backup-header relocation after a smaller image write.
 * Does not move or resize occupied partitions.
 */
object GptBackup {
    const val PRIMARY_SECTORS = 34 // 512-byte geometry default
    const val BACKUP_SECTORS = 33 // 512-byte geometry default

    /** Same ceiling as [GptTable]: refuse crafted entry arrays before allocation. */
    private const val MAX_ENTRY_ARRAY_BYTES = 1L * 1024L * 1024L

    /** Head + tail sector counts for a GPT critical-sector blob. */
    data class CriticalGeometry(
        val primarySectors: Int,
        val backupSectors: Int,
        val entryCount: Int,
        val entrySize: Int,
    ) {
        fun blobBytes(sectorSize: Int): Int {
            require(sectorSize > 0) { "invalid sector size $sectorSize" }
            val total = (primarySectors.toLong() + backupSectors.toLong()) * sectorSize.toLong()
            require(total in 1L..Int.MAX_VALUE.toLong()) {
                "GPT critical backup size $total exceeds Int range"
            }
            return total.toInt()
        }
    }

    fun primarySectors(sectorSize: Int, entryCount: Int = 128, entrySize: Int = 128): Int =
        entrySpanSectors(sectorSize, entryCount, entrySize, headerSectors = 2)

    fun backupSectors(sectorSize: Int, entryCount: Int = 128, entrySize: Int = 128): Int =
        entrySpanSectors(sectorSize, entryCount, entrySize, headerSectors = 1)

    private fun entrySpanSectors(
        sectorSize: Int,
        entryCount: Int,
        entrySize: Int,
        headerSectors: Int,
    ): Int {
        require(sectorSize > 0) { "invalid sector size $sectorSize" }
        require(entryCount > 0 && entrySize > 0) {
            "invalid GPT entry geometry ${entryCount}x${entrySize}"
        }
        val entryBytes = entryCount.toLong() * entrySize.toLong()
        require(entryBytes in 1L..MAX_ENTRY_ARRAY_BYTES) {
            "GPT entry array is too large: $entryBytes bytes"
        }
        val entrySectors = (entryBytes + sectorSize.toLong() - 1L) / sectorSize.toLong()
        val total = headerSectors.toLong() + entrySectors
        require(total in 1L..Int.MAX_VALUE.toLong()) {
            "GPT critical span $total sectors exceeds Int range"
        }
        return total.toInt()
    }

    fun criticalGeometry(sectorSize: Int, entryCount: Int = 128, entrySize: Int = 128): CriticalGeometry {
        val primary = primarySectors(sectorSize, entryCount, entrySize)
        val backup = backupSectors(sectorSize, entryCount, entrySize)
        val blob = (primary.toLong() + backup.toLong()) * sectorSize.toLong()
        require(blob in 1L..Int.MAX_VALUE.toLong()) {
            "GPT critical backup size $blob exceeds Int range"
        }
        return CriticalGeometry(
            primarySectors = primary,
            backupSectors = backup,
            entryCount = entryCount,
            entrySize = entrySize,
        )
    }

    /**
     * Derive capture/restore head+tail sizes from a parsed primary header. Refuses layouts
     * the contiguous head/tail blob cannot represent (entries not at LBA 2, backup not at
     * disk end, mismatched usable bounds). Geometry is taken from header entryCount/entrySize
     * so restore never assumes the 128×128 defaults.
     */
    fun criticalGeometry(header: GptHeader, sectorSize: Int, sectorCount: ULong): CriticalGeometry {
        require(header.entrySize >= 128) { "unsupported GPT entry size ${header.entrySize}" }
        require(header.currentLba == 1UL) {
            "primary GPT header claims current LBA ${header.currentLba}, expected 1"
        }
        require(header.entryLba == 2UL) {
            "primary GPT entries at LBA ${header.entryLba} cannot be represented in a " +
                "contiguous critical-sector blob (expected LBA 2)"
        }
        require(header.backupLba != ULong.MAX_VALUE) {
            "primary GPT header claims an impossible disk end ${header.backupLba}"
        }
        require(header.backupLba + 1UL == sectorCount) {
            "backup GPT header at LBA ${header.backupLba} is not at disk end " +
                "(sectorCount=$sectorCount); relocate the backup GPT first"
        }
        val geo = criticalGeometry(sectorSize, header.entryCount, header.entrySize)
        val entrySectors = ((header.entryCount.toLong() * header.entrySize.toLong() +
            sectorSize - 1) / sectorSize).toULong()
        require(header.firstUsable >= 2UL + entrySectors) {
            "primary first usable LBA ${header.firstUsable} overlaps the entry array"
        }
        require(header.backupLba >= entrySectors && header.lastUsable < header.backupLba - entrySectors) {
            "primary last usable LBA ${header.lastUsable} overlaps backup entries"
        }
        require(sectorCount > (geo.primarySectors + geo.backupSectors).toULong()) {
            "disk too small for GPT critical backup geometry " +
                "${geo.primarySectors}+${geo.backupSectors}"
        }
        // Head must cover MBR + header + entries; refuse if firstUsable leaves a gap the
        // blob would not restore (padding beyond entry array is zero-only metadata).
        require(geo.primarySectors.toULong() <= header.firstUsable) {
            "derived primary span ${geo.primarySectors} exceeds first usable LBA ${header.firstUsable}"
        }
        require(geo.backupSectors.toULong() <= sectorCount - 1UL - header.lastUsable) {
            "derived backup span ${geo.backupSectors} exceeds tail after last usable " +
                "${header.lastUsable}"
        }
        return geo
    }

    fun criticalGeometry(disk: BlockDevice): CriticalGeometry {
        val primary = GptTable.read(disk, 1UL)
        val geo = criticalGeometry(primary.header, disk.sectorSize, disk.sectorCount)
        // Backup header must agree on entry geometry so restore can trust either copy.
        val backupHeader = GptTable.readHeader(disk, primary.header.backupLba)
        require(
            backupHeader.entryCount == primary.header.entryCount &&
                backupHeader.entrySize == primary.header.entrySize &&
                backupHeader.currentLba == primary.header.backupLba &&
                backupHeader.backupLba == 1UL,
        ) {
            "primary and backup GPT headers disagree on critical-backup geometry"
        }
        val entrySectors = GptTable.entrySectors(primary.header, disk.sectorSize)
        require(backupHeader.entryLba == primary.header.backupLba - entrySectors) {
            "backup GPT entries at LBA ${backupHeader.entryLba} are not adjacent to the " +
                "backup header; refusing critical-sector capture"
        }
        return geo
    }

    fun backupCriticalSectors(disk: BlockDevice): ByteArray {
        val sectorSize = disk.sectorSize
        val geo = criticalGeometry(disk)
        val pSectors = geo.primarySectors
        val bSectors = geo.backupSectors
        val total = geo.blobBytes(sectorSize)
        val out = ByteArray(total)
        val head = ByteBuffer.wrap(out, 0, pSectors * sectorSize)
        disk.read(0UL, head)
        val tailLba = disk.sectorCount - bSectors.toULong()
        val tail = ByteBuffer.wrap(out, pSectors * sectorSize, bSectors * sectorSize)
        disk.read(tailLba, tail)
        return out
    }

    /**
     * Relocate GPT backup entry array + backup header to the end of [newDiskSectorCount].
     * Updates primary header backup-LBA / last-usable fields and CRCs.
     */
    fun relocateBackup(disk: BlockDevice, newDiskSectorCount: ULong): PartitionTable {
        require(newDiskSectorCount == disk.sectorCount) {
            "target disk size ${newDiskSectorCount} does not match live geometry ${disk.sectorCount}"
        }
        val protective = MbrTable.readProtectiveOnly(disk)
        val primary = try {
            GptTable.read(disk, 1UL)
        } catch (failure: IllegalArgumentException) {
            if (protective == null) runCatching { PartitionTable.read(disk) }
            throw failure
        }
        val expectedCurrentCount = minOf(newDiskSectorCount - 1UL, 0xFFFF_FFFFUL)
        val oldCount = minOf(primary.header.backupLba, 0xFFFF_FFFFUL)
        require(newDiskSectorCount > 1UL) { "disk too small for GPT relocation" }
        val repairPmbr = when {
            protective == null -> {
                runCatching { PartitionTable.read(disk) }
                throw HybridAmbiguityException("refusing GPT backup relocate on hybrid/ambiguous layout")
            }
            protective.sectorCount == expectedCurrentCount -> false
            protective.sectorCount == oldCount && oldCount != expectedCurrentCount -> true
            else -> throw HybridAmbiguityException("refusing GPT backup relocate with mismatched protective MBR")
        }
        val header = primary.header
        val entries = primary.entriesRaw
        val sectorSize = disk.sectorSize
        val entryBytes = header.entryCount.toLong() * header.entrySize.toLong()
        require(entryBytes <= Int.MAX_VALUE) { "GPT entry array is too large" }
        val entrySectors = ((entryBytes + sectorSize - 1) / sectorSize).toULong()
        require(newDiskSectorCount > header.firstUsable + entrySectors + 1UL) {
            "new disk size too small for GPT backup structures"
        }
        val newBackupHeaderLba = newDiskSectorCount - 1UL
        val newBackupEntriesLba = newBackupHeaderLba - entrySectors
        val newLastUsable = newBackupEntriesLba - 1UL
        require(newLastUsable >= header.firstUsable) { "no usable space after relocate" }
        if (primary.header.backupLba < disk.sectorCount) {
            val oldBackupHeader = GptTable.readHeaderOrNull(disk, primary.header.backupLba)
            if (oldBackupHeader != null) {
                require(
                    oldBackupHeader.backupLba == 1UL &&
                        oldBackupHeader.firstUsable == primary.header.firstUsable &&
                        oldBackupHeader.lastUsable == primary.header.lastUsable &&
                        oldBackupHeader.diskGuid.contentEquals(primary.header.diskGuid) &&
                        oldBackupHeader.entryCount == primary.header.entryCount &&
                        oldBackupHeader.entrySize == primary.header.entrySize,
                ) { "primary and backup GPT disagree before relocation" }
                val existingBackup = try {
                    GptTable.read(disk, primary.header.backupLba)
                } catch (failure: IllegalArgumentException) {
                    require(failure.message == "GPT entries CRC mismatch" &&
                        newBackupEntriesLba <= primary.header.backupLba) {
                        throw failure
                    }
                    null
                }
                if (existingBackup != null) {
                    require(existingBackup.entriesRaw.contentEquals(primary.entriesRaw)) {
                        "primary and backup GPT disagree before relocation"
                    }
                }
            } else {
                require(newBackupEntriesLba <= primary.header.backupLba) {
                    "primary and backup GPT disagree before relocation"
                }
            }
        }
        for (p in primary.partitions) {
            require(p.sectorCount > 0UL) { "partition ${p.name} has empty extent" }
            val end = p.startLba + p.sectorCount - 1UL
            require(end >= p.startLba && end <= newLastUsable) {
                "occupied partition ${p.name} extends past new last usable LBA"
            }
        }
        if (repairPmbr) {
            val pmbr = requireNotNull(protective).sector
            GptTable.putLeInt(pmbr, 446 + protective.slot * 16 + 12, expectedCurrentCount.toInt())
            disk.write(0UL, ByteBuffer.wrap(pmbr))
            disk.sync()
        }
        writeSectors(disk, newBackupEntriesLba, entries)
        val backupHeader = buildHeader(
            header, newBackupHeaderLba, 1UL, header.firstUsable, newLastUsable,
            newBackupEntriesLba, GptTable.crc32(entries), sectorSize,
        )
        disk.write(newBackupHeaderLba, ByteBuffer.wrap(backupHeader))
        disk.sync()
        val primaryHeader = buildHeader(
            header, 1UL, newBackupHeaderLba, header.firstUsable, newLastUsable,
            header.entryLba, GptTable.crc32(entries), sectorSize,
        )
        disk.write(1UL, ByteBuffer.wrap(primaryHeader))
        disk.sync()
        val result = PartitionTable.read(disk)
        require(result.scheme == TableScheme.GPT && !result.gptPrimaryDamaged && !result.hybridAmbiguous) {
            "GPT relocation reread is not a writable GPT"
        }
        val rereadPrimary = GptTable.read(disk, 1UL)
        require(rereadPrimary.header.backupLba == newBackupHeaderLba)
        require(GptTable.read(disk, newBackupHeaderLba).header.entryLba == newBackupEntriesLba)
        require(MbrTable.readProtectiveOnly(disk)?.sectorCount == expectedCurrentCount) {
            "GPT relocation reread has mismatched protective MBR"
        }
        return result
    }

    private fun writeSectors(disk: BlockDevice, startLba: ULong, raw: ByteArray) {
        val sectorSize = disk.sectorSize
        val padded = if (raw.size % sectorSize == 0) {
            raw
        } else {
            val n = ((raw.size + sectorSize - 1) / sectorSize) * sectorSize
            raw.copyOf(n)
        }
        disk.write(startLba, ByteBuffer.wrap(padded))
    }

    internal fun buildEmptyHeader(
        diskGuid: ByteArray,
        entryCount: Int,
        entrySize: Int,
        headerSize: Int,
        currentLba: ULong,
        backupLba: ULong,
        firstUsable: ULong,
        lastUsable: ULong,
        entryLba: ULong,
        entriesCrc: Int,
        sectorSize: Int,
    ): ByteArray {
        require(diskGuid.size == 16) { "disk GUID must be 16 bytes" }
        val bytes = ByteArray(sectorSize)
        "EFI PART".toByteArray().copyInto(bytes, 0)
        GptTable.putLeInt(bytes, 8, 0x00010000)
        GptTable.putLeInt(bytes, 12, headerSize)
        GptTable.putLeInt(bytes, 16, 0)
        GptTable.putLeInt(bytes, 20, 0)
        putU64(bytes, 24, currentLba)
        putU64(bytes, 32, backupLba)
        putU64(bytes, 40, firstUsable)
        putU64(bytes, 48, lastUsable)
        diskGuid.copyInto(bytes, 56)
        putU64(bytes, 72, entryLba)
        GptTable.putLeInt(bytes, 80, entryCount)
        GptTable.putLeInt(bytes, 84, entrySize)
        GptTable.putLeInt(bytes, 88, entriesCrc)
        val crc = GptTable.crc32(bytes, 0, headerSize)
        GptTable.putLeInt(bytes, 16, crc)
        return bytes
    }

    private fun buildHeader(
        header: GptHeader,
        currentLba: ULong,
        backupLba: ULong,
        firstUsable: ULong,
        lastUsable: ULong,
        entryLba: ULong,
        entriesCrc: Int,
        sectorSize: Int,
    ): ByteArray = buildEmptyHeader(
        diskGuid = header.diskGuid,
        entryCount = header.entryCount,
        entrySize = header.entrySize,
        headerSize = header.headerSize,
        currentLba = currentLba,
        backupLba = backupLba,
        firstUsable = firstUsable,
        lastUsable = lastUsable,
        entryLba = entryLba,
        entriesCrc = entriesCrc,
        sectorSize = sectorSize,
    )

    private fun putU64(bytes: ByteArray, offset: Int, value: ULong) {
        GptTable.putLeULong(bytes, offset, value)
    }
}
