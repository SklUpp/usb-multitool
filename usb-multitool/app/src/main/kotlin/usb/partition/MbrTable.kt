package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import usb.device.BlockDevice

object MbrTable {
    private const val PROTECTIVE_TYPE = 0xEE

    data class Result(
        val protective: Boolean,
        val partitions: List<Partition>,
        /** Recognition distinguishes known superfloppies from unrecognized bytes. */
        val recognition: TableRecognition,
        val signaturePresent: Boolean = recognition != TableRecognition.UNRECOGNIZED,
    )
    /** Local recognizer for GPT shrink recovery; ordinary reads remain fail-closed. */
    internal data class ProtectiveOnly(
        val sector: ByteArray,
        val slot: Int,
        val startLba: ULong,
        val sectorCount: ULong,
    )

    internal fun readProtectiveOnly(disk: BlockDevice): ProtectiveOnly? {
        val buf = ByteBuffer.allocate(disk.sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(0UL, buf)
        buf.flip()
        if (buf.limit() < 512 ||
            ((buf.get(510).toInt() and 0xFF) or ((buf.get(511).toInt() and 0xFF) shl 8)) != 0xAA55 ||
            filesystemBootStatus(buf, disk.sectorCount, disk.sectorSize) != FilesystemBoot.NONE
        ) return null
        var protective: ProtectiveOnly? = null
        for (i in 0 until 4) {
            val off = 446 + i * 16
            val type = buf.get(off + 4).toInt() and 0xFF
            val start = buf.getInt(off + 8).toUInt().toULong()
            val count = buf.getInt(off + 12).toUInt().toULong()
            val empty = (0 until 16).all { buf.get(off + it).toInt() == 0 }
            if (empty) continue
            if (type != PROTECTIVE_TYPE || start != 1UL || count == 0UL || protective != null) return null
            protective = ProtectiveOnly(buf.array().copyOf(), i, start, count)
        }
        return protective
    }


    fun read(disk: BlockDevice): Result {
        val sectorSize = disk.sectorSize
        val diskSectors = disk.sectorCount
        val buf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(0UL, buf)
        buf.flip()
        if (buf.limit() < 512) return Result(false, emptyList(), TableRecognition.UNRECOGNIZED)
        val sig = (buf.get(510).toInt() and 0xFF) or ((buf.get(511).toInt() and 0xFF) shl 8)
        val filesystemBoot = filesystemBootStatus(buf, diskSectors, sectorSize)
        if (sig != 0xAA55) {
            return Result(
                false,
                emptyList(),
                if (filesystemBoot != FilesystemBoot.NONE) TableRecognition.DAMAGED
                else TableRecognition.UNRECOGNIZED,
            )
        }
        if (filesystemBoot != FilesystemBoot.NONE) {
            var hasEntry = false
            var protective = false
            for (i in 0 until 4) {
                val off = 446 + i * 16
                val type = buf.get(off + 4).toInt() and 0xFF
                val start = buf.getInt(off + 8).toUInt().toULong()
                val count = buf.getInt(off + 12).toUInt().toULong()
                val plausible = type != 0 && count != 0UL &&
                    start <= diskSectors && count <= diskSectors - start
                if (!plausible) continue
                hasEntry = true
                if (type == PROTECTIVE_TYPE) protective = true
            }
            return Result(
                protective = protective,
                partitions = emptyList(),
                recognition = if (filesystemBoot == FilesystemBoot.DAMAGED || hasEntry) {
                    TableRecognition.DAMAGED
                } else {
                    TableRecognition.SUPERFLOPPY
                },
            )
        }
        val entries = ArrayList<Partition>(4)
        var protective = false
        var hasEntry = false
        var malformed = false
        for (i in 0 until 4) {
            val off = 446 + i * 16
            val type = buf.get(off + 4).toInt() and 0xFF
            val start = buf.getInt(off + 8).toUInt().toULong()
            val count = buf.getInt(off + 12).toUInt().toULong()
            if (type == 0 && count == 0UL) continue
            hasEntry = true
            if (type == 0 || count == 0UL || start > diskSectors || count > diskSectors - start) {
                require(false) {
                    "MBR partition past end of disk: start=$start count=$count capacity=$diskSectors"
                }
                continue
            }
            if (type == PROTECTIVE_TYPE) {
                protective = true
                continue
            }
            val bootable = (buf.get(off).toInt() and 0x80) != 0
            entries.add(
                Partition(
                    startLba = start,
                    sectorCount = count,
                    type = "0x%02X".format(type),
                    name = "P${i + 1}",
                    flags = if (bootable) 1L else 0L,
                    bootable = bootable,
                    slot = i,
                ),
            )
        }
        rejectOverlaps(entries)
        val recognition = when {
            filesystemBoot == FilesystemBoot.DAMAGED -> TableRecognition.DAMAGED
            filesystemBoot == FilesystemBoot.VALID && hasEntry -> TableRecognition.DAMAGED
            filesystemBoot == FilesystemBoot.VALID -> TableRecognition.SUPERFLOPPY
            else -> TableRecognition.RECOGNIZED
        }
        return Result(protective, entries, recognition)
    }

}

private enum class FilesystemBoot { NONE, VALID, DAMAGED }

private fun filesystemBootStatus(buf: ByteBuffer, diskSectors: ULong, sectorSize: Int): FilesystemBoot {
    if (looksLikeFilesystemBoot(buf, diskSectors, sectorSize)) return FilesystemBoot.VALID
    if (buf.limit() < 512) return FilesystemBoot.NONE
    val ntfsMarker = ascii(buf, 3, 8) == "NTFS    "
    val exfatMarker = ascii(buf, 3, 8) == "EXFAT   "
    val fatMarker = ascii(buf, 54, 8).startsWith("FAT") || ascii(buf, 82, 8).startsWith("FAT")
    return if (ntfsMarker || exfatMarker || fatMarker) FilesystemBoot.DAMAGED else FilesystemBoot.NONE
}

private fun looksLikeFilesystemBoot(buf: ByteBuffer, diskSectors: ULong, sectorSize: Int): Boolean =
    looksLikeFatBoot(buf, diskSectors, sectorSize) ||
        looksLikeNtfsBoot(buf, diskSectors, sectorSize) ||
        looksLikeExfatBoot(buf, diskSectors, sectorSize)
private fun looksLikeFatBoot(buf: ByteBuffer, diskSectors: ULong, sectorSize: Int): Boolean {
    if (sectorSize < 512 || buf.limit() < 512) return false
    val jump = u8(buf, 0)
    if (!((jump == 0xEB && u8(buf, 2) == 0x90) || jump == 0xE9)) return false
    val bytesPerSector = u16(buf, 11)
    val sectorsPerCluster = u8(buf, 13)
    val reserved = u16(buf, 14)
    val fats = u8(buf, 16)
    val rootEntries = u16(buf, 17)
    val total16 = u16(buf, 19)
    val media = u8(buf, 21)
    val fatSize16 = u16(buf, 22)
    val total32 = u32(buf, 32)
    if (bytesPerSector !in setOf(512, 1024, 2048, 4096) || bytesPerSector != sectorSize ||
        sectorsPerCluster == 0 || (sectorsPerCluster and (sectorsPerCluster - 1)) != 0 ||
        (bytesPerSector.toULong() * sectorsPerCluster.toULong() > 32768UL && fatSize16 != 0) ||
        reserved == 0 || fats == 0 || (media != 0xF0 && media !in 0xF8..0xFF)) return false
    val fatSize32 = u32(buf, 36)
    val fat32 = fatSize16 == 0
    if (fat32) {
        if (rootEntries != 0 || total16 != 0 || total32 == 0UL || fatSize32 == 0UL ||
            u16(buf, 42) != 0) return false
        val fsInfo = u16(buf, 48)
        val backup = u16(buf, 50)
        if (fsInfo >= reserved || (backup != 0xFFFF && backup >= reserved)) return false
    } else if (rootEntries == 0) return false
    val total = if (total16 != 0) total16.toULong() else total32
    val fatSize = if (fat32) fatSize32 else fatSize16.toULong()
    if (total == 0UL || total > diskSectors || fatSize == 0UL) return false
    val rootSectors = (rootEntries.toULong() * 32UL + bytesPerSector.toULong() - 1UL) /
        bytesPerSector.toULong()
    val firstData = reserved.toULong() + fats.toULong() * fatSize + rootSectors
    if (firstData < reserved.toULong() || firstData >= total) return false
    val clusters = (total - firstData) / sectorsPerCluster.toULong()
    if (fat32) {
        if (clusters !in 65525UL..0x0FFFFFF5UL || u32(buf, 44) !in 2UL..(clusters + 1UL)) return false
        return fatSize * bytesPerSector.toULong() >= (clusters + 2UL) * 4UL
    }
    val fatBytes = fatSize * bytesPerSector.toULong()
    return clusters in 1UL..65524UL && if (clusters <= 4084UL) {
        fatBytes >= ((clusters + 2UL) * 3UL + 1UL) / 2UL
    } else {
        fatBytes >= (clusters + 2UL) * 2UL
    }
}

private fun looksLikeNtfsBoot(buf: ByteBuffer, diskSectors: ULong, sectorSize: Int): Boolean {
    if (buf.limit() < 512 || ascii(buf, 3, 8) != "NTFS    " || u8(buf, 0) != 0xEB ||
        u8(buf, 2) != 0x90 || u16(buf, 11) !in setOf(512, 1024, 2048, 4096) ||
        u16(buf, 11) != sectorSize || u8(buf, 13) == 0 ||
        (u8(buf, 13) and (u8(buf, 13) - 1)) != 0 || u8(buf, 21) !in 0xF8..0xFF) return false
    val total = u64(buf, 40)
    val mft = u64(buf, 48)
    val mirror = u64(buf, 56)
    val recordSize = u8(buf, 64)
    val indexSize = u8(buf, 68)
    return total in 1UL..diskSectors && mft < total && mirror < total &&
        recordSize != 0 && indexSize != 0
}

private fun looksLikeExfatBoot(buf: ByteBuffer, diskSectors: ULong, sectorSize: Int): Boolean {
    if (buf.limit() < 512 || !ascii(buf, 3, 8).contentEquals("EXFAT   ")) return false
    val volumeLength = u64(buf, 72)
    val fatOffset = u32(buf, 80)
    val fatLength = u32(buf, 84)
    val heapOffset = u32(buf, 88)
    val clusterCount = u32(buf, 92)
    val rootCluster = u32(buf, 96)
    val bytesPerSectorShift = u8(buf, 108)
    val sectorsPerClusterShift = u8(buf, 109)
    val fatCount = u8(buf, 110)
    if (bytesPerSectorShift !in 9..12 || sectorsPerClusterShift > 25 || fatCount !in 1..2) return false
    val bytesPerSector = 1 shl bytesPerSectorShift
    val sectorsPerCluster = 1 shl sectorsPerClusterShift
    val dataStart = fatOffset + fatLength * fatCount.toULong()
    val dataEnd = heapOffset + clusterCount * sectorsPerCluster.toULong()
    return bytesPerSector == sectorSize &&
        volumeLength in 1UL..diskSectors && fatOffset > 0UL && fatLength > 0UL &&
        heapOffset >= dataStart && clusterCount > 0UL && rootCluster in 2UL..(clusterCount + 1UL) &&
        dataEnd >= heapOffset && dataEnd <= volumeLength
}

private fun u64(buf: ByteBuffer, offset: Int): ULong {
    return u32(buf, offset) or (u32(buf, offset + 4) shl 32)
}

private fun ascii(buf: ByteBuffer, offset: Int, length: Int): String {
    val bytes = ByteArray(length)
    for (index in bytes.indices) bytes[index] = buf.get(offset + index)
    return bytes.toString(Charsets.US_ASCII)
}
private fun u8(buf: ByteBuffer, offset: Int): Int = buf.get(offset).toInt() and 0xFF

private fun u16(buf: ByteBuffer, offset: Int): Int = buf.getShort(offset).toInt() and 0xFFFF

private fun u32(buf: ByteBuffer, offset: Int): ULong = buf.getInt(offset).toUInt().toULong()
internal fun rejectOverlaps(partitions: List<Partition>) {
    val sorted = partitions.sortedBy { it.startLba }
    for (i in 1 until sorted.size) {
        val prev = sorted[i - 1]
        val cur = sorted[i]
        val prevEnd = prev.startLba + prev.sectorCount
        require(prevEnd >= prev.startLba) { "partition end overflow" }
        require(cur.startLba >= prevEnd) {
            "overlapping partitions at LBA ${cur.startLba}"
        }
    }
}
