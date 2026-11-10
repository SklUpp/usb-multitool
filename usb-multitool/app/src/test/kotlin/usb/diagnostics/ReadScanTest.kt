package usb.diagnostics

import usb.formatBinaryBytesPerSecond

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.DiskException
import usb.operation.DiskOperation
import usb.testutil.RecordingDisk
import usb.ui.ReadScanOutcome

class ReadScanTest {
    private fun medium() = DiskException.SenseError(0x70, 0x03, 0x11, 0x00)

    /** [fail] is last so trailing-lambda call sites still compile. */
    private class Probe(
        sectorCount: ULong,
        private val onRead: (() -> Unit)? = null,
        private val fail: (ULong, Int) -> Throwable?,
    ) : RecordingDisk(sectorCount = sectorCount) {
        val calls = mutableListOf<Pair<ULong, Int>>()
        override fun read(lba: ULong, destination: ByteBuffer) {
            val sectors = destination.remaining() / 512
            calls += lba to sectors
            onRead?.invoke()
            fail(lba, sectors)?.let { throw it }
            super.read(lba, destination)
        }
    }

    private val chunk = Diagnostics.SCAN_CHUNK_BYTES / 512

    /** Deterministic monotonic clock advanced only via [advance]. */
    private class FakeClock(start: Long = 0L) {
        var now = start
        val nanos: () -> Long = { now }
        fun advance(delta: Long) {
            now += delta
        }
    }

    @Test fun cleanDiskUsesChunkedReads() {
        val disk = Probe((chunk * 2).toULong()) { _, _ -> null }
        val report = Diagnostics.readScan(disk)
        assertEquals((chunk * 2).toULong(), report.sectorsRead)
        assertTrue(report.failed.isEmpty())
        assertEquals(listOf(0UL to chunk, chunk.toULong() to chunk), disk.calls)
        assertEquals((chunk * 2).toLong() * 512L, report.successfulBytes)
        assertTrue(report.elapsedNanos >= 0L)
    }

    @Test fun mediumErrorIsolatedToBadSectors() {
        val bad = 5UL
        val disk = Probe(16UL) { lba, sectors ->
            if (sectors > 1) medium()
            else if (lba == bad) medium()
            else null
        }
        val report = Diagnostics.readScan(disk)
        assertEquals(16UL, report.sectorsRead)
        assertEquals(listOf(5UL..5UL), report.failed)
        assertEquals(15L * 512L, report.successfulBytes)
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
        val clock = FakeClock()
        val disk = Probe(
            16UL,
            onRead = { clock.advance(100_000_000L) },
            fail = { lba, sectors ->
                if (sectors > 1) medium() else {
                    if (lba == 2UL) active = false
                    null
                }
            },
        )
        val report = Diagnostics.readScan(disk, operation, monotonicNanos = clock.nanos)
        assertEquals(3UL, report.sectorsRead)
        assertEquals(3L * 512, operation.bytesDone)
        assertEquals(16L * 512, operation.bytesTotal)
        assertEquals(3L * 512L, report.successfulBytes)
        assertTrue(report.elapsedNanos > 0L)
        val expectedBps =
            (report.successfulBytes.toDouble() * 1_000_000_000.0 / report.elapsedNanos.toDouble()).toLong()
        assertEquals(expectedBps, report.averageBytesPerSecond)
        val outcome = ReadScanOutcome.from(report, 16UL)
        assertEquals(expectedBps, outcome.averageBytesPerSecond)
        assertTrue(outcome.toLogLine().contains("avg read"))
        assertTrue(!outcome.toLogLine().contains("unavailable"))
    }

    @Test fun disconnectAbortsInsteadOfMarkingRestFailed() {
        val disk = Probe(4096UL) { lba, _ ->
            if (lba >= chunk.toULong()) DiskException.Disconnected() else null
        }
        assertThrows(DiskException.Disconnected::class.java) { Diagnostics.readScan(disk) }
        assertEquals(2, disk.calls.size)
    }

    @Test fun nonMediumSenseAborts() {
        val disk = Probe(16UL) { _, _ -> DiskException.SenseError(0x70, 0x06, 0x29, 0x00) }
        assertThrows(DiskException.SenseError::class.java) { Diagnostics.readScan(disk) }
        assertEquals(1, disk.calls.size)
    }

