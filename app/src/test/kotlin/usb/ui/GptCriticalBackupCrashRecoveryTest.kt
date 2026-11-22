package usb.ui

import java.nio.ByteBuffer
import org.junit.Test
import usb.device.BlockDevice
import usb.multitool.GptCriticalBackup
import usb.operation.DiskOperation
import usb.operation.OperationState

/**
 * Exercises restore against a disk that separates volatile writes from synced durable state.
 * [VirtualDisk.simulatePowerLoss] models only deterministic loss of unsynced writes; it does not
 * claim a guarantee for arbitrary real-world power failures.
 */
class GptCriticalBackupCrashRecoveryTest {
    companion object {
        private const val SECTOR_SIZE = 512
        private const val SECTOR_COUNT = 2048
        private const val PRIMARY_SECTORS = 34
        private const val BACKUP_SECTORS = 33
        private const val ENTRY_COUNT = 128
        private const val ENTRY_SIZE = 128
        private const val PRIMARY_ENTRIES_LBA = 2
        private const val BACKUP_ENTRIES_LBA = SECTOR_COUNT - 1 - 32
        private const val FIRST_USABLE_LBA = PRIMARY_SECTORS
        private const val LAST_USABLE_LBA = BACKUP_ENTRIES_LBA - 1
        private const val BACKUP_HEADER_LBA = SECTOR_COUNT - 1
    }

    @Test
    fun tailWriteFailureAfterPrimaryIsPartialAndCrashKeepsPreviousReadableGpt() {
        val previousImage = validGptImage(partitionOffset = 0)
        val previousBlob = capture(previousImage)
        val incomingBlob = capture(validGptImage(partitionOffset = 100))
        require(!previousBlob.contentEquals(incomingBlob)) { "test GPT images must differ" }
        val disk = VirtualDisk(previousImage).also { it.failBeforeWriteCall = 2 }
        val operation = DiskOperation("gpt")

        val outcome = runCatching {
            GptCriticalBackup.restore(disk, incomingBlob, PRIMARY_SECTORS, BACKUP_SECTORS, operation)
        }

        require(outcome.isFailure) { "write interruption must not return success" }
        require(operation.state == OperationState.Partial) {
            "write interruption must be Partial, got ${operation.state}"
        }
        require(disk.writeLbas == listOf(0UL, (SECTOR_COUNT - BACKUP_SECTORS).toULong())) {
            "restore must attempt primary then tail: ${disk.writeLbas}"
        }
        require(disk.syncs == 0) { "failed tail write must not reach sync" }

        // Primary is new in volatile state while tail remains old. This mixed pair is not valid.
        val mixed = capture(disk)
        require(mixed.copyOfRange(0, PRIMARY_SECTORS * SECTOR_SIZE)
            .contentEquals(incomingBlob.copyOfRange(0, PRIMARY_SECTORS * SECTOR_SIZE))) {
            "primary write must be visible in volatile state"
        }
        require(mixed.copyOfRange(PRIMARY_SECTORS * SECTOR_SIZE, mixed.size)
            .contentEquals(previousBlob.copyOfRange(PRIMARY_SECTORS * SECTOR_SIZE, previousBlob.size))) {
            "failed tail write must leave old tail visible"
        }
        require(runCatching {
            GptCriticalBackup.inspect(mixed, disk, PRIMARY_SECTORS, BACKUP_SECTORS)
        }.isFailure) { "mixed primary/new + tail/old GPT must not validate" }

        disk.simulatePowerLoss()
        assertReadablePreviousGpt(disk, previousBlob)
    }

    @Test
    fun syncFailureIsPartialAndPowerLossDiscardsBothVolatileRegions() {
        val previousImage = validGptImage(partitionOffset = 0)
        val previousBlob = capture(previousImage)
        val incomingBlob = capture(validGptImage(partitionOffset = 100))
        val disk = VirtualDisk(previousImage).also { it.failSync = true }
        val operation = DiskOperation("gpt")

        val outcome = runCatching {
            GptCriticalBackup.restore(disk, incomingBlob, PRIMARY_SECTORS, BACKUP_SECTORS, operation)
        }

        require(outcome.isFailure) { "sync interruption must not return success" }
        require(operation.state == OperationState.Partial) {
            "sync interruption must be Partial, got ${operation.state}"
        }
        require(disk.writeLbas == listOf(0UL, (SECTOR_COUNT - BACKUP_SECTORS).toULong())) {
            "restore must write head then tail: ${disk.writeLbas}"
        }
        require(disk.syncs == 1) { "restore must attempt exactly one sync" }
        require(capture(disk).contentEquals(incomingBlob)) {
            "both writes must be visible in volatile state before simulated crash"
        }
        require(GptCriticalBackup.inspect(capture(disk), disk, PRIMARY_SECTORS, BACKUP_SECTORS)
            .partitions == 2) {
            "volatile post-write GPT should be readable before loss"
        }

        // sync failed, so simulated loss removes both writes and exposes prior durable GPT.
        disk.simulatePowerLoss()
        assertReadablePreviousGpt(disk, previousBlob)
    }

