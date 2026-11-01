package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockDevice

class PartitionTableTest {
    @Test
    fun gptProtectiveMbrDoesNotHideEntries() {
        val disk = DiskFixture.load("partition/mbr-gpt.bin")
        val partitions = PartitionTable.read(disk).partitions
        assertEquals(listOf("EFI", "DATA"), partitions.map { it.name })
    }

    @Test
    fun emptyDiskReturnsNoPartitions() {
        val disk = DiskFixture.empty(sectorCount = 64UL)
        assertEquals(emptyList<Partition>(), PartitionTable.read(disk).partitions)
    }

    @Test
    fun overlappingGptEntriesRejected() {
        val disk = DiskFixture.load("partition/mbr-gpt-overlap.bin")
        assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(disk)
        }
    }

    @Test
    fun badGptCrcRejected() {
        val disk = DiskFixture.load("partition/mbr-gpt-badcrc.bin")
        assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(disk)
        }
    }

    @Test
    fun classicMbrFourEntries() {
        val disk = DiskFixture.load("partition/mbr-only.bin")
        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.MBR, table.scheme)
        assertEquals(listOf("P1", "P2"), table.partitions.map { it.name })
        assertEquals(listOf(2048UL, 4096UL), table.partitions.map { it.startLba })
    }

    @Test
    fun gptRecordsBackupLbas() {
        val disk = DiskFixture.load("partition/mbr-gpt.bin")
        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, table.scheme)
        assertTrue(table.gptBackupHeaderLba != null)
        assertEquals(disk.sectorCount - 1UL, table.gptBackupHeaderLba)
        // 32 entry sectors end at headerLba-1 — never overlap the header.
        assertEquals(disk.sectorCount - 1UL - 32UL, table.gptBackupEntriesLba)
    }

    @Test
    fun protectiveEePlusRealPartitionIsHybridAmbiguous() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // Keep protective 0xEE in slot 0; plant a real partition in slot 1.
        val slot1 = 446 + 16
        bytes[slot1 + 4] = 0x83.toByte()
        putLeInt(bytes, slot1 + 8, 100)
        putLeInt(bytes, slot1 + 12, 50)
        val hybrid = ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount)
        val table = PartitionTable.read(hybrid)
        assertTrue(table.hybridAmbiguous)
        assertEquals(TableScheme.HYBRID, table.scheme)
    }

    @Test
    fun blankDiskNeverOffersLbaZero() {
        val disk = DiskFixture.empty(sectorCount = 4096UL)
        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.MBR, table.scheme)
        assertEquals(2048UL, table.freeRegions.single().startLba)

        val created = PartitionEditor.create(table, size = 1024UL, type = PartitionType.BASIC)
        assertEquals(2048UL, created.partitions.single().startLba)
        val committed = PartitionWriter.commit(disk, created)

        assertEquals(2048UL, committed.partitions.single().startLba)
        val mbr = sector(disk, 0UL)
        assertEquals(0x55, mbr[510].toInt() and 0xFF)
        assertEquals(0xAA, mbr[511].toInt() and 0xFF)
        assertEquals(0x83, mbr[446 + 4].toInt() and 0xFF)
        assertEquals(2048L, ByteBuffer.wrap(mbr, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong())
    }

    @Test
    fun blankGptStartsWithPartitionAtOneMiB() {
        val disk = SyntheticGpt.build(sectorCount = 16384UL)
        var table = PartitionTable.read(disk)
        while (table.partitions.isNotEmpty()) table = PartitionEditor.delete(table, 0)
        val blank = PartitionWriter.commit(disk, table)
        assertEquals(emptyList<Partition>(), blank.partitions)

        val created = PartitionEditor.create(blank, size = 1024UL, type = PartitionType.BASIC)
        assertEquals(2048UL, created.partitions.single().startLba)
        val committed = PartitionWriter.commit(disk, created)

        assertEquals(2048UL, committed.partitions.single().startLba)
        val protectiveMbr = sector(disk, 0UL)
        assertEquals(0x55, protectiveMbr[510].toInt() and 0xFF)
        assertEquals(0xAA, protectiveMbr[511].toInt() and 0xFF)
        assertEquals(0xEE, protectiveMbr[446 + 4].toInt() and 0xFF)
        assertEquals(TableScheme.GPT, committed.scheme)
    }

    private fun sector(disk: BlockDevice, lba: ULong): ByteArray {
        val buffer = ByteBuffer.allocate(disk.sectorSize)
        disk.read(lba, buffer)
        return buffer.array()
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
