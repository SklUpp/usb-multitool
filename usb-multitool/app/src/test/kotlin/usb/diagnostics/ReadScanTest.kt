package usb.diagnostics

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.DiskException
import usb.testutil.RecordingDisk

class ReadScanTest {
    private fun medium() = DiskException.SenseError(0x70, 0x03, 0x11, 0x00)

    private class Probe(
        sectorCount: ULong,
        private val fail: (ULong, Int) -> Throwable?,
    ) : RecordingDisk(sectorCount = sectorCount) {
        val calls = mutableListOf<Pair<ULong, Int>>()
        override fun read(lba: ULong, destination: ByteBuffer) {
            val sectors = destination.remaining() / 512
            calls += lba to sectors
            fail(lba, sectors)?.let { throw it }
            super.read(lba, destination)
        }
    }

    // SCAN_CHUNK_BYTES = 1 MiB = 2048 sectors at 512 B/sector
    private val chunk = Diagnostics.SCAN_CHUNK_BYTES / 512

    @Test fun cleanDiskUsesChunkedReads() {
        val disk = Probe(4096UL) { _, _ -> null }
        val report = Diagnostics.readScan(disk)
        assertEquals(4096UL, report.sectorsRead)
        assertTrue(report.failed.isEmpty())
        // 4096 sectors / 2048 per chunk = exactly 2 chunk reads
        assertEquals(listOf(0UL to chunk, chunk.toULong() to chunk), disk.calls)
    }

    @Test fun mediumErrorIsolatedToBadSectors() {
        // chunk [0,16) triggers medium; only sector 5 fails per-sector
        val bad = 5UL
        val disk = Probe(16UL) { lba, sectors ->
            if (sectors > 1) medium()           // chunk read always fails
            else if (lba == bad) medium()        // per-sector: only lba 5
            else null
        }
        val report = Diagnostics.readScan(disk)
        assertEquals(16UL, report.sectorsRead)
        assertEquals(listOf(5UL..5UL), report.failed)
    }

    @Test fun adjacentBadSectorsMerge() {
        val bad = setOf(3UL, 4UL)
        val disk = Probe(16UL) { lba, sectors ->
            if (sectors > 1) medium()
            else if (lba in bad) medium()
            else null
        }
        assertEquals(listOf(3UL..4UL), Diagnostics.readScan(disk).failed)
    }

    @Test fun disconnectAbortsInsteadOfMarkingRestFailed() {
        // second chunk throws disconnect
        val disk = Probe(4096UL) { lba, _ ->
            if (lba >= chunk.toULong()) DiskException.Disconnected() else null
        }
        assertThrows(DiskException.Disconnected::class.java) { Diagnostics.readScan(disk) }
        assertEquals(2, disk.calls.size) // first chunk OK, second throws
    }

    @Test fun nonMediumSenseAborts() {
        val disk = Probe(16UL) { _, _ -> DiskException.SenseError(0x70, 0x06, 0x29, 0x00) }
        assertThrows(DiskException.SenseError::class.java) { Diagnostics.readScan(disk) }
        assertEquals(1, disk.calls.size)
    }
}
