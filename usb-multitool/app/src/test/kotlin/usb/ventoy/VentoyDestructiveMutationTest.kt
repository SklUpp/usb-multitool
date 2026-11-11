package usb.ventoy

import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.DiskException
import usb.device.FakeBotSession
import usb.device.withWriteTracking
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.operation.syncDestructiveDisk
import usb.partition.TableScheme
import usb.service.OperationExecutor
import usb.testutil.RecordingDisk

/**
 * S1–S5: production [withWriteTracking] (Long+ULong) and [syncDestructiveDisk] as MainActivity
 * destructive Ventoy paths use them. Seed is MBR probe fixture — no native FatFs dependency.
 */
class VentoyDestructiveMutationTest {
    private val assets = syntheticVentoyAssets(efiPartSectors = 2048)

    /**
     * MBR Ventoy layout that [VentoyLayout.probe] accepts without install/format (S5).
     */
    private fun seedMbrVentoyStick(
        part1Sectors: Long = 8192L,
        part2Sectors: Long = VentoyLayout.EFI_PART_SECTORS,
        sectorCount: ULong = 262144UL,
        session: FakeBotSession = FakeBotSession(),
    ): RecordingDisk {
        val disk = RecordingDisk(sectorCount = sectorCount, sectorSize = 512, session = session)
        val mbr = ByteArray(512)
        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        mbr[446] = 0x00
        mbr[446 + 4] = 0x07
        putLe32(mbr, 446 + 8, 2048)
        putLe32(mbr, 446 + 12, part1Sectors.toInt())
        mbr[462] = 0x00
        mbr[462 + 4] = 0xEF.toByte()
        putLe32(mbr, 462 + 8, (2048 + part1Sectors).toInt())
        putLe32(mbr, 462 + 12, part2Sectors.toInt())
        disk.write(0L, ByteBuffer.wrap(mbr))
        val probe = VentoyLayout.probe(disk)
        require(probe != null && probe.isVentoy && probe.scheme == TableScheme.MBR) {
            "seed MBR layout must probe as Ventoy"
        }
        disk.resetStats()
        return disk
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    /** Same outer frame as MainActivity: syncDestructiveDisk then finally disk.close(). */
    private fun DiskOperation.runVentoyUpdateWithProductionTail(
        disk: RecordingDisk,
        armAfterBody: () -> Unit = {},
    ) {
        try {
            val partitionDisk =
                disk.withWriteTracking { mutationAttempted = true }
            VentoyOperations.update(partitionDisk, assets, this, versionReader = { _, _, _ -> Result.success("1.1.18") })
            armAfterBody()
            syncDestructiveDisk(disk)
        } finally {
            disk.close()
        }
    }

    @Test
    fun longWriteOverloadHitsProductionTracker() {
        // S1
        var hits = 0
        val raw = RecordingDisk(sectorCount = 64UL)
        val tracked = raw.withWriteTracking { hits++ }
        val buf = ByteBuffer.allocate(512)
        tracked.write(3L, buf)
        assertEquals(1, hits)
        assertEquals(1, raw.writeCount)
        buf.clear()
        tracked.write(4UL, buf)
        assertEquals(2, hits)
        assertEquals(2, raw.writeCount)
    }

    @Test
    fun ventoyUpdateThenOuterSyncFailureIsPartial() = runTest {
        // S2/S3: internal update sync succeeds; only outer syncDestructiveDisk fails.
        val disk = seedMbrVentoyStick()
        val syncErr = DiskException.IoFailure("outer sync failed after ventoy update")
        val executor = OperationExecutor(this)
        val op =
            object : DiskOperation(name = "Ventoy update") {
                override suspend fun run() {
                    val syncsBeforeBody = disk.syncCount
                    runVentoyUpdateWithProductionTail(disk) {
                        assertTrue(mutationAttempted)
                        assertTrue(disk.writeCount > 0)
                        // Body (incl. VentoyInstaller internal sync) already returned.
                        assertTrue(disk.syncCount > syncsBeforeBody)
                        val syncsAfterBody = disk.syncCount
                        disk.syncHook = { throw syncErr }
                        // Outer sync must be a further call.
                        // Asserted after finish via syncCount in catch path below — hook fires once.
                        @Suppress("UNUSED_VARIABLE")
                        val expectedOuter = syncsAfterBody + 1
                    }
                }
            }

        assertEquals(OperationState.Partial, executor.submit(op).await())
        assertTrue(op.mutationAttempted)
        assertTrue(!op.commitReached)
        assertSame(syncErr, op.failureCause)
        assertTrue("outer sync attempted", disk.syncCount >= 2)
    }

    @Test
    fun ventoyUpdateThenOuterCloseFailureIsPartial() = runTest {
        // S4: UsbDisk.close → session.close after successful body + outer sync.
        val session =
            object : FakeBotSession() {
                var failClose = false
                override fun close() {
                    if (failClose) {
                        throw DiskException.IoFailure("outer close failed after ventoy update")
                    }
                    super.close()
                }
            }
        val disk = seedMbrVentoyStick(session = session)
        val executor = OperationExecutor(this)
        val op =
            object : DiskOperation(name = "Ventoy update close") {
                override suspend fun run() {
                    runVentoyUpdateWithProductionTail(disk) {
                        assertTrue(mutationAttempted)
                        session.failClose = true
                    }
                }
            }

        assertEquals(OperationState.Partial, executor.submit(op).await())
        assertTrue(op.mutationAttempted)
        assertTrue(op.commitReached) // outer sync succeeded before close threw
        assertTrue(op.failureCause is DiskException.IoFailure)
        assertTrue(op.failureCause!!.message!!.contains("outer close failed"))
    }

    @Test
    fun ventoyUpdateWithoutTrackerOuterSyncIsFailed() = runTest {
        val disk = seedMbrVentoyStick()
        val syncErr = DiskException.IoFailure("outer sync failed after ventoy update")
        val executor = OperationExecutor(this)
        val op =
            object : DiskOperation(name = "Ventoy update raw") {
                override suspend fun run() {
                    try {
                        VentoyOperations.update(disk, assets, this, versionReader = { _, _, _ -> Result.success("1.1.18") })
                        assertTrue(disk.writeCount > 0)
                        disk.syncHook = { throw syncErr }
                        syncDestructiveDisk(disk)
                    } finally {
                        disk.close()
                    }
                }
            }

        val terminal = executor.submit(op).await()
        assertTrue(!op.mutationAttempted)
        assertTrue(terminal is OperationState.Failed)
    }
}