    private fun assertReadablePreviousGpt(disk: VirtualDisk, expected: ByteArray) {
        val afterCrash = capture(disk)
        require(afterCrash.contentEquals(expected)) { "crash must retain only synced GPT state" }
        require(GptCriticalBackup.inspect(afterCrash, disk, PRIMARY_SECTORS, BACKUP_SECTORS)
            .partitions == 2) {
            "previous GPT must remain readable after simulated crash"
        }
    }

    private fun capture(image: ByteArray): ByteArray {
        val headBytes = PRIMARY_SECTORS * SECTOR_SIZE
        return image.copyOfRange(0, headBytes) +
            image.copyOfRange(
                (SECTOR_COUNT - BACKUP_SECTORS) * SECTOR_SIZE,
                SECTOR_COUNT * SECTOR_SIZE,
            )
    }

    private fun capture(disk: BlockDevice): ByteArray {
        val head = ByteArray(PRIMARY_SECTORS * SECTOR_SIZE)
        disk.read(0UL, ByteBuffer.wrap(head))
        val tail = ByteArray(BACKUP_SECTORS * SECTOR_SIZE)
        disk.read(SECTOR_COUNT.toULong() - BACKUP_SECTORS.toULong(), ByteBuffer.wrap(tail))
        return head + tail
    }

    private fun validGptImage(partitionOffset: Int): ByteArray {
        val image = ByteArray(SECTOR_COUNT * SECTOR_SIZE)
        val entries = ByteArray(ENTRY_COUNT * ENTRY_SIZE)
        for (index in 0..1) {
            val offset = index * ENTRY_SIZE
            entries[offset] = 0x28
            entries[offset + 1] = (partitionOffset + index + 1).toByte()
            for (guidByte in 0 until 16) {
                entries[offset + 16 + guidByte] = (index * 16 + guidByte + 1).toByte()
            }
            val start = FIRST_USABLE_LBA + partitionOffset + index * 10
            putLeU64(entries, offset + 32, start.toULong())
            putLeU64(entries, offset + 40, (start + 4).toULong())
        }
        val entriesCrc = crc32(entries, 0, entries.size)
        entries.copyInto(image, PRIMARY_ENTRIES_LBA * SECTOR_SIZE)
        entries.copyInto(image, BACKUP_ENTRIES_LBA * SECTOR_SIZE)

        image[510] = 0x55
        image[511] = 0xAA.toByte()
        image[446 + 4] = 0xEE.toByte()
        putLeInt(image, 446 + 8, 1)
        putLeInt(image, 446 + 12, SECTOR_COUNT - 1)

        writeHeader(
            image = image,
            offset = SECTOR_SIZE,
            currentLba = 1UL,
            backupLba = BACKUP_HEADER_LBA.toULong(),
            entryLba = PRIMARY_ENTRIES_LBA.toULong(),
            entriesCrc = entriesCrc,
        )
        writeHeader(
            image = image,
            offset = BACKUP_HEADER_LBA * SECTOR_SIZE,
            currentLba = BACKUP_HEADER_LBA.toULong(),
            backupLba = 1UL,
            entryLba = BACKUP_ENTRIES_LBA.toULong(),
            entriesCrc = entriesCrc,
        )
        return image
    }

    private fun writeHeader(
        image: ByteArray,
        offset: Int,
        currentLba: ULong,
        backupLba: ULong,
        entryLba: ULong,
        entriesCrc: Int,
    ) {
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(image, offset)
        putLeInt(image, offset + 8, 0x00010000)
        putLeInt(image, offset + 12, 92)
        putLeU64(image, offset + 24, currentLba)
        putLeU64(image, offset + 32, backupLba)
        putLeU64(image, offset + 40, FIRST_USABLE_LBA.toULong())
        putLeU64(image, offset + 48, LAST_USABLE_LBA.toULong())
        for (guidByte in 0 until 16) image[offset + 56 + guidByte] = (guidByte + 1).toByte()
        putLeU64(image, offset + 72, entryLba)
        putLeInt(image, offset + 80, ENTRY_COUNT)
        putLeInt(image, offset + 84, ENTRY_SIZE)
        putLeInt(image, offset + 88, entriesCrc)
        putLeInt(image, offset + 16, crc32(image, offset, 92))
    }

    /** Volatile writes become durable only when sync succeeds. */
    private class VirtualDisk(initialDurable: ByteArray) : BlockDevice {
        override val sectorSize: Int = SECTOR_SIZE
        override val sectorCount: ULong = SECTOR_COUNT.toULong()
        private var durable = initialDurable.copyOf()
        private var volatile = durable.copyOf()
        var failBeforeWriteCall: Int? = null
        var failSync = false
        var writes = 0
            private set
        var syncs = 0
            private set
        val writeLbas = mutableListOf<ULong>()

        override fun read(lba: ULong, destination: ByteBuffer) {
            destination.put(volatile, (lba * sectorSize.toULong()).toInt(), destination.remaining())
        }

        override fun write(lba: ULong, source: ByteBuffer) {
            writes++
            writeLbas += lba
            if (failBeforeWriteCall == writes) error("injected write failure at call $writes")
            source.get(volatile, (lba * sectorSize.toULong()).toInt(), source.remaining())
        }

        override fun sync() {
            syncs++
            if (failSync) error("injected sync failure")
            durable = volatile.copyOf()
        }

        fun simulatePowerLoss() {
            volatile = durable.copyOf()
        }
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putLeU64(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 8) bytes[offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = java.util.zip.CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }
}
