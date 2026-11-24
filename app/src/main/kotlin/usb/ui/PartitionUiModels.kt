package usb.ui

import usb.fs.FilesystemId
import usb.partition.TableScheme
import kotlin.math.roundToLong

/**
 * Layout the user can initialize. Deliberately excludes HYBRID: an ambiguous MBR+GPT layout
 * is never written, it must be repaired or explicitly initialized over with a scheme choice.
 */
enum class PartitionScheme {
    Mbr,
    Gpt,
}

fun PartitionScheme.toTableScheme(): TableScheme =
    when (this) {
        PartitionScheme.Mbr -> TableScheme.MBR
        PartitionScheme.Gpt -> TableScheme.GPT
    }

fun TableScheme.toPartitionScheme(): PartitionScheme? =
    when (this) {
        TableScheme.MBR -> PartitionScheme.Mbr
        TableScheme.GPT -> PartitionScheme.Gpt
        TableScheme.HYBRID -> null
    }

/** Pinned geometry of one pre-existing partition; a table rewrite must see exactly these. */
data class PartitionRef(
    val startLba: ULong,
    val sectorCount: ULong,
)

/** Boot role the user can assign to a partition; maps to BASIC or EFI on disk. */
enum class PartitionRole {
    Data,
    Efi,
}


/** Exact target captured when a file mutation is requested. */
data class PartitionBinding(
    val device: DeviceIdentity,
    val partitionId: String,
    val startLba: ULong?,
    val sectorCount: ULong?,
)


data class PartitionTab(
    val id: String,
    val name: String,
    /**
     * Conservative write protection: real ESP type, EFI type byte/GUID, or the VTOYEFI
     * name. Independent of the current on-disk type; not a mount mode.
     */
    val readOnly: Boolean,
    /** Real ESP type on disk (GPT ESP GUID or MBR 0xEF), not a name-based guess. */
    val isEfiType: Boolean = false,
    val filesystemId: FilesystemId? = null,
    /** Current filesystem volume label, when the engine can read it. */
    val volumeLabel: String? = null,
    val startLba: ULong? = null,
    val sectorCount: ULong? = null,
    /** Legacy BIOS bootable flag as stored on disk (MBR 0x80 / GPT attribute bit 2). */
    val bootable: Boolean = false,
    /** Synthetic Files-tab volume spanning the whole medium; never a table partition. */
    val wholeDisk: Boolean = false,
) {
    /**
     * Display-only: Ventoy's VTOYEFI role/name is the boot ESP even when the on-disk type
     * GUID is not the ESP GUID and the legacy bootable bit is clear. Does not change
     * [isEfiType], [bootable], write-protection, or mutation policy.
     */
    val isVtoyEfiRole: Boolean
        get() = name.contains("VTOYEFI", ignoreCase = true)

    /** EFI fact shown in the info card / list badge; real type stays [isEfiType]. */
    val displayEfi: Boolean
        get() = isEfiType || isVtoyEfiRole

    /** EFI / system-boot class for strip coloring and layout special counts. */
    val special: Boolean
        get() = displayEfi

    /** No filesystem id and not special — reserved/unknown strip class. */
    val reserved: Boolean
        get() = !special && filesystemId == null

    /** Bootable fact shown in the info card; real flag stays [bootable]. */
    val displayBootable: Boolean
        get() = bootable || isVtoyEfiRole

    val filesystemLabel: String
        get() = when (filesystemId) {
            FilesystemId.FAT -> "FAT"
            FilesystemId.EXFAT -> "exFAT"
            FilesystemId.NTFS -> "NTFS"
            FilesystemId.EXT -> "EXT"
            FilesystemId.ISO -> "ISO9660"
            null -> ""
        }
}

data class FreeRegionUi(val startLba: ULong, val sectorCount: ULong)

/**
 * Partition write state shown in Files. EFI policy remains authoritative; otherwise the
 * badge reflects driver access of the currently mounted handle, or Unknown before measurement.
 */
enum class PartitionWriteAccess {
    /** Application EFI policy blocks mutation; not a driver mount report. */
    ReadOnly,
    /** Filesystem driver mounted handle read-only; physical medium state remains unknown. */
    DriverReadOnly,
    /** Filesystem driver mounted handle read-write; physical medium may still reject writes. */
    ReadWrite,
    Unknown,
}


/** One regular file offered for a raw write; no extension filter is applied. */
data class RawSourceCandidate(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    /** Partition the path belongs to; results never cross a partition change. */
    val partitionId: String,
)

/** Phone import destination: image files and XML templates live in different folders. */

object PartitionRangeMath {
    /** Whole MiB units that fit into [availableSectors]; 0 when [sectorsPerMib] is 0. */
    fun availableMib(availableSectors: ULong, sectorsPerMib: ULong): ULong =
        if (sectorsPerMib == 0UL) 0UL else availableSectors / sectorsPerMib

    /** Partition sizes users commonly aim for, shared by preview and magnets. */
    val standardSizesMib: List<ULong> = listOf(
        256UL, 512UL, 1024UL, 2048UL, 4096UL, 8192UL,
        10240UL, 16384UL, 20480UL, 32768UL, 65536UL, 131072UL,
    )

    /** Start candidates that keep an exact partition [sizeMib] below [anchorMib]. */
    fun sizePointsBelow(anchorMib: ULong, sizes: List<ULong>): List<ULong> =
        sizes.mapNotNull { size ->
            if (size in 1UL until anchorMib) anchorMib - size else null
        }.distinct()

