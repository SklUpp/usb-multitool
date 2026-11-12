package usb.ventoy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import usb.EngineMissingException
import usb.device.BlockDevice
import usb.requireLinkedEngine
import usb.fs.BlockDeviceFatCallback
import usb.fs.FatFs
import usb.fs.FatFsException
import usb.fs.FatKind
import usb.fs.FilesystemId
import usb.fs.Formatter
import usb.partition.FreeRegion
import usb.partition.Partition
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.TableScheme
import usb.partition.VirtualPartition

class IncompatibleVentoyException(message: String, cause: Throwable? = null) : Exception(message, cause)

class VentoyOverwriteRequiredException(message: String) : Exception(message)

class UnsupportedSectorSizeException(message: String) : Exception(message)

/** The release payload does not fit the existing partition; writing would truncate it. */
class VentoyPayloadTooLargeException(message: String) : Exception(message)

/**
 * Install/update mutated the disk then failed. Callers must surface Partial, never
 * "untouched failure".
 */
class VentoyMutationFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class VentoyProbeResult(
    val isVentoy: Boolean,
    val scheme: TableScheme,
    val part1StartLba: ULong,
    val part1SectorCount: ULong,
    val part2StartLba: ULong,
    val part2SectorCount: ULong,
    val diskUuid: ByteArray,
    val diskSignature: Int,
    val rsvData: ByteArray,
    /** Parsed `VENTOY_VERSION` or empty when the marker/file is absent. */
    val version: String,
    /** Non-null only when reading VTOYEFI failed (FAT/IO/engine); never swallowed silently. */
    val versionError: Throwable? = null,
)

object VentoyLayout {
    const val SECTOR_SIZE = 512
    const val PART1_START_SECTOR = 2048L
    const val EFI_PART_SECTORS = 65536L

    /** Initial/tail sectors zeroed before install: 34 × 512 B = 17 KiB per region. */
    const val ZERO_REGION_SECTORS = 34

    /** core.img fits LBA 1..2047 on MBR; GPT starts at LBA 34, so only 2014 sectors. */
    const val MBR_CORE_MAX_SECTORS = 2047L
    const val GPT_CORE_MAX_SECTORS = 2014L

    /** Ventoy reserved-data window restored after core writes (upstream LBA 2040..2047). */
    const val RSV_LBA = 2040L
    const val RSV_SECTORS = 8L

    /** `grub.cfg` is a small text file; anything larger is not a valid Ventoy marker. */
    const val MAX_GRUB_CFG_BYTES = 1_048_576L

    fun isVentoyDisk(disk: BlockDevice): Boolean {
        if (disk.sectorSize == 4096) {
            throw UnsupportedSectorSizeException("Upstream Ventoy does not support 4K native devices")
        }
        if (disk.sectorSize != SECTOR_SIZE) {
            throw UnsupportedSectorSizeException("Unsupported sector size: ${disk.sectorSize}")
        }
        return probe(disk)?.isVentoy == true
    }

    /**
     * Raw signature check for a damaged Ventoy layout that [probe] rejects (bad CRC, version
     * unreadable). Reads only the partition tables; no version, no mount, no guesses from
     * unrelated GPT/MBR content. False means "not provably Ventoy", never "definitely clean".
     *
     * I/O/transport failures are **not** converted to false: the caller must report ReadError.
     * Expected invalid layout (bad signature/geometry) is the only false result.
     */
    internal fun looksLikeVentoyLayout(disk: BlockDevice): Boolean {
        if (disk.sectorSize != SECTOR_SIZE) return false
        val mbr = ByteArray(SECTOR_SIZE)
        disk.read(0L, ByteBuffer.wrap(mbr))
        if (mbr[510] != 0x55.toByte() || mbr[511] != 0xAA.toByte()) return false
        val firstType = mbr[446 + 4].toInt() and 0xFF
        if (firstType == 0xEE) {
            val hdr = ByteArray(SECTOR_SIZE)
            disk.read(1L, ByteBuffer.wrap(hdr))
            if (String(hdr, 0, 8, Charsets.US_ASCII) != "EFI PART") return false
            val table = ByteArray(32 * SECTOR_SIZE)
            disk.read(2L, ByteBuffer.wrap(table))
            for (index in 0 until 128) {
                val nameOffset = index * 128 + 56
                val name = String(table, nameOffset, 14, Charsets.UTF_16LE)
                if (name == "VTOYEFI") return true
            }
            return false
        }
        val p1Type = mbr[446 + 4].toInt() and 0xFF
        val p2Type = mbr[462 + 4].toInt() and 0xFF
        fun le32(offset: Int): Int =
            (mbr[offset].toInt() and 0xFF) or
                ((mbr[offset + 1].toInt() and 0xFF) shl 8) or
                ((mbr[offset + 2].toInt() and 0xFF) shl 16) or
                ((mbr[offset + 3].toInt() and 0xFF) shl 24)
        val p1Start = le32(446 + 8)
        val p1Count = le32(446 + 12)
        val p2Start = le32(462 + 8)
        val p2Count = le32(462 + 12)
        return p1Type != 0 &&
            p2Type == 0xEF &&
            p1Start == PART1_START_SECTOR.toInt() &&
            p2Start == p1Start + p1Count &&
            p2Count == EFI_PART_SECTORS.toInt()
    }

    fun probe(disk: BlockDevice): VentoyProbeResult? = probe(disk, ::readVentoyVersionFromPart2)

