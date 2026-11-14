package usb.partition

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import usb.device.DiskException
import usb.testutil.RecordingDisk

/** End-to-end table edits and partition I/O against deterministic in-memory media. */
class VirtualPartitionChainTest {
    @Test
    fun mbrCreateModifyAndVirtualPartitionRoundTripUsesAbsoluteBytes() {
        val disk = RecordingDisk(sectorCount = 8192UL, sectorSize = 512)
        val blank = PartitionTable.read(disk)
        val created = PartitionEditor.create(blank, size = 1024UL, type = PartitionType.BASIC)
        val edited = PartitionEditor.setFlags(created, index = 0, bootable = true)

        val committed = PartitionWriter.commit(disk, edited)
        val partition = committed.partitions.single()
        assertEquals(2048UL, partition.startLba)
        assertEquals(1024UL, partition.sectorCount)
        val view = VirtualPartition(partition.startLba, partition.sectorCount, disk)
        val payload = ByteArray(disk.sectorSize * 2) { index -> (index * 29 + 11).toByte() }

        view.write(1UL, ByteBuffer.wrap(payload))
        val readBack = ByteBuffer.allocate(payload.size)
        view.read(1UL, readBack)
        readBack.flip()
        val actual = ByteArray(payload.size)
        readBack.get(actual)

        assertArrayEquals(payload, actual)
        assertEquals(partition.startLba + 1UL, disk.writes.last())
        val raw = disk.bytes()
        val offset = ((partition.startLba + 1UL) * disk.sectorSize.toULong()).toInt()
        assertArrayEquals(payload, raw.copyOfRange(offset, offset + payload.size))
        assertTrue(PartitionTable.read(disk).partitions.single().bootable)

        view.sync()
        assertEquals("commit and virtual-partition sync must both reach disk", 2L, disk.syncCount)
    }

    @Test
    fun gptInitializeCreateModifyAndVirtualPartitionRoundTripKeepsMetadataReadable() {
        val disk = DiskFixture.empty(sectorCount = 16384UL) as ImageBlockDevice
        val blank = PartitionTable.read(disk)
        TableInitializer.initialize(disk, blank, TableScheme.GPT)

        val initialized = PartitionTable.read(disk)
        val created = PartitionEditor.create(
            initialized,
            size = 2048UL,
            type = PartitionType.BASIC,
            name = "PAYLOAD",
        )
        val edited = PartitionEditor.setFlags(created, index = 0, bootable = true)
        val committed = PartitionWriter.commit(disk, edited)
        val partition = committed.partitions.single()
        assertEquals(2048UL, partition.startLba)
        assertEquals(2048UL, partition.sectorCount)
        val view = VirtualPartition(partition.startLba, partition.sectorCount, disk)
        val payload = ByteArray(disk.sectorSize) { index -> (0xA5 xor index).toByte() }

        view.write(0UL, ByteBuffer.wrap(payload))
        val readBack = ByteBuffer.allocate(payload.size)
        view.read(0UL, readBack)
        readBack.flip()
        val actual = ByteArray(payload.size)
        readBack.get(actual)

        assertArrayEquals(payload, actual)
        val raw = disk.snapshot()
        val offset = (partition.startLba * disk.sectorSize.toULong()).toInt()
        assertArrayEquals(payload, raw.copyOfRange(offset, offset + payload.size))
        val reread = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, reread.scheme)
        assertEquals("PAYLOAD", reread.partitions.single().name)
        assertTrue(reread.partitions.single().bootable)
        assertEquals(0xEE, raw[446 + 4].toInt() and 0xFF)
    }

    @Test
    fun non512GeometryAndVirtualPartitionBoundariesUseSectorsNotBytes() {
        val disk = DiskFixture.empty(sectorCount = 1024UL, sectorSize = 4096) as ImageBlockDevice
        val blank = PartitionTable.read(disk)
        TableInitializer.initialize(disk, blank, TableScheme.GPT)
        val table = PartitionTable.read(disk)
        val committed = PartitionWriter.commit(
            disk,
            PartitionEditor.create(table, size = 4UL, type = PartitionType.BASIC),
        )
        val partition = committed.partitions.single()
        assertEquals(256UL, partition.startLba)
        assertEquals(4UL, partition.sectorCount)
        val view = VirtualPartition(partition.startLba, partition.sectorCount, disk)
        val payload = ByteArray(view.sectorSize) { index -> (index * 7 + 3).toByte() }

        assertEquals(4096, view.sectorSize)
        view.write(view.sectorCount - 1UL, ByteBuffer.wrap(payload))
        val raw = disk.snapshot()
        val offset = ((partition.startLba + partition.sectorCount - 1UL) * 4096UL).toInt()
        assertArrayEquals(payload, raw.copyOfRange(offset, offset + 4096))

        val afterValidWrite = disk.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            view.read(0UL, ByteBuffer.allocate(512))
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            view.read(view.sectorCount, ByteBuffer.allocate(4096))
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            view.write(view.sectorCount - 1UL, ByteBuffer.allocate(8192))
        }
        assertArrayEquals(afterValidWrite, disk.snapshot())
        assertThrows(IllegalArgumentException::class.java) {
            VirtualPartition(disk.sectorCount - 1UL, 2UL, disk)
        }
    }

    @Test
    fun virtualPartitionSyncPropagatesDiskFailure() {
        val image = DiskFixture.empty(sectorCount = 16UL) as ImageBlockDevice
        val failing = FaultyBlockDevice(image, failAtSync = 1)
        val view = VirtualPartition(2UL, 4UL, failing)

        assertThrows(DiskException.IoFailure::class.java) { view.sync() }
        assertEquals(listOf("sync"), failing.ops)
    }

    @Test
    fun mbrWriteAndSyncFaultsLeaveMutatedBytesReadableForPartialRecovery() {
        for (fault in listOf("write", "sync")) {
            val disk = DiskFixture.empty(sectorCount = 4096UL) as ImageBlockDevice
            val blank = PartitionTable.read(disk)
            val edited = PartitionEditor.create(blank, size = 1024UL, type = PartitionType.BASIC)
            val failing = when (fault) {
                "write" -> FaultyBlockDevice(disk, failAtWrite = 1, applyBeforeFailing = true)
                else -> FaultyBlockDevice(disk, failAtSync = 1, applyBeforeFailing = true)
            }

            assertThrows(DiskException.IoFailure::class.java) {
                PartitionWriter.commit(failing, edited)
            }

            val recovered = PartitionTable.read(disk)
            assertEquals(
                edited.partitions.map { it.startLba to it.sectorCount },
                recovered.partitions.map { it.startLba to it.sectorCount },
            )
            if (fault == "sync") assertTrue(failing.ops.contains("sync"))
        }
    }
}
