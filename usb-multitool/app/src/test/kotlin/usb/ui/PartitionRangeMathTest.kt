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

    @Test
    fun snapPointsIncludeBoundariesFractionsAndSizes() {
        val points = PartitionRangeMath.snapPoints(
            availableMib = 4096UL,
            firstAligned = 2048UL,
            sectorsPerMib = 2048UL,
            otherPartitionSizesMib = listOf(500UL),
        )
        // Boundaries
        org.junit.Assert.assertTrue(points.contains(0UL))
        org.junit.Assert.assertTrue(points.contains(4096UL))
        // Fractions
        org.junit.Assert.assertTrue(points.contains(1024UL)) // 25%
        org.junit.Assert.assertTrue(points.contains(2048UL)) // 50%
        org.junit.Assert.assertTrue(points.contains(3072UL)) // 75%
        // Other partition size
        org.junit.Assert.assertTrue(points.contains(500UL))
        org.junit.Assert.assertTrue(points.contains(4096UL - 500UL))
    }

    @Test
    fun snapPointsIncludeEveryDiskAlignedGibBoundary() {
        val points = PartitionRangeMath.snapPoints(
            availableMib = 4096UL,
            firstAligned = 2048UL, // 1 MiB, so disk GiB boundaries are offset by 1023 MiB
            sectorsPerMib = 2048UL,
        )
        org.junit.Assert.assertTrue(points.contains(1023UL))
        org.junit.Assert.assertTrue(points.contains(2047UL))
        org.junit.Assert.assertTrue(points.contains(3071UL))
    }

    @Test
    fun relativeSizePointsKeepTheExactPartitionSize() {
        assertEquals(
            listOf(1500UL, 500UL),
            PartitionRangeMath.sizePointsBelow(anchorMib = 2000UL, sizes = listOf(500UL, 1500UL)),
        )
        assertEquals(
            listOf(1500UL, 3500UL),
            PartitionRangeMath.sizePointsAbove(
                anchorMib = 1000UL,
                maxMib = 3500UL,
                sizes = listOf(500UL, 2500UL, 2600UL),
            ),
        )
        assertEquals(
            emptyList<ULong>(),
            PartitionRangeMath.sizePointsAbove(4000UL, 1000UL, listOf(1UL)),
        )
    }

    @Test
    fun snapWithMagnetsSnapsNearSnapPoint() {
        val snapPoints = listOf(0UL, 1024UL, 2048UL, 4096UL)
        // Position 1020 is within threshold 10f of 1024
        assertEquals(1024UL, PartitionRangeMath.snapWithMagnets(1020f, 4096UL, snapPoints, 10f))
        // Position 1030 is within threshold 10f of 1024
        assertEquals(1024UL, PartitionRangeMath.snapWithMagnets(1030f, 4096UL, snapPoints, 10f))
        // Position 1050 is outside threshold 10f, falls back to snapToMib
        assertEquals(1050UL, PartitionRangeMath.snapWithMagnets(1050f, 4096UL, snapPoints, 10f))
        // Close to boundary 0
        assertEquals(0UL, PartitionRangeMath.snapWithMagnets(5f, 4096UL, snapPoints, 10f))
        // Close to boundary 4096
        assertEquals(4096UL, PartitionRangeMath.snapWithMagnets(4092f, 4096UL, snapPoints, 10f))
    }

    @Test
    fun nearestMagnetIsNullOutsideThreshold() {
        val snapPoints = listOf(0UL, 1024UL, 2048UL, 4096UL)
        assertEquals(1024UL, PartitionRangeMath.nearestMagnet(1030f, 4096UL, snapPoints, 10f))
        assertNull(PartitionRangeMath.nearestMagnet(1050f, 4096UL, snapPoints, 10f))
        assertNull(PartitionRangeMath.nearestMagnet(1020f, 4096UL, snapPoints, 0f))
    }
}
