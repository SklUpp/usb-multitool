package usb.diagnostics

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

class RescueRangePolicyTest {
    @Test fun acceptsSignedRange() {
        assertEquals(1024L, RescueRangePolicy.requireSignedBytes(2UL, 512))
    }

    @Test fun rejectsOverflowBeforeOffsetUse() {
        assertThrows(IllegalArgumentException::class.java) { RescueRangePolicy.requireSignedBytes(ULong.MAX_VALUE, 512) }
    }
}
