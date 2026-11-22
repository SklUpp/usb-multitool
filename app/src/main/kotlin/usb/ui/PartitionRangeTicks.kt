package usb.ui

/**
 * Visible whole-MiB boundary marks for the create-partition slider.
 *
 * The slider axis stays a uniform MiB scale of the free region (convenience first).
 * Marks never remap that axis onto proportional disk LBA widths — DiskMapBar /
 * RawPlanMap keep LBA geometry separately.
 */
internal object PartitionRangeTicks {
    /**
     * Whole-MiB positions including 0 and [maxMib]. For large ranges, interior marks
     * are sparse so labels stay readable; the returned values stay exact ULong MiB
     * indices and never exceed [maxMib].
     */
    fun marks(maxMib: ULong, maxInterior: Int = 7): List<ULong> {
        require(maxInterior >= 1) { "maxInterior must be positive" }
        if (maxMib == 0UL) return listOf(0UL)
        if (maxMib <= maxInterior.toULong() + 1UL) {
            return buildList {
                var i = 0UL
                while (i <= maxMib) {
                    add(i)
                    i += 1UL
                }
            }
        }
        val step = ((maxMib + maxInterior.toULong() - 1UL) / maxInterior.toULong())
            .coerceAtLeast(1UL)
        val out = ArrayList<ULong>(maxInterior + 2)
        out.add(0UL)
        var v = step
        while (v < maxMib) {
            out.add(v)
            v += step
        }
        if (out.last() != maxMib) out.add(maxMib)
        return out
    }
}
