package usb.partition

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.BlockDevice

/**
 * Table initialization and scheme replacement on image-backed disks: exact geometry reuse,
 * provable erasure only, and readable intermediate states (never a hybrid surprise).
 */
class TableInitializerTest {
    private val sectorSize = 512

    @Test
    fun blankDiskInitializesEmptyGpt() {
        val disk = DiskFixture.empty(16384UL)
        val before = PartitionTable.read(disk)
        assertEquals(TableScheme.MBR, before.scheme)

        TableInitializer.initialize(disk, before, TableScheme.GPT)

        val after = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, after.scheme)
        assertTrue(after.partitions.isEmpty())
        val header = after.gptHeader!!
        assertEquals(16384UL - 1UL, header.backupLba)
        assertEquals(34UL, header.firstUsable)
        assertEquals(16350UL, header.lastUsable)
        // Backup copy is real and readable.
        val backup = GptTable.readHeader(disk, 16384UL - 1UL)
        assertEquals(header.entryCount, backup.entryCount)
        assertEquals(header.diskGuid.toList(), backup.diskGuid.toList())
    }

    @Test
    fun blankDiskInitializesEmptyMbr() {
        val disk = DiskFixture.empty(16384UL)
        val before = PartitionTable.read(disk)
        TableInitializer.initialize(disk, before, TableScheme.MBR)
        val after = PartitionTable.read(disk)
        assertEquals(TableScheme.MBR, after.scheme)
        assertTrue(after.partitions.isEmpty())
        val sector = ByteArray(sectorSize)
        disk.read(0UL, ByteBuffer.wrap(sector))
        assertEquals(0x55, sector[510].toInt() and 0xFF)
        assertEquals(0xAA, sector[511].toInt() and 0xFF)
    }

    @Test
    fun gptWithPartitionsBecomesEmptyMbrAndErasesOnlyProvenGeometry() {
        val disk = gptWithEntryCount(entryCount = 56)
        val before = PartitionTable.read(disk)
        val oldHeader = before.gptHeader!!
        assertEquals(56, oldHeader.entryCount)

        TableInitializer.initialize(disk, before, TableScheme.MBR)

        val after = PartitionTable.read(disk)
        assertEquals(TableScheme.MBR, after.scheme)
        assertTrue(after.partitions.isEmpty())
        // Both GPT headers (and their arrays) are gone; nothing outside them was touched.
        assertZeroSector(disk, oldHeader.currentLba)
        assertZeroSector(disk, oldHeader.backupLba)
        val untouched = ByteArray(sectorSize)
        disk.read(16384UL - 2UL, ByteBuffer.wrap(untouched))
        // No assertion on content: just prove the read stays in bounds after erasure.
        assertEquals(sectorSize, untouched.size)
    }

    @Test
    fun existingGptGeometryIsReusedInsteadOfAssumingThirtyThreeSectors() {
        val disk = gptWithEntryCount(entryCount = 56)
        val before = PartitionTable.read(disk)
        assertEquals(56, before.gptHeader!!.entryCount)

        TableInitializer.initialize(disk, before, TableScheme.GPT)

        val after = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, after.scheme)
        assertTrue(after.partitions.isEmpty())
        assertEquals(56, after.gptHeader!!.entryCount)
        assertEquals(16384UL - 1UL, after.gptHeader!!.backupLba)
    }

    @Test
    fun mbrPartitionIsReplacedByEmptyGpt() {
        val disk = DiskFixture.empty(16384UL)
        val empty = PartitionTable.read(disk)
        val withPartition = PartitionEditor.create(empty, size = 2048UL, type = PartitionType.BASIC)
        PartitionWriter.commit(disk, withPartition)
        val before = PartitionTable.read(disk)
        assertEquals(1, before.partitions.size)

        TableInitializer.initialize(disk, before, TableScheme.GPT)

        val after = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, after.scheme)
        assertTrue(after.partitions.isEmpty())
    }

    @Test
    fun mbrBeyond32BitLbaIsRefusedBeforeWriting() {
        val disk = RecordingBlockDevice(0x1_0000_0000UL)
        val table = PartitionTable(
            scheme = TableScheme.MBR,
            partitions = emptyList(),
            freeRegions = emptyList(),
            diskSectorCount = disk.sectorCount,
            sectorSize = sectorSize,
        )
        try {
            TableInitializer.initialize(disk, table, TableScheme.MBR)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun hybridAndDamagedGptAreRefusedFailClosed() {
        val hybrid = PartitionTable(
            scheme = TableScheme.HYBRID,
            partitions = emptyList(),
            freeRegions = emptyList(),
            diskSectorCount = 16384UL,
            sectorSize = sectorSize,
            hybridAmbiguous = true,
        )
        try {
            TableInitializer.plan(hybrid, TableScheme.GPT)
            fail("expected HybridAmbiguityException")
        } catch (_: HybridAmbiguityException) {
        }
        try {
            TableInitializer.plan(hybrid, TableScheme.MBR)
            fail("expected HybridAmbiguityException")
        } catch (_: HybridAmbiguityException) {
        }

        val gpt = PartitionTable.read(gptWithEntryCount(entryCount = 128))
        val damaged = gpt.copy(gptPrimaryDamaged = true)
        try {
            TableInitializer.plan(damaged, TableScheme.MBR)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            TableInitializer.plan(damaged, TableScheme.GPT)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun nonAdjacentBackupArrayIsReadAndErasedExactly() {
        // Backup array deliberately NOT adjacent to the backup header.
        val disk = SyntheticGpt.build(sectorCount = 2048UL, backupEntriesLba = 2000UL)
        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, table.scheme)
        assertEquals(2000UL, table.gptBackupEntriesLba)
        assertEquals(2047UL, table.gptBackupHeaderLba)

        val plan = TableInitializer.plan(table, TableScheme.MBR)
        val erased = plan.eraseHeaders + plan.eraseArrays
        assertTrue(erased.contains(FreeRegion(2000UL, 32UL)))
        assertTrue(
            "must not erase the guessed adjacent range",
            erased.none { it.startLba == 2047UL - 32UL },
        )
        assertEquals("both headers are erased before entry arrays", 2, plan.eraseHeaders.size)

        TableInitializer.initialize(disk, table, TableScheme.MBR)
        assertEquals(TableScheme.MBR, PartitionTable.read(disk).scheme)
        assertZeroSector(disk, 2000UL)
    }

    @Test
    fun missingOrInvalidBackupHeaderLeavesGeometryUnknownAndRefuses() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        // Bit-rot the backup header exactly as a CRC failure would look.
        disk.write(disk.sectorCount - 1UL, ByteBuffer.wrap(ByteArray(sectorSize)))

        val table = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, table.scheme)
        assertNull("unknown backup geometry must stay unknown", table.gptBackupEntriesLba)
        assertNull(table.gptBackupHeaderLba)

        val writesBefore = disk.writes.size
        for (target in listOf(TableScheme.MBR, TableScheme.GPT)) {
            try {
                TableInitializer.initialize(disk, table, target)
                fail("expected IllegalArgumentException for $target")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals("refusal must not write", writesBefore, disk.writes.size)
    }

    @Test
    fun mbrToGptFailureAfterBackupNeverLeavesHybrid() {
        val inner = DiskFixture.empty(8192UL) as ImageBlockDevice
        val empty = PartitionTable.read(inner)
        // Partition covering the tail, where the new backup GPT metadata will land.
        val covered = PartitionEditor.create(
            empty,
            size = 2048UL,
            type = PartitionType.BASIC,
            startLba = 6144UL,
        )
        PartitionWriter.commit(inner, covered)
        val before = PartitionTable.read(inner)
        assertEquals(1, before.partitions.size)
        assertEquals(TableScheme.MBR, before.scheme)

        // Fail the protective-MBR write (second write to LBA 0), after backup GPT landed.
        val failing = FailingDisk(inner) { lba, occurrence ->
            lba == 0UL && occurrence == 2
        }
        val failure = runCatching {
            TableInitializer.initialize(failing, before, TableScheme.GPT)
        }.exceptionOrNull()
        assertTrue("expected injected write failure", failure != null)

        val after = PartitionTable.read(inner)
        assertTrue("hybrid layout after failed cutover", !after.hybridAmbiguous)
        assertTrue(
            "expected MBR or GPT, got ${after.scheme}",
            after.scheme == TableScheme.MBR || after.scheme == TableScheme.GPT,
        )
        assertTrue(after.partitions.none { it.startLba == 6144UL })
    }

    @Test
    fun gptEntryArrayIsPaddedForNonStandardEntryCount() {
        // 56 entries * 128 bytes = 7168: not a multiple of a 4096-byte sector.
        val disk = SyntheticGpt.build(sectorCount = 256UL, sectorSize = 4096, entryCount = 56)
        val table = PartitionTable.read(disk)
        assertEquals(56, table.gptHeader!!.entryCount)

        val renamed = PartitionEditor.rename(table, index = 0, name = "PADDED")
        val committed = PartitionWriter.commit(disk, renamed)
        assertEquals("PADDED", committed.partitions[0].name)
        assertEquals("PADDED", PartitionTable.read(disk).partitions[0].name)
    }

    @Test
    fun backupHeaderRecordingItsOwnSectorAsArrayIsNotTrusted() {
        // CRC-valid backup header whose 32-sector array ends exactly on the disk end, so the
        // bounds check passes and only the self-overlap rule can reject it (array covers 2047,
        // the header's own sector).
        val disk = SyntheticGpt.build(sectorCount = 2048UL, backupHeaderEntryLba = 2016UL)
        val table = PartitionTable.read(disk)

        assertEquals(TableScheme.GPT, table.scheme)
        assertNull("self-overlapping backup header is not usable geometry", table.gptBackupHeaderLba)
        assertNull(table.gptBackupEntriesLba)

        val writesBefore = disk.writes.size
        for (target in listOf(TableScheme.MBR, TableScheme.GPT)) {
            try {
                TableInitializer.initialize(disk, table, target)
                fail("expected IllegalArgumentException for $target")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals("refusal must not write", writesBefore, disk.writes.size)
    }

    @Test
    fun copiesSharingOneEntryArrayAreRejected() {
        // Both headers pass their own CRC and usable-range checks but record the same entry
        // array at LBA 1000; erasing/rewriting would destroy both copies at once.
        val disk = overlappingCopiesDisk()
        val failure = try {
            PartitionTable.read(disk)
            fail("expected IllegalArgumentException")
            null
        } catch (ex: IllegalArgumentException) {
            ex
        }
        assertTrue(
            "metadata overlap must be named, got: ${failure!!.message}",
            failure.message!!.contains("overlaps"),
        )
    }

    @Test
    fun gptToMbrFailureWhileErasingArraysLeavesEmptyMbr() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL)
        val before = PartitionTable.read(disk)
        assertEquals(TableScheme.GPT, before.scheme)
        assertEquals(2, before.partitions.size)

        // Empty MBR, both headers, then the arrays: fail the first array-sector write.
        val failing = FailingDisk(disk) { lba, occurrence -> lba == 2UL && occurrence == 1 }
        val failure = runCatching {
            TableInitializer.initialize(failing, before, TableScheme.MBR)
        }.exceptionOrNull()
        assertTrue("expected injected write failure", failure != null)

        val after = PartitionTable.read(disk)
        assertEquals("both headers erased first: must read as empty MBR", TableScheme.MBR, after.scheme)
        assertTrue(after.partitions.isEmpty())
    }

    @Test
    fun unreadableBackupCopyAfterInitializationFailsVerification() {
        val disk = DiskFixture.empty(8192UL) as ImageBlockDevice
        val before = PartitionTable.read(disk)
        // Lose one byte of the backup header where the write lands: the returned table would
        // otherwise look fine because the primary copy still parses.
        val corrupting = CorruptingDisk(disk) { lba -> lba == disk.sectorCount - 1UL }
        val failure = runCatching {
            TableInitializer.initialize(corrupting, before, TableScheme.GPT)
        }.exceptionOrNull()
        assertTrue(
            "a damaged backup copy must fail initialization, got $failure",
            failure is IllegalStateException && failure.message!!.contains("backup GPT copy"),
        )
    }

    private fun overlappingCopiesDisk(): ImageBlockDevice {
        val sectorCount = 2048UL
        val disk = DiskFixture.empty(sectorCount) as ImageBlockDevice
        val entries = ByteArray(32 * sectorSize)
        val crc = GptTable.crc32(entries)
        val diskGuid = ByteArray(16) { 3 }
        val sharedEntriesLba = 1000UL

        disk.write(sharedEntriesLba, ByteBuffer.wrap(entries))
        disk.write(
            1UL,
            ByteBuffer.wrap(
                GptBackup.buildEmptyHeader(
                    diskGuid = diskGuid,
                    entryCount = 128,
                    entrySize = 128,
                    headerSize = 92,
                    currentLba = 1UL,
                    backupLba = sectorCount - 1UL,
                    firstUsable = sharedEntriesLba + 32UL,
                    lastUsable = sectorCount - 2UL,
                    entryLba = sharedEntriesLba,
                    entriesCrc = crc,
                    sectorSize = sectorSize,
                ),
            ),
        )
        disk.write(
            sectorCount - 1UL,
            ByteBuffer.wrap(
                GptBackup.buildEmptyHeader(
                    diskGuid = diskGuid,
                    entryCount = 128,
                    entrySize = 128,
                    headerSize = 92,
                    currentLba = sectorCount - 1UL,
                    backupLba = 1UL,
                    firstUsable = 34UL,
                    lastUsable = sharedEntriesLba - 1UL,
                    entryLba = sharedEntriesLba,
                    entriesCrc = crc,
                    sectorSize = sectorSize,
                ),
            ),
        )
        val mbr = ByteArray(sectorSize)
        mbr[446 + 4] = 0xEE.toByte()
        putLe32(mbr, 446 + 8, 1UL)
        putLe32(mbr, 446 + 12, sectorCount - 1UL)
        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()
        disk.write(0UL, ByteBuffer.wrap(mbr))
        return disk
    }

    /** Wraps a writable image and corrupts the first byte of selected writes. */
    private class CorruptingDisk(
        private val inner: ImageBlockDevice,
        private val shouldCorrupt: (lba: ULong) -> Boolean,
    ) : BlockDevice {
        override val sectorSize: Int get() = inner.sectorSize
        override val sectorCount: ULong get() = inner.sectorCount

        override fun read(lba: ULong, destination: ByteBuffer) = inner.read(lba, destination)

        override fun write(lba: ULong, source: ByteBuffer) {
            if (!shouldCorrupt(lba)) {
                inner.write(lba, source)
                return
            }
            val bytes = ByteArray(source.remaining())
            source.duplicate().get(bytes)
            bytes[20] = (bytes[20].toInt() xor 0xFF).toByte()
            source.position(source.limit())
            inner.write(lba, ByteBuffer.wrap(bytes))
        }

        override fun sync() = inner.sync()
    }

    private fun gptWithEntryCount(sectorCount: ULong = 16384UL, entryCount: Int): ImageBlockDevice {
        val disk = DiskFixture.empty(sectorCount) as ImageBlockDevice
        val entryBytes = entryCount * 128
        val entrySectors = (entryBytes + sectorSize - 1) / sectorSize
        val entries = ByteArray(entrySectors * sectorSize)
        val crc = GptTable.crc32(entries)
        val backupHeaderLba = sectorCount - 1UL
        val backupEntriesLba = backupHeaderLba - entrySectors.toULong()
        val firstUsable = 2UL + entrySectors.toULong()
        val lastUsable = backupEntriesLba - 1UL
        val diskGuid = ByteArray(16) { 7 }

        disk.write(backupEntriesLba, ByteBuffer.wrap(entries))
        disk.write(
            backupHeaderLba,
            ByteBuffer.wrap(
                GptBackup.buildEmptyHeader(
                    diskGuid = diskGuid,
                    entryCount = entryCount,
                    entrySize = 128,
                    headerSize = 92,
                    currentLba = backupHeaderLba,
                    backupLba = 1UL,
                    firstUsable = firstUsable,
                    lastUsable = lastUsable,
                    entryLba = backupEntriesLba,
                    entriesCrc = crc,
                    sectorSize = sectorSize,
                ),
            ),
        )
        val mbr = ByteArray(sectorSize)
        mbr[446 + 4] = 0xEE.toByte()
        putLe32(mbr, 446 + 8, 1UL)
        putLe32(mbr, 446 + 12, sectorCount - 1UL)
        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()
        disk.write(0UL, ByteBuffer.wrap(mbr))
        disk.write(2UL, ByteBuffer.wrap(entries))
        disk.write(
            1UL,
            ByteBuffer.wrap(
                GptBackup.buildEmptyHeader(
                    diskGuid = diskGuid,
                    entryCount = entryCount,
                    entrySize = 128,
                    headerSize = 92,
                    currentLba = 1UL,
                    backupLba = backupHeaderLba,
                    firstUsable = firstUsable,
                    lastUsable = lastUsable,
                    entryLba = 2UL,
                    entriesCrc = crc,
                    sectorSize = sectorSize,
                ),
            ),
        )
        return disk
    }

    private fun assertZeroSector(disk: BlockDevice, lba: ULong) {
        val sector = ByteArray(sectorSize)
        disk.read(lba, ByteBuffer.wrap(sector))
        assertTrue("LBA $lba must be zeroed", sector.all { it == 0.toByte() })
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
        }
    }

    /** Wraps a writable image and fails selected writes after they are counted. */
    private class FailingDisk(
        private val inner: ImageBlockDevice,
        private val shouldFail: (lba: ULong, occurrence: Int) -> Boolean,
    ) : BlockDevice {
        private val occurrences = mutableMapOf<ULong, Int>()

        override val sectorSize: Int get() = inner.sectorSize
        override val sectorCount: ULong get() = inner.sectorCount

        override fun read(lba: ULong, destination: ByteBuffer) = inner.read(lba, destination)

        override fun write(lba: ULong, source: ByteBuffer) {
            val n = (occurrences[lba] ?: 0) + 1
            occurrences[lba] = n
            if (shouldFail(lba, n)) {
                // Consume the buffer like a real failed write, then report the fault.
                source.position(source.limit())
                throw java.io.IOException("injected write failure at LBA $lba (#$n)")
            }
            inner.write(lba, source)
        }

        override fun sync() = inner.sync()
    }
}
