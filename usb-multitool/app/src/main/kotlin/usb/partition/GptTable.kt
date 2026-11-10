package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import usb.device.BlockDevice

data class GptHeader(
    val headerSize: Int,
    val currentLba: ULong,
    val backupLba: ULong,
    val firstUsable: ULong,
    val lastUsable: ULong,
    val diskGuid: ByteArray,
    val entryLba: ULong,
    val entryCount: Int,
    val entrySize: Int,
    val entriesCrc: Int,
    val headerCrc: Int,
)

object GptTable {
    private val SIGNATURE = "EFI PART".toByteArray(StandardCharsets.US_ASCII)
    private val UNUSED = ByteArray(16)

    data class Result(
        val partitions: List<Partition>,
        val header: GptHeader,
        val entriesRaw: ByteArray,
        /** True when [headerLba] was the backup header, not the primary at LBA 1. */
        val fromBackup: Boolean = false,
    )

    fun read(disk: BlockDevice, headerLba: ULong = 1UL): Result {
        val sectorSize = disk.sectorSize
        val headerBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(headerLba, headerBuf)
        headerBuf.flip()
        require(headerBuf.remaining() >= 92) { "GPT header truncated" }

        val sig = ByteArray(8)
        headerBuf.position(0)
        headerBuf.get(sig)
        require(sig.contentEquals(SIGNATURE)) { "missing GPT signature" }

        val headerSize = headerBuf.getInt(12)
        require(headerSize in 92..sectorSize) {
            "invalid GPT header size $headerSize for sector size $sectorSize"
        }
        val storedCrc = headerBuf.getInt(16)
        val headerBytes = ByteArray(headerSize)
        headerBuf.position(0)
        headerBuf.get(headerBytes)
        headerBytes[16] = 0
        headerBytes[17] = 0
        headerBytes[18] = 0
        headerBytes[19] = 0
        val crc = CRC32()
        crc.update(headerBytes)
        require(crc.value.toInt() == storedCrc) { "GPT header CRC mismatch" }

        val currentLba = headerBuf.getLong(24).toULong()
        val backupLba = headerBuf.getLong(32).toULong()
        val firstUsable = headerBuf.getLong(40).toULong()
        val lastUsable = headerBuf.getLong(48).toULong()
        val diskGuid = ByteArray(16)
        headerBuf.position(56)
        headerBuf.get(diskGuid)
        val entryLba = headerBuf.getLong(72).toULong()
        val entryCount = headerBuf.getInt(80)
        val entrySize = headerBuf.getInt(84)
        val entriesCrc = headerBuf.getInt(88)
        require(entrySize >= 128) { "GPT entry size $entrySize too small" }
        require(entryCount > 0) { "no GPT entries" }
        require(currentLba == headerLba) { "GPT header current LBA $currentLba != expected $headerLba" }
        require(firstUsable <= lastUsable) { "GPT first usable $firstUsable > last usable $lastUsable" }

        val entriesBytes = entryCount * entrySize
        val sectorsNeeded = (entriesBytes + sectorSize - 1) / sectorSize
        require(entryLba + sectorsNeeded.toULong() <= disk.sectorCount) {
            "GPT entry array extends past disk bounds"
        }
        val entriesBuf = ByteBuffer.allocate(sectorsNeeded * sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(entryLba, entriesBuf)
        entriesBuf.flip()
        val rawEntries = ByteArray(entriesBytes)
        entriesBuf.get(rawEntries)
        val ecrc = CRC32()
        ecrc.update(rawEntries)
        require(ecrc.value.toInt() == entriesCrc) { "GPT entries CRC mismatch" }

        val partitions = ArrayList<Partition>()
        for (i in 0 until entryCount) {
            val off = i * entrySize
            val type = rawEntries.copyOfRange(off, off + 16)
            if (type.contentEquals(UNUSED)) continue
            val start = leULong(rawEntries, off + 32)
            val end = leULong(rawEntries, off + 40)
            require(end >= start) { "GPT entry end < start" }
            val count = end - start + 1UL
            require(start >= firstUsable && end <= lastUsable) {
                "GPT entry outside usable range"
            }
            val attrs = leULong(rawEntries, off + 48).toLong()
            val name = decodeName(rawEntries, off + 56)
            val guid = guidString(type)
            partitions.add(
                Partition(
                    startLba = start,
                    sectorCount = count,
                    type = guid,
                    name = name,
                    flags = attrs,
                    bootable = (attrs and GPT_LEGACY_BOOTABLE_FLAG) != 0L,
                    esp = guid.equals(EFI_SYSTEM_GUID, ignoreCase = true),
                    slot = i,
                ),
            )
        }
        rejectOverlaps(partitions)
        val header = GptHeader(
            headerSize = headerSize,
            currentLba = currentLba,
            backupLba = backupLba,
            firstUsable = firstUsable,
            lastUsable = lastUsable,
            diskGuid = diskGuid,
            entryLba = entryLba,
            entryCount = entryCount,
            entrySize = entrySize,
            entriesCrc = entriesCrc,
            headerCrc = storedCrc,
        )
        return Result(partitions, header, rawEntries)
    }

    internal fun leULong(bytes: ByteArray, offset: Int): ULong {
        var v = 0UL
        for (i in 0 until 8) {
            v = v or ((bytes[offset + i].toULong() and 0xFFu) shl (8 * i))
        }
        return v
    }

    internal fun putLeULong(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
        }
    }

    internal fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    internal fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    private fun decodeName(bytes: ByteArray, offset: Int): String {
        val utf16 = bytes.copyOfRange(offset, offset + 72)
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < utf16.size) {
            val c = (utf16[i].toInt() and 0xFF) or ((utf16[i + 1].toInt() and 0xFF) shl 8)
            if (c == 0) break
            sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun guidString(raw: ByteArray): String {
        fun revHex(start: Int, len: Int): String =
            raw.copyOfRange(start, start + len).reversedArray().joinToString("") { "%02X".format(it) }
        fun hex(start: Int, len: Int): String =
            raw.copyOfRange(start, start + len).joinToString("") { "%02X".format(it) }
        return "${revHex(0, 4)}-${revHex(4, 2)}-${revHex(6, 2)}-${hex(8, 2)}-${hex(10, 6)}"
    }

    private const val EFI_SYSTEM_GUID = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
}
