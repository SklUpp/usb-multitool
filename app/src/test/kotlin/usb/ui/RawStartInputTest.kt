package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawStartInputTest {
    @Test
    fun wholeMibMapsToSectors() {
        assertEquals(4096uL, rawStartLbaFromMib("2", 512))
        assertEquals(512uL, rawStartLbaFromMib("2", 4096))
        assertEquals(0uL, rawStartLbaFromMib("0", 512))
    }

    @Test
    fun fractionalMibRoundsUpToWholeMib() {
        assertEquals(4096uL, rawStartLbaFromMib("1.5", 512))
        assertEquals(2048uL, rawStartLbaFromMib("0.0001", 512))
    }

    @Test
    fun commaDecimalAndInvalidInput() {
        assertEquals(4096uL, rawStartLbaFromMib("1,5", 512))
        assertNull(rawStartLbaFromMib("", 512))
        assertNull(rawStartLbaFromMib("abc", 512))
        assertNull(rawStartLbaFromMib("-1", 512))
    }

    @Test
    fun unrepresentableOrOverflowingInputIsRejected() {
        assertNull(rawStartLbaFromMib("1", 1000))
        assertNull(rawStartLbaFromMib("1000000000000000000", 512))
    }
}
