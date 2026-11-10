package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartitionRangeMathTest {
    @Test
    fun availableMibUsesWholeUnitsOnly() {
        assertEquals(5UL, PartitionRangeMath.availableMib(5UL * 2048UL + 2047UL, 2048UL))
        assertEquals(0UL, PartitionRangeMath.availableMib(2047UL, 2048UL))
        assertEquals(0UL, PartitionRangeMath.availableMib(1024UL, 0UL))
    }

    @Test
    fun snapClampsFloatRoundingToTheExactLimit() {
        // 16_777_219 is not representable as Float; 16_777_220f rounds up past the region.
        val max = 16_777_219UL
        assertEquals(max, PartitionRangeMath.snapToMib(16_777_220f, max))
        assertEquals(max, PartitionRangeMath.snapToMib(max.toFloat(), max))
        assertEquals(0UL, PartitionRangeMath.snapToMib(-1f, max))
        assertEquals(0UL, PartitionRangeMath.snapToMib(Float.NaN, max))
        assertEquals(0UL, PartitionRangeMath.snapToMib(7f, 0UL))
    }

    @Test
    fun sectorsStayInsideTheRegion() {
        val mib = 2048UL
        val regionEnd = 10UL * mib
        assertEquals(
            2UL * mib to 3UL * mib,
            PartitionRangeMath.sectorsFor(
                firstAligned = 0UL,
                regionEnd = regionEnd,
                startMib = 2UL,
                endMib = 5UL,
                sectorsPerMib = mib,
            ),
        )
        assertNull(PartitionRangeMath.sectorsFor(0UL, regionEnd, 4UL, 4UL, mib))
        assertNull(PartitionRangeMath.sectorsFor(0UL, regionEnd, 9UL, 11UL, mib))
        assertNull(PartitionRangeMath.sectorsFor(0UL, regionEnd, 0UL, 1UL, 0UL))
    }

    @Test
    fun sectorsRespectUnalignedRegionEnd() {
        val mib = 2048UL
        val firstAligned = 1024UL
        val regionEnd = firstAligned + 3UL * mib + 100UL
        val selection = PartitionRangeMath.sectorsFor(
            firstAligned = firstAligned,
            regionEnd = regionEnd,
            startMib = 0UL,
            endMib = 3UL,
            sectorsPerMib = mib,
        )
        assertEquals(firstAligned to 3UL * mib, selection)
        assertNull(
            PartitionRangeMath.sectorsFor(
                firstAligned = firstAligned,
                regionEnd = regionEnd,
                startMib = 0UL,
                endMib = 4UL,
                sectorsPerMib = mib,
            ),
        )
    }
}
