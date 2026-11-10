package usb.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockDevice
import usb.partition.RawWriteRange
import usb.testutil.RecordingDisk

class RangeProbeTest {
    private fun diskWithData(sector: ULong, byte: Byte = 0x41, sectors: ULong = 100UL) =
        RecordingDisk(sectorCount = sectors, sectorSize = 512).apply {
            bytes[sector] = ByteArray(sectorSize) { byte }
        }

    @Test fun allZeroSamplesReportNoDataInSamplesOnly() {
        val disk = RecordingDisk(sectorCount = 100UL, sectorSize = 512)
        val result = RangeProbe.sample(disk, RawWriteRange(0UL, 100UL))
        assertEquals(RangeProbeFinding.NoDataInSamples, result.finding)
        assertTrue(result.sampledSectors.size <= RangeProbe.MAX_SAMPLES)
        assertEquals(0UL, result.sampledSectors.first())
        assertEquals(99UL, result.sampledSectors.last())
    }

    @Test fun nonZeroSampledSectorReportsDataPresent() {
        val disk = diskWithData(sector = 57UL)
        val result = RangeProbe.sample(disk, RawWriteRange(0UL, 100UL))
        assertEquals(RangeProbeFinding.DataPresent, result.finding)
    }

    @Test fun samplerIsDeterministicAndCoversFirstAndLast() {
        val range = RawWriteRange(1000UL, 1000UL)
        val first = RangeProbe.sampleLbas(range)
        val second = RangeProbe.sampleLbas(range)
        assertEquals(first, second)
        assertEquals(1000UL, first.first())
        assertEquals(1999UL, first.last())
        assertEquals(first.distinct().size, first.size)
        assertEquals(RangeProbe.MAX_SAMPLES, first.size)
    }

    @Test fun singleSectorRangeSamplesThatSector() {
        assertEquals(listOf(7UL), RangeProbe.sampleLbas(RawWriteRange(7UL, 1UL)))
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val result = RangeProbe.sample(disk, RawWriteRange(7UL, 1UL))
        assertEquals(listOf(7UL), result.sampledSectors)
        assertEquals(RangeProbeFinding.NoDataInSamples, result.finding)
    }

    @Test fun rangeOutsideTheDiskIsUnknownNotZeroData() {
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val result = RangeProbe.sample(disk, RawWriteRange(8UL, 1UL))
        assertEquals(RangeProbeFinding.Unknown, result.finding)
        assertTrue(result.sampledSectors.isEmpty())
        assertTrue(disk.readCount == 0L)
    }

    @Test fun readFailureReportsUnknownWithoutContent() {
        val disk =
            object : RecordingDisk(sectorCount = 16UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: java.nio.ByteBuffer) {
                    throw java.io.IOException("USB stall")
                }
            }
        val result = RangeProbe.sample(disk, RawWriteRange(0UL, 16UL))
        assertEquals(RangeProbeFinding.Unknown, result.finding)
        assertTrue(result.detail!!.contains("IOException"))
        assertTrue(result.sampledSectors.isEmpty())
    }

    @Test fun matchesRequiresSameAttachmentGeometryAndRange() {
        val disk = RecordingDisk(sectorCount = 16UL, sectorSize = 512)
        val attachment = RangeProbeAttachment("LABEL#SERIAL", attachGeneration = 4L)
        val range = RawWriteRange(0UL, 16UL)
        val result = RangeProbe.sample(disk, range, attachment = attachment, requestId = 42L)
        assertEquals(42L, result.requestId)
        assertTrue(result.matches(range, 16UL, 512, attachment))
        assertFalse(result.matches(RawWriteRange(0UL, 8UL), 16UL, 512, attachment))
        assertFalse(result.matches(range, 32UL, 512, attachment))
        assertFalse(result.matches(range, 16UL, 4096, attachment))
        // Same-model stick re-plug without a serial must not reuse the probe.
        assertFalse(result.matches(range, 16UL, 512, attachment.copy(attachGeneration = 5L)))
        assertFalse(result.matches(range, 16UL, 512, null))
    }

    @Test fun samplerMatchesDiskGeometryNotRequestedDraft() {
        val disk = RecordingDisk(sectorCount = 64UL, sectorSize = 4096)
        val result = RangeProbe.sample(disk, RawWriteRange(0UL, 8UL))
        assertEquals(64UL, result.diskSectorCount)
        assertEquals(4096, result.sectorSize)
        assertFalse(result.matches(RawWriteRange(0UL, 8UL), 64UL, 512, null))
    }

    @Test fun probeIsAPureReaderAndNeverWrites() {
        val disk = RecordingDisk(sectorCount = 64UL, sectorSize = 512)
        RangeProbe.sample(disk, RawWriteRange(0UL, 64UL))
        assertEquals(0L, disk.writeCount)
    }

    @Test fun blockDeviceTypeIsUsedWithoutContentLeak() {
        // LBA 0 is always sampled (first sector of the range); odd mid LBAs may be skipped.
        val disk: BlockDevice = diskWithData(sector = 0UL)
        val result = RangeProbe.sample(disk, RawWriteRange(0UL, 64UL))
        assertEquals(RangeProbeFinding.DataPresent, result.finding)
        // Result exposes presence and sector numbers only; there is no byte array field.
        val fields = result.javaClass.declaredFields.map { it.type }
        assertFalse(fields.contains(ByteArray::class.java))
    }
}
