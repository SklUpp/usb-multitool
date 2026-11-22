package usb.ventoy

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Absence, read failure and cancellation stay three different outcomes. */
class VentoyVersionReadTest {
    private fun probe(version: String, error: Throwable? = null) = VentoyProbeResult(
        isVentoy = true,
        scheme = usb.partition.TableScheme.MBR,
        part1StartLba = 2048uL,
        part1SectorCount = 2048uL,
        part2StartLba = 4096uL,
        part2SectorCount = 65536uL,
        diskUuid = ByteArray(16),
        diskSignature = 0,
        rsvData = ByteArray(8 * 512),
        version = version,
        versionError = error,
    )

    @Test
    fun absentProbeOrBlankMarkerIsNotAnError() {
        assertTrue(
            classifyVentoyVersion(null) is VentoyInstalledVersion.Present,
        )
        assertNull(
            (classifyVentoyVersion(null) as VentoyInstalledVersion.Present).release,
        )
        assertNull(
            (classifyVentoyVersion(probe("  ")) as VentoyInstalledVersion.Present).release,
        )
    }

    @Test
    fun realVersionIsReported() {
        val present = classifyVentoyVersion(probe("1.1.18")) as VentoyInstalledVersion.Present
        assertEquals("1.1.18", present.release)
    }

    @Test
    fun fatIoFailureIsNotReportedAsN_a() {
        val failed = classifyVentoyVersion(probe("", IOException("FAT read failed")))
        assertTrue(failed is VentoyInstalledVersion.ReadFailed)
        assertEquals("FAT read failed", (failed as VentoyInstalledVersion.ReadFailed).message)
    }

    @Test
    fun cancellationIsPropagated() {
        assertThrows(CancellationException::class.java) {
            classifyVentoyVersion(probe("", CancellationException("cancelled")))
        }
    }
}
