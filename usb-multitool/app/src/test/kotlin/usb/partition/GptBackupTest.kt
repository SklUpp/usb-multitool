package usb.partition

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GptBackupTest {
    @Test
    fun backupCoversFirst34AndLast33Sectors() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val backup = GptBackup.backupCriticalSectors(disk)
        assertEquals(
            (GptBackup.PRIMARY_SECTORS + GptBackup.BACKUP_SECTORS) * disk.sectorSize,
            backup.size,
        )
        val head = ByteBuffer.allocate(disk.sectorSize)
        disk.read(0UL, head)
        assertTrue(backup.copyOfRange(0, disk.sectorSize).contentEquals(head.array()))

        val tail = ByteBuffer.allocate(disk.sectorSize)
        disk.read(disk.sectorCount - 1UL, tail)
        val tailOffset = GptBackup.PRIMARY_SECTORS * disk.sectorSize +
            (GptBackup.BACKUP_SECTORS - 1) * disk.sectorSize
        assertTrue(
            backup.copyOfRange(tailOffset, tailOffset + disk.sectorSize)
                .contentEquals(tail.array()),
        )
    }

    @Test
    fun relocateRewritesBackupHeaderAtDiskEnd() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val before = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, before.scheme)
        assertEquals(listOf("EFI", "DATA"), before.partitions.map { it.name })

        val after = GptBackup.relocateBackup(disk, disk.sectorCount)
        assertEquals(disk.sectorCount - 1UL, after.gptBackupHeaderLba)
        assertEquals(before.partitions.map { it.name }, after.partitions.map { it.name })
        val reread = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, reread.scheme)
        assertEquals(disk.sectorCount - 1UL, reread.gptBackupHeaderLba)
    }

    @Test
    fun backupEntriesOccupySectorsImmediatelyBeforeHeader() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val table = PartitionTable.read(disk)
        val headerLba = requireNotNull(table.gptBackupHeaderLba)
        val entriesLba = requireNotNull(table.gptBackupEntriesLba)
        // 128 entries * 128 bytes / 512 = 32 sectors: headerLba-32 .. headerLba-1.
        assertEquals(headerLba - 32UL, entriesLba)
        assertTrue(
            "entries must not overlap backup header",
            entriesLba + 32UL == headerLba,
        )
    }

    @Test
    fun relocateWritesBackupEntryLbaWithoutHeaderOverlap() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val after = GptBackup.relocateBackup(disk, disk.sectorCount)
        val headerLba = requireNotNull(after.gptBackupHeaderLba)
        assertEquals(disk.sectorCount - 1UL, headerLba)
        assertEquals(headerLba - 32UL, after.gptBackupEntriesLba)

        // Backup header on disk must advertise the non-overlapping entry LBA.
        val buf = ByteBuffer.allocate(disk.sectorSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        disk.read(headerLba, buf)
        buf.flip()
        val entryLba = buf.getLong(72).toULong()
        assertEquals(headerLba - 32UL, entryLba)
        // CRC-valid reread after relocate.
        assertEquals(TableScheme.GPT, PartitionTable.read(disk).scheme)
    }


    @Test
    fun relocateShrinksBackupToSmallerDiskEnd() {
        val disk = SyntheticGpt.build(sectorCount = 4096UL)
        GptBackup.relocateBackup(disk, 4096UL)
        // Shrink to 2048: copy prefix and relocate backup.
        val shrunkBytes = disk.snapshot().copyOf(2048 * disk.sectorSize)
        val shrunk = ImageBlockDevice(shrunkBytes, disk.sectorSize, 2048UL)
        val after = GptBackup.relocateBackup(shrunk, 2048UL)
        assertEquals(2047UL, after.gptBackupHeaderLba)
        assertEquals(listOf("EFI", "DATA"), after.partitions.map { it.name })
        // Fresh parse must succeed with valid CRCs.
        assertEquals(TableScheme.GPT, PartitionTable.read(shrunk).scheme)
    }

    @Test
    fun relocateRejectsNonGpt() {
        assertThrows(IllegalArgumentException::class.java) {
            GptBackup.relocateBackup(DiskFixture.empty(64UL), 64UL)
        }
    }

    @Test
    fun relocateRejectsHybridAmbiguous() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // Clear protective EE; plant a classic MBR partition while GPT remains → hybrid.
        val ee = 446
        bytes[ee + 4] = 0x83.toByte()
        putLeInt(bytes, ee + 8, 100)
        putLeInt(bytes, ee + 12, 50)
        val hybrid = ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount)
        val table = PartitionTable.read(hybrid)
        assertTrue(
            "expected hybrid, got ${table.scheme} ambiguous=${table.hybridAmbiguous}",
            table.scheme == TableScheme.HYBRID || table.hybridAmbiguous,
        )
        assertThrows(HybridAmbiguityException::class.java) {
            GptBackup.relocateBackup(hybrid, hybrid.sectorCount)
        }
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