    /** End candidates that keep an exact partition [sizeMib] above [anchorMib]. */
    fun sizePointsAbove(anchorMib: ULong, maxMib: ULong, sizes: List<ULong>): List<ULong> {
        if (anchorMib > maxMib) return emptyList()
        val room = maxMib - anchorMib
        return sizes.mapNotNull { size ->
            if (size in 1UL..room) anchorMib + size else null
        }.distinct()
    }

    /** Smallest multiple of [step] at or above [value]; overflow keeps [value]. */
    fun alignUp(value: ULong, step: ULong): ULong {
        if (step <= 1UL) return value
        val remainder = value % step
        if (remainder == 0UL) return value
        val add = step - remainder
        return if (value > ULong.MAX_VALUE - add) value else value + add
    }

    /** Snap a slider position to a whole MiB, clamped to [0, maxMib]. */
    fun snapToMib(position: Float, maxMib: ULong): ULong {
        if (maxMib == 0UL || position.isNaN()) return 0UL
        val rounded = position.roundToLong()
        if (rounded <= 0L) return 0UL
        val candidate = rounded.toULong()
        return if (candidate > maxMib) maxMib else candidate
    }
    /**
     * Compute candidate magnetic snap points for the slider within [0, availableMib].
     * Includes region boundaries (0, availableMib), fractions (25%, 50%, 75%),
     * standard round/power-of-two sizes, 1 GiB multiples, disk-aligned GiB boundaries,
     * and sizes of existing partitions.
     */
    fun snapPoints(
        availableMib: ULong,
        firstAligned: ULong = 0UL,
        sectorsPerMib: ULong = 0UL,
        otherPartitionSizesMib: List<ULong> = emptyList(),
    ): List<ULong> {
        if (availableMib == 0UL) return listOf(0UL)
        val points = sortedSetOf<ULong>()
        points.add(0UL)
        points.add(availableMib)

        if (availableMib >= 4UL) {
            points.add(availableMib / 4UL)
            points.add(availableMib / 2UL)
            points.add(availableMib / 4UL * 3UL)
        }

        for (sz in standardSizesMib) {
            if (sz < availableMib) {
                points.add(sz)
                points.add(availableMib - sz)
            }
        }

        if (availableMib in 1024UL..1048576UL) {
            val step = if (availableMib > 65536UL) 10240UL else 1024UL
            var m = step
            while (m < availableMib) {
                points.add(m)
                m += step
            }
        }

        if (sectorsPerMib in 1UL..(ULong.MAX_VALUE / 1024UL)) {
            val gibSectors = sectorsPerMib * 1024UL
            val startRemainder = firstAligned % gibSectors
            var nextGibSectors = if (startRemainder == 0UL) gibSectors else gibSectors - startRemainder
            var nextGibMib = nextGibSectors / sectorsPerMib
            while (nextGibMib < availableMib) {
                points.add(nextGibMib)
                if (nextGibSectors > ULong.MAX_VALUE - gibSectors) break
                nextGibSectors += gibSectors
                nextGibMib += 1024UL
            }
        }

        for (pSz in otherPartitionSizesMib) {
            if (pSz in 1UL until availableMib) {
                points.add(pSz)
                points.add(availableMib - pSz)
            }
        }

        return points.toList()
    }

    /**
     * Nearest magnetic point within [thresholdMib], or null when [position] is only
     * being rounded to the next whole MiB.
     */
    fun nearestMagnet(
        position: Float,
        maxMib: ULong,
        snapPoints: List<ULong>,
        thresholdMib: Float = 0f,
    ): ULong? {
        if (maxMib == 0UL || snapPoints.isEmpty() || thresholdMib <= 0f) return null
        var bestPoint: ULong? = null
        var bestDist = Float.MAX_VALUE
        for (pt in snapPoints) {
            if (pt > maxMib) continue
            val dist = kotlin.math.abs(position - pt.toFloat())
            if (dist <= thresholdMib && dist < bestDist) {
                bestDist = dist
                bestPoint = pt
            }
        }
        return bestPoint
    }

    /**
     * Snap [position] to the nearest magnetic snap point in [snapPoints] if within
     * [thresholdMib], otherwise fallback to standard [snapToMib].
     */
    fun snapWithMagnets(
        position: Float,
        maxMib: ULong,
        snapPoints: List<ULong>,
        thresholdMib: Float = 0f,
    ): ULong =
        nearestMagnet(position, maxMib, snapPoints, thresholdMib)
            ?: snapToMib(position, maxMib)

    /**
     * Sector selection for a snapped range, or null for an empty/inverted range or when
     * the computed end would pass [regionEnd]. All arithmetic stays in [ULong].
     */
    fun sectorsFor(
        firstAligned: ULong,
        regionEnd: ULong,
        startMib: ULong,
        endMib: ULong,
        sectorsPerMib: ULong,
    ): Pair<ULong, ULong>? {
        if (sectorsPerMib == 0UL || endMib <= startMib) return null
        val start = firstAligned + startMib * sectorsPerMib
        val count = (endMib - startMib) * sectorsPerMib
        if (start > regionEnd || count > regionEnd - start) return null
        return start to count
    }
}

/** Normalize slash framing only: names keep spaces exactly. Root is "/". */
