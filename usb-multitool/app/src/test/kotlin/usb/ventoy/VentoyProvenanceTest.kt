package usb.ventoy

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent pin, exact-snapshot resolution and no-fallback install contract. */
class VentoyProvenanceTest {
    private fun assetDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val current = dir ?: return@repeat
            for (candidate in listOf(
                File(current, "usb-multitool/app/src/main/assets/ventoy"),
                File(current, "app/src/main/assets/ventoy"),
            )) {
                if (candidate.isDirectory) return candidate
            }
            dir = current.parentFile
        }
        throw AssertionError("cannot locate assets/ventoy from ${File("").absolutePath}")
    }

    private val realAssets: VentoyAssets by lazy {
        VentoyAssets.loadFromDirectory(assetDir(), strict = true)
    }

    @Test
    fun unknownReleaseHasNoPinnedSource() {
        assertNull(VentoyDownloader.pinnedSourceOrNull("9.9.9"))
        assertNotNull(VentoyDownloader.pinnedSourceOrNull(VentoyAssets.PINNED_RELEASE))
    }

    @Test
    fun downloadUsesPinnedUpstreamOrFallsBackToNewestPin() {
        assertEquals(
            VentoyAssets.RELEASE_1_1_05,
            VentoyDownloader.pinnedDownloadSource("1.1.05").release,
        )
        // Upstream latest is not in RELEASES: still offer the newest independently
        // pinned release instead of a dead button or an API-only hash.
        assertNull(VentoyDownloader.pinnedSourceOrNull("1.1.21"))
        assertEquals(
            VentoyAssets.PINNED_RELEASE,
            VentoyDownloader.pinnedDownloadSource("1.1.21").release,
        )
        assertEquals(
            VentoyAssets.PINNED_RELEASE,
            VentoyDownloader.pinnedDownloadSource(null).release,
        )
        assertEquals(
            VentoyAssets.PINNED_RELEASE,
            VentoyDownloader.newestPinnedSource().release,
        )
    }

    @Test
    fun syntheticAssetsWithAClaimedReleaseAreRejectedByTheIndependentPin() {
        val failure = assertThrows(VentoyAssetsMissingException::class.java) {
            VentoyDownloader.requirePinned(syntheticVentoyAssets(release = VentoyAssets.PINNED_RELEASE))
        }
        assertTrue(failure.message!!.contains("independently pinned"))
    }

    @Test
    fun realBundledAssetsMatchTheIndependentPin() {
        val source = VentoyDownloader.requirePinned(realAssets)
        assertEquals(VentoyAssets.PINNED_RELEASE, source.release)
    }

    @Test
    fun downloadedSnapshotResolvesOnlyTheMatchingStoredProvenance() {
        val root = Files.createTempDirectory("ventoy-install").toFile()
        val source = VentoyDownloader.pinnedSourceOrNull(realAssets.release)!!
        val stored = VentoyReleaseStore.save(
            root,
            VentoyDownloader.VentoyReleaseDownload(
                realAssets,
                source.archiveSha256,
                source.urls.first(),
            ),
        )
        val confirmed = VentoyReleaseSource.Downloaded(
            VentoyReleaseProvenance(
                release = stored.assets.release,
                archiveSha256 = stored.archiveSha256,
                sourceUrl = stored.sourceUrl,
            ),
        )
        val resolved = VentoyInstallAssets.resolve(
            source = confirmed,
            loadBundled = { throw AssertionError("must not fall back to bundled") },
            loadStored = { VentoyReleaseStore.load(root) },
        )
        assertEquals(stored.assets.release, resolved.release)

        val changed = VentoyReleaseSource.Downloaded(
            confirmed.provenance.copy(archiveSha256 = "0".repeat(64)),
        )
        assertThrows(VentoyAssetsMissingException::class.java) {
            VentoyInstallAssets.resolve(
                source = changed,
                loadBundled = { throw AssertionError("must not fall back to bundled") },
                loadStored = { VentoyReleaseStore.load(root) },
            )
        }
    }

    @Test
    fun missingDownloadedSnapshotAbortsInsteadOfUsingBundled() {
        assertThrows(VentoyAssetsMissingException::class.java) {
            VentoyInstallAssets.resolve(
                source = VentoyReleaseSource.Downloaded(
                    VentoyReleaseProvenance(
                        release = "1.1.18",
                        archiveSha256 = "a".repeat(64),
                        sourceUrl = "https://example.invalid/ventoy.tar.gz",
                    ),
                ),
                loadBundled = { throw AssertionError("must not fall back to bundled") },
                loadStored = { null },
            )
        }
    }

    @Test
    fun bundledSnapshotMustMatchTheLoadedBundledRelease() {
        val resolved = VentoyInstallAssets.resolve(
            source = VentoyReleaseSource.Bundled(realAssets.release),
            loadBundled = { realAssets },
            loadStored = { throw AssertionError("not a downloaded snapshot") },
        )
        assertEquals(realAssets.release, resolved.release)

        assertThrows(VentoyAssetsMissingException::class.java) {
            VentoyInstallAssets.resolve(
                source = VentoyReleaseSource.Bundled("9.9.9"),
                loadBundled = { realAssets },
                loadStored = { null },
            )
        }
    }
}