    /** Testable seam: [versionReader] must return the real FAT read in production. */
    internal fun probe(
        disk: BlockDevice,
        versionReader: (BlockDevice, ULong, ULong) -> Result<String>,
    ): VentoyProbeResult? {
        if (disk.sectorSize == 4096) {
            throw UnsupportedSectorSizeException("Upstream Ventoy does not support 4K native devices")
        }
        if (disk.sectorSize != SECTOR_SIZE) {
            throw UnsupportedSectorSizeException("Unsupported sector size: ${disk.sectorSize}")
        }
        val minSectors = (PART1_START_SECTOR + EFI_PART_SECTORS + 2048L).toULong()
        if (disk.sectorCount < minSectors) return null

        val mbrBuf = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(0L, mbrBuf)
        val mbr = mbrBuf.array()
        if (mbr[510] != 0x55.toByte() || mbr[511] != 0xAA.toByte()) {
            return null
        }

        val uuid = mbr.copyOfRange(0x180, 0x190)
        val sigBuf = ByteBuffer.wrap(mbr, 0x1B8, 4).order(ByteOrder.LITTLE_ENDIAN)
        val sig = sigBuf.int

        val part0Fs = mbr[450].toInt() and 0xFF
        val isGpt = (part0Fs == 0xEE)

        if (isGpt) {
            val gptHdrBuf = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            disk.read(1L, gptHdrBuf)
            val hdr = gptHdrBuf.array()
            val sigStr = String(hdr, 0, 8, Charsets.US_ASCII)
            if (sigStr != "EFI PART") return null

            val tblBuf = ByteBuffer.allocate(32 * SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            disk.read(2L, tblBuf)
            val tbl = tblBuf.array()

            // Fail closed: GPT header + entries CRC + header geometry vs disk size.
            if (!gptHeaderAndEntriesCrcOk(hdr, tbl, disk.sectorCount)) return null

            // Verify partition 1 and 2
            val p1Start = ByteBuffer.wrap(tbl, 32, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            val p1Last = ByteBuffer.wrap(tbl, 40, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            val p2Start = ByteBuffer.wrap(tbl, 128 + 32, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            val p2Last = ByteBuffer.wrap(tbl, 128 + 40, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()

            val p2Count = p2Last - p2Start + 1UL
            // Fail closed on malformed tables before any update write.
            if (p1Last < p1Start || p2Last < p2Start) return null
            if (p2Start != (p1Last + 1UL)) return null
            if (p1Start != PART1_START_SECTOR.toULong()) return null
            // Upstream is_disk_contains_ventoy: Part2 size is exactly 65536 sectors.
            if (p2Count != EFI_PART_SECTORS.toULong()) return null
            if (p1Last - p1Start + 1UL < 2048UL) return null
            if (p1Start >= disk.sectorCount || p1Last >= disk.sectorCount) return null
            if (p2Start >= disk.sectorCount || p2Last >= disk.sectorCount) return null
            // Backup GPT occupies the last 33 LBAs — partitions must not overlap it.
            if (p2Last >= disk.sectorCount - 33UL) return null
            val firstUsable = ByteBuffer.wrap(hdr, 40, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            val lastUsable = ByteBuffer.wrap(hdr, 48, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            if (p1Start < firstUsable || p2Last > lastUsable) return null
            // Check VTOYEFI name
            val nameBytes = tbl.copyOfRange(128 + 56, 128 + 56 + 14)
            val nameStr = String(nameBytes, Charsets.UTF_16LE)
            if (nameStr != "VTOYEFI") return null

            val rsv = ByteBuffer.allocate((RSV_SECTORS * SECTOR_SIZE).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            disk.read(RSV_LBA, rsv)
            val versionResult = versionReader(disk, p2Start, p2Count)

            return VentoyProbeResult(
                isVentoy = true,
                scheme = TableScheme.GPT,
                part1StartLba = p1Start,
                part1SectorCount = p1Last - p1Start + 1UL,
                part2StartLba = p2Start,
                part2SectorCount = p2Count,
                diskUuid = uuid,
                diskSignature = sig,
                rsvData = rsv.array(),
                // Real version from Part2 FAT /grub/grub.cfg; absence vs failure is explicit.
                version = versionResult.getOrNull().orEmpty(),
                versionError = versionResult.exceptionOrNull(),
            )
        } else {
            val p0Start = (ByteBuffer.wrap(mbr, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toULong()
            val p0Count = (ByteBuffer.wrap(mbr, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toULong()
            val p1Flag = mbr[462 + 4].toInt() and 0xFF
            val p1Start = (ByteBuffer.wrap(mbr, 462 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toULong()
            val p1Count = (ByteBuffer.wrap(mbr, 462 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toULong()

            if (p0Count == 0UL || p1Count == 0UL) return null
            if (p0Count < 2048UL) return null
            if (p0Start != PART1_START_SECTOR.toULong() || (p0Start + p0Count) != p1Start) return null
            // Upstream: Part2 size must be exactly VENTOY_SECTOR_NUM (65536); type 0xEF.
            if (p1Count != EFI_PART_SECTORS.toULong() || p1Flag != 0xEF) return null
            if (p0Start >= disk.sectorCount || p0Count > disk.sectorCount - p0Start) return null
            if (p1Start >= disk.sectorCount || p1Count > disk.sectorCount - p1Start) return null

            val rsv = ByteBuffer.allocate((RSV_SECTORS * SECTOR_SIZE).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            disk.read(RSV_LBA, rsv)
            val versionResult = versionReader(disk, p1Start, p1Count)

            return VentoyProbeResult(
                isVentoy = true,
                scheme = TableScheme.MBR,
                part1StartLba = p0Start,
                part1SectorCount = p0Count,
                part2StartLba = p1Start,
                part2SectorCount = p1Count,
                diskUuid = uuid,
                diskSignature = sig,
                rsvData = rsv.array(),
                version = versionResult.getOrNull().orEmpty(),
                versionError = versionResult.exceptionOrNull(),
            )
        }
    }

    /**
     * Fail closed: GPT header CRC, partition-entry CRC, and basic header geometry.
     * [diskSectorCount] when non-null also checks AlternateLBA / usable range / backup room.
     */
    internal fun gptHeaderAndEntriesCrcOk(
        hdr: ByteArray,
        entries: ByteArray,
        diskSectorCount: ULong? = null,
    ): Boolean {
        if (hdr.size < 92) return false
        // UEFI GPT HeaderSize (offset 12): must be 92; refuse truncated/extended headers.
        val headerSize = ByteBuffer.wrap(hdr, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (headerSize != 92) return false
        if (hdr.size < headerSize) return false
        val hdrCopy = hdr.copyOf(headerSize)
        hdrCopy[16] = 0; hdrCopy[17] = 0; hdrCopy[18] = 0; hdrCopy[19] = 0
        val hdrCrc = ieeeCrc32(hdrCopy, 0, headerSize)
        val storedHdr = ByteBuffer.wrap(hdr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (hdrCrc != storedHdr) return false
        val myLba = ByteBuffer.wrap(hdr, 24, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
        val alternateLba = ByteBuffer.wrap(hdr, 32, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
        val firstUsable = ByteBuffer.wrap(hdr, 40, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
        val lastUsable = ByteBuffer.wrap(hdr, 48, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
        val entryLba = ByteBuffer.wrap(hdr, 72, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
        val entryCount = ByteBuffer.wrap(hdr, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val entrySize = ByteBuffer.wrap(hdr, 84, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (entrySize != 128 || entryCount < 2) return false
        // Overflow-safe need = entrySize * entryCount
        if (entryCount > Int.MAX_VALUE / entrySize) return false
        val need = entrySize * entryCount
        if (entries.size < need) return false
        val entriesCrcStored = ByteBuffer.wrap(hdr, 88, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (ieeeCrc32(entries, 0, need) != entriesCrcStored) return false

        if (myLba != 1UL) return false
        if (entryLba != 2UL) return false
        if (firstUsable > lastUsable) return false
        if (diskSectorCount != null) {
            if (alternateLba != diskSectorCount - 1UL) return false
            if (alternateLba >= diskSectorCount) return false
            // Primary entry array is 32 sectors at LBA 2; backup array occupies last 33 LBAs
            // (32 entry sectors + 1 backup header). Usable must sit strictly between them.
            if (firstUsable < 34UL) return false
            if (lastUsable >= diskSectorCount - 33UL) return false
        }
        return true
    }

    private fun ieeeCrc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFFFFFF.toInt()
        val end = offset + length
        var i = offset
        while (i < end) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
            i++
        }
        return crc.inv()
    }

    /**
     * Upstream `vtoyfat` reads Part2 FAT `/grub/grub.cfg` and extracts `VENTOY_VERSION=`.
     * Returns [Result.failure] for FAT/IO/engine failures (never swallowed); a mounted FAT
     * volume without the marker/file yields an empty [Result.success] (absent, not unknown).
     */
    internal fun readVentoyVersionFromPart2(
        disk: BlockDevice,
        part2StartLba: ULong,
        part2SectorCount: ULong,
    ): Result<String> {
        if (part2SectorCount == 0UL) {
            return Result.failure(IllegalArgumentException("VTOYEFI sector count is zero"))
        }
        return try {
            Result.success(
                requireLinkedEngine("FatFs") {
                    val partition = VirtualPartition(part2StartLba, part2SectorCount, disk)
                    FatFs.mount(
                        BlockDeviceFatCallback(partition),
                        partition.sectorSize,
                        partition.sectorCount.toLong(),
                    ).use { volume ->
                        val entry = volume.statOrNull("/grub/grub.cfg")
                        when {
                            entry == null -> ""
                            entry.isDirectory ->
                                throw FatFsException("/grub/grub.cfg is a directory, not a file")
                            entry.size <= 0L || entry.size > MAX_GRUB_CFG_BYTES ->
                                throw FatFsException("/grub/grub.cfg size ${entry.size} is invalid")
                            else -> parseVentoyVersionFromGrubCfg(
                                volume.read("/grub/grub.cfg", 0L, entry.size.toInt())
                                    .toString(Charsets.US_ASCII),
                            ).orEmpty()
                        }
                    }
                },
            )
        } catch (cancel: java.util.concurrent.CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    /**
     * Sanity gate for the parsed marker: non-blank printable token, bounded length.
     * Deliberately does not pin a release format (upstream versions/packagings vary).
     */
    internal fun isValidVentoyVersion(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.length > 32) return false
        return v.all {
            it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' ||
                it == '.' || it == '-' || it == '_' || it == '+'
        }
    }

    /**
     * Byte-exact parse of upstream `get_ventoy_version` in `vtoyfat.c`:
     * find `VENTOY_VERSION=`, optional opening quote, then run until `"`, CR, LF, or NUL.
     */
    internal fun parseVentoyVersionFromGrubCfg(text: String): String? {
        val marker = "VENTOY_VERSION="
        val at = text.indexOf(marker)
        if (at < 0) return null
        var pos = at + marker.length
        if (pos < text.length && text[pos] == '"') pos++
        var end = pos
        while (end < text.length) {
            val c = text[end]
            if (c == '"' || c == '\r' || c == '\n' || c == '\u0000') break
            end++
        }
        val ver = text.substring(pos, end).trim()
        return ver.takeIf { it.isNotEmpty() }
    }


}


/** Pure install geometry after alignment; no disk I/O. */
data class VentoyInstallGeometry(
    val part1Start: Long,
    val part1End: Long,
    val part1Count: Long,
    val part2Start: Long,
    val part2End: Long,
    val diskSectors: Long,
    val gpt: Boolean,
)


object VentoyInstaller {

    fun install(
        disk: BlockDevice,
        assets: VentoyAssets,
        gpt: Boolean = true,
        reserveBytes: Long = 0L,
        align4k: Boolean = true,
        isActive: () -> Boolean = { true },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): PartitionTable {
        checkActive(isActive, "Ventoy install")
        if (disk.sectorSize == 4096) {
            throw UnsupportedSectorSizeException("Upstream Ventoy does not support 4K native devices")
        }
        if (disk.sectorSize != VentoyLayout.SECTOR_SIZE) {
            throw UnsupportedSectorSizeException("Unsupported sector size: ${disk.sectorSize}")
        }
        if (!gpt && disk.sectorCount > 0xFFFFFFFFUL) {
            throw IllegalArgumentException("Disks larger than 2TB require GPT; MBR cannot address > 0xFFFFFFFF sectors")
        }
        assets.validateChecksums(strict = true)

        val geometry = computeInstallGeometry(
            diskSectorCount = disk.sectorCount,
            gpt = gpt,
            reserveBytes = reserveBytes,
            align4k = align4k,
        )
        val diskSectors = geometry.diskSectors
        val part1Start = geometry.part1Start
        val part1End = geometry.part1End
        val part1Count = geometry.part1Count
        val part2Start = geometry.part2Start
        val part2End = geometry.part2End
        val decompressedCore = assets.decompressCoreImg()
        val decompressedEfi = assets.decompressEfiPartImg()
        preflightInstallPayloads(gpt, decompressedCore, decompressedEfi)
        Formatter.requireSupported(FilesystemId.EXFAT)
        checkActive(isActive, "Ventoy install")

        try {
        // Clean initial sectors — first mutation; failures after this → Partial.
        // One 17 KiB request per region instead of 34 sector requests: same bytes, same
        // error propagation, 34× fewer USB round trips.
        val zeroRegion = ByteBuffer.allocate(ZERO_REGION_SECTORS * VentoyLayout.SECTOR_SIZE)
        disk.write(0L, zeroRegion.duplicate().clear())
        if (gpt) {
            disk.write(diskSectors - ZERO_REGION_SECTORS.toLong(), zeroRegion.duplicate().clear())
        }

        // Write VTOYEFI partition data
        val payloadBytes = VentoyLayout.EFI_PART_SECTORS * VentoyLayout.SECTOR_SIZE
        writeBufferToDisk(disk, part2Start, decompressedEfi, VentoyLayout.EFI_PART_SECTORS) { done ->
            onProgress(done, payloadBytes)
        }
        checkActive(isActive, "Ventoy install")

        if (gpt) {
            // Write legacy GRUB stage1 at LBA 34
            val coreBuf = ByteBuffer.allocate(
                VentoyLayout.GPT_CORE_MAX_SECTORS.toInt() * VentoyLayout.SECTOR_SIZE,
            ).order(ByteOrder.LITTLE_ENDIAN)
            // Preflight proved any tail beyond 2014 sectors is zero padding (upstream's
            // GPT dd also stops at LBA 2047); real bytes are never dropped here.
            coreBuf.put(decompressedCore, 0, minOf(decompressedCore.size, coreBuf.capacity()))
            coreBuf.array()[500] = 35 // Blocklist marker
            val coreExpected = coreBuf.array().copyOf()
            coreBuf.flip()
            disk.write(34L, coreBuf)

            // Construct GPT tables — unique DiskGuid/PartGuid per install (upstream CoCreateGuid).
            val diskGuid = randomGptGuidBytes()
            val part1Guid = randomGptGuidBytes()
            val part2Guid = randomGptGuidBytes()
            val tblBuf = ByteBuffer.allocate(32 * VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val tbl = tblBuf.array()
            // Entry 0: Ventoy Data (Partition 1)
            // Windows basic data GUID: ebd0a0a2-b9e5-4433-87c0-68b6b72699c7
            putGuid(tbl, 0, 0xebd0a0a2.toInt(), 0xb9e5.toShort(), 0x4433.toShort(),
                byteArrayOf(0x87.toByte(), 0xc0.toByte(), 0x68.toByte(), 0xb6.toByte(), 0xb7.toByte(), 0x26.toByte(), 0x99.toByte(), 0xc7.toByte()))
            part1Guid.copyInto(tbl, 16)
            ByteBuffer.wrap(tbl, 32, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(part1Start)
            ByteBuffer.wrap(tbl, 40, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(part1End)
            ByteBuffer.wrap(tbl, 48, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L)
            putUtf16Name(tbl, 56, "Ventoy")

            // Entry 1: VTOYEFI (Partition 2)
            putGuid(tbl, 128, 0xebd0a0a2.toInt(), 0xb9e5.toShort(), 0x4433.toShort(),
                byteArrayOf(0x87.toByte(), 0xc0.toByte(), 0x68.toByte(), 0xb6.toByte(), 0xb7.toByte(), 0x26.toByte(), 0x99.toByte(), 0xc7.toByte()))
            part2Guid.copyInto(tbl, 128 + 16)
            ByteBuffer.wrap(tbl, 128 + 32, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(part2Start)
            ByteBuffer.wrap(tbl, 128 + 40, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(part2End)
            ByteBuffer.wrap(tbl, 128 + 48, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(Long.MIN_VALUE)
            putUtf16Name(tbl, 128 + 56, "VTOYEFI")

            val tblCrc = crc32(tbl)

            // Primary Header
            val primHdr = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val pHdrArr = primHdr.array()
            "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(pHdrArr, 0)
            pHdrArr[8] = 0; pHdrArr[9] = 0; pHdrArr[10] = 1; pHdrArr[11] = 0
            ByteBuffer.wrap(pHdrArr, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(92)
            ByteBuffer.wrap(pHdrArr, 24, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(1L)
            ByteBuffer.wrap(pHdrArr, 32, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(diskSectors - 1L)
            ByteBuffer.wrap(pHdrArr, 40, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(34L)
            ByteBuffer.wrap(pHdrArr, 48, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(diskSectors - 34L)
            diskGuid.copyInto(pHdrArr, 56)
            ByteBuffer.wrap(pHdrArr, 72, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(2L)
            ByteBuffer.wrap(pHdrArr, 80, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(128)
            ByteBuffer.wrap(pHdrArr, 84, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(128)
            ByteBuffer.wrap(pHdrArr, 88, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(tblCrc)
            val primCrc = crc32(pHdrArr, 0, 92)
            ByteBuffer.wrap(pHdrArr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(primCrc)

            // Backup Header
            val backHdr = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val bHdrArr = backHdr.array()
            pHdrArr.copyInto(bHdrArr, 0, 0, 92)
            ByteBuffer.wrap(bHdrArr, 24, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(diskSectors - 1L)
            ByteBuffer.wrap(bHdrArr, 32, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(1L)
            ByteBuffer.wrap(bHdrArr, 72, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(diskSectors - 33L)
            ByteBuffer.wrap(bHdrArr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
            val backCrc = crc32(bHdrArr, 0, 92)
            ByteBuffer.wrap(bHdrArr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(backCrc)

            // Protective MBR — upstream install dd boot.img count=446
            val mbrBuf = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val mbr = mbrBuf.array()
            copyBootCode(assets.bootImg, mbr, INSTALL_BOOT_BYTES)
            mbr[92] = 0x22
            mbr[446] = 0x00 // Not active
            mbr[450] = 0xEE.toByte() // Protective MBR
            putChs(mbr, 446 + 1, head = 0, sector = 1, cylinder = 0)
            putChs(mbr, 446 + 5, head = 254, sector = 63, cylinder = 1023)
            val randUuid = randomRawBytes(16)
            randUuid.copyInto(mbr, 0x180)
            // Upstream: last 4 bytes of a fresh uuid as disk signature
            ByteBuffer.wrap(mbr, 0x1B8, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ByteBuffer.wrap(randomRawBytes(4)).order(ByteOrder.LITTLE_ENDIAN).int)
            ByteBuffer.wrap(mbr, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(1)
            val protSectors = minOf(diskSectors - 1L, 0xFFFFFFFFL).toInt()
            ByteBuffer.wrap(mbr, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(protSectors)
            mbr[510] = 0x55.toByte()
            mbr[511] = 0xAA.toByte()

            // Write structures
            disk.write(0L, ByteBuffer.wrap(mbr))
            disk.write(1L, ByteBuffer.wrap(pHdrArr))
            disk.write(2L, ByteBuffer.wrap(tbl))
            disk.write(diskSectors - 33L, ByteBuffer.wrap(tbl))
            disk.write(diskSectors - 1L, ByteBuffer.wrap(bHdrArr))

            // Flush before read-back: verifying the page cache proves nothing about the medium.
            disk.sync()
            requireReadBack(disk, 0L, mbr)
            requireReadBack(disk, 1L, pHdrArr)
            requireReadBack(disk, 2L, tbl)
            requireReadBack(disk, diskSectors - 33L, tbl)
            requireReadBack(disk, diskSectors - 1L, bHdrArr)
            requireReadBack(disk, 34L, coreExpected)
            requirePart2Written(disk, part2Start, decompressedEfi)
        } else {
            // Write legacy GRUB stage1 at LBA 1
            val coreBuf = ByteBuffer.allocate(
                VentoyLayout.MBR_CORE_MAX_SECTORS.toInt() * VentoyLayout.SECTOR_SIZE,
            ).order(ByteOrder.LITTLE_ENDIAN)
            val copyLen = decompressedCore.size
            coreBuf.put(decompressedCore, 0, copyLen)
            val coreExpected = coreBuf.array().copyOf(coreBuf.position())
            coreBuf.flip()
            disk.write(1L, coreBuf)

            // Construct MBR — upstream install boot.img count=446
            val mbrBuf = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val mbr = mbrBuf.array()
            copyBootCode(assets.bootImg, mbr, INSTALL_BOOT_BYTES)
            // Partition 1
            mbr[446] = 0x80.toByte() // Active bootable
            mbr[450] = 0x07.toByte() // exFAT/NTFS
            val randUuid = randomRawBytes(16)
            randUuid.copyInto(mbr, 0x180)
            ByteBuffer.wrap(mbr, 0x1B8, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ByteBuffer.wrap(randomRawBytes(4)).order(ByteOrder.LITTLE_ENDIAN).int)
            ByteBuffer.wrap(mbr, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(part1Start.toInt())
            ByteBuffer.wrap(mbr, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(part1Count.toInt())
            fillMbrPartChs(mbr, 446, diskSectors, part1Start, part1Count)

            // Partition 2
            mbr[462] = 0x00.toByte()
            mbr[466] = 0xEF.toByte() // EFI System Partition
            ByteBuffer.wrap(mbr, 462 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(part2Start.toInt())
            ByteBuffer.wrap(mbr, 462 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(VentoyLayout.EFI_PART_SECTORS.toInt())
            fillMbrPartChs(mbr, 462, diskSectors, part2Start, VentoyLayout.EFI_PART_SECTORS)

            mbr[510] = 0x55.toByte()
            mbr[511] = 0xAA.toByte()

            disk.write(0L, mbrBuf)
            // Flush before read-back: verifying the page cache proves nothing about the medium.
            disk.sync()
            requireReadBack(disk, 0L, mbr)
            requireReadBack(disk, 1L, coreExpected)
            requirePart2Written(disk, part2Start, decompressedEfi)
        }

        // Part1 exFAT only after layout preflight+writes; failure here is Partial.
        formatDataPartitionExfat(
            disk = disk,
            startLba = part1Start.toULong(),
            sectorCount = part1Count.toULong(),
        )
        disk.sync()

        return PartitionTable(
            scheme = if (gpt) TableScheme.GPT else TableScheme.MBR,
            partitions = listOf(
                Partition(
                    startLba = part1Start.toULong(),
                    sectorCount = part1Count.toULong(),
                    type = PartitionType.BASIC.name,
                    name = "Ventoy",
                ),
                Partition(
                    startLba = part2Start.toULong(),
                    sectorCount = VentoyLayout.EFI_PART_SECTORS.toULong(),
                    type = PartitionType.VTOYEFI.name,
                    name = "VTOYEFI",
                    esp = true,
                ),
            ),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
            sectorSize = disk.sectorSize,
        )
        } catch (t: Throwable) {
            if (t is VentoyMutationFailedException) throw t
            // Disk already mutated — even cancel is Partial, never untouched Cancelled.
            throw VentoyMutationFailedException(
                "Ventoy install failed after the disk was modified: ${t.message}",
                t,
            )
        }
    }

    fun update(
        disk: BlockDevice,
        assets: VentoyAssets,
        isActive: () -> Boolean = { true },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): PartitionTable = updateChecked(
        disk = disk,
        assets = assets,
        isActive = isActive,
        onProgress = onProgress,
        versionReader = VentoyLayout::readVentoyVersionFromPart2,
    )

    /** Update with an explicit version reader; tests inject it, production uses the FAT read. */
    internal fun updateChecked(
        disk: BlockDevice,
        assets: VentoyAssets,
        isActive: () -> Boolean = { true },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        versionReader: (BlockDevice, ULong, ULong) -> Result<String>,
    ): PartitionTable {
        checkActive(isActive, "Ventoy update")
        if (disk.sectorSize == 4096) {
            throw UnsupportedSectorSizeException("Upstream Ventoy does not support 4K native devices")
        }
        if (disk.sectorSize != VentoyLayout.SECTOR_SIZE) {
            throw UnsupportedSectorSizeException("Unsupported sector size: ${disk.sectorSize}")
        }
        assets.validateChecksums(strict = true)

        // Refuse anything that is not an existing Ventoy layout: update never formats.
        val probe = VentoyLayout.probe(disk, versionReader)
        if (probe == null || !probe.isVentoy) {
            throw IncompatibleVentoyException(
                "Disk is not a recognized Ventoy layout; refusing update (use install to create one)",
            )
        }

        // Structure alone is forgeable: require a real, readable VENTOY_VERSION marker.
        val versionFailure = probe.versionError
        if (versionFailure != null) {
            when (versionFailure) {
                is java.util.concurrent.CancellationException -> throw versionFailure
                is EngineMissingException -> throw versionFailure
                else -> throw IncompatibleVentoyException(
                    "Cannot read the Ventoy version from VTOYEFI; refusing update: " +
                        "${versionFailure.message}",
                    versionFailure,
                )
            }
        }
        if (!VentoyLayout.isValidVentoyVersion(probe.version)) {
            throw IncompatibleVentoyException(
                "VTOYEFI carries no valid VENTOY_VERSION marker (got '${probe.version}'); " +
                    "refusing update without proof this is a real Ventoy disk",
            )
        }

        // CRITICAL CONTRACT: Partition 1 (from 2048 to part2StartLba - 1) is NEVER touched!
        val part2Start = probe.part2StartLba.toLong()
        val diskSectors = disk.sectorCount.toLong()

        val decompressedCore = assets.decompressCoreImg()
        val decompressedEfi = assets.decompressEfiPartImg()

        // Payload preflight — before ANY disk mutation.
        // Upstream always rewrites exactly VENTOY_SECTOR_NUM (65536) into Part2.
        if (probe.part2SectorCount != VentoyLayout.EFI_PART_SECTORS.toULong()) {
            throw IncompatibleVentoyException(
                "VTOYEFI size is ${probe.part2SectorCount} sectors; upstream requires exactly " +
                    "${VentoyLayout.EFI_PART_SECTORS}",
            )
        }
        val efiSectors =
            (decompressedEfi.size.toLong() + VentoyLayout.SECTOR_SIZE - 1) / VentoyLayout.SECTOR_SIZE
        if (efiSectors > VentoyLayout.EFI_PART_SECTORS) {
            throw VentoyPayloadTooLargeException(
                "EFI payload needs $efiSectors sectors but the VTOYEFI partition has only " +
                    "${VentoyLayout.EFI_PART_SECTORS}; update would truncate the boot image",
            )
        }
        val coreLimitSectors = if (probe.scheme == TableScheme.GPT) {
            VentoyLayout.GPT_CORE_MAX_SECTORS
        } else {
            VentoyLayout.MBR_CORE_MAX_SECTORS
        }
        val coreLimit = coreLimitSectors * VentoyLayout.SECTOR_SIZE
        if (decompressedCore.size > coreLimit) {
            // Same contract as install: zero padding past the GPT gap is harmless and is
            // exactly what upstream truncates; non-zero bytes must fail before writing.
            val losslessPadding = probe.scheme == TableScheme.GPT &&
                decompressedCore.size <= VentoyLayout.MBR_CORE_MAX_SECTORS * VentoyLayout.SECTOR_SIZE &&
                isAllZero(decompressedCore, coreLimit.toInt(), decompressedCore.size)
            if (!losslessPadding) {
                throw VentoyPayloadTooLargeException(
                    "core.img is ${decompressedCore.size} bytes; ${probe.scheme} boot gap " +
                        "holds only $coreLimitSectors sectors ($coreLimit bytes) and the tail is not zero padding",
                )
            }
        }
        if (decompressedCore.size % VentoyLayout.SECTOR_SIZE != 0) {
            throw VentoyPayloadTooLargeException(
                "core.img size ${decompressedCore.size} is not a multiple of ${VentoyLayout.SECTOR_SIZE}",
            )
        }
        if (decompressedEfi.size % VentoyLayout.SECTOR_SIZE != 0) {
            throw VentoyPayloadTooLargeException(
                "EFI payload size ${decompressedEfi.size} is not a multiple of ${VentoyLayout.SECTOR_SIZE}",
            )
        }
        // Backup-zone overlap already refused by probe; re-assert before write.
        if (probe.scheme == TableScheme.GPT &&
            probe.part2StartLba + probe.part2SectorCount > disk.sectorCount - 33UL
        ) {
            throw IncompatibleVentoyException(
                "VTOYEFI overlaps GPT backup area; refusing update",
            )
        }
        checkActive(isActive, "Ventoy update")

        try {
        // 1. Write new VTOYEFI image — always exactly 65536 sectors (upstream VENTOY_SECTOR_NUM).
        val payloadBytes = VentoyLayout.EFI_PART_SECTORS * VentoyLayout.SECTOR_SIZE
        writeBufferToDisk(disk, part2Start, decompressedEfi, VentoyLayout.EFI_PART_SECTORS) { done ->
            onProgress(done, payloadBytes)
        }
        checkActive(isActive, "Ventoy update")

        // 2. Write new core.img in boot gap
        val coreExpected: ByteArray
        if (probe.scheme == TableScheme.GPT) {
            val coreBuf = ByteBuffer.allocate(
                VentoyLayout.GPT_CORE_MAX_SECTORS.toInt() * VentoyLayout.SECTOR_SIZE,
            ).order(ByteOrder.LITTLE_ENDIAN)
            coreBuf.put(decompressedCore, 0, minOf(decompressedCore.size, coreBuf.capacity()))
            coreBuf.array()[500] = 35
            coreExpected = coreBuf.array().copyOf()
            coreBuf.flip()
            disk.write(34L, coreBuf)
        } else {
            val coreBuf = ByteBuffer.allocate(
                VentoyLayout.MBR_CORE_MAX_SECTORS.toInt() * VentoyLayout.SECTOR_SIZE,
            ).order(ByteOrder.LITTLE_ENDIAN)
            coreBuf.put(decompressedCore, 0, decompressedCore.size)
            coreExpected = coreBuf.array().copyOf(coreBuf.position())
            coreBuf.flip()
            disk.write(1L, coreBuf)
        }
        // 3. Restore reserved data at LBA 2040
        val rsvBuf = ByteBuffer.wrap(probe.rsvData)
        disk.write(VentoyLayout.RSV_LBA, rsvBuf)

        // 4. Update MBR boot code (upstream update: 440 bytes; preserve UUID + signature)
        val mbrBuf = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(0L, mbrBuf)
        val mbr = mbrBuf.array()
        copyBootCode(assets.bootImg, mbr, UPDATE_BOOT_BYTES)
        probe.diskUuid.copyInto(mbr, 0x180, 0, 16)
        ByteBuffer.wrap(mbr, 0x1B8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(probe.diskSignature)
        if (probe.scheme == TableScheme.MBR) {
            mbr[446] = 0x80.toByte()
        } else {
            mbr[92] = 0x22
        }
        mbrBuf.clear()
        disk.write(0L, mbrBuf)

        // 5. GPT: vtoygpt-style — rewrite attr/CRC only when Part2 attr is wrong.
        if (probe.scheme == TableScheme.GPT) {
            val tblBuf = ByteBuffer.allocate(32 * VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            disk.read(2L, tblBuf)
            val tbl = tblBuf.array()
            val curAttr = ByteBuffer.wrap(tbl, 128 + 48, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (curAttr != Long.MIN_VALUE) {
                ByteBuffer.wrap(tbl, 128 + 48, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(Long.MIN_VALUE)
                val tblCrc = crc32(tbl)

                val primHdr = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                disk.read(1L, primHdr)
                val pHdrArr = primHdr.array()
                ByteBuffer.wrap(pHdrArr, 88, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(tblCrc)
                ByteBuffer.wrap(pHdrArr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
                val primCrc = crc32(pHdrArr, 0, 92)
                ByteBuffer.wrap(pHdrArr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(primCrc)

                val backHdr = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                disk.read(diskSectors - 1L, backHdr)
                val bHdrArr = backHdr.array()
                ByteBuffer.wrap(bHdrArr, 88, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(tblCrc)
                ByteBuffer.wrap(bHdrArr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
                val backCrc = crc32(bHdrArr, 0, 92)
                ByteBuffer.wrap(bHdrArr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(backCrc)

                disk.write(2L, ByteBuffer.wrap(tbl))
                disk.write(1L, ByteBuffer.wrap(pHdrArr))
                disk.write(diskSectors - 33L, ByteBuffer.wrap(tbl))
                disk.write(diskSectors - 1L, ByteBuffer.wrap(bHdrArr))

                disk.sync()
                requireReadBack(disk, 2L, tbl)
                requireReadBack(disk, 1L, pHdrArr)
                requireReadBack(disk, diskSectors - 33L, tbl)
                requireReadBack(disk, diskSectors - 1L, bHdrArr)
            }
        }

        // Flush before read-back: the verify must observe the medium, not the page cache.
        disk.sync()
        requireReadBack(disk, 0L, mbr)
        if (probe.scheme == TableScheme.GPT) {
            // RSV window (2040..2047) is restored separately; core bytes end before it.
            requireReadBack(disk, 34L, coreExpected, VentoyLayout.RSV_LBA - 34L)
        } else {
            requireReadBack(disk, 1L, coreExpected, VentoyLayout.RSV_LBA - 1L)
        }
        requireReadBack(disk, VentoyLayout.RSV_LBA, probe.rsvData)
        requirePart2Written(disk, part2Start, decompressedEfi)

        return PartitionTable(
            scheme = probe.scheme,
            partitions = listOf(
                Partition(
                    startLba = probe.part1StartLba,
                    sectorCount = probe.part1SectorCount,
                    type = PartitionType.BASIC.name,
                    name = "Ventoy",
                ),
                Partition(
                    startLba = probe.part2StartLba,
                    sectorCount = probe.part2SectorCount,
                    type = PartitionType.VTOYEFI.name,
                    name = "VTOYEFI",
                    esp = true,
                ),
            ),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
            sectorSize = disk.sectorSize,
        )
        } catch (t: Throwable) {
            if (t is VentoyMutationFailedException) throw t
            // Disk already mutated — even cancel is Partial, never untouched Cancelled.
            throw VentoyMutationFailedException(
                "Ventoy update failed after the disk was modified: ${t.message}",
                t,
            )
        }
    }


    /**
     * Format Ventoy data partition (partition 1) as exFAT.
     * Call only after geometry/payload preflight + layout writes.
     *
     * Defaults use real [Formatter.format] + [FatFs.probe]. Tests may inject
     * [format]/[probeKind] to prove ULE→[usb.EngineMissingException] without native,
     * and that a non-EXFAT probe still fails closed. Success always requires
     * [FatKind.EXFAT] from [probeKind].
     */
    internal fun formatDataPartitionExfat(
        disk: BlockDevice,
        startLba: ULong,
        sectorCount: ULong,
        format: (VirtualPartition) -> Unit = { partition ->
            // Upstream: mkexfatfs -n Ventoy -s {64|256}
            Formatter.format(
                partition,
                FilesystemId.EXFAT,
                label = "Ventoy",
                auSizeBytes = ventoyExfatAuSizeBytes(disk.sectorCount, disk.sectorSize),
            )
        },
        probeKind: (VirtualPartition) -> FatKind = { partition ->
            FatFs.probe(
                BlockDeviceFatCallback(partition),
                partition.sectorSize,
                partition.sectorCount.toLong(),
            )
        },
    ) {
        require(sectorCount > 0UL) { "part1 sectorCount must be positive" }
        val partition = VirtualPartition(startLba, sectorCount, disk)
        requireLinkedEngine("FatFs") { format(partition) }
        disk.sync()
        val kind = requireLinkedEngine("FatFs") { probeKind(partition) }
        if (kind != FatKind.EXFAT) {
            throw IllegalStateException(
                "Ventoy part1 format verify failed: probe=$kind, expected EXFAT",
            )
        }
    }

    /**
     * Upstream VentoyWorker.sh: cluster sectors = 64 if disk ≤ 32 GiB else 256.
     * GiB divisor is 2097152 sectors (512-byte LBA). Returns au_size bytes for FatFs.
     */
    internal fun ventoyExfatAuSizeBytes(diskSectorCount: ULong, sectorSize: Int = VentoyLayout.SECTOR_SIZE): Int {
        require(sectorSize > 0) { "sectorSize must be positive" }
        val diskSizeGb = diskSectorCount / 2_097_152UL
        val clusterSectors = if (diskSizeGb > 32UL) 256 else 64
        return clusterSectors * sectorSize
    }

    /**
     * Pure geometry + bounds for Ventoy install. Throws before any disk mutation.
     */
    internal fun computeInstallGeometry(
        diskSectorCount: ULong,
        gpt: Boolean,
        reserveBytes: Long = 0L,
        align4k: Boolean = true,
    ): VentoyInstallGeometry {
        require(diskSectorCount <= Long.MAX_VALUE.toULong()) { "Disk sector count exceeds signed geometry range" }
        val diskSectors = diskSectorCount.toLong()
        require(reserveBytes >= 0L) { "reserveBytes must be >= 0, got $reserveBytes" }
        require(gpt || diskSectorCount <= 0xFFFFFFFFUL) {
            "Disks larger than 2TB require GPT; MBR cannot address > 0xFFFFFFFF sectors"
        }
        val reserveSectors = reserveBytes / VentoyLayout.SECTOR_SIZE
        // GPT keeps the last 33 LBAs for the backup entry array + backup header
        // (upstream ventoy_lib.sh: part1_end = disk - VTOYEFI - 34).
        val minNeeded =
            VentoyLayout.PART1_START_SECTOR + VentoyLayout.EFI_PART_SECTORS + (if (gpt) 34L else 1L) + 2047L + if (align4k) 7L else 0L
        require(diskSectors >= minNeeded && reserveSectors <= diskSectors - minNeeded) {
            "Disk too small for Ventoy after reserve/alignment: sectors=$diskSectors reserve=$reserveSectors minimum=$minNeeded"
        }
        val part1Start = VentoyLayout.PART1_START_SECTOR
        var part1End = diskSectors - reserveSectors - VentoyLayout.EFI_PART_SECTORS - if (gpt) 34L else 1L
        var part2Start = part1End + 1L
        if (align4k) {
            val misalignment = (part2Start % 8L).toInt()
            part1End -= misalignment.toLong()
            part2Start = part1End + 1L
        }
        val part2End = part2Start + VentoyLayout.EFI_PART_SECTORS - 1L
        val part1Count = part1End - part1Start + 1L
        require(part1Count >= 2048L) {
            "Ventoy data partition needs at least 2048 sectors after reserve/alignment; got $part1Count"
        }
        require(part2Start > part1End && part2End >= part2Start) {
            "VTOYEFI geometry invalid: start=$part2Start end=$part2End"
        }
        if (gpt) {
            require(part2End < diskSectors - 33L) {
                "VTOYEFI would overlap GPT backup area (part2End=$part2End disk=$diskSectors)"
            }
        } else {
            require(part2End < diskSectors) {
                "VTOYEFI exceeds disk (part2End=$part2End disk=$diskSectors)"
            }
        }
        return VentoyInstallGeometry(part1Start, part1End, part1Count, part2Start, part2End, diskSectors, gpt)
    }


    /** Payload size checks only; no disk I/O. */
    internal fun preflightInstallPayloads(
        gpt: Boolean,
        decompressedCore: ByteArray,
        decompressedEfi: ByteArray,
    ) {
        val efiSectors =
            (decompressedEfi.size.toLong() + VentoyLayout.SECTOR_SIZE - 1) / VentoyLayout.SECTOR_SIZE
        if (efiSectors > VentoyLayout.EFI_PART_SECTORS) {
            throw VentoyPayloadTooLargeException(
                "EFI payload needs $efiSectors sectors but VTOYEFI partition has only " +
                    "${VentoyLayout.EFI_PART_SECTORS}; install would truncate the boot image",
            )
        }
        val coreLimitSectors =
            if (gpt) VentoyLayout.GPT_CORE_MAX_SECTORS else VentoyLayout.MBR_CORE_MAX_SECTORS
        val coreLimit = coreLimitSectors * VentoyLayout.SECTOR_SIZE
        if (decompressedCore.size > coreLimit) {
            // Official core.img is 2047 sectors with a zero tail; GPT writes only 2014
            // sectors (LBA 34..2047). Accept the padding, refuse anything that would lose
            // real bytes instead of truncating silently.
            val losslessPadding = gpt &&
                decompressedCore.size <= VentoyLayout.MBR_CORE_MAX_SECTORS * VentoyLayout.SECTOR_SIZE &&
                isAllZero(decompressedCore, coreLimit.toInt(), decompressedCore.size)
            if (!losslessPadding) {
                throw VentoyPayloadTooLargeException(
                    "core.img is ${decompressedCore.size} bytes; ${if (gpt) "GPT" else "MBR"} boot gap " +
                        "holds only $coreLimitSectors sectors ($coreLimit bytes) and the tail is not zero padding",
                )
            }
        }
        if (decompressedCore.size % VentoyLayout.SECTOR_SIZE != 0 ||
            decompressedEfi.size % VentoyLayout.SECTOR_SIZE != 0
        ) {
            throw VentoyPayloadTooLargeException(
                "Ventoy payload sizes must be multiples of ${VentoyLayout.SECTOR_SIZE}",
            )
        }
    }

    private fun isAllZero(data: ByteArray, from: Int, until: Int): Boolean {
        for (i in from until until) if (data[i] != 0.toByte()) return false
        return true
    }

    private fun writeBufferToDisk(
        disk: BlockDevice,
        startLba: Long,
        data: ByteArray,
        totalSectors: Long,
        onWritten: (Long) -> Unit = {},
    ) {
        val chunkSectors = 64
        val chunkBytes = chunkSectors * VentoyLayout.SECTOR_SIZE
        val buf = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = totalSectors
        var currentLba = startLba
        var offset = 0
        var written = 0L
        while (remaining > 0L) {
            val count = minOf(remaining, chunkSectors.toLong()).toInt()
            val bytes = count * VentoyLayout.SECTOR_SIZE
            buf.clear()
            val available = minOf(data.size - offset, bytes).coerceAtLeast(0)
            if (available > 0) {
                buf.put(data, offset, available)
                offset += available
            }
            repeat(bytes - available) { buf.put(0) }
            buf.flip()
            disk.write(currentLba, buf)
            currentLba += count
            remaining -= count
            written += bytes
            onWritten(written)
        }
    }

    private fun checkActive(isActive: () -> Boolean, action: String) {
        if (!isActive()) throw java.util.concurrent.CancellationException("$action cancelled")
    }

    private fun putGuid(dest: ByteArray, offset: Int, data1: Int, data2: Short, data3: Short, data4: ByteArray) {
        ByteBuffer.wrap(dest, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(data1)
        ByteBuffer.wrap(dest, offset + 4, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(data2)
        ByteBuffer.wrap(dest, offset + 6, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(data3)
        data4.copyInto(dest, offset + 8, 0, 8)
    }

    /**
     * Legacy CHS bytes at [offset] (head, sector|cylinder-high, cylinder-low).
     * Upstream fills them in `VentoyFillProtectMBR` (fixed 0/1/0 -> 254/63/1023) and in
     * `VentoyFillMBRLocation` for data partitions (see [fillMbrPartChs]); UEFI ignores
     * these bytes, old BIOS boot code does not.
     */
    private fun putChs(mbr: ByteArray, offset: Int, head: Int, sector: Int, cylinder: Int) {
        val cyl = cylinder and 0x3FF
        mbr[offset] = head.toByte()
        mbr[offset + 1] = ((sector and 0x3F) or ((cyl ushr 8) shl 6)).toByte()
        mbr[offset + 2] = (cyl and 0xFF).toByte()
    }

    /**
     * Start/end CHS for one MBR data-partition entry, replicating upstream
     * `VentoyFillMBRLocation` (`ventoy_util.c`) head probing. The Linux shell installer
     * delegates this to parted/fdisk and may pick different CHS values; layout is otherwise
     * identical. The values matter only to legacy BIOS boot code.
     */
    private fun fillMbrPartChs(
        mbr: ByteArray,
        entryOffset: Int,
        diskSectors: Long,
        startLba: Long,
        sectorCount: Long,
    ) {
        // Upstream probes up to 255 heads in powers of two; uint8 wrap selects 255 heads.
        var heads = 8
        while (heads != 0 && diskSectors / 63L / heads > 1024L) {
            heads = (heads * 2) and 0xFF
        }
        if (heads == 0) heads = 255

        fun chs(lba: Long): Triple<Int, Int, Int> = Triple(
            (lba / 63L % heads).toInt(),
            (lba % 63L + 1L).toInt(),
            (lba / 63L / heads).toInt() and 0x3FF,
        )

        val start = chs(startLba)
        putChs(mbr, entryOffset + 1, start.first, start.second, start.third)
        val end = chs(startLba + sectorCount - 1L)
        putChs(mbr, entryOffset + 5, end.first, end.second, end.third)
    }

    private fun putUtf16Name(dest: ByteArray, offset: Int, name: String) {
        val utf16 = name.toByteArray(Charsets.UTF_16LE)
        utf16.copyInto(dest, offset, 0, minOf(utf16.size, 72))
    }

    private fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    /** Upstream install writes boot.img count=446; update uses 440 to keep signature region. */
    private const val INSTALL_BOOT_BYTES = 446
    private const val UPDATE_BOOT_BYTES = 440

    /** 64 KiB read-back granularity for the 32 MiB Part2 verify. */
    private const val PART2_VERIFY_CHUNK_SECTORS = 128

    private fun copyBootCode(bootImg: ByteArray, mbr: ByteArray, count: Int) {
        require(count in 1..minOf(bootImg.size, 446)) {
            "boot.img copy count $count out of range (boot=${bootImg.size})"
        }
        bootImg.copyInto(mbr, 0, 0, count)
    }

    /** Random 16-byte GPT GUID in mixed-endian on-disk form (matches CoCreateGuid layout). */
    private fun randomGptGuidBytes(): ByteArray {
        // On-disk GPT GUID bytes (mixed-endian fields). Uniqueness only; no fixed IDs.
        return randomRawBytes(16)
    }

    private fun randomRawBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        java.security.SecureRandom().nextBytes(out)
        return out
    }

    /**
     * Fail closed: critical write must match read-back or Partial mutation.
     * Verify [expected]'s first [sectorLimit] sectors against disk at [lba].
     * [sectorLimit] lets callers exclude the RSV window that legitimately overlaps
     * the core region; a real mismatch still fails closed as Partial.
     */
    private fun requireReadBack(
        disk: BlockDevice,
        lba: Long,
        expected: ByteArray,
        sectorLimit: Long = (expected.size / VentoyLayout.SECTOR_SIZE).toLong(),
    ) {
        val available = (expected.size / VentoyLayout.SECTOR_SIZE).toLong()
        // Clamp: short core payloads legitimately cover fewer sectors than the RSV gap allows.
        val sectors = minOf(sectorLimit, available)
        require(sectors > 0L) { "read-back buffer $available sectors is empty" }
        val bytes = sectors * VentoyLayout.SECTOR_SIZE
        val buf = ByteBuffer.allocate(bytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(lba, buf)
        val actual = buf.array()
        for (i in actual.indices) {
            if (actual[i] != expected[i]) {
                throw VentoyMutationFailedException(
                    "Ventoy write verify failed at LBA $lba ($bytes bytes)",
                )
            }
        }
    }

    /**
     * Read back every Part2 sector that was written — the image plus the zero padding up to
     * [VentoyLayout.EFI_PART_SECTORS] — in 64 KiB chunks and compare it byte-for-byte.
     * A mismatch means the medium does not hold what was written, so the caller reports
     * Partial: the disk was already mutated.
     */
    private fun requirePart2Written(disk: BlockDevice, part2Start: Long, efi: ByteArray) {
        if (efi.isEmpty()) return
        val chunkSectors = PART2_VERIFY_CHUNK_SECTORS
        val chunkBytes = chunkSectors * VentoyLayout.SECTOR_SIZE
        val buf = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
        val expected = ByteArray(chunkBytes)
        val totalSectors = VentoyLayout.EFI_PART_SECTORS
        var sector = 0L
        while (sector < totalSectors) {
            val count = minOf(chunkSectors.toLong(), totalSectors - sector).toInt()
            val bytes = count * VentoyLayout.SECTOR_SIZE
            // Written range is the image followed by zeros (writeBufferToDisk pads).
            expected.fill(0, 0, bytes)
            val from = (sector * VentoyLayout.SECTOR_SIZE).toInt()
            if (from < efi.size) {
                val len = minOf(bytes, efi.size - from)
                efi.copyInto(expected, 0, from, from + len)
            }
            buf.clear()
            disk.read(part2Start + sector, buf)
            val actual = buf.array()
            for (i in 0 until bytes) {
                if (actual[i] != expected[i]) {
                    val badLba = part2Start + sector + i / VentoyLayout.SECTOR_SIZE
                    throw VentoyMutationFailedException(
                        "Ventoy Part2 read-back mismatch at LBA $badLba " +
                            "(byte ${sector * VentoyLayout.SECTOR_SIZE + i} of $totalSectors sectors)",
                    )
                }
            }
            sector += count
        }
    }
}

data class SetupOptions(
    /**
     * Official `VTOY_WIN11_BYPASS_CHECK`, all-or-nothing: RAM, TPM, Secure Boot and CPU
     * checks are bypassed together. null = leave the existing ventoy.json value untouched
     * (Ventoy's own default is "1"); true = "1"; false = explicit "0".
     */
    val bypassChecks: Boolean? = null,
    /**
     * Official `VTOY_WIN11_BYPASS_NRO`, applied during setup (before OOBE). A local account
     * is offered only while the network is unplugged. null = leave the existing value untouched.
     */
    val localAccount: Boolean? = null,
)

/** One raw `auto_install` entry read from the real ventoy.json. */
data class AutoInstallLink(val image: String, val templates: List<String>)

class AmbiguousAutoInstallEntryException(message: String) : IllegalStateException(message)

object WindowsSetup {
    /**
     * Normalize the image reference used by Ventoy's auto_install entry.
     *
     * An absolute reference keeps its real directory (`/Win/install.iso`, `/ISO/a.img`).
     * A bare file name keeps the legacy `/ISO/<name>` target older builds wrote.
     * Backslashes are treated as separators and `.`/`..` segments are rejected so a
     * picked name can never escape the Ventoy data partition.
     */
    fun normalizeImagePath(reference: String): String {
        val trimmed = reference.trim()
        require(trimmed.isNotEmpty()) { "image path is empty" }
        val slashed = trimmed.replace('\\', '/')
        val bareName = !slashed.contains('/')
        val absolute = if (slashed.startsWith('/')) slashed else "/$slashed"
        val segments = absolute.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "image path is empty" }
        require(segments.none { it == "." || it == ".." }) {
            "image path must not contain . or .. segments: $reference"
        }
        require(segments.none { it.contains('\u0000') }) { "image path contains NUL: $reference" }
        val path = "/" + segments.joinToString("/")
        return if (bareName) "/ISO/${path.drop(1)}" else path
    }

    /** Auto_install entries currently present in [existingJson], in file order. */
    fun parseLinks(existingJson: String?): List<AutoInstallLink> {
        val root = parseRootOrNull(existingJson) ?: return emptyList()
        val entries = root["auto_install"] as? List<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val image = map["image"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val templates =
                when (val template = map["template"]) {
                    is String -> listOf(template)
                    is List<*> -> template.mapNotNull { it?.toString() }
                    else -> emptyList()
                }
            AutoInstallLink(image = image, templates = templates)
        }
    }

    /**
     * Remove bindings for [imagePaths] only. The image files and XML files are never
     * touched; every other auto_install entry and top-level key is preserved.
     * Returns null when nothing changed, so the host can avoid a needless write.
     */
    fun removeLinks(existingJson: String?, imagePaths: List<String>): String? {
        if (existingJson.isNullOrBlank()) return null
        val root = parseRootOrThrow(existingJson)
        val entries = (root["auto_install"] as? List<*> ?: return null).toMutableList()
        val normalized = imagePaths.map(::normalizeImagePath)
        for (target in normalized) {
            val matches = entries.count { entry ->
                matchesTargetImage((entry as? Map<*, *>)?.get("image")?.toString(), target)
            }
            if (matches > 1) {
                throw AmbiguousAutoInstallEntryException(
                    "$target has $matches auto_install entries; refusing to guess which one to remove — " +
                        "edit /ventoy/ventoy.json on a PC",
                )
            }
        }
        val kept = entries.filterNot { entry ->
            val image = (entry as? Map<*, *>)?.get("image")?.toString()
            normalized.any { matchesTargetImage(image, it) }
        }
        if (kept.size == entries.size) return null
        root["auto_install"] = kept
        return MiniJson.stringify(root)
    }

    /**
     * Update only the global `control` array. No auto_install entries, images, or XML
     * files are touched. These flags are global by Ventoy design; they are not per-image.
     */
    fun mergeControlOptions(existingJson: String?, options: SetupOptions): String {
        val root = parseRootOrThrow(existingJson)
        applyControlOptions(root, options)
        return MiniJson.stringify(root)
    }

    fun mergeAutoInstall(
        existingJson: String?,
        imagePaths: List<String>,
        templatePath: String,
        options: SetupOptions,
        overwrite: Boolean,
    ): String {
        val normalized = imagePaths.map(::normalizeImagePath).distinct()
        require(normalized.isNotEmpty()) { "no image selected" }
        val rootMap = parseRootOrThrow(existingJson)
        applyControlOptions(rootMap, options)

        val autoInstallList: MutableList<Any?> =
            ((rootMap["auto_install"] as? List<*>)?.toMutableList()) ?: mutableListOf()

        if (!overwrite) {
            val conflict = normalized.firstOrNull { path ->
                autoInstallList.any { entry ->
                    matchesTargetImage((entry as? Map<*, *>)?.get("image")?.toString(), path)
                }
            }
            if (conflict != null) {
                throw VentoyOverwriteRequiredException(
                    "Existing configuration for $conflict requires explicit overwrite permission",
                )
            }
        }

        for (targetImage in normalized) {
            val matchIndexes = autoInstallList.indices.filter { i ->
                val item = autoInstallList[i]
                item is Map<*, *> && matchesTargetImage(item["image"]?.toString(), targetImage)
            }
            if (matchIndexes.size > 1) {
                throw AmbiguousAutoInstallEntryException(
                    "$targetImage has ${matchIndexes.size} auto_install entries; " +
                        "refusing to overwrite them ambiguously — edit /ventoy/ventoy.json on a PC",
                )
            }
            val index = matchIndexes.firstOrNull()
            if (index != null) {
                val item = autoInstallList[index] as Map<*, *>
                val newEntry = linkedMapOf<String, Any?>()
                for ((k, v) in item) newEntry[k.toString()] = v
                newEntry["image"] = targetImage
                newEntry["template"] = templatePath
                autoInstallList[index] = newEntry
            } else {
                autoInstallList.add(linkedMapOf("image" to targetImage, "template" to templatePath))
            }
        }
        rootMap["auto_install"] = autoInstallList
        return MiniJson.stringify(rootMap)
    }

    /**
     * Set or remove one validated global control key; all other entries/keys are preserved.
     * Known keys are restricted to their catalog values; other keys are explicit key/value.
     */
    fun setControl(existingJson: String?, key: String, value: String?): String =
        applyControls(existingJson, listOf(VentoyControlSetting(key, value)))
            ?: existingJson
            ?: "{}"

    /**
     * Apply explicit control writes in one pass. A null value removes the key. Every other
     * `control` entry, `auto_install` entry, and top-level JSON key stays untouched.
     * Returns null when nothing would change, so the caller can skip the write entirely.
     */
    fun applyControls(
        existingJson: String?,
        settings: List<VentoyControlSetting>,
    ): String? {
        require(settings.isNotEmpty()) { "no Ventoy control setting to apply" }
        VentoyControlCatalog.problem(settings)?.let { throw IllegalArgumentException(it) }
        val root = parseRootOrThrow(existingJson)
        val rawControl = root["control"]
        if (rawControl != null && rawControl !is List<*>) {
            throw IllegalStateException(
                "\"control\" is not a JSON array; refusing to overwrite it",
            )
        }
        val hadControl = rawControl is List<*>
        val controlList: MutableList<Any?> =
            ((rawControl as? List<*>)?.toMutableList()) ?: mutableListOf()
        val byKey = settings.associateBy { it.key }
        var changed = false
        val kept = ArrayList<Any?>()
        for (item in controlList) {
            val map = item as? Map<*, *>
            if (map == null || map.keys.none { it.toString() in byKey }) {
                kept.add(item)
                continue
            }
            val updated = linkedMapOf<String, Any?>()
            for ((rawKey, rawValue) in map) {
                val entryKey = rawKey.toString()
                val setting = byKey[entryKey]
                when {
                    setting == null -> updated[entryKey] = rawValue
                    setting.value != null -> {
                        if (rawValue?.toString() != setting.value) changed = true
                        updated[entryKey] = setting.value
                    }
                    // null removes just this key; an empty object is dropped below.
                    else -> changed = true
                }
            }
            if (updated.isNotEmpty()) kept.add(updated)
        }
        for (setting in settings) {
            val value = setting.value ?: continue
            val present = controlList.any {
                (it as? Map<*, *>)?.containsKey(setting.key) == true
            }
            if (!present) {
                kept.add(linkedMapOf(setting.key to value))
                changed = true
            }
        }
        if (!changed) return null
        // A removal-only snapshot on a file that never had `control` must not invent an
        // empty array (or create a ventoy.json) just to report a no-op.
        if (hadControl || kept.isNotEmpty()) root["control"] = kept
        return MiniJson.stringify(root)
    }

    /** Remove only the entry linking [imagePath] to [templatePath]; other templates stay. */
    fun removeLinkForTemplate(
        existingJson: String?,
        imagePath: String,
        templatePath: String,
    ): String? {
        if (existingJson.isNullOrBlank()) return null
        val root = parseRootOrThrow(existingJson)
        val entries = (root["auto_install"] as? List<*> ?: return null).toMutableList()
        val targetImage = normalizeImagePath(imagePath)
        val targetTemplate = normalizeImagePath(templatePath)
        val matches =
            entries.count { entry ->
                val map = entry as? Map<*, *> ?: return@count false
                val templates =
                    when (val template = map["template"]) {
                        is String -> listOf(template)
                        is List<*> -> template.mapNotNull { it?.toString() }
                        else -> emptyList()
                    }
                matchesTargetImage(map["image"]?.toString(), targetImage) &&
                    templates.any { it.equals(targetTemplate, ignoreCase = true) }
            }
        if (matches == 0) return null
        if (matches > 1) {
            throw AmbiguousAutoInstallEntryException(
                "$targetImage has $matches entries for $targetTemplate; " +
                    "refusing to guess which one to remove — edit /ventoy/ventoy.json on a PC",
            )
        }
        var changed = false
        val updated = ArrayList<Any?>()
        for (entry in entries) {
            val map = entry as? Map<*, *>
            if (map == null || !matchesTargetImage(map["image"]?.toString(), targetImage)) {
                updated.add(entry)
                continue
            }
            val rawTemplate = map["template"]
            val templates =
                when (rawTemplate) {
                    is String -> listOf(rawTemplate)
                    is List<*> -> rawTemplate.mapNotNull { it?.toString() }
                    else -> emptyList()
                }
            if (templates.none { it.equals(targetTemplate, ignoreCase = true) }) {
                updated.add(entry)
                continue
            }
            changed = true
            val remaining = templates.filterNot { it.equals(targetTemplate, ignoreCase = true) }
            if (remaining.isEmpty()) continue // drop the whole entry only then
            val newMap = linkedMapOf<String, Any?>()
            for ((k, v) in map) newMap[k.toString()] = v
            newMap["template"] = if (rawTemplate is String) remaining.first() else remaining
            updated.add(newMap)
        }
        if (!changed) return null
        root["auto_install"] = updated
        return MiniJson.stringify(root)
    }

    private fun parseRootOrNull(json: String?): MutableMap<String, Any?>? {
        if (json.isNullOrBlank()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            MiniJson.parse(json) as? MutableMap<String, Any?>
                ?: throw IllegalStateException(
                    "ventoy.json parsed but root is not an object; refusing to overwrite",
                )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(
                "ventoy.json is not valid JSON; refusing to destroy existing config: ${e.message}",
                e,
            )
        }
    }

    private fun parseRootOrThrow(json: String?): MutableMap<String, Any?> =
        parseRootOrNull(json) ?: linkedMapOf()

    private fun applyControlOptions(rootMap: MutableMap<String, Any?>, options: SetupOptions) {
        val controlList: MutableList<Any?> =
            ((rootMap["control"] as? List<*>)?.toMutableList()) ?: mutableListOf()

        fun setControlFlag(key: String, value: String) {
            for (i in controlList.indices) {
                val item = controlList[i]
                if (item is MutableMap<*, *> && item.containsKey(key)) {
                    @Suppress("UNCHECKED_CAST")
                    (item as MutableMap<String, Any?>)[key] = value
                    return
                } else if (item is Map<*, *> && item.containsKey(key)) {
                    val newMap = linkedMapOf<String, Any?>()
                    for ((k, v) in item) newMap[k.toString()] = v
                    newMap[key] = value
                    controlList[i] = newMap
                    return
                }
            }
            controlList.add(linkedMapOf(key to value))
        }

        // Do not invent VTOY_DEFAULT_SEARCH_ROOT: adding /ISO would hide images bound
        // outside it. The UI warns when the existing real scope excludes a bound image.
        options.bypassChecks?.let { setControlFlag("VTOY_WIN11_BYPASS_CHECK", if (it) "1" else "0") }
        val obsoleteWin11Keys = setOf(
            "VTOY_WIN11_BYPASS_TPM",
            "VTOY_WIN11_BYPASS_RAM",
            "VTOY_WIN11_BYPASS_LOCAL_ACCOUNT",
        )
        val cleanedControl = controlList.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull item
            if (map.keys.none { it.toString() in obsoleteWin11Keys }) return@mapNotNull item
            val kept = linkedMapOf<String, Any?>()
            for ((k, v) in map) if (k.toString() !in obsoleteWin11Keys) kept[k.toString()] = v
            if (kept.isEmpty()) null else kept
        }
        controlList.clear()
        controlList.addAll(cleanedControl)

        options.bypassChecks?.let { setControlFlag("VTOY_WIN11_BYPASS_CHECK", if (it) "1" else "0") }
        options.localAccount?.let { setControlFlag("VTOY_WIN11_BYPASS_NRO", if (it) "1" else "0") }
        rootMap["control"] = controlList
    }

    /**
     * Exact path match plus the two bare-name forms older builds wrote for `/ISO`.
     * Basename matching is not used for arbitrary directories: two images may share a name.
     */
    private fun matchesTargetImage(stored: String?, target: String): Boolean {
        if (stored == null) return false
        val normalized = stored.replace('\\', '/')
        if (normalized.equals(target, ignoreCase = true)) return true
        val file = target.substringAfterLast('/')
        val legacyTarget = "/ISO/$file".equals(target, ignoreCase = true)
        return legacyTarget &&
            (normalized.equals("/$file", ignoreCase = true) || normalized.equals(file, ignoreCase = true))
    }
}

internal object MiniJson {
    fun parse(input: String): Any? {
        val parser = Parser(input.trim())
        val value = parser.parseValue()
        parser.skipWhitespacePublic()
        if (!parser.exhausted()) {
            throw IllegalArgumentException("Trailing junk after JSON value at ${parser.position()}")
        }
        return value
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun exhausted(): Boolean = pos >= s.length
        fun position(): Int = pos
        fun skipWhitespacePublic() = skipWhitespace()

        private fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseString(): String {
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\' && pos < s.length) {
                    when (val esc = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("Bad escape \\$esc at $pos")
                    }
                } else {
                    if (c.code < 0x20) {
                        throw IllegalArgumentException("Unescaped control char in string at ${pos - 1}")
                    }
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string at $pos")
        }

        fun parseObject(): MutableMap<String, Any?> {
            pos++
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') {
                    throw IllegalArgumentException("Expected object key string at $pos")
                }
                val key = parseString()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' after object key at $pos")
                }
                pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when {
                    pos < s.length && s[pos] == ',' -> { pos++; continue }
                    pos < s.length && s[pos] == '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}' in object at $pos")
                }
            }
        }

        fun parseArray(): MutableList<Any?> {
            pos++
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                val value = parseValue()
                list.add(value)
                skipWhitespace()
                when {
                    pos < s.length && s[pos] == ',' -> { pos++; continue }
                    pos < s.length && s[pos] == ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("Expected ',' or ']' in array at $pos")
                }
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) return null
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> {
                    if (s.startsWith("true", pos)) { pos += 4; true }
                    else if (s.startsWith("false", pos)) { pos += 5; false }
                    else throw IllegalArgumentException("Unexpected token at $pos")
                }
                'n' -> {
                    if (s.startsWith("null", pos)) { pos += 4; null }
                    else throw IllegalArgumentException("Unexpected token at $pos")
                }
                else -> {
                    if (c != '-' && !c.isDigit()) {
                        throw IllegalArgumentException("Unexpected token at $pos")
                    }
                    val start = pos
                    if (c == '-') pos++
                    val digitsStart = pos
                    if (pos < s.length && s[pos] == '0') {
                        pos++
                        if (pos < s.length && s[pos].isDigit()) {
                            throw IllegalArgumentException("Leading zeros in number at $start")
                        }
                    } else {
                        while (pos < s.length && s[pos].isDigit()) pos++
                        if (pos == digitsStart) {
                            throw IllegalArgumentException("Expected digit in number at $pos")
                        }
                    }
                    var isFloat = false
                    if (pos < s.length && s[pos] == '.') {
                        isFloat = true
                        pos++
                        val fracStart = pos
                        while (pos < s.length && s[pos].isDigit()) pos++
                        if (pos == fracStart) {
                            throw IllegalArgumentException("Expected digit after '.' at $pos")
                        }
                    }
                    if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                        isFloat = true
                        pos++
                        if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                        val expStart = pos
                        while (pos < s.length && s[pos].isDigit()) pos++
                        if (pos == expStart) {
                            throw IllegalArgumentException("Expected digit in exponent at $pos")
                        }
                    }
                    val token = s.substring(start, pos)
                    // Integers stay Long so stringify→parse keeps 42L as Long (not Int).
                    // Callers that need numeric equality must compare via Number.toLong().
                    if (isFloat) token.toDouble() else token.toLong()
                }
            }
        }
    }

    fun stringify(value: Any?, indent: Int = 0): String {
        val spaces = "  ".repeat(indent)
        val innerSpaces = "  ".repeat(indent + 1)
        return when (value) {
            null -> "null"
            is String -> "\"${escapeJsonString(value)}\""
            is Boolean -> value.toString()
            is Number -> value.toString()
            is Map<*, *> -> {
                if (value.isEmpty()) "{}"
                else {
                    val entries = value.entries.joinToString(",\n") { (k, v) ->
                        "$innerSpaces\"${escapeJsonString(k.toString())}\": ${stringify(v, indent + 1)}"
                    }
                    "{\n$entries\n$spaces}"
                }
            }
            is List<*> -> {
                if (value.isEmpty()) "[]"
                else {
                    val items = value.joinToString(",\n") { item ->
                        "$innerSpaces${stringify(item, indent + 1)}"
                    }
                    "[\n$items\n$spaces]"
                }
            }
            else -> "\"${escapeJsonString(value.toString())}\""
        }
    }

    /** RFC 8259 string escapes; control chars as \\uXXXX. */
    private fun escapeJsonString(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    val code = ch.code
                    if (code < 0x20) {
                        sb.append("\\u").append("%04x".format(code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }
}
