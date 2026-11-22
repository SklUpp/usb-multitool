package usb.image

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockDevice
import usb.device.TransferPlan
import usb.device.capacityBytes
import usb.diagnostics.DestructiveConfirmation
import usb.operation.DiskOperation
import usb.partition.DiskFixture
import usb.partition.ImageBlockDevice
import usb.partition.RawWriteRange
import usb.partition.VirtualPartition
import usb.testutil.RecordingDisk

/** RawWriter scenarios against real in-memory block bytes and a relative partition view. */
class VirtualRawWriterTest {
    private val identity = "VIRTUAL-RAW-TEST"
    private val sectorSize = 512

    private fun confirmation(device: BlockDevice) =
        DestructiveConfirmation(identity, device.capacityBytes)

    @Test
    fun imageWritesAndReadsBackAtPartitionOffsetThroughLastRelativeSector() {
        val disk = DiskFixture.empty(24UL, sectorSize) as ImageBlockDevice
        seed(disk, 0x31)
        val before = disk.snapshot()
        val partition = VirtualPartition(startLba = 4UL, sectorCount = 8UL, disk = disk)
        val payload = ByteArray(2 * sectorSize + 37) { index -> (index * 7 + 3).toByte() }
        val result =
            RawWriter.write(
                source = ImageSource.fromBytes(payload, ImageFormat.IMG, payload.size.toLong()),
                disk = partition,
                identity = identity,
                confirmation = confirmation(partition),
                overwrite = true,
                // Relative [5, 8) maps to absolute [9, 12), ending at partition boundary.
                range = RawWriteRange(startLba = 5UL, sectorCount = 3UL),
            )

        assertEquals(RawWriteStatus.Completed, result.state)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertEquals(payload.size.toLong(), result.writtenBytes)
        val after = disk.snapshot()
        val expected = ByteArray(3 * sectorSize)
        payload.copyInto(expected)
        assertArrayEquals(expected, after.copyOfRange(9 * sectorSize, 12 * sectorSize))
        for (lba in 0 until disk.sectorCount.toInt()) {
            if (lba !in 9 until 12) {
                assertArrayEquals(
                    before.copyOfRange(lba * sectorSize, (lba + 1) * sectorSize),
                    after.copyOfRange(lba * sectorSize, (lba + 1) * sectorSize),
                )
            }
        }
    }

    @Test
    fun rangeBeyondVirtualPartitionIsRejectedBeforeUnderlyingWrite() {
        val disk = DiskFixture.empty(24UL, sectorSize) as ImageBlockDevice
        seed(disk, 0x45)
        val before = disk.snapshot()
        val writesBefore = disk.writes.size
        val partition = VirtualPartition(startLba = 4UL, sectorCount = 8UL, disk = disk)

        assertThrows(IllegalArgumentException::class.java) {
            RawWriter.write(
                source = ImageSource.fromBytes(ByteArray(sectorSize) { 9 }, ImageFormat.IMG),
                disk = partition,
                identity = identity,
                confirmation = confirmation(partition),
                overwrite = true,
                range = RawWriteRange(startLba = 8UL, sectorCount = 1UL),
            )
        }

        assertEquals(writesBefore, disk.writes.size)
        assertArrayEquals(before, disk.snapshot())
    }

    @Test
    fun cancellationAfterCommittedVirtualPartitionChunkSyncsAndReadsBackPrefix() {
        val active = AtomicBoolean(true)
        val disk = RecordingDisk(sectorCount = 152UL, sectorSize = sectorSize)
        val before = disk.bytes()
        val partition = VirtualPartition(startLba = 7UL, sectorCount = 140UL, disk = disk)
        val operation = DiskOperation(name = "virtual-raw-cancel", active = { active.get() })
        val payload = ByteArray(TransferPlan.MAX_CHUNK + sectorSize) { index -> (0x60 + index).toByte() }
        val cancellingDisk =
            object : BlockDevice by partition {
                override fun write(lba: ULong, source: ByteBuffer) {
                    partition.write(lba, source)
                    active.set(false)
                }
            }

        val result =
            RawWriter.write(
                source = ImageSource.fromBytes(payload, ImageFormat.IMG, payload.size.toLong()),
                disk = cancellingDisk,
                identity = identity,
                confirmation = confirmation(cancellingDisk),
                overwrite = true,
                range = RawWriteRange(startLba = 1UL, sectorCount = 129UL),
                operation = operation,
            )

        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertTrue(result.failure is ImageCancelledException)
        assertEquals(1L, disk.syncCount)
        val after = disk.bytes()
        assertArrayEquals(payload.copyOfRange(0, TransferPlan.MAX_CHUNK), after.copyOfRange(8 * sectorSize, 136 * sectorSize))
        assertArrayEquals(before.copyOfRange(136 * sectorSize, 137 * sectorSize), after.copyOfRange(136 * sectorSize, 137 * sectorSize))
        assertArrayEquals(before.copyOfRange(137 * sectorSize, 138 * sectorSize), after.copyOfRange(137 * sectorSize, 138 * sectorSize))
    }

