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

    fun primarySectors(sectorSize: Int, entryCount: Int = 128, entrySize: Int = 128): Int =
        2 + (entryCount * entrySize + sectorSize - 1) / sectorSize

    fun backupSectors(sectorSize: Int, entryCount: Int = 128, entrySize: Int = 128): Int =
        1 + (entryCount * entrySize + sectorSize - 1) / sectorSize

    fun backupCriticalSectors(disk: BlockDevice): ByteArray {
        val sectorSize = disk.sectorSize
        val pSectors = primarySectors(sectorSize)
        val bSectors = backupSectors(sectorSize)
        require(disk.sectorCount >= (pSectors + bSectors).toULong()) {
            "disk too small for GPT critical backup"
        }
        val total = (pSectors + bSectors) * sectorSize
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
