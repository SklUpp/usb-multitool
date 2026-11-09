package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import usb.device.BlockDevice

/**
 * Commits an edited [PartitionTable] back to [disk] and returns the table re-read
 * from disk, so callers never trust an in-memory guess.
 *
 * Partition type strings are either GPT GUID strings (as produced by [GptTable.read])
 * or [PartitionType] names (as produced by [PartitionEditor.create]).
 */
object PartitionWriter {
    private const val ENTRY_SIZE = 128
    private const val HEADER_SIZE = 92
    private const val EFI_SYSTEM_GUID = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
    private const val LINUX_DATA_GUID = "0FC63DAF-8483-4772-8E79-3D69D8477DE4"
    private const val MBR_SLOTS = 4
    private const val MBR_TYPE_LINUX = 0x83
    private const val MBR_TYPE_EFI = 0xEF
    private val SIGNATURE = "EFI PART".toByteArray(StandardCharsets.US_ASCII)

    fun commit(disk: BlockDevice, table: PartitionTable): PartitionTable {
        rejectOverlaps(table.partitions)
        for (partition in table.partitions) {
            require(partition.sectorCount > 0UL) {
                "zero-length partition at LBA ${partition.startLba}"
            }
            require(partition.startLba <= ULong.MAX_VALUE - partition.sectorCount) {
                "partition at LBA ${partition.startLba} overflows the address space"
            }
        }
        when (table.scheme) {
            TableScheme.GPT -> commitGpt(disk, table)
            TableScheme.MBR -> commitMbr(disk, table)
            TableScheme.HYBRID ->
                throw HybridAmbiguityException(
                    "hybrid MBR/GPT layout cannot be rewritten safely; repair it explicitly",
                )
        }
        disk.sync()
        return PartitionTable.read(disk)
    }

    private fun validateGptGeometry(disk: BlockDevice, header: GptHeader, entrySectors: ULong) {
        require(header.entrySize == ENTRY_SIZE) {
            "unsupported GPT entry size ${header.entrySize}; only $ENTRY_SIZE is writable"
        }
        require(header.entryCount > 0) { "GPT entry count must be positive" }
        require(header.currentLba < disk.sectorCount && header.backupLba < disk.sectorCount) {
            "GPT header outside disk"
        }
        require(header.currentLba != header.backupLba && header.currentLba != 0UL && header.backupLba != 0UL) {
            "GPT headers overlap protected metadata"
        }
        require(header.firstUsable <= header.lastUsable && header.lastUsable < disk.sectorCount) {
            "invalid GPT usable range"
        }
        require(header.currentLba !in header.firstUsable..header.lastUsable && header.backupLba !in header.firstUsable..header.lastUsable) {
            "GPT header overlaps usable range"
        }
        require(entrySectors > 0UL && entrySectors <= disk.sectorCount) { "invalid GPT entry geometry" }
        require(header.entryLba <= disk.sectorCount - entrySectors) { "GPT primary entries outside disk" }
        require(header.backupLba >= entrySectors) { "GPT backup entries overlap disk start" }
        val primaryEnd = header.entryLba + entrySectors
        val backupStart = header.backupLba - entrySectors
        val backupEnd = header.backupLba
        fun overlaps(start: ULong, end: ULong, otherStart: ULong, otherEnd: ULong) =
            start < otherEnd && otherStart < end
        require(!overlaps(header.entryLba, primaryEnd, 0UL, 1UL)) { "GPT entries overlap LBA 0" }
        require(!overlaps(backupStart, backupEnd, 0UL, 1UL)) { "GPT entries overlap LBA 0" }
        require(!overlaps(header.entryLba, primaryEnd, header.currentLba, header.currentLba + 1UL)) { "GPT primary entries overlap header" }
        require(!overlaps(header.entryLba, primaryEnd, header.backupLba, header.backupLba + 1UL)) { "GPT primary entries overlap header" }
        require(!overlaps(backupStart, backupEnd, header.currentLba, header.currentLba + 1UL)) { "GPT backup entries overlap header" }
        require(!overlaps(backupStart, backupEnd, header.backupLba, header.backupLba + 1UL)) { "GPT backup entries overlap header" }
        require(!overlaps(header.entryLba, primaryEnd, backupStart, backupEnd)) { "GPT entry arrays overlap" }
        require(!overlaps(header.entryLba, primaryEnd, header.firstUsable, header.lastUsable + 1UL)) { "GPT entries overlap usable range" }
        require(!overlaps(backupStart, backupEnd, header.firstUsable, header.lastUsable + 1UL)) { "GPT entries overlap usable range" }
    }

