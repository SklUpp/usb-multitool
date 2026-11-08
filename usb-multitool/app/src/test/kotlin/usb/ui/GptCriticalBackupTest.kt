package usb.ui

import java.nio.ByteBuffer
import org.junit.Test
import usb.device.BlockDevice
import usb.multitool.GptCriticalBackup

/**
 * Covers the GPT critical-sector restore path: geometry derivation, CRC validation and the
 * read-back that decides whether a restore is reported as successful.
 */
class GptCriticalBackupTest {
    companion object {
        private const val SECTOR_SIZE = 512
        private const val PRIMARY_SECTORS = 34
        private const val BACKUP_SECTORS = 33
        private const val ENTRY_COUNT = 128
        private const val ENTRY_SIZE = 128
    }

    @Test
    fun restoreWritesAndVerifiesEveryCriticalRegion() {
        val disk = syntheticGpt(2048UL)
        val blob = capture(disk)

        // inspect() reports the captured geometry without writing.
        val inspected =
            GptCriticalBackup.inspect(blob, disk, PRIMARY_SECTORS, BACKUP_SECTORS)
        require(inspected.partitions == 2) { "expected 2 partitions, got ${inspected.partitions}" }
        require(inspected.primary.backupLba == 2047UL) {
            "primary backup LBA ${inspected.primary.backupLba}"
        }
        require(inspected.backup.currentLba == 2047UL) {
            "backup current LBA ${inspected.backup.currentLba}"
        }
        require(inspected.sectorsRestored == PRIMARY_SECTORS + BACKUP_SECTORS)

        // restore() rewrites both regions and verifies the read-back.
        val target = MemoryDisk(2048UL)
        val restored = GptCriticalBackup.restore(target, blob, PRIMARY_SECTORS, BACKUP_SECTORS)
        require(restored.partitions == 2) { "restore lost partitions" }
        require(target.syncs >= 1) { "restore must sync the disk" }
        require(
            target.range(0, PRIMARY_SECTORS * SECTOR_SIZE)
                .contentEquals(blob.copyOfRange(0, PRIMARY_SECTORS * SECTOR_SIZE)),
        ) { "head region not restored" }
        val tailStart = 2048 - BACKUP_SECTORS
        require(
            target.range(tailStart, BACKUP_SECTORS * SECTOR_SIZE)
                .contentEquals(
                    blob.copyOfRange(PRIMARY_SECTORS * SECTOR_SIZE, blob.size),
                ),
        ) { "tail region not restored" }
        require(target.range(PRIMARY_SECTORS, SECTOR_SIZE).all { it == 0.toByte() }) {
            "restore must not touch sectors between the critical regions"
        }

        // 4K-sector geometry: 6 primary sectors (MBR+hdr+4 entries) and 5 backup sectors (4 entries+hdr).
        val fourKDisk = syntheticGpt(2048UL, 4096)
        val fourKPSectors = usb.partition.GptBackup.primarySectors(4096)
        val fourKBSectors = usb.partition.GptBackup.backupSectors(4096)
        val fourKBlob = capture(fourKDisk, fourKPSectors, fourKBSectors)
        require(fourKBlob.size == (fourKPSectors + fourKBSectors) * 4096)
        val fourKTarget = MemoryDisk(2048UL, 4096)
        val fourKRestored =
            GptCriticalBackup.restore(fourKTarget, fourKBlob, fourKPSectors, fourKBSectors)
        require(fourKRestored.backup.entryLba == 2048UL - 5UL) {
            "4K backup entries at ${fourKRestored.backup.entryLba}"
        }
        require(
            fourKTarget.range(0, fourKPSectors * 4096)
                .contentEquals(fourKBlob.copyOfRange(0, fourKPSectors * 4096)),
        ) { "4K head region not restored" }

        // Aligned GPT disk: last usable LBA is strictly less than backup.entryLba - 1UL.
        val alignedDisk = syntheticGpt(4096UL, SECTOR_SIZE, lastUsableOffset = 2048UL)
        val alignedBlob = capture(alignedDisk)
        val alignedTarget = MemoryDisk(4096UL)
        val alignedRestored = GptCriticalBackup.restore(alignedTarget, alignedBlob, PRIMARY_SECTORS, BACKUP_SECTORS)
        require(alignedRestored.partitions == 2) { "aligned restore lost partitions" }
        // Wrong blob size (e.g. a rescue image from another capture).
        expectFailure(blob.copyOf(blob.size - 1), disk, "GPT critical backup is")

        // Corrupted primary header CRC.
        val badCrc = blob.copyOf()
        badCrc[SECTOR_SIZE + 16] = (badCrc[SECTOR_SIZE + 16].toInt() xor 0xFF).toByte()
        expectFailure(badCrc, disk, "CRC mismatch")

        // Blob captured on a smaller disk: stale backupLba must be refused, not written.
        val smaller = capture(syntheticGpt(1024UL))
        expectFailure(smaller, disk, "sectors — restore the matching backup")

        // Device too small for 34 + 33 critical sectors.
        expectFailure(blob, MemoryDisk(60UL), "too small")

        // Device that silently drops the tail write: verification must fail loudly.
        val liarsDisk = MemoryDisk(2048UL)
        liarsDisk.dropWritesAt = (2048 - BACKUP_SECTORS).toULong()
        val liarFailure =
            runCatching {
                GptCriticalBackup.restore(liarsDisk, blob, PRIMARY_SECTORS, BACKUP_SECTORS)
            }.exceptionOrNull()
        require(liarFailure is IllegalArgumentException) { "dropped write must fail: $liarFailure" }
        require(liarFailure.message.orEmpty().contains("verification failed")) {
            "unexpected dropped-write message: ${liarFailure.message}"
        }

        // Capture-side validation accepts a consistent GPT pair ...
        val captured =
            GptCriticalBackup.inspectCaptured(blob, disk, PRIMARY_SECTORS, BACKUP_SECTORS)
        require(captured.partitions == 2) { "capture lost partitions" }

        // ... and refuses an MBR-only disk instead of saving a bogus backup.
        val mbrOnly = MemoryDisk(2048UL)
        mbrOnly.write(0UL, ByteBuffer.wrap(ByteArray(SECTOR_SIZE).also { it[510] = 0x55 }))
        val noGpt =
            runCatching {
                GptCriticalBackup.inspectCaptured(
                    capture(mbrOnly),
                    mbrOnly,
                    PRIMARY_SECTORS,
                    BACKUP_SECTORS,
                )
            }.exceptionOrNull()
        require(noGpt is IllegalArgumentException && noGpt.message.orEmpty().contains("signature")) {
            "MBR-only capture must fail loudly: ${noGpt?.message}"
        }

        // ... and refuses to call a stale tail a backup (backup header claims another size).
        val staleDisk = MemoryDisk(2048UL)
        val smallBlob = smaller
        staleDisk.write(0UL, ByteBuffer.wrap(smallBlob, 0, PRIMARY_SECTORS * SECTOR_SIZE))
        staleDisk.write(
            2048UL - BACKUP_SECTORS.toULong(),
            ByteBuffer.wrap(smallBlob, PRIMARY_SECTORS * SECTOR_SIZE, BACKUP_SECTORS * SECTOR_SIZE),
        )
        val stale =
            runCatching {
                GptCriticalBackup.inspectCaptured(
                    capture(staleDisk),
                    staleDisk,
                    PRIMARY_SECTORS,
                    BACKUP_SECTORS,
                )
            }.exceptionOrNull()
        require(
            stale is IllegalArgumentException &&
                stale.message.orEmpty().contains("relocate the backup GPT"),
        ) { "stale backup header must fail loudly: ${stale?.message}" }

        // Entries outside usable range must fail before any write.
        val oobDisk = syntheticGpt(2048UL)
        val oobBlob = capture(oobDisk)
        val oobTarget = MemoryDisk(2048UL)
        expectFailure(
            mutatePrimaryEntry(oobBlob, index = 0) { entry ->
                // Push end past lastUsable while keeping type GUID occupied.
                putLeU64(entry, 32, 34UL)
                putLeU64(entry, 40, 2047UL)
            },
            oobTarget,
            "outside usable range",
        )
        require(oobTarget.syncs == 0) { "OOB restore must not write" }

        // Overlapping entries must fail before any write.
        val overlapDisk = syntheticGpt(2048UL)
        val overlapBlob = capture(overlapDisk)
        val overlapTarget = MemoryDisk(2048UL)
        expectFailure(
            mutatePrimaryEntry(overlapBlob, index = 1) { entry ->
                putLeU64(entry, 32, 34UL)
                putLeU64(entry, 40, 43UL)
            },
            overlapTarget,
            "overlapping partitions",
        )
        require(overlapTarget.syncs == 0) { "overlap restore must not write" }
    }


