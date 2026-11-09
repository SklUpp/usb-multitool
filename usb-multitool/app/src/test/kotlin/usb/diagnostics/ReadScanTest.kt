package usb.diagnostics

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.DiskException
import usb.service.DiskOperation
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

    private val chunk = Diagnostics.SCAN_CHUNK_BYTES / 512

    @Test fun cleanDiskUsesChunkedReads() {
        val disk = Probe((chunk * 2).toULong()) { _, _ -> null }
        val report = Diagnostics.readScan(disk)
        assertEquals((chunk * 2).toULong(), report.sectorsRead)
        assertTrue(report.failed.isEmpty())
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

    @Test fun cancellationDuringSectorFallbackReportsOnlyScannedSectors() {
        var active = true
        val operation = DiskOperation(active = { active })
        val disk = Probe(16UL) { lba, sectors ->
            if (sectors > 1) medium() else {
                if (lba == 2UL) active = false
                null
            }
        }
        val report = Diagnostics.readScan(disk, operation)
        assertEquals(3UL, report.sectorsRead)
        assertEquals(3L * 512, operation.bytesDone)
        assertEquals(16L * 512, operation.bytesTotal)
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
