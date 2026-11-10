package usb.partition

import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GptFallbackTest {
    private val sectorSize = 512

    @Test
    fun damagedPrimaryHeaderReadsBackupCopy() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val damaged = withDamagedPrimaryHeader(disk)

        val table = PartitionTable.read(damaged)

        assertTrue("table must be flagged as recovered from backup", table.gptPrimaryDamaged)
        assertEquals(TableScheme.GPT, table.scheme)
        assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
        assertEquals(2047UL, table.gptBackupHeaderLba)
        assertEquals(2047UL - 32UL, table.gptBackupEntriesLba)
        assertNull("unknown primary entry location must not be guessed", table.gptPrimaryEntryLba)
    }

    @Test
    fun damagedPrimaryEntriesReadBackupCopy() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // Primary entries start at LBA 2; flipping a name byte breaks their CRC only.
        bytes[sectorSize * 2 + 56] = (bytes[sectorSize * 2 + 56].toInt() xor 0xFF).toByte()
        val damaged = ImageBlockDevice(bytes, sectorSize, disk.sectorCount)

        val table = PartitionTable.read(damaged)

        assertTrue(table.gptPrimaryDamaged)
        assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
    }

    @Test
    fun bothCopiesDamagedRejected() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = withDamagedPrimaryHeader(disk).snapshot()
        bytes[(sectorSize * 2047) + 16] =
            (bytes[(sectorSize * 2047) + 16].toInt() xor 0xFF).toByte()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))
        }
        assertTrue(ex.message!!.contains("GPT header CRC mismatch"))
    }

    @Test
    fun backupPointingAtNonPrimaryAlternateLbaIsRejected() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = withDamagedPrimaryHeader(disk).snapshot()
        // A backup header that does not point back at LBA 1 mixes two layouts.
        patchU64(bytes, sectorSize * 2047, offset = 32, value = 2UL)
        rewriteHeaderCrc(bytes, 2047UL)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))
        }
        assertTrue(ex.message!!.contains("expected 1"))
    }

    @Test
    fun damagedPrimaryRefusesGptCommit() {
        val damaged = withDamagedPrimaryHeader(SyntheticGpt.build(sectorCount = 2048UL))
        val table = PartitionTable.read(damaged)
        val before = damaged.snapshot()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionWriter.commit(damaged, PartitionEditor.delete(table, 0))
        }

        assertTrue(ex.message!!.contains("refusing an ambiguous primary rewrite"))
        assertTrue("no write may reach a backup-recovered table", damaged.writes.isEmpty())
        assertTrue("disk must be byte-identical", damaged.snapshot().contentEquals(before))
    }

    @Test
    fun damagedPrimaryRefusesBackupRelocate() {
        val damaged = withDamagedPrimaryHeader(SyntheticGpt.build(sectorCount = 2048UL))
        val before = damaged.snapshot()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            GptBackup.relocateBackup(damaged, damaged.sectorCount)
        }

        assertTrue(ex.message!!.contains("refusing to relocate"))
        assertTrue(damaged.writes.isEmpty())
        assertTrue(damaged.snapshot().contentEquals(before))
    }

    @Test
    fun oversizedPrimaryHeaderSizeFallsBackToBackup() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // 1 MB claim: without a sector-size bound this underflows/allocates before fallback.
        patchU32(bytes, sectorSize, offset = 12, value = 1_000_000)

        val table = PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))

        assertTrue(table.gptPrimaryDamaged)
        assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
    }

    @Test
    fun oversizedHeaderSizeRejectedWithoutBufferUnderflow() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        patchU32(bytes, sectorSize, offset = 12, value = 1_000_000)
        patchU32(bytes, sectorSize * 2047, offset = 12, value = 1_000_000)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))
        }
        assertTrue(ex.message!!.contains("invalid GPT header size"))
    }

    private fun withDamagedPrimaryHeader(disk: ImageBlockDevice): ImageBlockDevice {
        val bytes = disk.snapshot()
        // Stored header CRC lives at offset 16 of the primary header at LBA 1.
        bytes[sectorSize + 16] = (bytes[sectorSize + 16].toInt() xor 0xFF).toByte()
        return ImageBlockDevice(bytes, sectorSize, disk.sectorCount)
    }

    private fun patchU64(bytes: ByteArray, base: Int, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            bytes[base + offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
        }
    }

    private fun patchU32(bytes: ByteArray, base: Int, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[base + offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun rewriteHeaderCrc(bytes: ByteArray, lba: ULong) {
        val base = (lba * sectorSize.toULong()).toInt()
        for (i in 16 until 20) bytes[base + i] = 0
        val crc = CRC32().apply { update(bytes, base, 92) }.value.toInt()
        for (i in 0 until 4) {
            bytes[base + 16 + i] = ((crc shr (8 * i)) and 0xFF).toByte()
        }
    }
}