    private fun expectFailure(blob: ByteArray, disk: BlockDevice, needle: String) {
        val failure =
            runCatching {
                GptCriticalBackup.restore(disk, blob, PRIMARY_SECTORS, BACKUP_SECTORS)
            }.exceptionOrNull()
        require(failure is IllegalArgumentException) { "expected failure for: $needle" }
        require(failure.message.orEmpty().contains(needle)) {
            "message '${failure.message}' lacks '$needle'"
        }
    }

    private fun capture(
        disk: BlockDevice,
        primarySectors: Int = PRIMARY_SECTORS,
        backupSectors: Int = BACKUP_SECTORS,
    ): ByteArray {
        val sectorSize = disk.sectorSize
        val head = ByteArray(primarySectors * sectorSize)
        disk.read(0UL, ByteBuffer.wrap(head))
        val tail = ByteArray(backupSectors * sectorSize)
        disk.read(disk.sectorCount - backupSectors.toULong(), ByteBuffer.wrap(tail))
        return head + tail
    }

    private fun syntheticGpt(
        sectorCount: ULong,
        sectorSize: Int = SECTOR_SIZE,
        lastUsableOffset: ULong = 1UL,
    ): MemoryDisk {
        val disk = MemoryDisk(sectorCount, sectorSize)
        val entryBytes = ENTRY_COUNT * ENTRY_SIZE
        val entrySectors = ((entryBytes + sectorSize - 1) / sectorSize).toULong()
        val primaryEntriesLba = 2UL
        val firstUsable = primaryEntriesLba + entrySectors
        val backupHeaderLba = sectorCount - 1UL
        val backupEntriesLba = backupHeaderLba - entrySectors
        val lastUsable = backupEntriesLba - lastUsableOffset
        val entries = ByteArray(entryBytes)
        for (index in 0 until 2) {
            val offset = index * ENTRY_SIZE
            entries[offset] = 0x28
            entries[offset + 1] = 0x73
            val start = firstUsable + index.toULong() * 10UL
            val end = start + 4UL
            putLeU64(entries, offset + 32, start)
            putLeU64(entries, offset + 40, end)
        }
        val entriesCrc = crc32(entries, 0, entries.size)

        fun writeHeader(lba: ULong, current: ULong, backup: ULong, entryLba: ULong) {
            val sector = ByteArray(sectorSize)
            "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(sector)
            putLeInt(sector, 8, 0x00010000)
            putLeInt(sector, 12, 92)
            putLeU64(sector, 24, current)
            putLeU64(sector, 32, backup)
            putLeU64(sector, 40, firstUsable)
            putLeU64(sector, 48, lastUsable)
            putLeU64(sector, 72, entryLba)
            putLeInt(sector, 80, ENTRY_COUNT)
            putLeInt(sector, 84, ENTRY_SIZE)
            putLeInt(sector, 88, entriesCrc)
            putLeInt(sector, 16, crc32(sector, 0, 92))
            disk.write(lba, ByteBuffer.wrap(sector))
        }

        disk.write(0UL, ByteBuffer.wrap(ByteArray(sectorSize).also {
            it[510] = 0x55
            it[511] = 0xAA.toByte()
        }))
        writeHeader(1UL, 1UL, backupHeaderLba, primaryEntriesLba)
        writeHeader(backupHeaderLba, backupHeaderLba, 1UL, backupEntriesLba)
        disk.write(primaryEntriesLba, ByteBuffer.wrap(entries))
        disk.write(backupEntriesLba, ByteBuffer.wrap(entries))
        return disk
    }

