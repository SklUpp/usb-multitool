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

    @Test
    fun backupDerivesGeometryFromHeaderEntryArray() {
        for ((entryCount, wantPrimary, wantBackup) in listOf(
            Triple(128, 34, 33),
            Triple(4, 3, 2),
            Triple(32, 10, 9),
        )) {
            val disk = SyntheticGpt.build(sectorCount = 2048UL, entryCount = entryCount)
            val geo = GptBackup.criticalGeometry(disk)
            assertEquals("entryCount=$entryCount primary", wantPrimary, geo.primarySectors)
            assertEquals("entryCount=$entryCount backup", wantBackup, geo.backupSectors)
            assertEquals(entryCount, geo.entryCount)
            val blob = GptBackup.backupCriticalSectors(disk)
            assertEquals(geo.blobBytes(disk.sectorSize), blob.size)
            val firstUsable = PartitionTable.read(disk).gptHeader!!.firstUsable
            assertTrue(
                "capture must not reach usable LBA $firstUsable",
                geo.primarySectors.toULong() <= firstUsable,
            )
        }
    }

    @Test
    fun backupRejectsNonRepresentableBackupEntryPlacement() {
        // Entries live far from the backup header: contiguous tail blob cannot cover them.
        val disk = SyntheticGpt.build(
            sectorCount = 2048UL,
            backupEntriesLba = 100UL,
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            GptBackup.backupCriticalSectors(disk)
        }
        assertTrue(
            error.message.orEmpty().contains("adjacent") ||
                error.message.orEmpty().contains("overlaps") ||
                error.message.orEmpty().contains("represent"),
        )
    }

    @Test
    fun criticalGeometryRejectsCallerDefaultsForSmallEntryArray() {
        val derived = GptBackup.criticalGeometry(sectorSize = 512, entryCount = 4, entrySize = 128)
        assertEquals(3, derived.primarySectors)
        assertEquals(2, derived.backupSectors)
        assertTrue(derived.primarySectors < GptBackup.PRIMARY_SECTORS)
        assertTrue(derived.backupSectors < GptBackup.BACKUP_SECTORS)
    }

    @Test
    fun criticalGeometryRejectsOversizedEntryArray() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GptBackup.criticalGeometry(
                sectorSize = 512,
                entryCount = (1 * 1024 * 1024 / 128) + 1,
                entrySize = 128,
            )
        }
        assertTrue(error.message.orEmpty().contains("too large"))
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
