package usb.partition

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class PartitionTableTest {
    @Test fun gptProtectiveMbrDoesNotHideEntries() {
        val disk = DiskFixture.load("partition/mbr-gpt.bin")
        val partitions = PartitionTable.read(disk)
        assertEquals(listOf("EFI", "DATA"), partitions.map { it.name })
    }

    @Test fun emptyDiskReturnsNoPartitions() {
        val disk = DiskFixture.empty(sectorCount = 64UL)
        assertEquals(emptyList<Partition>(), PartitionTable.read(disk))
    }

    @Test fun overlappingGptEntriesRejected() {
        val disk = DiskFixture.load("partition/mbr-gpt-overlap.bin")
        assertFailsWith<IllegalArgumentException> {
            PartitionTable.read(disk)
        }
    }

    @Test fun badGptCrcRejected() {
        val disk = DiskFixture.load("partition/mbr-gpt-badcrc.bin")
        assertFailsWith<IllegalArgumentException> {
            PartitionTable.read(disk)
        }
    }

    @Test fun classicMbrFourEntries() {
        val disk = DiskFixture.load("partition/mbr-only.bin")
        val partitions = PartitionTable.read(disk)
        assertEquals(listOf("P1", "P2"), partitions.map { it.name })
        assertEquals(listOf(2048UL, 4096UL), partitions.map { it.startLba })
    }
}
