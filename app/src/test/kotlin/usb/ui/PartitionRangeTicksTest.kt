package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionRangeTicksTest {
    @Test
    fun zeroAndSmallRangesListEveryWholeMib() {
        assertEquals(listOf(0UL), PartitionRangeTicks.marks(0UL))
        assertEquals(listOf(0UL, 1UL, 2UL, 3UL), PartitionRangeTicks.marks(3UL, maxInterior = 7))
    }

    @Test
    fun largeRangeKeepsEndsAndStaysInside() {
        val marks = PartitionRangeTicks.marks(10_000UL, maxInterior = 7)
        assertEquals(0UL, marks.first())
        assertEquals(10_000UL, marks.last())
        assertTrue(marks.size <= 9)
        assertTrue(marks.zipWithNext().all { (a, b) -> a < b })
        assertTrue(marks.all { it <= 10_000UL })
    }

    @Test
    fun marksNeverDistortAxisBeyondMax() {
        // Float cannot represent every large MiB; marks stay exact ULong indices.
        val max = 16_777_219UL
        val marks = PartitionRangeTicks.marks(max, maxInterior = 5)
        assertEquals(max, marks.last())
        assertTrue(marks.none { it > max })
    }
}
