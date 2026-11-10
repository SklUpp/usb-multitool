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
        val table = PartitionTable.read(disk)
        if (table.scheme == TableScheme.HYBRID || table.hybridAmbiguous) {
            throw HybridAmbiguityException("refusing GPT backup relocate on hybrid/ambiguous layout")
        }
        require(table.scheme == TableScheme.GPT) { "relocateBackup requires GPT" }
        require(!table.gptPrimaryDamaged) {
            "primary GPT header or entries are damaged; refusing to relocate an " +
                "ambiguous table — restore a matching GPT critical backup first"
        }
        val header = requireNotNull(table.gptHeader) { "missing GPT header" }
        val entries = requireNotNull(table.gptEntriesRaw) { "missing GPT entries" }
        val sectorSize = disk.sectorSize

        val entryBytes = header.entryCount * header.entrySize
        val entrySectors = ((entryBytes + sectorSize - 1) / sectorSize).toULong()
        require(newDiskSectorCount > header.firstUsable + entrySectors + 1UL) {
            "new disk size too small for GPT backup structures"
        }

        val newBackupHeaderLba = newDiskSectorCount - 1UL
        val newBackupEntriesLba = newBackupHeaderLba - entrySectors
        val newLastUsable = newBackupEntriesLba - 1UL
        require(newLastUsable >= header.firstUsable) { "no usable space after relocate" }

        // Refuse if any partition would fall outside the new usable range.
        for (p in table.partitions) {
            val end = p.startLba + p.sectorCount - 1UL
            require(end <= newLastUsable) {
                "occupied partition ${p.name} extends past new last usable LBA"
            }
        }

        // Write backup entries then backup header.
        writeSectors(disk, newBackupEntriesLba, entries)

        val backupHeader = buildHeader(
            header = header,
            currentLba = newBackupHeaderLba,
            backupLba = 1UL,
            firstUsable = header.firstUsable,
            lastUsable = newLastUsable,
            entryLba = newBackupEntriesLba,
            entriesCrc = GptTable.crc32(entries),
            sectorSize = sectorSize,
        )
        disk.write(newBackupHeaderLba, ByteBuffer.wrap(backupHeader))
        // Durable backup before the primary header stops pointing at the relocated copy.
        disk.sync()

        // Update primary header to point at new backup.
        val primaryHeader = buildHeader(
            header = header,
            currentLba = 1UL,
            backupLba = newBackupHeaderLba,
            firstUsable = header.firstUsable,
            lastUsable = newLastUsable,
            entryLba = header.entryLba,
            entriesCrc = GptTable.crc32(entries),
            sectorSize = sectorSize,
        )
        disk.write(1UL, ByteBuffer.wrap(primaryHeader))
        disk.sync()

        return PartitionTable.read(disk)
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
