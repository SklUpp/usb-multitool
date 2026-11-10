package usb.image

import java.nio.ByteBuffer
import usb.device.BlockDevice
import usb.partition.RawWriteRange

/** What the sampled sectors of a planned range contain. Never claims the whole range. */
enum class RangeProbeFinding {
    /** At least one sampled sector holds a non-zero byte. */
    DataPresent,

    /** Every sampled sector was all zeroes; the rest of the range was not read. */
    NoDataInSamples,

    /** No sample could be classified (read error, empty range, unusable geometry). */
    Unknown,
}

/**
 * Presence-only result. Sector contents are deliberately not exposed: the probe answers
 * "is something there", not "what is there", so no file names or secrets leak.
 */
data class RangeProbeResult(
    val finding: RangeProbeFinding,
    val range: RawWriteRange,
    val diskSectorCount: ULong,
    val sectorSize: Int,
    val sampledSectors: List<ULong>,
    val attachment: RangeProbeAttachment? = null,
    /** Echo of the caller's probe request; stale ids must be discarded by the host. */
    val requestId: Long = 0L,
    /** Diagnostic text for [RangeProbeFinding.Unknown]; never sector content. */
    val detail: String? = null,
) {
    /**
     * True when [this] still describes [otherRange] on the same live attachment and
     * geometry. A caller must discard a probe whose attachment, geometry or range no
     * longer matches the draft.
     */
    fun matches(
        otherRange: RawWriteRange,
        otherDiskSectorCount: ULong,
        otherSectorSize: Int,
        otherAttachment: RangeProbeAttachment?,
    ): Boolean =
        range == otherRange &&
            diskSectorCount == otherDiskSectorCount &&
            sectorSize == otherSectorSize &&
            attachment == otherAttachment
}

/** Attachment the probe was bound to; a re-plug without a serial must not reuse it. */
data class RangeProbeAttachment(
    val identity: String,
    val attachGeneration: Long,
)

/**
 * Light, deterministic sampling of a planned raw range before confirmation.
 *
 * Samples at most [maxSamples] distinct sectors spread over the range (first and last
 * always included), reads exactly one sector per sample and only checks for non-zero
 * bytes. It never reads the whole range, never returns content and never guesses a
 * filesystem or image name from the bytes.
 */
object RangeProbe {
    const val MAX_SAMPLES = 32

    fun sample(
        disk: BlockDevice,
        range: RawWriteRange,
        maxSamples: Int = MAX_SAMPLES,
        attachment: RangeProbeAttachment? = null,
        requestId: Long = 0L,
    ): RangeProbeResult {
        val sectorSize = disk.sectorSize
        if (range.startLba >= disk.sectorCount ||
            range.sectorCount > disk.sectorCount - range.startLba
        ) {
            return RangeProbeResult(
                finding = RangeProbeFinding.Unknown,
                range = range,
                diskSectorCount = disk.sectorCount,
                sectorSize = sectorSize,
                sampledSectors = emptyList(),
                attachment = attachment,
                requestId = requestId,
                detail = "range lies outside the live disk geometry",
            )
        }
        val sampled = sampleLbas(range, maxSamples)
        if (sampled.isEmpty()) {
            return RangeProbeResult(
                finding = RangeProbeFinding.Unknown,
                range = range,
                diskSectorCount = disk.sectorCount,
                sectorSize = sectorSize,
                sampledSectors = emptyList(),
                attachment = attachment,
                requestId = requestId,
                detail = "range is empty or wraps past the end of the sector space",
            )
        }
        val buffer = ByteBuffer.allocate(sectorSize)
        val classified = mutableListOf<ULong>()
        var sawData = false
        for (lba in sampled) {
            buffer.clear()
            try {
                disk.read(lba, buffer)
            } catch (e: Exception) {
                return RangeProbeResult(
                    finding = RangeProbeFinding.Unknown,
                    range = range,
                    diskSectorCount = disk.sectorCount,
                    sectorSize = sectorSize,
                    sampledSectors = classified,
                    attachment = attachment,
                    requestId = requestId,
                    detail = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
            classified += lba
            val data = buffer.array()
            for (byte in data) {
                if (byte != 0.toByte()) {
                    sawData = true
                    break
                }
            }
        }
        return RangeProbeResult(
            finding = if (sawData) RangeProbeFinding.DataPresent else RangeProbeFinding.NoDataInSamples,
            range = range,
            diskSectorCount = disk.sectorCount,
            sectorSize = sectorSize,
            sampledSectors = classified,
            attachment = attachment,
            requestId = requestId,
        )
    }

    /** Distinct, ordered sample LBAs inside [range]; first and last sectors included. */
    fun sampleLbas(range: RawWriteRange, maxSamples: Int = MAX_SAMPLES): List<ULong> {
        if (range.sectorCount == 0UL) return emptyList()
        if (range.startLba > ULong.MAX_VALUE - (range.sectorCount - 1UL)) return emptyList()
        val wanted = maxOf(1UL, maxSamples.toULong())
        val count = minOf(range.sectorCount, wanted)
        if (count == 1UL) return listOf(range.startLba)
        val lastOffset = range.sectorCount - 1UL
        val step = lastOffset / (count - 1UL)
        if (step == 0UL) {
            return (0UL until count).map { range.startLba + it }
        }
        val result = mutableListOf<ULong>()
        val lastIndex = count.toInt() - 1
        for (i in 0..lastIndex) {
            val offset = if (i == lastIndex) lastOffset else minOf(i.toULong() * step, lastOffset)
            val lba = range.startLba + offset
            if (result.lastOrNull() != lba) result += lba
        }
        return result
    }
}
