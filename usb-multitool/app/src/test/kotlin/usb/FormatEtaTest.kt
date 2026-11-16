package usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatEtaTest {
    @Test fun zeroSpeedOmitsEta() {
        assertNull(remainingSeconds(bytesDone = 50L, bytesTotal = 100L, bytesPerSecond = 0L))
    }

    @Test fun negativeSpeedOmitsEta() {
        assertNull(remainingSeconds(50L, 100L, -1L))
    }

    @Test fun unknownTotalOmitsEta() {
        assertNull(remainingSeconds(50L, -1L, 10L))
        assertNull(remainingSeconds(50L, 0L, 10L))
    }

    @Test fun completeOmitsEta() {
        assertNull(remainingSeconds(100L, 100L, 10L))
        assertNull(remainingSeconds(150L, 100L, 10L))
    }

    @Test fun roundsUpPartialSecond() {
        assertEquals(10L, remainingSeconds(0L, 100L, 10L))
        assertEquals(11L, remainingSeconds(0L, 101L, 10L))
        assertEquals(1L, remainingSeconds(99L, 100L, 10L))
    }

    @Test fun largeValuesDoNotOverflow() {
        assertEquals(Long.MAX_VALUE, remainingSeconds(0L, Long.MAX_VALUE, 1L))
        assertEquals(1L, remainingSeconds(0L, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(2L, remainingSeconds(0L, Long.MAX_VALUE, Long.MAX_VALUE - 1L))
    }

    @Test fun negativeDoneClampsToZero() {
        assertEquals(10L, remainingSeconds(-50L, 100L, 10L))
    }

    @Test fun formatsClockLabels() {
        assertEquals("0:00", formatEtaDuration(0L))
        assertEquals("0:59", formatEtaDuration(59L))
        assertEquals("1:00", formatEtaDuration(60L))
        assertEquals("1:01", formatEtaDuration(61L))
        assertEquals("59:59", formatEtaDuration(3599L))
        assertEquals("1:00:00", formatEtaDuration(3600L))
        assertEquals("1:01:01", formatEtaDuration(3661L))
        assertEquals("25:01:01", formatEtaDuration(90061L))
    }
}
