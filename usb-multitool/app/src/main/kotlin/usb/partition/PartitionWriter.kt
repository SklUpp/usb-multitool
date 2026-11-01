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
    private const val MBR_TYPE_LINUX = 0x83
    private const val MBR_TYPE_EFI = 0xEF
    private val SIGNATURE = "EFI PART".toByteArray(StandardCharsets.US_ASCII)

    fun commit(disk: BlockDevice, table: PartitionTable): PartitionTable {
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

    private fun commitGpt(disk: BlockDevice, table: PartitionTable) {
        val header =
            table.gptHeader
                ?: throw IllegalArgumentException("GPT scheme without a parsed header")
        val sectorSize = disk.sectorSize
        val entryCount = header.entryCount
        require(table.partitions.size <= entryCount) {
            "too many partitions: ${table.partitions.size} > $entryCount"
        }
        val entryBytes = entryCount * ENTRY_SIZE
        val entrySectors = (entryBytes + sectorSize - 1) / sectorSize
        val backupLba = header.backupLba
        val backupEntriesLba = backupLba - entrySectors.toULong()
        require(entrySectors.toULong() < backupLba) { "GPT backup entries overlap header" }

        val entries = ByteArray(entryBytes)
        val previous = table.gptEntriesRaw
        table.partitions.forEachIndexed { index, partition ->
            val offset = index * ENTRY_SIZE
            typeGuidBytes(partition).copyInto(entries, offset)
            uniqueGuid(previous, partition, header.entrySize).copyInto(entries, offset + 16)
            putLeU64(entries, offset + 32, partition.startLba)
            putLeU64(entries, offset + 40, partition.startLba + partition.sectorCount - 1UL)
            putLeU64(entries, offset + 48, partition.flags.toULong())
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
        val sectorSize = disk.sectorSize
        require(sectorSize >= 512) { "sector size $sectorSize too small for MBR" }
        val sector = ByteArray(sectorSize)
        val buffer = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(0UL, buffer)
        buffer.flip()
        buffer.get(sector)
        // Keep boot code (0..445); rebuild the partition entries and signature.
        sector.fill(0.toByte(), 446, sectorSize)
        table.partitions.forEachIndexed { index, partition ->
            val offset = 446 + index * 16
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

    /** Reuse the on-disk unique GUID when the partition already existed. */
    private fun uniqueGuid(previous: ByteArray?, partition: Partition, entrySize: Int): ByteArray {
        if (previous != null && entrySize >= ENTRY_SIZE) {
            var index = 0
            while ((index + 1) * entrySize <= previous.size) {
                val offset = index * entrySize
                val start = leU64(previous, offset + 32)
                if (start == partition.startLba) {
                    return previous.copyOfRange(offset + 16, offset + 32)
                }
                index++
            }
        }
        return randomGuid()
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
            else -> MBR_TYPE_LINUX
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
