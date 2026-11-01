package usb.ventoy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import usb.device.BlockDevice
import usb.partition.FreeRegion
import usb.partition.Partition
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.TableScheme

class IncompatibleVentoyException(message: String) : Exception(message)

class VentoyOverwriteRequiredException(message: String) : Exception(message)

class UnsupportedSectorSizeException(message: String) : Exception(message)

class EngineMissingException(message: String) : Exception(message)

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
    val version: String,
)

object VentoyLayout {
    const val SECTOR_SIZE = 512
    const val PART1_START_SECTOR = 2048L
    const val EFI_PART_SECTORS = 65536L

    fun isVentoyDisk(disk: BlockDevice): Boolean {
        if (disk.sectorSize == 4096) {
            throw UnsupportedSectorSizeException("Upstream Ventoy does not support 4K native devices")
        }
        if (disk.sectorSize != SECTOR_SIZE) {
            throw UnsupportedSectorSizeException("Unsupported sector size: ${disk.sectorSize}")
        }
        return probe(disk)?.isVentoy == true
    }

    fun probe(disk: BlockDevice): VentoyProbeResult? {
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

            // Verify partition 1 and 2
            val p1Start = ByteBuffer.wrap(tbl, 32, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            val p1Last = ByteBuffer.wrap(tbl, 40, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            val p2Start = ByteBuffer.wrap(tbl, 128 + 32, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            val p2Last = ByteBuffer.wrap(tbl, 128 + 40, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()

            val p2Count = p2Last - p2Start + 1UL
            if (p1Start != PART1_START_SECTOR.toULong() || p2Start != (p1Last + 1UL)) return null
            if (p2Count < 2048UL) return null
            // Check VTOYEFI name
            val nameBytes = tbl.copyOfRange(128 + 56, 128 + 56 + 14)
            val nameStr = String(nameBytes, Charsets.UTF_16LE)
            if (nameStr != "VTOYEFI") return null

            val rsv = ByteBuffer.allocate(8 * SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            disk.read(2040L, rsv)

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
                version = VentoyAssets.PINNED_RELEASE,
            )
        } else {
            val p0Start = (ByteBuffer.wrap(mbr, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toULong()
            val p0Count = (ByteBuffer.wrap(mbr, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toULong()
            val p1Flag = mbr[462 + 4].toInt() and 0xFF
            val p1Start = (ByteBuffer.wrap(mbr, 462 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toULong()
            val p1Count = (ByteBuffer.wrap(mbr, 462 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toULong()

            if (p0Start != PART1_START_SECTOR.toULong() || (p0Start + p0Count) != p1Start) return null
            if (p1Count < 2048UL || p1Flag != 0xEF) return null

            val rsv = ByteBuffer.allocate(8 * SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            disk.read(2040L, rsv)

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
                version = VentoyAssets.PINNED_RELEASE,
            )
        }
    }
}

object VentoyInstaller {

    fun install(
        disk: BlockDevice,
        assets: VentoyAssets,
        gpt: Boolean = true,
        reserveBytes: Long = 0L,
        align4k: Boolean = true,
    ): PartitionTable {
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

        val diskSectors = disk.sectorCount.toLong()
        val reserveSectors = reserveBytes / VentoyLayout.SECTOR_SIZE
        val minNeeded = VentoyLayout.PART1_START_SECTOR + VentoyLayout.EFI_PART_SECTORS + 2048L
        if (diskSectors < minNeeded) {
            throw IllegalArgumentException("Disk too small for Ventoy: $diskSectors sectors")
        }

        val part1Start = VentoyLayout.PART1_START_SECTOR
        var part1End = if (gpt) {
            diskSectors - reserveSectors - VentoyLayout.EFI_PART_SECTORS - 35L
        } else {
            diskSectors - reserveSectors - VentoyLayout.EFI_PART_SECTORS - 1L
        }
        var part2Start = part1End + 1L
        if (align4k) {
            val mod = (part2Start % 8L).toInt()
            if (mod > 0) {
                part1End -= mod.toLong()
                part2Start = part1End + 1L
            }
        }
        val part2End = part2Start + VentoyLayout.EFI_PART_SECTORS - 1L
        val part1Count = part1End - part1Start + 1L

        // Clean initial sectors
        val zeroSec = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE)
        for (i in 0L until 34L) {
            zeroSec.clear()
            disk.write(i, zeroSec)
        }
        if (gpt) {
            for (i in (diskSectors - 34L) until diskSectors) {
                zeroSec.clear()
                disk.write(i, zeroSec)
            }
        }

        val decompressedCore = assets.decompressCoreImg()
        val decompressedEfi = assets.decompressEfiPartImg()

        // Write VTOYEFI partition data
        writeBufferToDisk(disk, part2Start, decompressedEfi, VentoyLayout.EFI_PART_SECTORS)

        if (gpt) {
            // Write legacy GRUB stage1 at LBA 34
            val coreBuf = ByteBuffer.allocate(2014 * VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val copyLen = minOf(decompressedCore.size, 2014 * VentoyLayout.SECTOR_SIZE)
            coreBuf.put(decompressedCore, 0, copyLen)
            coreBuf.array()[500] = 35 // Blocklist marker
            coreBuf.flip()
            disk.write(34L, coreBuf)

            // Construct GPT tables
            val tblBuf = ByteBuffer.allocate(32 * VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val tbl = tblBuf.array()
            // Entry 0: Ventoy Data (Partition 1)
            // Windows basic data GUID: ebd0a0a2-b9e5-4433-87c0-68b6b72699c7
            putGuid(tbl, 0, 0xebd0a0a2.toInt(), 0xb9e5.toShort(), 0x4433.toShort(),
                byteArrayOf(0x87.toByte(), 0xc0.toByte(), 0x68.toByte(), 0xb6.toByte(), 0xb7.toByte(), 0x26.toByte(), 0x99.toByte(), 0xc7.toByte()))
            putGuid(tbl, 16, 0x11111111, 0x2222.toShort(), 0x3333.toShort(),
                byteArrayOf(0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte()))
            ByteBuffer.wrap(tbl, 32, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(part1Start)
            ByteBuffer.wrap(tbl, 40, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(part1End)
            ByteBuffer.wrap(tbl, 48, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L)
            putUtf16Name(tbl, 56, "Ventoy")

            // Entry 1: VTOYEFI (Partition 2)
            putGuid(tbl, 128, 0xebd0a0a2.toInt(), 0xb9e5.toShort(), 0x4433.toShort(),
                byteArrayOf(0x87.toByte(), 0xc0.toByte(), 0x68.toByte(), 0xb6.toByte(), 0xb7.toByte(), 0x26.toByte(), 0x99.toByte(), 0xc7.toByte()))
            putGuid(tbl, 128 + 16, 0x22222222, 0x3333.toShort(), 0x4444.toShort(),
                byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()))
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
            ByteBuffer.wrap(pHdrArr, 48, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(diskSectors - 35L)
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

            // Protective MBR
            val mbrBuf = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val mbr = mbrBuf.array()
            assets.bootImg.copyInto(mbr, 0, 0, minOf(assets.bootImg.size, 440))
            mbr[92] = 0x22
            mbr[446] = 0x00 // Not active
            mbr[450] = 0xEE.toByte() // Protective MBR
            val rng = java.util.Random()
            val randUuid = ByteArray(16).apply { rng.nextBytes(this) }
            randUuid.copyInto(mbr, 0x180)
            ByteBuffer.wrap(mbr, 0x1B8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(rng.nextInt())
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
        } else {
            // Write legacy GRUB stage1 at LBA 1
            val coreBuf = ByteBuffer.allocate(2047 * VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val copyLen = minOf(decompressedCore.size, 2047 * VentoyLayout.SECTOR_SIZE)
            coreBuf.put(decompressedCore, 0, copyLen)
            coreBuf.flip()
            disk.write(1L, coreBuf)

            // Construct MBR
            val mbrBuf = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val mbr = mbrBuf.array()
            assets.bootImg.copyInto(mbr, 0, 0, minOf(assets.bootImg.size, 440))
            // Partition 1
            mbr[446] = 0x80.toByte() // Active bootable
            mbr[450] = 0x07.toByte() // exFAT/NTFS
            val rng = java.util.Random()
            val randUuid = ByteArray(16).apply { rng.nextBytes(this) }
            randUuid.copyInto(mbr, 0x180)
            ByteBuffer.wrap(mbr, 0x1B8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(rng.nextInt())
            ByteBuffer.wrap(mbr, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(part1Start.toInt())
            ByteBuffer.wrap(mbr, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(part1Count.toInt())

            // Partition 2
            mbr[462] = 0x00.toByte()
            mbr[466] = 0xEF.toByte() // EFI System Partition
            ByteBuffer.wrap(mbr, 462 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(part2Start.toInt())
            ByteBuffer.wrap(mbr, 462 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(VentoyLayout.EFI_PART_SECTORS.toInt())

            mbr[510] = 0x55.toByte()
            mbr[511] = 0xAA.toByte()

            disk.write(0L, mbrBuf)
        }

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
    }

    fun update(
        disk: BlockDevice,
        assets: VentoyAssets,
    ): PartitionTable {
        if (disk.sectorSize == 4096) {
            throw UnsupportedSectorSizeException("Upstream Ventoy does not support 4K native devices")
        }
        if (disk.sectorSize != VentoyLayout.SECTOR_SIZE) {
            throw UnsupportedSectorSizeException("Unsupported sector size: ${disk.sectorSize}")
        }
        assets.validateChecksums(strict = true)

        val probe = VentoyLayout.probe(disk)
        if (probe == null || !probe.isVentoy) {
            throw IncompatibleVentoyException("Disk is not a recognized Ventoy layout; refusing update")
        }

        // CRITICAL CONTRACT: Partition 1 (from 2048 to part2StartLba - 1) is NEVER touched!
        val part2Start = probe.part2StartLba.toLong()
        val diskSectors = disk.sectorCount.toLong()

        val decompressedCore = assets.decompressCoreImg()
        val decompressedEfi = assets.decompressEfiPartImg()

        // 1. Write new VTOYEFI partition image at part2Start
        writeBufferToDisk(disk, part2Start, decompressedEfi, probe.part2SectorCount.toLong())

        // 2. Write new core.img in boot gap
        if (probe.scheme == TableScheme.GPT) {
            val coreBuf = ByteBuffer.allocate(2014 * VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val copyLen = minOf(decompressedCore.size, 2014 * VentoyLayout.SECTOR_SIZE)
            coreBuf.put(decompressedCore, 0, copyLen)
            coreBuf.array()[500] = 35
            coreBuf.flip()
            disk.write(34L, coreBuf)
        } else {
            val coreBuf = ByteBuffer.allocate(2047 * VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val copyLen = minOf(decompressedCore.size, 2047 * VentoyLayout.SECTOR_SIZE)
            coreBuf.put(decompressedCore, 0, copyLen)
            coreBuf.flip()
            disk.write(1L, coreBuf)
        }
        // 3. Restore reserved data at LBA 2040
        val rsvBuf = ByteBuffer.wrap(probe.rsvData)
        disk.write(2040L, rsvBuf)

        // 4. Update MBR boot code (preserving original UUID and signature)
        val mbrBuf = ByteBuffer.allocate(VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(0L, mbrBuf)
        val mbr = mbrBuf.array()
        assets.bootImg.copyInto(mbr, 0, 0, minOf(assets.bootImg.size, 440))
        probe.diskUuid.copyInto(mbr, 0x180, 0, 16)
        ByteBuffer.wrap(mbr, 0x1B8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(probe.diskSignature)
        if (probe.scheme == TableScheme.MBR) {
            mbr[446] = 0x80.toByte()
        } else {
            mbr[92] = 0x22
        }
        mbrBuf.clear()
        disk.write(0L, mbrBuf)

        // 5. If GPT, refresh partition table attributes and recompute CRC
        if (probe.scheme == TableScheme.GPT) {
            val tblBuf = ByteBuffer.allocate(32 * VentoyLayout.SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            disk.read(2L, tblBuf)
            val tbl = tblBuf.array()
            // Set Part 2 attribute 0x8000000000000000L
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
        }

        disk.sync()

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
    }

    private fun writeBufferToDisk(disk: BlockDevice, startLba: Long, data: ByteArray, totalSectors: Long) {
        val chunkSectors = 64
        val chunkBytes = chunkSectors * VentoyLayout.SECTOR_SIZE
        val buf = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
        var remSectors = totalSectors
        var curLba = startLba
        var offset = 0

        while (remSectors > 0L) {
            val count = minOf(remSectors, chunkSectors.toLong()).toInt()
            val bytesToWrite = count * VentoyLayout.SECTOR_SIZE
            buf.clear()
            if (offset < data.size) {
                val available = minOf(data.size - offset, bytesToWrite)
                buf.put(data, offset, available)
                if (available < bytesToWrite) {
                    buf.put(ByteArray(bytesToWrite - available))
                }
                offset += available
            } else {
                buf.put(ByteArray(bytesToWrite))
            }
            buf.flip()
            disk.write(curLba, buf)
            curLba += count.toLong()
            remSectors -= count.toLong()
        }
    }

    private fun putGuid(dest: ByteArray, offset: Int, data1: Int, data2: Short, data3: Short, data4: ByteArray) {
        ByteBuffer.wrap(dest, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(data1)
        ByteBuffer.wrap(dest, offset + 4, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(data2)
        ByteBuffer.wrap(dest, offset + 6, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(data3)
        data4.copyInto(dest, offset + 8, 0, 8)
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
}

data class SetupOptions(
    val tpmBypass: Boolean = false,
    val bypassRamCheck: Boolean = false,
    val localAccount: Boolean = false,
)

object WindowsSetup {
    fun write(
        volumeRoot: MutableMap<String, Any?>,
        isoName: String,
        options: SetupOptions,
        overwrite: Boolean,
    ): String {
        val baseName = isoName.substringBeforeLast(".")
        val xmlName = "$baseName.xml"
        val xmlPath = "ventoy/$xmlName"
        val jsonPath = "ventoy/ventoy.json"

        val hasExistingJson = volumeRoot.containsKey("ventoy.json") || volumeRoot.containsKey(jsonPath)
        val hasExistingXml = volumeRoot.containsKey(xmlName) || volumeRoot.containsKey(xmlPath)

        if (!overwrite && (hasExistingJson || hasExistingXml)) {
            throw VentoyOverwriteRequiredException(
                "Existing configuration for $isoName requires explicit overwrite permission"
            )
        }

        // Build unattended answer XML template
        val xmlContent = buildXmlTemplate(baseName, options)
        volumeRoot[xmlPath] = xmlContent
        volumeRoot[xmlName] = xmlContent

        // Build or merge ventoy.json linking ISO to XML
        val existingJson = (volumeRoot[jsonPath] ?: volumeRoot["ventoy.json"]) as? String
        val jsonContent = mergeVentoyJson(existingJson, isoName, xmlName, options)
        volumeRoot[jsonPath] = jsonContent
        volumeRoot["ventoy.json"] = jsonContent

        return jsonContent
    }

    fun mergeVentoyJson(
        existingJson: String?,
        isoName: String,
        xmlName: String,
        options: SetupOptions,
    ): String {
        val rootMap: MutableMap<String, Any?> = if (!existingJson.isNullOrBlank()) {
            try {
                @Suppress("UNCHECKED_CAST")
                (MiniJson.parse(existingJson) as? MutableMap<String, Any?>) ?: linkedMapOf()
            } catch (_: Throwable) {
                linkedMapOf()
            }
        } else {
            linkedMapOf()
        }

        val controlList: MutableList<Any?> = ((rootMap["control"] as? List<*>)?.toMutableList()) ?: mutableListOf()

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

        var hasSearchRoot = false
        for (item in controlList) {
            if (item is Map<*, *> && item.containsKey("VTOY_DEFAULT_SEARCH_ROOT")) {
                hasSearchRoot = true
                break
            }
        }
        if (!hasSearchRoot) {
            controlList.add(linkedMapOf("VTOY_DEFAULT_SEARCH_ROOT" to "/ISO"))
        }

        setControlFlag("VTOY_WIN11_BYPASS_TPM", if (options.tpmBypass) "1" else "0")
        setControlFlag("VTOY_WIN11_BYPASS_RAM", if (options.bypassRamCheck) "1" else "0")
        setControlFlag("VTOY_WIN11_BYPASS_LOCAL_ACCOUNT", if (options.localAccount) "1" else "0")

        rootMap["control"] = controlList

        val autoInstallList: MutableList<Any?> = ((rootMap["auto_install"] as? List<*>)?.toMutableList()) ?: mutableListOf()
        val targetImage = "/$isoName"
        val targetTemplate = "/ventoy/$xmlName"
        var matched = false

        for (i in autoInstallList.indices) {
            val item = autoInstallList[i]
            if (item is Map<*, *>) {
                val img = item["image"]?.toString()
                if (img == targetImage || img == isoName) {
                    val newEntry = linkedMapOf<String, Any?>()
                    for ((k, v) in item) newEntry[k.toString()] = v
                    newEntry["image"] = targetImage
                    newEntry["template"] = targetTemplate
                    autoInstallList[i] = newEntry
                    matched = true
                    break
                }
            }
        }
        if (!matched) {
            autoInstallList.add(linkedMapOf("image" to targetImage, "template" to targetTemplate))
        }

        rootMap["auto_install"] = autoInstallList

        return MiniJson.stringify(rootMap)
    }

    private fun buildXmlTemplate(name: String, options: SetupOptions): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        appendLine("<unattend xmlns=\"urn:schemas-microsoft-com:unattend\">")
        appendLine("  <settings pass=\"windowsPE\">")
        appendLine("    <component name=\"Microsoft-Windows-Setup\" processorArchitecture=\"amd64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\">")
        appendLine("      <UserData>")
        appendLine("        <AcceptEula>true</AcceptEula>")
        appendLine("        <FullName>$name</FullName>")
        appendLine("      </UserData>")
        if (options.tpmBypass || options.bypassRamCheck) {
            appendLine("      <RunSynchronous>")
            var order = 1
            if (options.tpmBypass) {
                appendLine("        <RunSynchronousCommand>")
                appendLine("          <Order>$order</Order>")
                appendLine("          <Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v BypassTPMCheck /t REG_DWORD /d 1 /f</Path>")
                appendLine("        </RunSynchronousCommand>")
                order++
                appendLine("        <RunSynchronousCommand>")
                appendLine("          <Order>$order</Order>")
                appendLine("          <Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v BypassSecureBootCheck /t REG_DWORD /d 1 /f</Path>")
                appendLine("        </RunSynchronousCommand>")
                order++
            }
            if (options.bypassRamCheck) {
                appendLine("        <RunSynchronousCommand>")
                appendLine("          <Order>$order</Order>")
                appendLine("          <Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v BypassRAMCheck /t REG_DWORD /d 1 /f</Path>")
                appendLine("        </RunSynchronousCommand>")
                order++
            }
            appendLine("      </RunSynchronous>")
        }
        appendLine("    </component>")
        appendLine("  </settings>")
        if (options.localAccount) {
            appendLine("  <settings pass=\"oobeSystem\">")
            appendLine("    <component name=\"Microsoft-Windows-Shell-Setup\" processorArchitecture=\"amd64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\">")
            appendLine("      <OOBE>")
            appendLine("        <ProtectYourPC>3</ProtectYourPC>")
            appendLine("      </OOBE>")
            appendLine("      <FirstLogonCommands>")
            appendLine("        <SynchronousCommand>")
            appendLine("          <Order>1</Order>")
            appendLine("          <CommandLine>reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE /v BypassNRO /t REG_DWORD /d 1 /f</CommandLine>")
            appendLine("          <Description>Bypass Network Requirement for Local Account</Description>")
            appendLine("        </SynchronousCommand>")
            appendLine("      </FirstLogonCommands>")
            appendLine("    </component>")
            appendLine("  </settings>")
        }
        appendLine("</unattend>")
    }
}

internal object MiniJson {
    fun parse(input: String): Any? = Parser(input.trim()).parseValue()

    private class Parser(private val s: String) {
        private var pos = 0

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
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        fun parseObject(): MutableMap<String, Any?> {
            pos++
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (pos < s.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (pos < s.length && s[pos] == ':') pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                if (pos < s.length && s[pos] == '}') { pos++; break }
            }
            return map
        }

        fun parseArray(): MutableList<Any?> {
            pos++
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (pos < s.length) {
                val value = parseValue()
                list.add(value)
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                if (pos < s.length && s[pos] == ']') { pos++; break }
            }
            return list
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
                    val start = pos
                    if (c == '-') pos++
                    while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
                    s.substring(start, pos)
                }
            }
        }
    }

    fun stringify(value: Any?, indent: Int = 0): String {
        val spaces = "  ".repeat(indent)
        val innerSpaces = "  ".repeat(indent + 1)
        return when (value) {
            null -> "null"
            is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
            is Boolean -> value.toString()
            is Number -> value.toString()
            is Map<*, *> -> {
                if (value.isEmpty()) "{}"
                else {
                    val entries = value.entries.joinToString(",\n") { (k, v) ->
                        "$innerSpaces\"$k\": ${stringify(v, indent + 1)}"
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
            else -> "\"$value\""
        }
    }
}
