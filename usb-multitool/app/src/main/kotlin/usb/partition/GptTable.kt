package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import usb.device.UsbDisk

object GptTable {
    private val SIGNATURE = "EFI PART".toByteArray(StandardCharsets.US_ASCII)
    private val UNUSED = ByteArray(16)

    fun read(disk: UsbDisk): List<Partition> {
        val sectorSize = disk.geometry.sectorSize
        val headerBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(1UL, headerBuf)
        headerBuf.flip()
        require(headerBuf.remaining() >= 92) { "GPT header truncated" }
        val sig = ByteArray(8)
        headerBuf.position(0)
        headerBuf.get(sig)
        require(sig.contentEquals(SIGNATURE)) { "missing GPT signature" }

        val headerSize = headerBuf.getInt(12)
        require(headerSize >= 92) { "invalid GPT header size $headerSize" }
        val storedCrc = headerBuf.getInt(16)
        val crcCheck = headerBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val headerBytes = ByteArray(headerSize)
        crcCheck.position(0)
        crcCheck.get(headerBytes)
        // zero CRC field at offset 16
        headerBytes[16] = 0
        headerBytes[17] = 0
        headerBytes[18] = 0
        headerBytes[19] = 0
        val crc = CRC32()
        crc.update(headerBytes)
        require(crc.value.toInt() == storedCrc) { "GPT header CRC mismatch" }

        val firstUsable = headerBuf.getLong(40).toULong()
        val lastUsable = headerBuf.getLong(48).toULong()
        val entryLba = headerBuf.getLong(72).toULong()
        val entryCount = headerBuf.getInt(80)
        val entrySize = headerBuf.getInt(84)
        val entriesCrc = headerBuf.getInt(88)
        require(entrySize >= 128) { "GPT entry size $entrySize too small" }
        require(entryCount > 0) { "no GPT entries" }

        val entriesBytes = entryCount * entrySize
        val sectorsNeeded = (entriesBytes + sectorSize - 1) / sectorSize
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
            partitions.add(
                Partition(
                    startLba = start,
                    sectorCount = count,
                    type = guidString(type),
                    name = name,
                    flags = attrs,
                ),
            )
        }
        rejectOverlaps(partitions)
        return partitions
    }

    private fun leULong(bytes: ByteArray, offset: Int): ULong {
        var v = 0UL
        for (i in 0 until 8) {
            v = v or ((bytes[offset + i].toULong() and 0xFFu) shl (8 * i))
        }
        return v
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
        // Microsoft mixed-endian display form
        fun revHex(start: Int, len: Int): String =
            raw.copyOfRange(start, start + len).reversedArray().joinToString("") { "%02X".format(it) }
        fun hex(start: Int, len: Int): String =
            raw.copyOfRange(start, start + len).joinToString("") { "%02X".format(it) }
        return "${revHex(0, 4)}-${revHex(4, 2)}-${revHex(6, 2)}-${hex(8, 2)}-${hex(10, 6)}"
    }
}