    private fun commitGpt(disk: BlockDevice, table: PartitionTable) {
        val header =
            table.gptHeader
                ?: throw IllegalArgumentException("GPT scheme without a parsed header")
        val sectorSize = disk.sectorSize
        val entryCount = header.entryCount
        require(table.partitions.size <= entryCount) {
            "too many partitions: ${table.partitions.size} > $entryCount"
        }
        require(entryCount <= Int.MAX_VALUE / ENTRY_SIZE) { "GPT entry count too large" }
        val entryBytes = entryCount * ENTRY_SIZE
        val entrySectors = (entryBytes.toULong() + sectorSize.toULong() - 1UL) / sectorSize.toULong()
        validateGptGeometry(disk, header, entrySectors)
        val backupLba = header.backupLba
        val backupEntriesLba = backupLba - entrySectors
        require(entrySectors < backupLba) { "GPT backup entries overlap header" }

        for (partition in table.partitions) {
            val last = partition.startLba + partition.sectorCount - 1UL
            require(partition.startLba >= header.firstUsable && last <= header.lastUsable) {
                "partition ${partition.startLba}..$last outside usable GPT range " +
                    "${header.firstUsable}..${header.lastUsable}"
            }
        }

        val entries = ByteArray(entryBytes)
        val previous = table.gptEntriesRaw
        val slots = resolveSlots(table.partitions, entryCount, "GPT")
        table.partitions.forEachIndexed { index, partition ->
            val offset = slots[index] * ENTRY_SIZE
            typeGuidBytes(partition).copyInto(entries, offset)
            uniqueGuid(previous, partition, slots[index], header.entrySize).copyInto(entries, offset + 16)
            putLeU64(entries, offset + 32, partition.startLba)
            putLeU64(entries, offset + 40, partition.startLba + partition.sectorCount - 1UL)
            // Keep every attribute bit; make the legacy-bootable bit follow the model.
            val attributes = if (partition.bootable) {
                partition.flags or GPT_LEGACY_BOOTABLE_FLAG
            } else {
                partition.flags and GPT_LEGACY_BOOTABLE_FLAG.inv()
            }
            putLeU64(entries, offset + 48, attributes.toULong())
            val name = partition.name.toByteArray(StandardCharsets.UTF_16LE)
            name.copyInto(entries, offset + 56, 0, minOf(name.size, ENTRY_SIZE - 56))
        }
        val entriesCrc = crc32(entries)

        writeSectors(disk, header.entryLba, entries, sectorSize)
        writeSectors(disk, backupEntriesLba, entries, sectorSize)

        writeHeader(
            disk = disk,
            lba = header.currentLba,
            currentLba = header.currentLba,
            backupLba = backupLba,
            firstUsable = header.firstUsable,
            lastUsable = header.lastUsable,
            diskGuid = header.diskGuid,
            entryLba = header.entryLba,
            entryCount = entryCount,
            entriesCrc = entriesCrc,
        )
        writeHeader(
            disk = disk,
            lba = backupLba,
            currentLba = backupLba,
            backupLba = header.currentLba,
            firstUsable = header.firstUsable,
            lastUsable = header.lastUsable,
            diskGuid = header.diskGuid,
            entryLba = backupEntriesLba,
            entryCount = entryCount,
            entriesCrc = entriesCrc,
        )
    }

    private fun commitMbr(disk: BlockDevice, table: PartitionTable) {
        require(table.partitions.size <= 4) {
            "MBR supports at most 4 primary partitions, got ${table.partitions.size}"
        }
        require(table.partitions.none { it.type.uppercase() in setOf("0X05", "0X0F", "0X85") }) {
            "editing extended MBR partitions is not supported"
        }
        require(table.partitions.all { it.startLba <= 0xFFFF_FFFFUL && it.sectorCount <= 0xFFFF_FFFFUL }) {
            "MBR partition geometry exceeds 32-bit LBA fields"
        }
        val sectorSize = disk.sectorSize
        require(sectorSize >= 512) { "sector size $sectorSize too small for MBR" }
        val sector = ByteArray(sectorSize)
        val buffer = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(0UL, buffer)
        buffer.flip()
        buffer.get(sector)
        // Keep boot code (0..445); rebuild the 4 partition entries and signature.
        sector.fill(0.toByte(), 446, 510)
        val slots = resolveSlots(table.partitions, MBR_SLOTS, "MBR")
        table.partitions.forEachIndexed { index, partition ->
            val offset = 446 + slots[index] * 16
            sector[offset] = if (partition.bootable) 0x80.toByte() else 0.toByte()
            // LBA-only addressing: CHS fields are set to their "use LBA" sentinels.
            sector[offset + 1] = 0xFE.toByte()
            sector[offset + 2] = 0xFF.toByte()
            sector[offset + 3] = 0xFF.toByte()
            sector[offset + 4] = mbrType(partition).toByte()
            sector[offset + 5] = 0xFE.toByte()
            sector[offset + 6] = 0xFF.toByte()
            sector[offset + 7] = 0xFF.toByte()
            putLeU32(sector, offset + 8, partition.startLba)
            putLeU32(sector, offset + 12, partition.sectorCount)
        }
        sector[510] = 0x55.toByte()
        sector[511] = 0xAA.toByte()
        val out = ByteBuffer.wrap(sector)
        disk.write(0UL, out)
    }

