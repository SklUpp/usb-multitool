package usb.fs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import usb.partition.VirtualPartition

object Filesystem {
    /** Current volume label of a formatted partition, or null when the engine/volume has none. */
    fun readLabel(partition: VirtualPartition, id: FilesystemId): String? =
        when (id) {
            FilesystemId.FAT, FilesystemId.EXFAT ->
                FatFs.getLabel(
                    callback = BlockDeviceFatCallback(partition),
                    sectorSize = partition.sectorSize,
                    sectorCount = partition.sectorCount.toLong(),
                )
            FilesystemId.NTFS -> NtfsFs.getLabel(partition)
            FilesystemId.EXT -> extVolumeLabel(partition)
        }?.trim()?.takeIf { it.isNotEmpty() }

    fun probe(partition: VirtualPartition): FilesystemId {
        val sector = ByteArray(partition.sectorSize)
        val buf = ByteBuffer.wrap(sector)
        partition.read(0UL, buf)

        if (isExfat(sector)) return FilesystemId.EXFAT
        if (isFat(sector)) return FilesystemId.FAT
        if (isNtfs(sector)) return FilesystemId.NTFS
        if (isExt(partition)) return FilesystemId.EXT
        throw UnsupportedFilesystemException("no supported filesystem signature")
    }

    private fun isExfat(boot: ByteArray): Boolean =
        boot.size >= 11 &&
            boot[510] == 0x55.toByte() && boot[511] == 0xAA.toByte() &&
            oem(boot) == "EXFAT   "

    private fun isFat(boot: ByteArray): Boolean {
        if (boot.size < 512 || boot[510] != 0x55.toByte() || boot[511] != 0xAA.toByte()) return false
        if (oem(boot) == "EXFAT   " || oem(boot) == "NTFS    ") return false
        val fat16 = ascii(boot, 54, 8)
        val fat32 = ascii(boot, 82, 8)
        return fat16.startsWith("FAT") || fat32.startsWith("FAT") ||
            // BPB with valid bytes/sector and sectors/cluster, common for FAT images without FS type string
            (u16(boot, 11) in setOf(512, 1024, 2048, 4096) && boot[13].toInt() and 0xFF != 0)
    }

    private fun isNtfs(boot: ByteArray): Boolean =
        boot.size >= 11 &&
            boot[510] == 0x55.toByte() && boot[511] == 0xAA.toByte() &&
            oem(boot) == "NTFS    "

    private fun isExt(partition: VirtualPartition): Boolean {
        val block = readExtSuperblock(partition) ?: return false
        val magic = u16(block, 0x38)
        return magic == 0xEF53
    }

    /** `ext4_superblock.volume_name[16]` at superblock offset 0x78, NUL-terminated UTF-8. */
    private fun extVolumeLabel(partition: VirtualPartition): String? {
        val block = readExtSuperblock(partition) ?: return null
        if (u16(block, 0x38) != 0xEF53) return null
        val raw = block.copyOfRange(0x78, 0x78 + 16)
        val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.UTF_8)
    }

    /** First 1024 bytes at absolute byte 1024 (sector-sized reads, any sector size). */
    private fun readExtSuperblock(partition: VirtualPartition): ByteArray? {
        val sectorSize = partition.sectorSize
        if (partition.sectorCount * sectorSize.toULong() < 2048u) return null
        val startSector = (1024L / sectorSize).toULong()
        val offsetInSector = (1024L % sectorSize).toInt()
        val need = offsetInSector + 1024
        val sectors = (need + sectorSize - 1) / sectorSize
        val raw = ByteArray(sectors * sectorSize)
        partition.read(startSector, ByteBuffer.wrap(raw))
        return raw.copyOfRange(offsetInSector, offsetInSector + 1024)
    }

    private fun oem(boot: ByteArray): String = ascii(boot, 3, 8)

    private fun ascii(bytes: ByteArray, offset: Int, len: Int): String =
        bytes.copyOfRange(offset, offset + len).toString(Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int {
        val bb = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN)
        return bb.short.toInt() and 0xFFFF
    }
}

class UnsupportedFilesystemException(message: String) : Exception(message)