    @Test
    fun syncFailureReturnsPartialAfterBytesAreCommittedAndReadBack() {
        val disk = RecordingDisk(sectorCount = 24UL, sectorSize = sectorSize)
        val before = disk.bytes()
        disk.syncHook = { throw IOException("sync failed") }
        val partition = VirtualPartition(startLba = 6UL, sectorCount = 6UL, disk = disk)
        val payload = ByteArray(sectorSize) { 0x2A }

        val result =
            RawWriter.write(
                source = ImageSource.fromBytes(payload, ImageFormat.IMG, payload.size.toLong()),
                disk = partition,
                identity = identity,
                confirmation = confirmation(partition),
                overwrite = true,
                range = RawWriteRange(startLba = 2UL, sectorCount = 1UL),
            )

        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(ReadbackVerdict.Verified, result.readback)
        assertTrue(result.failure is IOException)
        assertEquals("sync failed", result.failure?.message)
        assertEquals(1L, disk.syncCount)
        val after = disk.bytes()
        assertArrayEquals(payload, after.copyOfRange(8 * sectorSize, 9 * sectorSize))
        assertArrayEquals(before.copyOfRange(7 * sectorSize, 8 * sectorSize), after.copyOfRange(7 * sectorSize, 8 * sectorSize))
        assertArrayEquals(before.copyOfRange(9 * sectorSize, 10 * sectorSize), after.copyOfRange(9 * sectorSize, 10 * sectorSize))
    }

    @Test
    fun writeFailureAfterMediumCommitIsUnverifiedAtRelativeRange() {
        val disk =
            object : RecordingDisk(sectorCount = 24UL, sectorSize = sectorSize) {
                override fun write(lba: ULong, source: ByteBuffer) {
                    super.write(lba, source)
                    throw IOException("write failed after transfer")
                }
            }
        val before = disk.bytes()
        val partition = VirtualPartition(startLba = 5UL, sectorCount = 7UL, disk = disk)
        val payload = ByteArray(sectorSize) { 0x53 }

        val result =
            RawWriter.write(
                source = ImageSource.fromBytes(payload, ImageFormat.IMG, payload.size.toLong()),
                disk = partition,
                identity = identity,
                confirmation = confirmation(partition),
                overwrite = true,
                range = RawWriteRange(startLba = 3UL, sectorCount = 1UL),
            )

        assertEquals(RawWriteStatus.Partial, result.state)
        assertEquals(0L, result.writtenBytes)
        val verdict = result.readback as ReadbackVerdict.Unverified
        assertEquals(RawWriteRange(startLba = 3UL, sectorCount = 1UL), verdict.range)
        assertTrue(result.failure is IOException)
        assertEquals(1L, disk.syncCount)
        val after = disk.bytes()
        assertArrayEquals(payload, after.copyOfRange(8 * sectorSize, 9 * sectorSize))
        assertArrayEquals(before.copyOfRange(7 * sectorSize, 8 * sectorSize), after.copyOfRange(7 * sectorSize, 8 * sectorSize))
        assertArrayEquals(before.copyOfRange(9 * sectorSize, 10 * sectorSize), after.copyOfRange(9 * sectorSize, 10 * sectorSize))
    }

    private fun seed(disk: ImageBlockDevice, base: Int) {
        repeat(disk.sectorCount.toInt()) { lba ->
            disk.write(
                lba.toULong(),
                ByteBuffer.wrap(ByteArray(sectorSize) { index -> (base + lba * 3 + index).toByte() }),
            )
        }
    }
}
