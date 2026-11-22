package usb.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PartitionEditorTest {
    @Test fun createUsesFreeSpaceWithoutMovingNeighbors() {
        val before = fixture("between-partitions")
        val after = PartitionEditor.create(before, size = 2048UL, type = PartitionType.BASIC)
        assertEquals(before.partitions, after.partitions.take(2))
        assertEquals(2048UL, after.partitions.last().sectorCount)
    }

    @Test fun createFailsWhenNoFreeSpace() {
        val before = fixture("full-disk")
        try {
            PartitionEditor.create(before, size = 2048UL, type = PartitionType.BASIC)
            fail("expected NoFreeSpaceException")
        } catch (_: NoFreeSpaceException) {
        }
    }

    @Test fun createStoresGptNameAndRenameRewritesIt() {
        val disk = SyntheticGpt.build(sectorCount = 16384UL)
        val before = PartitionTable.read(disk)
        val created = PartitionEditor.create(
            before,
            size = 2048UL,
            type = PartitionType.BASIC,
            name = "  DATA  ",
        )
        val committed = PartitionWriter.commit(disk, created)
        val entry = committed.partitions.last()
        assertEquals("DATA", entry.name)

        val index = committed.partitions.indexOf(entry)
        val renamed = PartitionWriter.commit(disk, PartitionEditor.rename(committed, index, "LINUX"))
        assertEquals("LINUX", renamed.partitions[index].name)
        // Other entries keep their name.
        assertEquals("EFI", renamed.partitions.first().name)
    }

    @Test fun mbrRefusesNameInsteadOfDroppingIt() {
        val mbr = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = listOf(Partition(2048UL, 2048UL, type = PartitionType.BASIC.name)),
            freeRegions = listOf(FreeRegion(4096UL, 4096UL)),
            diskSectorCount = 16384UL,
            sectorSize = 512,
        )
        try {
            PartitionEditor.create(mbr, size = 1024UL, type = PartitionType.BASIC, name = "DATA")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            PartitionEditor.rename(mbr, 0, "DATA")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun invalidGptNameIsRejectedBeforeAnyWrite() {
        val before = fixture("between-partitions")
        try {
            PartitionEditor.create(
                before,
                size = 1024UL,
                type = PartitionType.BASIC,
                name = "x".repeat(37),
            )
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun deleteRemovesEntryOnly() {
        val before = fixture("between-partitions")
        val after = PartitionEditor.delete(before, index = 1)
        assertEquals(1, after.partitions.size)
        assertEquals(before.partitions[0], after.partitions[0])
        assertTrue(after.freeRegions.any { it.sectorCount >= before.partitions[1].sectorCount })
    }

    @Test fun setFlagsChangesBootAndEspIndependently() {
        val before = fixture("between-partitions")
        val boot = PartitionEditor.setFlags(before, index = 0, bootable = true)
        assertTrue(boot.partitions[0].bootable)
        assertFalse(boot.partitions[0].esp)
        val esp = PartitionEditor.setFlags(boot, index = 0, esp = true)
        assertTrue(esp.partitions[0].bootable)
        assertTrue(esp.partitions[0].esp)
    }

    @Test fun setTypeWritesRealEfiType() {
        val before = fixture("between-partitions")
        val efi = PartitionEditor.setType(before, index = 0, type = PartitionType.EFI)
        assertEquals(PartitionType.EFI.name, efi.partitions[0].type)
        assertTrue(efi.partitions[0].esp)
        val data = PartitionEditor.setType(efi, index = 0, type = PartitionType.BASIC)
        assertEquals(PartitionType.BASIC.name, data.partitions[0].type)
        assertFalse(data.partitions[0].esp)
    }

    @Test
    fun createAfterDeletingLastGptPartitionLandsInsideUsableRange() {
        val disk = SyntheticGpt.build(sectorCount = 16384UL)
        val table = PartitionTable.read(disk)
        val header = table.gptHeader!!
        val afterDelete = PartitionEditor.delete(table, table.partitions.lastIndex)

        // Same derivation as the CREATE_PARTITION caller: fill the whole free region.
        val region = afterDelete.freeRegions.single()
        assertTrue(
            "free region ${region.startLba}+${region.sectorCount} must stop at usable end " +
                "(lastUsable ${header.lastUsable})",
            region.startLba + region.sectorCount <= header.lastUsable + 1UL,
        )

        val align = PartitionTable.alignmentSectors(afterDelete.sectorSize)
        val alignedStart = ((region.startLba + align - 1UL) / align) * align
        val size = region.sectorCount - (alignedStart - region.startLba)
        val created = PartitionEditor.create(afterDelete, size = size, type = PartitionType.BASIC)
        val committed = PartitionWriter.commit(disk, created)

        val newPartition = committed.partitions.last()
        assertEquals(size, newPartition.sectorCount)
        assertTrue(newPartition.startLba >= header.firstUsable)
        assertTrue(newPartition.startLba + newPartition.sectorCount - 1UL <= header.lastUsable)
    }

    private fun fixture(name: String): PartitionTable {
        val diskSectors = 16384UL // 8 MiB at 512-byte sectors
        val align = 2048UL // 1 MiB
        return when (name) {
            "between-partitions" -> {
                val p0 = Partition(align, 2048UL, PartitionType.EFI.name, name = "EFI", esp = false)
                val p1 = Partition(align + 4096UL, 2048UL, PartitionType.BASIC.name, name = "DATA")
                // Gap between p0 end (4096) and p1 start (6144) = 2048 sectors free mid-disk,
                // plus trailing free after p1.
                val partitions = listOf(p0, p1)
                PartitionTable(
                    scheme = TableScheme.GPT,
                    partitions = partitions,
                    freeRegions = listOf(
                        FreeRegion(p0.startLba + p0.sectorCount, 2048UL),
                        FreeRegion(p1.startLba + p1.sectorCount, diskSectors - (p1.startLba + p1.sectorCount)),
                    ),
                    diskSectorCount = diskSectors,
                    sectorSize = 512,
                )
            }
            "full-disk" -> {
                val p0 = Partition(align, diskSectors - align, PartitionType.BASIC.name, name = "ONLY")
                PartitionTable(
                    scheme = TableScheme.MBR,
                    partitions = listOf(p0),
                    freeRegions = emptyList(),
                    diskSectorCount = diskSectors,
                    sectorSize = 512,
                )
            }
            else -> error("unknown fixture $name")
        }
    }
}
