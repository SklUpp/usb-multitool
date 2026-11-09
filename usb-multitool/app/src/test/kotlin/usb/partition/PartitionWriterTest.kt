package usb.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionWriterTest {
    private fun gptDisk(): ImageBlockDevice {
        // 8 MiB: leaves aligned free space past the 33-sector GPT prologue.
        return SyntheticGpt.build(sectorCount = 16384UL)
    }

    @Test
    fun deletePersistsAndKeepsBackupHeaderValid() {
        val disk = gptDisk()
        val table = PartitionTable.read(disk)
        assertEquals(2, table.partitions.size)

        val committed = PartitionWriter.commit(disk, PartitionEditor.delete(table, 1))

        assertEquals(1, committed.partitions.size)
        assertEquals("EFI", committed.partitions.single().name)
    }

    @Test
    fun createWritesAnEntryReadableAfterCommit() {
        val disk = gptDisk()
        val table = PartitionTable.read(disk)
        val edited = PartitionEditor.create(table, size = 8UL, type = PartitionType.BASIC)

        val committed = PartitionWriter.commit(disk, edited)

        assertEquals(table.partitions.size + 1, committed.partitions.size)
        val created = committed.partitions.single { it.type == "0FC63DAF-8483-4772-8E79-3D69D8477DE4" }
        assertEquals(8UL, created.sectorCount)
        assertTrue("created partition must stay 1 MiB aligned", created.startLba % 2048UL == 0UL)
    }

    @Test
    fun existingUniqueGuidsSurviveARewrite() {
        val disk = gptDisk()
        val before = PartitionTable.read(disk).partitionGuids()
        val after = PartitionWriter.commit(disk, PartitionTable.read(disk)).partitionGuids()

        assertEquals(before, after)
    }

    @Test
    fun mbrCommitRoundTrips() {
        val image = ByteArray(512 * 4096)
        image[446 + 4] = 0x83.toByte()
        putLe32(image, 446 + 8, 2048)
        putLe32(image, 446 + 12, 1024)
        image[510] = 0x55.toByte()
        image[511] = 0xAA.toByte()
        val disk = ImageBlockDevice(image, 512, 4096UL)

        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.MBR, table.scheme)
        assertEquals(1, table.partitions.size)

        val committed = PartitionWriter.commit(disk, PartitionEditor.delete(table, 0))

        assertEquals(TableScheme.MBR, committed.scheme)
        assertEquals(emptyList<Partition>(), committed.partitions)
        assertTrue(committed.freeRegions.isNotEmpty())
    }

    @Test
    fun hybridTablesAreRejectedInsteadOfGuessed() {
        val disk = gptDisk()
        val table = PartitionTable.read(disk)
        val hybrid = table.copy(scheme = TableScheme.HYBRID)

        assertThrows(HybridAmbiguityException::class.java) {
            PartitionWriter.commit(disk, hybrid)
        }
    }

    @Test
    fun unsupportedGptEntrySizeWritesNothing() {
        val disk = gptDisk()
        val before = disk.snapshot()
        val table = PartitionTable.read(disk)
        val header = table.gptHeader!!.copy(entrySize = 256)
        assertThrows(IllegalArgumentException::class.java) { PartitionWriter.commit(disk, table.copy(gptHeader = header)) }
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun invalidGptBackupGeometryWritesNothing() {
        val disk = gptDisk()
        val before = disk.snapshot()
        val table = PartitionTable.read(disk)
        val header = table.gptHeader!!.copy(backupLba = 2UL)
        assertThrows(IllegalArgumentException::class.java) { PartitionWriter.commit(disk, table.copy(gptHeader = header)) }
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun overlappingGptEntryArraysWriteNothing() {
        val disk = gptDisk()
        val before = disk.snapshot()
        val table = PartitionTable.read(disk)
        val header = table.gptHeader!!.copy(entryLba = 16350UL, firstUsable = 100UL, lastUsable = 16000UL)
        assertThrows(IllegalArgumentException::class.java) { PartitionWriter.commit(disk, table.copy(gptHeader = header)) }
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun backupHeaderAtZeroWritesNothing() {
        val disk = gptDisk()
        val before = disk.snapshot()
        val table = PartitionTable.read(disk)
        assertThrows(IllegalArgumentException::class.java) {
            PartitionWriter.commit(disk, table.copy(gptHeader = table.gptHeader!!.copy(backupLba = 0UL)))
        }
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun headerInsideUsableRangeWritesNothing() {
        val disk = gptDisk()
        val before = disk.snapshot()
        val table = PartitionTable.read(disk)
        assertThrows(IllegalArgumentException::class.java) {
            PartitionWriter.commit(disk, table.copy(gptHeader = table.gptHeader!!.copy(currentLba = table.gptHeader!!.firstUsable)))
        }
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun mbr32BitOverflowWritesNothing() {
        val disk = RecordingBlockDevice(0x1_0000_0001UL)
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(0x1_0000_0000UL, 1UL)),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
        )
        assertThrows(IllegalArgumentException::class.java) { PartitionWriter.commit(disk, table) }
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun mbrCountBeyond32BitWritesNothing() {
        val disk = RecordingBlockDevice(0x1_0000_0001UL)
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(1UL, 0x1_0000_0000UL)),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
        )
        assertThrows(IllegalArgumentException::class.java) { PartitionWriter.commit(disk, table) }
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun mbr32BitBoundaryIsAccepted() {
        val disk = RecordingBlockDevice(0x1_0000_0001UL)
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(0UL, 0xFFFF_FFFFUL)),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
        )
        PartitionWriter.commit(disk, table)
        assertTrue(disk.writes.isNotEmpty())
        val encoded = disk.lastWrite!!
        assertEquals(0x00, encoded[446 + 8].toInt() and 0xFF)
        assertEquals(0x00, encoded[446 + 9].toInt() and 0xFF)
        assertEquals(0x00, encoded[446 + 10].toInt() and 0xFF)
        assertEquals(0x00, encoded[446 + 11].toInt() and 0xFF)
        assertEquals(0xFF, encoded[446 + 12].toInt() and 0xFF)
        assertEquals(0xFF, encoded[446 + 13].toInt() and 0xFF)
        assertEquals(0xFF, encoded[446 + 14].toInt() and 0xFF)
        assertEquals(0xFF, encoded[446 + 15].toInt() and 0xFF)
    }

    private fun PartitionTable.partitionGuids(): List<String> =
        gptEntriesRaw!!
            .toList()
            .chunked(16)
            .filter { it.any { b -> b.toInt() != 0 } }
            .map { it.joinToString("") { b -> "%02x".format(b) } }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }
}
