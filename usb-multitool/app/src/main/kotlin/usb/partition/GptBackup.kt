package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import usb.device.BlockDevice

/**
 * Critical-sector backup and GPT backup-header relocation after a smaller image write.
 * Does not move or resize occupied partitions.
 */
object GptBackup {
    const val PRIMARY_SECTORS = 34 // MBR + primary header + 32 entry sectors (512-byte geometry)
    const val BACKUP_SECTORS = 33 // 32 entry sectors + backup header

    fun backupCriticalSectors(disk: BlockDevice): ByteArray {
        val sectorSize = disk.sectorSize
        require(disk.sectorCount >= (PRIMARY_SECTORS + BACKUP_SECTORS).toULong()) {
            "disk too small for GPT critical backup"
        }
        val total = (PRIMARY_SECTORS + BACKUP_SECTORS) * sectorSize
        val out = ByteArray(total)
        // First 34 sectors.
        val head = ByteBuffer.wrap(out, 0, PRIMARY_SECTORS * sectorSize)
        disk.read(0UL, head)
        // Last 33 sectors.
        val tailLba = disk.sectorCount - BACKUP_SECTORS.toULong()
        val tail = ByteBuffer.wrap(out, PRIMARY_SECTORS * sectorSize, BACKUP_SECTORS * sectorSize)
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

    private fun buildHeader(
        header: GptHeader,
        currentLba: ULong,
        backupLba: ULong,
        firstUsable: ULong,
        lastUsable: ULong,
        entryLba: ULong,
        entriesCrc: Int,
        sectorSize: Int,
    ): ByteArray {
        val bytes = ByteArray(sectorSize)
        // Signature
        "EFI PART".toByteArray().copyInto(bytes, 0)
        // Revision 1.0
        GptTable.putLeInt(bytes, 8, 0x00010000)
        GptTable.putLeInt(bytes, 12, header.headerSize)
        // CRC placeholder zeroed
        GptTable.putLeInt(bytes, 16, 0)
        GptTable.putLeInt(bytes, 20, 0)
        putU64(bytes, 24, currentLba)
        putU64(bytes, 32, backupLba)
        putU64(bytes, 40, firstUsable)
        putU64(bytes, 48, lastUsable)
        header.diskGuid.copyInto(bytes, 56)
        putU64(bytes, 72, entryLba)
        GptTable.putLeInt(bytes, 80, header.entryCount)
        GptTable.putLeInt(bytes, 84, header.entrySize)
        GptTable.putLeInt(bytes, 88, entriesCrc)
        val crc = GptTable.crc32(bytes, 0, header.headerSize)
        GptTable.putLeInt(bytes, 16, crc)
        return bytes
    }

    private fun putU64(bytes: ByteArray, offset: Int, value: ULong) {
        GptTable.putLeULong(bytes, offset, value)
    }
}