    private fun writeHeader(
        disk: BlockDevice,
        lba: ULong,
        currentLba: ULong,
        backupLba: ULong,
        firstUsable: ULong,
        lastUsable: ULong,
        diskGuid: ByteArray,
        entryLba: ULong,
        entryCount: Int,
        entriesCrc: Int,
    ) {
        val sectorSize = disk.sectorSize
        val sector = ByteArray(sectorSize)
        SIGNATURE.copyInto(sector, 0)
        putLeU32(sector, 8, 0x00010000UL)
        putLeU32(sector, 12, HEADER_SIZE.toULong())
        putLeU64(sector, 24, currentLba)
        putLeU64(sector, 32, backupLba)
        putLeU64(sector, 40, firstUsable)
        putLeU64(sector, 48, lastUsable)
        diskGuid.copyInto(sector, 56, 0, 16)
        putLeU64(sector, 72, entryLba)
        putLeU32(sector, 80, entryCount.toULong())
        putLeU32(sector, 84, ENTRY_SIZE.toULong())
        putLeU32(sector, 88, entriesCrc.toUInt().toULong())
        putLeU32(sector, 16, crc32(sector, 0, HEADER_SIZE).toUInt().toULong())
        val out = ByteBuffer.wrap(sector)
        disk.write(lba, out)
    }

    private fun writeSectors(disk: BlockDevice, lba: ULong, data: ByteArray, sectorSize: Int) {
        require(data.size % sectorSize == 0) { "payload must be sector aligned" }
        val buffer = ByteBuffer.wrap(data)
        disk.write(lba, buffer)
    }

    /**
     * Reuse the on-disk unique GUID only when the same slot still holds the same
     * geometry. Matching by start alone would also inherit the GUID of a deleted
     * record that happened to use the same LBA.
     */
    private fun uniqueGuid(
        previous: ByteArray?,
        partition: Partition,
        slot: Int,
        entrySize: Int,
    ): ByteArray {
        if (previous != null && entrySize >= ENTRY_SIZE) {
            val offset = slot * entrySize
            if (offset + ENTRY_SIZE <= previous.size) {
                val start = leU64(previous, offset + 32)
                val end = leU64(previous, offset + 40)
                if (start == partition.startLba &&
                    end == partition.startLba + partition.sectorCount - 1UL
                ) {
                    return previous.copyOfRange(offset + 16, offset + 32)
                }
            }
        }
        return randomGuid()
    }

    /**
     * Maps each record to its original entry slot and fills records that have none
     * (`slot < 0`, e.g. hand-built tables) into the first free slots. Never moves a
     * record that already owns a slot, so sparse layouts keep their positions.
     */
    private fun resolveSlots(
        partitions: List<Partition>,
        slotCount: Int,
        label: String,
    ): List<Int> {
        val taken = BooleanArray(slotCount)
        for (partition in partitions) {
            if (partition.slot >= 0) {
                require(partition.slot < slotCount) {
                    "$label slot ${partition.slot} out of range 0..${slotCount - 1}"
                }
                require(!taken[partition.slot]) {
                    "$label slot ${partition.slot} used by more than one partition"
                }
                taken[partition.slot] = true
            }
        }
        var cursor = 0
        return partitions.map { partition ->
            if (partition.slot >= 0) {
                partition.slot
            } else {
                while (cursor < slotCount && taken[cursor]) cursor++
                require(cursor < slotCount) { "$label table has no free entry slot" }
                taken[cursor] = true
                cursor++
            }
        }
    }

    private fun typeGuidBytes(partition: Partition): ByteArray =
        when (partition.type.uppercase()) {
            "BASIC", LINUX_DATA_GUID -> guidBytes(LINUX_DATA_GUID)
            "EFI", "VTOYEFI", EFI_SYSTEM_GUID -> guidBytes(EFI_SYSTEM_GUID)
            else -> {
                require(partition.type.length == 36) {
                    "unknown partition type '${partition.type}'"
                }
                guidBytes(partition.type)
            }
        }

    private fun mbrType(partition: Partition): Int =
        when (partition.type.uppercase()) {
            "EFI", "VTOYEFI", EFI_SYSTEM_GUID -> MBR_TYPE_EFI
            "", "BASIC" -> MBR_TYPE_LINUX
            else -> partition.type.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                ?.takeIf { it in 1..255 && it != 0xEE }
                ?: throw IllegalArgumentException("unsupported MBR partition type '${partition.type}'")
        }

    private fun guidBytes(text: String): ByteArray {
        val hex = text.replace("-", "")
        require(hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "invalid GUID '$text'"
        }
        val raw = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // GUID text is mixed-endian: first three fields are little-endian on disk.
        raw.copyOfRange(0, 4).reversedArray().copyInto(raw, 0)
        raw.copyOfRange(4, 6).reversedArray().copyInto(raw, 4)
        raw.copyOfRange(6, 8).reversedArray().copyInto(raw, 6)
        return raw
    }

    private fun randomGuid(): ByteArray {
        val uuid = java.util.UUID.randomUUID()
        return guidBytes(uuid.toString())
    }

    private fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    private fun leU64(bytes: ByteArray, offset: Int): ULong {
        var value = 0UL
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toULong() and 0xFFu) shl (8 * i))
        }
        return value
    }

    private fun putLeU64(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
        }
    }

    private fun putLeU32(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
        }
    }
}
