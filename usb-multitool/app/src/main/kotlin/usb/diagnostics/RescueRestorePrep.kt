package usb.diagnostics

/**
 * Builds the in-memory RESTORE map used by [Rescue.restore] from a loaded mapfile.
 */
object RescueRestorePrep {
    /**
     * Resume is allowed only for the same destination: the checkpoint's disk identity
     * must match the live stick (and the live stick must expose a serial, otherwise a
     * label alone collides across same-model sticks). Geometry is re-checked by
     * [Rescue.restore]. Identity is only the admission gate — every inherited COPIED
     * range is still read back from the disk and compared with the image before it may
     * be skipped, so whatever the selected image currently provides is compared and
     * ranges that differ are rewritten.
     *
     * @param liveHasSerial when false, never resume — label-only identity collides across sticks.
     * @throws IllegalStateException on partial COPY with NON_TRIED (would write zeros).
     */
    fun prepare(
        loaded: RescueMap,
        liveIdentity: String,
        sectorSize: Int,
        liveHasSerial: Boolean,
    ): Pair<RescueMap, String?> {
        val nonTriedSectors = loaded.nonTried.sumOf { it.last - it.first + 1UL }
        val failedSectors = loaded.failed.sumOf { it.last - it.first + 1UL }

        if (loaded.kind == MapKind.COPY && nonTriedSectors > 0UL) {
            throw IllegalStateException(
                "refusing partial COPY map: $nonTriedSectors sector(s) were never read " +
                    "and would be written as zeros. Finish the backup (or pick a complete map) first.",
            )
        }

        val restoreMap =
            RescueMap.forRestore(
                loaded.sectorCount,
                sectorSize = sectorSize,
                identity = liveIdentity,
            )

        var note: String? = null
        when (loaded.kind) {
            MapKind.COPY -> {
                if (failedSectors > 0UL) {
                    note =
                        "COPY map has $failedSectors FAILED sector(s); those restore as zeros from the image."
                }
            }
            MapKind.RESTORE -> {
                val identityOk =
                    liveHasSerial &&
                        loaded.identity != null &&
                        loaded.identity == liveIdentity
                if (identityOk) {
                    for (r in loaded.copied) {
                        restoreMap.markCopied(r.first, r.last - r.first + 1UL)
                    }
                    for (r in loaded.failed) {
                        restoreMap.markFailed(r.first, r.last - r.first + 1UL)
                    }
                    note =
                        "resuming RESTORE map (disk identity matched); every copied range is re-read " +
                            "from the disk and rewritten unless it matches the selected image."
                } else {
                    note =
                        when {
                            !liveHasSerial ->
                                "live disk has no USB serial; rewriting all sectors (no resume)."
                            loaded.identity == null ->
                                "RESTORE map has no identity; rewriting all sectors (no resume)."
                            else ->
                                "map identity '${loaded.identity}' differs from live '$liveIdentity'; " +
                                    "rewriting all sectors on the attached disk (no resume)."
                        }
                    if (failedSectors > 0UL || nonTriedSectors > 0UL) {
                        note +=
                            " Map still lists failed=$failedSectors nonTried=$nonTriedSectors " +
                                "(FAILED image gaps are zeros)."
                    }
                }
            }
        }
        return restoreMap to note
    }
}
