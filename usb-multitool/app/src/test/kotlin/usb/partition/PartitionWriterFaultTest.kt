package usb.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.DiskException

class PartitionWriterFaultTest {
    private fun gptDisk(): ImageBlockDevice = SyntheticGpt.build(sectorCount = 16384UL)

    private fun edited(disk: ImageBlockDevice): Pair<PartitionTable, PartitionTable> {
        val old = PartitionTable.read(disk)
        val next = PartitionEditor.create(old, size = 2048UL, type = PartitionType.BASIC)
        return old to next
    }

    @Test
    fun backupEntriesAndHeaderAreSyncedBeforePrimaryIsTouched() {
        val disk = gptDisk()
        val table = PartitionTable.read(disk)
        val next = PartitionEditor.create(table, size = 2048UL, type = PartitionType.BASIC)
        val fault = FaultyBlockDevice(disk)

        PartitionWriter.commit(fault, next)

        val backupEntriesLba = requireNotNull(table.gptBackupEntriesLba)
        val backupHeaderLba = requireNotNull(table.gptBackupHeaderLba)
        val primaryEntriesLba = requireNotNull(table.gptPrimaryEntryLba)
        val primaryHeaderLba = table.gptHeader!!.currentLba

        val firstSync = fault.ops.indexOf("sync")
        val backupEntries = fault.ops.indexOf("write:$backupEntriesLba")
        val backupHeader = fault.ops.indexOf("write:$backupHeaderLba")
        val primaryEntries = fault.ops.indexOf("write:$primaryEntriesLba")
        val primaryHeader = fault.ops.indexOf("write:$primaryHeaderLba")

        assertTrue("backup entries write missing", backupEntries >= 0)
        assertTrue("backup header write missing", backupHeader >= 0)
        assertTrue("primary entries write missing", primaryEntries >= 0)
        assertTrue("primary header write missing", primaryHeader >= 0)
        assertTrue("backup entries before backup header", backupEntries < backupHeader)
        assertTrue("sync must follow the backup header", firstSync > backupHeader)
        assertTrue("primary must change only after the backup is durable", firstSync < primaryEntries)
        assertTrue("entries before the primary header", primaryEntries < primaryHeader)
        assertEquals("primary header is written exactly once", 1, fault.ops.count { it == "write:$primaryHeaderLba" })
    }

    @Test
    fun failureAtAnyWriteLeavesOneCompleteGptCopy() {
        for (failAt in 1..4) {
            for (applyBeforeFailing in listOf(false, true)) {
                val disk = gptDisk()
                val (old, next) = edited(disk)
                val fault = FaultyBlockDevice(
                    delegate = disk,
                    failAtWrite = failAt,
                    applyBeforeFailing = applyBeforeFailing,
                )

                assertThrows(DiskException.IoFailure::class.java) {
                    PartitionWriter.commit(fault, next)
                }

                val recovered = PartitionTable.read(fault)
                val actual = recovered.partitions.map { Triple(it.startLba, it.sectorCount, it.name) }
                val context = "failAt=$failAt applyBeforeFailing=$applyBeforeFailing"
                assertTrue(
                    "$context: recovered a mixed table $actual",
                    actual == old.partitions.map { Triple(it.startLba, it.sectorCount, it.name) } ||
                        actual == next.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
                )
            }
        }
    }

    @Test
    fun tornWriteAtAnyPointNeverProducesMixedTable() {
        for (tornAt in 1..4) {
            val disk = gptDisk()
            val (old, next) = edited(disk)
            val fault = FaultyBlockDevice(disk, tornWriteAt = tornAt)

            assertThrows(DiskException.IoFailure::class.java) {
                PartitionWriter.commit(fault, next)
            }

            val recovered = PartitionTable.read(fault)
            val actual = recovered.partitions.map { Triple(it.startLba, it.sectorCount, it.name) }
            assertTrue(
                "tornAt=$tornAt: recovered a mixed table $actual",
                actual == old.partitions.map { Triple(it.startLba, it.sectorCount, it.name) } ||
                    actual == next.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
            )
        }
    }

