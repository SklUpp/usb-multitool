package usb.partition

import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockDevice
import usb.device.DiskException

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

    @Test
    fun destroyedProtectiveMbrAndPrimaryStillReachBackup() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = withDamagedPrimaryHeader(disk).snapshot()
        // Wipe sector 0: no 0xAA55 signature and no protective 0xEE entry remain.
        bytes.fill(0, 0, sectorSize)
        val damaged = ImageBlockDevice(bytes, sectorSize, 2048UL)

        val table = PartitionTable.read(damaged)

        assertEquals(TableScheme.GPT, table.scheme)
        assertTrue("one damaged copy must not turn a recoverable disk into an empty MBR", table.gptPrimaryDamaged)
        assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
        assertTrue("no free region may cover the metadata window", table.freeRegions.all { it.startLba >= 34UL })

        val before = damaged.snapshot()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionWriter.commit(damaged, PartitionEditor.delete(table, 0))
        }
        assertTrue(ex.message!!.contains("refusing an ambiguous primary rewrite"))
        assertTrue("no write may reach a backup-recovered table", damaged.writes.isEmpty())
        assertTrue("disk must stay byte-identical", damaged.snapshot().contentEquals(before))
    }

    @Test
    fun craftedEntryCountRejectedBeforeAllocation() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // 1e6 * 128 bytes is a 128 MB claim in Int math; the byte cap must reject it.
        patchU32(bytes, sectorSize, offset = 80, value = 1_000_000)
        rewriteHeaderCrc(bytes, 1UL)
        patchU32(bytes, sectorSize * 2047, offset = 80, value = 1_000_000)
        rewriteHeaderCrc(bytes, 2047UL)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))
        }
        assertTrue(ex.message!!.contains("exceeds the"))
    }

    @Test
    fun entryArrayOffDiskRejectedWithoutOverflow() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // 32 entry sectors cannot start at LBA 2040 on a 2048-sector disk.
        patchU64(bytes, sectorSize, offset = 72, value = 2040UL)
        rewriteHeaderCrc(bytes, 1UL)
        patchU64(bytes, sectorSize * 2047, offset = 72, value = 2040UL)
        rewriteHeaderCrc(bytes, 2047UL)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))
        }
        assertTrue(ex.message!!.contains("past disk bounds"))
    }

    @Test
    fun backupProbeTransportFailureSurfaces() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        bytes.fill(0, 0, sectorSize)
        bytes.fill(0, sectorSize, 2 * sectorSize)
        val probe = object : BlockDevice {
            override val sectorSize = 512
            override val sectorCount = 2048UL
            override fun read(lba: ULong, destination: ByteBuffer) {
                if (lba == 2047UL) throw DiskException.IoFailure("backup sector unreadable")
                destination.put(bytes, (lba * 512UL).toInt(), destination.remaining())
            }
            override fun write(lba: ULong, source: ByteBuffer) {
                throw UnsupportedOperationException()
            }
            override fun sync() = Unit
        }

        val failure = assertThrows(DiskException.IoFailure::class.java) {
            PartitionTable.read(probe)
        }
        assertTrue(failure.message!!.contains("backup sector unreadable"))
    }

    @Test
    fun shrunkCopyClampsFreeSpaceToMedium() {
        val disk = SyntheticGpt.build(sectorCount = 128UL)
        val bytes = disk.snapshot()
        // Header still describes the larger original disk: backup and usable end past the end.
        patchU64(bytes, sectorSize, offset = 32, value = 300UL)
        patchU64(bytes, sectorSize, offset = 48, value = 299UL)
        rewriteHeaderCrc(bytes, 1UL)

        val table = PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 128UL))

        assertEquals(TableScheme.GPT, table.scheme)
        // The declared backup header (LBA 300) is not on this medium, so no real backup
        // geometry is known: never publish a location that cannot be read back.
        assertNull("truncated copy has no on-medium backup", table.gptBackupHeaderLba)
        assertNull(table.gptBackupEntriesLba)
        assertTrue(
            "free space must stop at the medium: ${table.freeRegions}",
            table.freeRegions.all { it.startLba + it.sectorCount <= 128UL },
        )
    }

    @Test
    fun malformedMbrWithValidGptStaysAmbiguousAndUnwritable() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // One ordinary MBR record is valid and one runs past the disk end, so the MBR cannot
        // be trusted while the GPT copy stays perfectly valid.
        bytes.fill(0, 446, 446 + 64)
        bytes[446 + 4] = 0x83.toByte()
        patchU32(bytes, 446, offset = 8, value = 6)
        patchU32(bytes, 446, offset = 12, value = 4096)
        val validSlot = 446 + 16
        bytes[validSlot + 4] = 0x83.toByte()
        patchU32(bytes, validSlot, offset = 8, value = 100)
        patchU32(bytes, validSlot, offset = 12, value = 50)
        val hybrid = ImageBlockDevice(bytes, sectorSize, 2048UL)

        val table = PartitionTable.read(hybrid)

        assertEquals(TableScheme.HYBRID, table.scheme)
        assertTrue("MBR parse failure must stay ambiguous", table.hybridAmbiguous)
        assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
        assertEquals("no free region may be offered", emptyList<FreeRegion>(), table.freeRegions)

        val before = hybrid.snapshot()
        assertThrows(HybridAmbiguityException::class.java) { table.requireUnambiguousLayout() }
        assertThrows(HybridAmbiguityException::class.java) { PartitionWriter.commit(hybrid, table) }
        assertThrows(HybridAmbiguityException::class.java) {
            GptBackup.relocateBackup(hybrid, hybrid.sectorCount)
        }
        assertTrue("no write may reach an ambiguous layout", hybrid.writes.isEmpty())
        assertTrue("disk must stay byte-identical", hybrid.snapshot().contentEquals(before))
    }

    @Test
    fun malformedMbrWithBackupOnlyGptStaysAmbiguous() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        bytes.fill(0, 446, 446 + 64)
        bytes[446 + 4] = 0x83.toByte()
        patchU32(bytes, 446, offset = 8, value = 2000)
        patchU32(bytes, 446, offset = 12, value = 500)
        bytes.fill(0, sectorSize, 2 * sectorSize)

        val table = PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))

        assertEquals(TableScheme.HYBRID, table.scheme)
        assertTrue("a broken MBR entry must not publish a writable GPT", table.hybridAmbiguous)
        assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
        assertEquals(emptyList<FreeRegion>(), table.freeRegions)
        assertThrows(HybridAmbiguityException::class.java) { table.requireUnambiguousLayout() }
    }

    @Test
    fun primaryUsableOverRealBackupArrayFallsBackToBackup() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // Move the real backup entry array to LBA 1000 and make the backup header describe it,
        // so the array sits far below where primary geometry would predict it.
        val entryBytes = 128 * 128
        bytes.copyInto(bytes, 1000 * sectorSize, 2015 * sectorSize, 2015 * sectorSize + entryBytes)
        patchU64(bytes, sectorSize * 2047, offset = 72, value = 1000UL)
        patchU64(bytes, sectorSize * 2047, offset = 48, value = 999UL)
        rewriteHeaderCrc(bytes, 2047UL)
        // Crafted primary: valid CRC, but its usable end reaches into the real backup array.
        patchU64(bytes, sectorSize, offset = 48, value = 1030UL)
        rewriteHeaderCrc(bytes, 1UL)

        val table = PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))

        assertTrue("the unsafe primary candidate must be rejected", table.gptPrimaryDamaged)
        assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
        assertEquals(999UL, table.gptHeader!!.lastUsable)
    }

    @Test
    fun backupArrayPastItsHeaderIsRejected() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = withDamagedPrimaryHeader(disk).snapshot()
        // Array still fits on the disk but runs into its own header sector.
        patchU64(bytes, sectorSize * 2047, offset = 72, value = 2016UL)
        rewriteHeaderCrc(bytes, 2047UL)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))
        }
        assertTrue(ex.message!!.contains("past its header"))
    }

    @Test
    fun corruptedBackupArrayDoesNotDenyValidPrimary() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val bytes = disk.snapshot()
        // Break only the backup entry array: its header still carries trustworthy geometry.
        val backupEntries = 2015 * sectorSize
        bytes[backupEntries + 56] = (bytes[backupEntries + 56].toInt() xor 0xFF).toByte()

        val table = PartitionTable.read(ImageBlockDevice(bytes, sectorSize, 2048UL))

        assertEquals(TableScheme.GPT, table.scheme)
        assertEquals(false, table.gptPrimaryDamaged)
        assertEquals(listOf("EFI", "DATA"), table.partitions.map { it.name })
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
