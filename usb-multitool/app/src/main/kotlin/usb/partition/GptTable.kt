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

    /**
     * Upper bound for `entryCount * entrySize`. UEFI layouts use 16–32 KiB; 1 MiB still
     * accepts every sane table while refusing crafted counts before any allocation.
     */
    private const val MAX_ENTRY_ARRAY_BYTES = 1L * 1024L * 1024L

    /** [headerLba] holds no GPT signature at all, so no copy lives in that sector. */
    class MissingSignatureException(message: String) : IllegalArgumentException(message)

    data class Result(
        val partitions: List<Partition>,
        val header: GptHeader,
        val entriesRaw: ByteArray,
        /** True when [headerLba] was the backup header, not the primary at LBA 1. */
        val fromBackup: Boolean = false,
        /**
         * Entry-array LBA taken from the validated alternate header; null when no valid
         * alternate copy exists, so callers must not predict it from geometry.
         */
        val backupEntriesLba: ULong? = null,
    )

    /** Sector span of the entry array described by [header]; [readHeader] bounded the size. */
    internal fun entrySectors(header: GptHeader, sectorSize: Int): ULong =
        ((header.entryCount.toLong() * header.entrySize.toLong() + sectorSize - 1) / sectorSize).toULong()

    /**
     * Parse and validate one header sector without touching its entry array. [read] uses it
     * for the copy it returns; candidate selection also uses it to learn the *real*
     * entry-array geometry of the other copy instead of predicting it.
     */
    internal fun readHeader(disk: BlockDevice, headerLba: ULong): GptHeader {
        val sectorSize = disk.sectorSize
        val headerBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(headerLba, headerBuf)
        headerBuf.flip()
        require(headerBuf.remaining() >= 92) { "GPT header truncated" }

        val sig = ByteArray(8)
        headerBuf.position(0)
        headerBuf.get(sig)
        if (!sig.contentEquals(SIGNATURE)) {
            throw MissingSignatureException("missing GPT signature at LBA $headerLba")
        }

        val headerSize = headerBuf.getInt(12)
        require(headerSize in 92..sectorSize) {
            "invalid GPT header size $headerSize for sector size $sectorSize"
        }
        require(headerBuf.limit() >= headerSize) {
            "GPT header truncated to ${headerBuf.limit()} of $headerSize bytes"
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

        val entriesBytes = entryCount.toLong() * entrySize.toLong()
        require(entriesBytes <= MAX_ENTRY_ARRAY_BYTES) {
            "GPT entry array of $entriesBytes bytes exceeds the $MAX_ENTRY_ARRAY_BYTES byte limit"
        }
        val entrySectors = (entriesBytes + sectorSize - 1) / sectorSize
        val entryBufferBytes = entrySectors * sectorSize
        require(entryBufferBytes <= MAX_ENTRY_ARRAY_BYTES + sectorSize &&
            entryBufferBytes <= Int.MAX_VALUE) {
            "GPT entry buffer of $entryBufferBytes bytes is not allocatable"
        }
        require(entryLba >= 2UL) {
            "GPT entry array at LBA $entryLba overlaps metadata sector 0/1"
        }
        // Bounds come first: an array running off the medium is the more specific fault and
        // must not be misreported as a backup self-overlap.
        require(entryLba <= disk.sectorCount && entrySectors.toULong() <= disk.sectorCount - entryLba) {
            "GPT entry array extends past disk bounds"
        }
        if (headerLba != 1UL) {
            // The alternate header sits at the last recorded LBA and its entry array must
            // precede it. A CRC-valid header that puts its array on top of its own sector
            // would be rewritten/erased over itself and leave an unreadable copy.
            require(entryLba + entrySectors.toULong() <= headerLba) {
                "backup GPT entry array ends at LBA ${entryLba + entrySectors.toULong()} past its header " +
                    "at LBA $headerLba"
            }
        }
        // A truncated copy whose declared backup header lies past the disk end (shrunk image
        // awaiting relocate) may also declare a usable end past the disk; anything else must fit.
        if (backupLba < disk.sectorCount) {
            require(lastUsable < disk.sectorCount) {
                "GPT usable range ends at LBA $lastUsable past disk capacity ${disk.sectorCount}"
            }
        }
        return GptHeader(
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
    }

    /** Header geometry of one copy, or null when that sector holds no usable GPT header. */
    internal fun readHeaderOrNull(disk: BlockDevice, headerLba: ULong): GptHeader? = try {
        readHeader(disk, headerLba)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun read(disk: BlockDevice, headerLba: ULong = 1UL): Result {
        val sectorSize = disk.sectorSize
        val header = readHeader(disk, headerLba)
        val entryLba = header.entryLba
        val firstUsable = header.firstUsable
        val lastUsable = header.lastUsable
        val backupLba = header.backupLba
        val entrySectors = entrySectors(header, sectorSize)
        val entryEnd = entryLba + entrySectors
        if (headerLba == 1UL) {
            // Primary header: every metadata sector of this copy is known, so usable space
            // must sit strictly above the entry array and below the backup header.
            require(firstUsable >= entryEnd) {
                "GPT usable range starts at LBA $firstUsable inside the primary entry array " +
                    "ending at LBA $entryEnd"
            }
            require(lastUsable < backupLba) {
                "GPT usable range ends at LBA $lastUsable past the backup header at LBA $backupLba"
            }
            require(entryEnd <= backupLba) {
                "primary GPT entry array ends at LBA $entryEnd past the backup header at LBA $backupLba"
            }
        } else {
            // Recovered backup header: the primary entry-array location is unknown, so keep
            // the standard primary metadata window [0, 2 + entrySectors) out of usable space.
            require(backupLba == 1UL) {
                "backup GPT header at LBA $headerLba points at alternate LBA $backupLba, " +
                    "expected 1; refusing ambiguous primary recovery"
            }
            require(firstUsable >= 2UL + entrySectors) {
                "GPT usable range starts at LBA $firstUsable inside presumed primary metadata " +
                    "ending at LBA ${2UL + entrySectors}"
            }
            require(lastUsable < entryLba) {
                "backup GPT usable range ends at LBA $lastUsable, " +
                    "past its entries at LBA $entryLba"
            }
            require(entryEnd <= headerLba) {
                "backup GPT entry array ends at LBA $entryEnd past its header at LBA $headerLba"
            }
        }

        val entryBufferBytes = entrySectors * sectorSize.toULong()
        val entriesBuf = ByteBuffer.allocate(entryBufferBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(entryLba, entriesBuf)
        entriesBuf.flip()
        require(entriesBuf.remaining() >= entryBufferBytes.toInt()) {
            "GPT entry array truncated to ${entriesBuf.remaining()} of $entryBufferBytes bytes"
        }
        val rawEntries = ByteArray((header.entryCount.toLong() * header.entrySize).toInt())
        entriesBuf.get(rawEntries)
        val ecrc = CRC32()
        ecrc.update(rawEntries)
        require(ecrc.value.toInt() == header.entriesCrc) { "GPT entries CRC mismatch" }

        val partitions = ArrayList<Partition>()
        for (i in 0 until header.entryCount) {
            val off = i * header.entrySize
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
                    esp = guid.equals(EFI_SYSTEM_TYPE_GUID, ignoreCase = true),
                    slot = i,
                ),
            )
        }
        rejectOverlaps(partitions)
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
}