    @Test
    fun syncFailureBeforePrimaryKeepsOldTableReadable() {
        for (applyBeforeFailing in listOf(false, true)) {
            val disk = gptDisk()
            val (old, next) = edited(disk)
            val fault = FaultyBlockDevice(
                delegate = disk,
                failAtSync = 1,
                applyBeforeFailing = applyBeforeFailing,
            )

            assertThrows(DiskException.IoFailure::class.java) {
                PartitionWriter.commit(fault, next)
            }

            val recovered = PartitionTable.read(fault)
            assertEquals(false, recovered.gptPrimaryDamaged)
            assertEquals(
                old.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
                recovered.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
            )

            // The backup was fully written before the failed sync and survives primary loss.
            val bytes = fault.snapshot()
            bytes[512 + 16] = (bytes[512 + 16].toInt() xor 0xFF).toByte()
            val fromBackup = PartitionTable.read(
                ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount),
            )
            assertTrue(fromBackup.gptPrimaryDamaged)
            assertEquals(
                next.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
                fromBackup.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
            )
        }
    }

    @Test
    fun syncFailureAfterPrimaryHeaderLeavesNewTableReadable() {
        val disk = gptDisk()
        val (_, next) = edited(disk)
        // Second sync is the outer commit flush after both GPT copies were written.
        val fault = FaultyBlockDevice(disk, failAtSync = 2)

        assertThrows(DiskException.IoFailure::class.java) {
            PartitionWriter.commit(fault, next)
        }

        val recovered = PartitionTable.read(fault)
        assertEquals(false, recovered.gptPrimaryDamaged)
        assertEquals(
            next.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
            recovered.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
        )
    }

    @Test
    fun backupCopyIsCompleteBeforePrimaryEntriesAreWritten() {
        val disk = gptDisk()
        val (old, next) = edited(disk)
        // Third write is the primary entry array; the backup is already synced by then.
        val fault = FaultyBlockDevice(disk, failAtWrite = 3, applyBeforeFailing = false)

        assertThrows(DiskException.IoFailure::class.java) {
            PartitionWriter.commit(fault, next)
        }

        val primaryIntact = PartitionTable.read(fault)
        assertEquals(false, primaryIntact.gptPrimaryDamaged)
        assertEquals(
            old.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
            primaryIntact.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
        )

        // Destroy the untouched primary copy: the new layout must still be readable.
        val bytes = fault.snapshot()
        bytes[512 + 16] = (bytes[512 + 16].toInt() xor 0xFF).toByte()
        val damagedPrimary = ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount)
        val fromBackup = PartitionTable.read(damagedPrimary)

        assertTrue(fromBackup.gptPrimaryDamaged)
        assertEquals(
            next.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
            fromBackup.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
        )
    }

    @Test
    fun successfulCommitLeavesReadableBackupCopy() {
        val disk = gptDisk()
        val (_, next) = edited(disk)

        PartitionWriter.commit(disk, next)

        // Hide the primary: the committed layout must still be recoverable from backup.
        val bytes = disk.snapshot()
        bytes[512 + 16] = (bytes[512 + 16].toInt() xor 0xFF).toByte()
        val recovered = PartitionTable.read(
            ImageBlockDevice(bytes, disk.sectorSize, disk.sectorCount),
        )

        assertTrue(recovered.gptPrimaryDamaged)
        assertEquals(
            next.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
            recovered.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
        )
    }

    @Test
    fun tornPrimaryEntriesAreRecoveredFromBackup() {
        val disk = gptDisk()
        val (_, next) = edited(disk)
        // The primary entry array lands, then the disconnect hits before its header.
        val fault = FaultyBlockDevice(disk, failAtWrite = 3, applyBeforeFailing = true)

        assertThrows(DiskException.IoFailure::class.java) {
            PartitionWriter.commit(fault, next)
        }

        val recovered = PartitionTable.read(fault)

        assertTrue("torn primary must fall back to backup", recovered.gptPrimaryDamaged)
        assertEquals(
            next.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
            recovered.partitions.map { Triple(it.startLba, it.sectorCount, it.name) },
        )
    }
}