    /** Mutate one GPT entry in both primary and backup arrays; refresh entry + header CRCs. */
    private fun mutatePrimaryEntry(
        blob: ByteArray,
        index: Int,
        mutate: (ByteArray) -> Unit,
    ): ByteArray {
        val out = blob.copyOf()
        val entryBytes = ENTRY_COUNT * ENTRY_SIZE
        val primaryEntriesOff = 2 * SECTOR_SIZE
        val backupEntriesOff = PRIMARY_SECTORS * SECTOR_SIZE
        val entry = out.copyOfRange(
            primaryEntriesOff + index * ENTRY_SIZE,
            primaryEntriesOff + (index + 1) * ENTRY_SIZE,
        )
        mutate(entry)
        entry.copyInto(out, primaryEntriesOff + index * ENTRY_SIZE)
        entry.copyInto(out, backupEntriesOff + index * ENTRY_SIZE)
        val entriesCrc = crc32(out, primaryEntriesOff, entryBytes)
        fun patchHeader(headerOff: Int) {
            putLeInt(out, headerOff + 88, entriesCrc)
            putLeInt(out, headerOff + 16, 0)
            putLeInt(out, headerOff + 16, crc32(out, headerOff, 92))
        }
        patchHeader(SECTOR_SIZE)
        patchHeader((PRIMARY_SECTORS + BACKUP_SECTORS - 1) * SECTOR_SIZE)
        // Keep backup entry array CRC in sync with primary.
        out.copyOfRange(primaryEntriesOff, primaryEntriesOff + entryBytes)
            .copyInto(out, backupEntriesOff)
        return out
    }

    private inner class MemoryDisk(
        override val sectorCount: ULong,
        override val sectorSize: Int = SECTOR_SIZE,
    ) : BlockDevice {
        private val image = ByteArray((sectorCount * sectorSize.toULong()).toInt())
        var syncs = 0
            private set
        var dropWritesAt: ULong? = null

        override fun read(lba: ULong, destination: ByteBuffer) {
            destination.put(image, (lba * sectorSize.toULong()).toInt(), destination.remaining())
        }

        override fun write(lba: ULong, source: ByteBuffer) {
            if (dropWritesAt == lba) return
            source.get(image, (lba * sectorSize.toULong()).toInt(), source.remaining())
        }

        override fun sync() {
            syncs++
        }

        fun range(lba: Int, bytes: Int): ByteArray =
            image.copyOfRange(lba * sectorSize, lba * sectorSize + bytes)
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun putLeU64(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
        }
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = java.util.zip.CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }
}
