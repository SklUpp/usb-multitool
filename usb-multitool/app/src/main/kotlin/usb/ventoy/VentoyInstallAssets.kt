package usb.ventoy

/**
 * Resolves the exact payload named in a destructive confirmation. There is no fallback:
 * a downloaded snapshot must still match the stored provenance and pin, and a bundled
 * snapshot must load exactly the bundled release that was confirmed. Any mismatch aborts
 * before the caller can write a disk sector.
 */
object VentoyInstallAssets {
    fun resolve(
        source: VentoyReleaseSource?,
        loadBundled: () -> VentoyAssets,
        loadStored: () -> VentoyReleaseStore.StoredRelease?,
    ): VentoyAssets {
        val confirmed = source
            ?: throw VentoyAssetsMissingException(
                "The confirmation did not snapshot a Ventoy release; re-open the confirmation",
            )
        val assets = when (confirmed) {
            is VentoyReleaseSource.Bundled -> loadBundled()
            is VentoyReleaseSource.Downloaded -> {
                val stored = loadStored()
                    ?: throw VentoyAssetsMissingException(
                        "Confirmed Ventoy ${confirmed.release} is no longer downloaded; " +
                            "re-open the confirmation or download it again",
                    )
                VentoyReleaseStore.requireMatches(stored, confirmed.provenance).assets
            }
        }
        VentoyDownloader.requirePinned(assets)
        if (assets.release != confirmed.release) {
            throw VentoyAssetsMissingException(
                "Confirmed Ventoy ${confirmed.release}, but the available payload is " +
                    "${assets.release}; refusing to write a different release",
            )
        }
        return assets
    }
}