    @Test fun averageSpeedUsesOnlySuccessfulBytesOverMonotonicElapsed() {
        val clock = FakeClock()
        val bad = 5UL
        val disk = Probe(
            16UL,
            onRead = { clock.advance(50_000_000L) },
            fail = { lba, sectors ->
                if (sectors > 1) medium()
                else if (lba == bad) medium()
                else null
            },
        )
        val report = Diagnostics.readScan(disk, monotonicNanos = clock.nanos)
        assertEquals(15L * 512L, report.successfulBytes)
        assertEquals(clock.now, report.elapsedNanos)
        val expected = ((15L * 512L).toDouble() * 1_000_000_000.0 / report.elapsedNanos.toDouble()).toLong()
        assertEquals(expected, report.averageBytesPerSecond)
        assertEquals(formatBinaryBytesPerSecond(expected), report.formatAverageReadSpeed())
    }

    @Test fun zeroElapsedMakesSpeedUnavailable() {
        val clock = FakeClock(start = 1_000L)
        val disk = Probe(16UL) { _, _ -> null }
        val report = Diagnostics.readScan(disk, monotonicNanos = clock.nanos)
        assertEquals(16L * 512L, report.successfulBytes)
        assertEquals(0L, report.elapsedNanos)
        assertNull(report.averageBytesPerSecond)
        assertNull(report.formatAverageReadSpeed())
        val outcome = ReadScanOutcome.from(report, 16UL)
        assertNull(outcome.averageBytesPerSecond)
        assertTrue(outcome.toLogLine().endsWith("avg read unavailable"))
    }

    @Test fun noSuccessfulBytesMakesSpeedUnavailable() {
        val clock = FakeClock()
        val disk = Probe(
            8UL,
            onRead = { clock.advance(10_000_000L) },
            fail = { _, _ -> medium() },
        )
        val report = Diagnostics.readScan(disk, monotonicNanos = clock.nanos)
        assertEquals(8UL, report.sectorsRead)
        assertEquals(8, report.failed.sumOf { (it.last - it.first + 1UL).toInt() })
        assertEquals(0L, report.successfulBytes)
        assertTrue(report.elapsedNanos > 0L)
        assertNull(report.averageBytesPerSecond)
        val outcome = ReadScanOutcome.from(report, 8UL)
        assertEquals(8UL, outcome.unreadableSectors)
        assertEquals(1, outcome.failedRangeCount)
        assertNull(outcome.averageBytesPerSecond)
        assertEquals(
            "Read scan: 8 of 8 sectors read, 8 unreadable in 1 range(s), avg read unavailable",
            outcome.toLogLine(),
        )
    }

    @Test fun cancellationWithZeroTimeStaysUnavailable() {
        var active = true
        val operation = DiskOperation(active = { active })
        val clock = FakeClock(start = 42L)
        val disk = Probe(16UL) { lba, sectors ->
            if (sectors > 1) medium() else {
                if (lba == 0UL) active = false
                null
            }
        }
        val report = Diagnostics.readScan(disk, operation, monotonicNanos = clock.nanos)
        assertEquals(1UL, report.sectorsRead)
        assertEquals(512L, report.successfulBytes)
        assertEquals(0L, report.elapsedNanos)
        assertNull(report.averageBytesPerSecond)
        assertNull(ReadScanOutcome.from(report, 16UL).averageBytesPerSecond)
    }

    @Test fun formatBinaryRateBuckets() {
        assertEquals("512 B/s", formatBinaryBytesPerSecond(512))
        assertEquals("1.5 KiB/s", formatBinaryBytesPerSecond(1536))
        assertEquals("2.00 MiB/s", formatBinaryBytesPerSecond(2L * 1024 * 1024))
        assertEquals("1.00 GiB/s", formatBinaryBytesPerSecond(1024L * 1024 * 1024))
    }

    @Test fun hardErrorDoesNotInventPartialSpeedReport() {
        val clock = FakeClock()
        val disk = Probe(16UL) { _, _ ->
            clock.advance(1_000_000L)
            DiskException.SenseError(0x70, 0x06, 0x29, 0x00)
        }
        assertThrows(DiskException.SenseError::class.java) {
            Diagnostics.readScan(disk, monotonicNanos = clock.nanos)
        }
    }
}
