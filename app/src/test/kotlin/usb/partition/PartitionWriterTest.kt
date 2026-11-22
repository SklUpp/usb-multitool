package usb.partition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun mbrKeepsExistingFilesystemTypeWhenAddingPartition() {
        val disk = RecordingBlockDevice(20000UL)
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(2048UL, 2048UL, type = "0x07"), Partition(8192UL, 2048UL, type = "BASIC")),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
        )
        PartitionWriter.commit(disk, table)
        assertEquals(0x07, disk.lastWrite!![450].toInt() and 0xFF)
        assertEquals(0x83, disk.lastWrite!![466].toInt() and 0xFF)
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

    @Test
    fun mbrStaleLiveGeometryWritesNothing() {
        val disk = RecordingBlockDevice(2048UL)
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(1024UL, 512UL, slot = 0)),
            freeRegions = emptyList(),
            // The table was read from a 4096-sector disk; the device now reports 2048.
            diskSectorCount = 4096UL,
        )
        assertThrows(IllegalArgumentException::class.java) { PartitionWriter.commit(disk, table) }
        assertEquals(emptyList<ULong>(), disk.writes)
        assertEquals(emptyList<ULong>(), disk.reads)
    }

    @Test
    fun mbrPartitionPastEndOfLiveGeometryWritesNothing() {
        val image = ByteArray(512 * 4096)
        image[510] = 0x55.toByte()
        image[511] = 0xAA.toByte()
        val disk = ImageBlockDevice(image, 512, 4096UL)
        val before = disk.snapshot()
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(4000UL, 200UL, slot = 0)),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
        )
        assertThrows(IllegalArgumentException::class.java) { PartitionWriter.commit(disk, table) }
        assertEquals(emptyList<ULong>(), disk.writes)
        assertArrayEquals(before, disk.snapshot())
    }

    @Test
    fun mbrBoundsCheckDoesNotWrapPastTheAddressSpace() {
        val disk = RecordingBlockDevice(0x1_0000_0001UL)
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(0xFFFF_FFFFUL, 0xFFFF_FFFFUL, slot = 0)),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
        )
        assertThrows(IllegalArgumentException::class.java) { PartitionWriter.commit(disk, table) }
        assertEquals(emptyList<ULong>(), disk.writes)
    }

    @Test
    fun mbrPartitionEndingAtLastSectorIsAccepted() {
        val image = ByteArray(512 * 4096)
        image[510] = 0x55.toByte()
        image[511] = 0xAA.toByte()
        val disk = ImageBlockDevice(image, 512, 4096UL)
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(2048UL, 2048UL, type = "0x83", slot = 0)),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
        )
        val committed = PartitionWriter.commit(disk, table)
        val written = committed.partitions.single()
        assertEquals(2048UL, written.startLba)
        assertEquals(2048UL, written.sectorCount)
    }

    @Test
    fun sparseGptSlotsAndMetadataSurviveRewrite() {
        val disk = sparseGptDisk()
        val table = PartitionTable.read(disk)
        assertEquals(listOf(0, 2), table.partitions.map { it.slot })
        val guids = uniqueGuidAt(table, 0) to uniqueGuidAt(table, 2)

        val committed = PartitionWriter.commit(disk, table)

        assertEquals(listOf(0, 2), committed.partitions.map { it.slot })
        assertEquals(guids.first, uniqueGuidAt(committed, 0))
        assertEquals(guids.second, uniqueGuidAt(committed, 2))
        assertEquals(0x10L, committed.partitions.single { it.slot == 2 }.flags)
        assertEquals(2048UL, committed.partitions.single { it.slot == 0 }.startLba)
        assertTrue(committed.partitions.single { it.slot == 0 }.esp)
    }

    @Test
    fun createAtAlignedStartBeforeExistingPartitionKeepsNeighborAndFreeSlot() {
        val disk = sparseGptDisk()
        val table = PartitionTable.read(disk)
        val neighbor = table.partitions.single { it.slot == 2 }
        val neighborGuid = uniqueGuidAt(table, 2)

        val edited = PartitionEditor.create(
            table,
            size = 2048UL,
            type = PartitionType.BASIC,
            startLba = 4096UL,
        )
        assertEquals(1, edited.partitions.last().slot)

        val committed = PartitionWriter.commit(disk, edited)

        val created = committed.partitions.single { it.slot == 1 }
        assertEquals(4096UL, created.startLba)
        assertEquals(2048UL, created.sectorCount)
        assertEquals(neighbor, committed.partitions.single { it.slot == 2 })
        assertEquals(neighborGuid, uniqueGuidAt(committed, 2))
    }

    @Test
    fun deleteClearsOnlyItsSlotAndKeepsTheRemainingOne() {
        val disk = sparseGptDisk()
        val table = PartitionTable.read(disk)
        val dataGuid = uniqueGuidAt(table, 2)

        val committed = PartitionWriter.commit(disk, PartitionEditor.delete(table, 0))

        assertEquals(listOf(2), committed.partitions.map { it.slot })
        assertEquals("DATA", committed.partitions.single().name)
        assertEquals(dataGuid, uniqueGuidAt(committed, 2))
        assertTrue(
            "freed GPT slot must be zeroed",
            committed.gptEntriesRaw!!.copyOfRange(0, 16).all { it == 0.toByte() },
        )
    }

    @Test
    fun createAfterDeleteReusesTheFreedSlot() {
        val disk = sparseGptDisk()
        val afterDelete = PartitionEditor.delete(PartitionTable.read(disk), 0)

        val recreated = PartitionEditor.create(
            afterDelete,
            size = 1024UL,
            type = PartitionType.BASIC,
            startLba = 2048UL,
        )
        assertEquals(0, recreated.partitions.last().slot)

        val committed = PartitionWriter.commit(disk, recreated)
        assertEquals(listOf(0, 2), committed.partitions.map { it.slot })
        assertEquals("DATA", committed.partitions.single { it.slot == 2 }.name)
    }

    @Test
    fun gptLegacyBootFlagMapsToAttributeAndRoundTrips() {
        val disk = sparseGptDisk()
        val table = PartitionTable.read(disk)
        assertFalse(table.partitions.single { it.slot == 2 }.bootable)

        val booted = PartitionEditor.setFlags(table, 1, bootable = true)
        assertEquals(0x14L, booted.partitions.single { it.slot == 2 }.flags)

        val committed = PartitionWriter.commit(disk, booted)
        assertTrue(committed.partitions.single { it.slot == 2 }.bootable)
        assertEquals(0x14L, committed.partitions.single { it.slot == 2 }.flags)

        val cleared = PartitionWriter.commit(
            disk,
            PartitionEditor.setFlags(committed, 1, bootable = false),
        )
        assertFalse(cleared.partitions.single { it.slot == 2 }.bootable)
        assertEquals(0x10L, cleared.partitions.single { it.slot == 2 }.flags)
    }

    @Test
    fun createBeforeFirstPartitionUsesAlignedStartWithoutMovingData() {
        val disk = SyntheticGpt.build(
            sectorCount = 16384UL,
            layout = listOf(SyntheticGpt.Layout(2, 8192UL, 10239UL, "DATA")),
        )
        val table = PartitionTable.read(disk)

        assertThrows(IllegalArgumentException::class.java) {
            PartitionEditor.create(table, size = 1024UL, type = PartitionType.BASIC, startLba = 2049UL)
        }

        val edited = PartitionEditor.create(
            table,
            size = 2048UL,
            type = PartitionType.BASIC,
            startLba = 2048UL,
        )
        assertEquals(0, edited.partitions.last().slot)

        val committed = PartitionWriter.commit(disk, edited)
        assertEquals(2048UL, committed.partitions.single { it.slot == 0 }.startLba)
        assertEquals("DATA", committed.partitions.single { it.slot == 2 }.name)
        assertEquals(8192UL, committed.partitions.single { it.slot == 2 }.startLba)
    }

    @Test
    fun sparseMbrSlotsAndBootFlagSurviveDeleteAndCreate() {
        val image = ByteArray(512 * 8192)
        image[510] = 0x55.toByte()
        image[511] = 0xAA.toByte()
        putMbrEntry(image, slot = 1, boot = false, type = 0x07, start = 2048, count = 1024)
        putMbrEntry(image, slot = 3, boot = true, type = 0x83, start = 6144, count = 1024)
        val disk = ImageBlockDevice(image, 512, 8192UL)

        val table = PartitionTable.read(disk)
        assertEquals(listOf(1, 3), table.partitions.map { it.slot })
        assertTrue(table.partitions.single { it.slot == 3 }.bootable)
        assertFalse(table.partitions.single { it.slot == 1 }.bootable)

        val edited = PartitionEditor.create(
            table,
            size = 2048UL,
            type = PartitionType.BASIC,
            startLba = 4096UL,
        )
        assertEquals(0, edited.partitions.last().slot)
        assertEquals(4096UL, edited.partitions.last().startLba)
        assertEquals(6144UL, edited.partitions.last().startLba + edited.partitions.last().sectorCount)
        val committed = PartitionWriter.commit(disk, edited)

        assertEquals(listOf(0, 1, 3), committed.partitions.map { it.slot })
        assertEquals("0x07", committed.partitions.single { it.slot == 1 }.type)
        assertEquals(6144UL, committed.partitions.single { it.slot == 3 }.startLba)
        val raw = disk.snapshot()
        assertEquals(0x80, raw[446 + 3 * 16].toInt() and 0xFF)
        assertEquals(0x00, raw[446 + 1 * 16].toInt() and 0xFF)

        val deleted = PartitionWriter.commit(disk, PartitionEditor.delete(committed, 1))
        assertEquals(listOf(0, 3), deleted.partitions.map { it.slot })
        val afterDelete = disk.snapshot()
        assertEquals(0x00, afterDelete[446 + 1 * 16 + 4].toInt() and 0xFF)
        assertEquals(0x83, afterDelete[446 + 3 * 16 + 4].toInt() and 0xFF)
        assertEquals(0x80, afterDelete[446 + 3 * 16].toInt() and 0xFF)
    }

    private fun sparseGptDisk(): ImageBlockDevice = SyntheticGpt.build(
        sectorCount = 16384UL,
        layout = listOf(
            SyntheticGpt.Layout(0, 2048UL, 4095UL, "EFI", efi = true),
            SyntheticGpt.Layout(2, 8192UL, 10239UL, "DATA", attributes = 0x10UL),
        ),
    )

    private fun uniqueGuidAt(table: PartitionTable, slot: Int): List<Byte> {
        val offset = slot * 128 + 16
        return table.gptEntriesRaw!!.copyOfRange(offset, offset + 16).toList()
    }

    private fun putMbrEntry(
        bytes: ByteArray,
        slot: Int,
        boot: Boolean,
        type: Int,
        start: Int,
        count: Int,
    ) {
        val offset = 446 + slot * 16
        if (boot) bytes[offset] = 0x80.toByte()
        bytes[offset + 4] = type.toByte()
        putLe32(bytes, offset + 8, start)
        putLe32(bytes, offset + 12, count)
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
