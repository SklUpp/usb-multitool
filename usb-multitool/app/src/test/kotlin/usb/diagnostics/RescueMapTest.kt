package usb.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.testutil.RecordingDisk

class RescueMapTest {
    @Test fun failedRangeRemainsPartialAfterResume() {
        val map = RescueMap.whole(100UL)
        map.markFailed(10UL, 2UL)
        map.markCopied(0UL, 10UL)
        assertTrue(map.partial)
        assertEquals(listOf(10UL until 12UL), map.failed)
    }

    @Test fun fullyCopiedIsNotPartial() {
        val map = RescueMap.whole(16UL)
        map.markCopied(0UL, 16UL)
        assertFalse(map.partial)
        assertTrue(map.failed.isEmpty())
        assertEquals(listOf(0UL until 16UL), map.copied)
    }

    @Test fun encodeDecodeRoundTrip() {
        val map = RescueMap.whole(32UL)
        map.markCopied(0UL, 8UL)
        map.markFailed(8UL, 4UL)
        val restored = RescueMap.decode(map.encode())
        assertEquals(map.copied, restored.copied)
        assertEquals(map.failed, restored.failed)
        assertTrue(restored.partial)
    }

    @Test fun wipeRequiresConfirmation() {
        val disk = RecordingDisk(sectorCount = 4UL)
        try {
            Diagnostics.wipe(disk, confirmed = false)
            fail("expected WipeNotConfirmedException")
        } catch (_: WipeNotConfirmedException) {
        }
        assertEquals(0, disk.bytesWritten)
        Diagnostics.wipe(disk, confirmed = true)
        assertEquals(4 * 512, disk.bytesWritten)
    }

    @Test fun readScanNeverWrites() {
        val disk = RecordingDisk(sectorCount = 8UL)
        val report = Diagnostics.readScan(disk)
        assertEquals(8UL, report.sectorsRead)
        assertEquals(0, disk.bytesWritten)
        assertTrue(report.failed.isEmpty())
    }
}
