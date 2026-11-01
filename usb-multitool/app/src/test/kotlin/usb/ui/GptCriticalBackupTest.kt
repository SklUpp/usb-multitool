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

        // 4K-sector geometry: backup entries span 4 sectors, not 32, and are derived.
        val fourKDisk = syntheticGpt(2048UL, 4096)
        val fourKBlob = capture(fourKDisk)
        require(fourKBlob.size == (PRIMARY_SECTORS + BACKUP_SECTORS) * 4096)
        val fourKTarget = MemoryDisk(2048UL, 4096)
        val fourKRestored =
            GptCriticalBackup.restore(fourKTarget, fourKBlob, PRIMARY_SECTORS, BACKUP_SECTORS)
        require(fourKRestored.backup.entryLba == 2048UL - 5UL) {
            "4K backup entries at ${fourKRestored.backup.entryLba}"
        }
        require(
            fourKTarget.range(0, PRIMARY_SECTORS * 4096)
                .contentEquals(fourKBlob.copyOfRange(0, PRIMARY_SECTORS * 4096)),
        ) { "4K head region not restored" }

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

    private fun capture(disk: BlockDevice): ByteArray {
        val sectorSize = disk.sectorSize
        val head = ByteArray(PRIMARY_SECTORS * sectorSize)
        disk.read(0UL, ByteBuffer.wrap(head))
        val tail = ByteArray(BACKUP_SECTORS * sectorSize)
        disk.read(disk.sectorCount - BACKUP_SECTORS.toULong(), ByteBuffer.wrap(tail))
        return head + tail
    }

    private fun syntheticGpt(sectorCount: ULong, sectorSize: Int = SECTOR_SIZE): MemoryDisk {
        val disk = MemoryDisk(sectorCount, sectorSize)
        val entryBytes = ENTRY_COUNT * ENTRY_SIZE
        val entrySectors = ((entryBytes + sectorSize - 1) / sectorSize).toULong()
        val primaryEntriesLba = 2UL
        val firstUsable = primaryEntriesLba + entrySectors
        val backupHeaderLba = sectorCount - 1UL
        val backupEntriesLba = backupHeaderLba - entrySectors
        val lastUsable = backupEntriesLba - 1UL

        val entries = ByteArray(entryBytes)
        for (index in 0 until 2) {
            val offset = index * ENTRY_SIZE
            entries[offset] = 0x28
            entries[offset + 1] = 0x73
            entries[offset + 32] = (firstUsable + index.toULong() * 10UL).toByte()
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
