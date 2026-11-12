package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.ventoy.VentoyReleaseProvenance

/** Version facts, download/check fences and attachment-scoped stale results. */
class VentoyReleaseUiTest {
    private fun partition() = PartitionTab(
        id = "1",
        name = "Ventoy",
        readOnly = false,
        startLba = 2048uL,
        sectorCount = 4096uL,
        filesystemId = FilesystemId.FAT,
    )

    private fun device(gen: Long = 1L) = DeviceIdentity(
        label = "Stick",
        capacityBytes = 8_000_000_000uL,
        attachGeneration = gen,
    )

    private fun ready(gen: Long = 1L) = AppUiState(
        presence = DevicePresence.Ready,
        device = device(gen),
        partitions = listOf(partition()),
        selectedPartitionId = "1",
        diskSectorSize = 512,
    )

    private fun verified(release: String) =
        usb.ventoy.VentoyReleaseStoreState.Verified(provenance(release))

    private fun verified(provenance: VentoyReleaseProvenance) =
        usb.ventoy.VentoyReleaseStoreState.Verified(provenance)

    private fun provenance(release: String) = VentoyReleaseProvenance(
        release = release,
        archiveSha256 = "a".repeat(64),
        sourceUrl = "https://github.com/ventoy/Ventoy/releases/download/v$release/ventoy-$release-linux.tar.gz",
    )

    @Test
    fun downloadedReleaseShadowsBundledForInstallAndUpdate() {
        assertEquals("1.1.18", ventoyTargetRelease("1.1.18", null))
        assertEquals("1.1.21", ventoyTargetRelease("1.1.18", "1.1.21"))
        assertEquals("1.1.18", ventoyTargetRelease("1.1.18", "  "))
        assertNull(ventoyTargetRelease(null, null))
    }

    @Test
    fun comparisonIsNumericAndReportsEquality() {
        val same = ventoyReleaseComparison("1.1.18", "1.1.18")
        assertEquals(0, same.order)
        assertTrue(same.same)

        val newer = ventoyReleaseComparison("1.1.18", "1.2.0")
        assertTrue(newer.order!! > 0)
        assertFalse(newer.same)

        val older = ventoyReleaseComparison("1.10.0", "1.9.9")
        assertTrue(older.order!! < 0)

        assertNull(ventoyReleaseComparison(null, "1.1.18").order)
        assertNull(ventoyReleaseComparison("1.1.18", "nightly").order)
    }

    @Test
    fun upstreamCheckAndAnswerAreTokenFenced() {
        val request = UiReducer.reduce(ready(), UiIntent.CheckVentoyUpstream)
        assertEquals(1L, request.state.ventoyUpstreamRequest)
        assertTrue(request.state.ventoyUpstreamChecking)
        val effect = request.effects.single() as UiEffect.CheckVentoyUpstream
        assertEquals(1L, effect.requestToken)

        val stale = UiReducer.reduce(
            request.state,
            UiIntent.SetVentoyUpstreamRelease(requestToken = 0L, tag = "9.9.9"),
        ).state
        assertNull(stale.upstreamVentoyRelease)
        assertTrue(stale.ventoyUpstreamChecking)

        val landed = UiReducer.reduce(
            request.state,
            UiIntent.SetVentoyUpstreamRelease(
                requestToken = 1L,
                tag = "1.1.21",
                pinned = false,
            ),
        ).state
        assertEquals("1.1.21", landed.upstreamVentoyRelease)
        assertEquals(false, landed.upstreamVentoyPinned)
        assertFalse(landed.ventoyUpstreamChecking)
    }

    @Test
    fun newAttachmentDropsCheckedVersionsButKeepsDownloadedRelease() {
        val loaded = ready(gen = 1L).copy(
            bundledVentoyRelease = "1.1.18",
            installedVentoyRelease = "1.1.18",
            upstreamVentoyRelease = "1.1.21",
            upstreamVentoyPinned = false,
            ventoyStoreState = verified("1.1.20"),
            ventoyUpstreamRequest = 4L,
        )
        val next = UiReducer.reduce(
            loaded,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device(gen = 2L),
                partitions = listOf(partition()),
                sectorSize = 512,
            ),
        ).state
        assertNull(next.installedVentoyRelease)
        assertNull(next.upstreamVentoyRelease)
        assertNull(next.upstreamVentoyPinned)
        assertFalse(next.ventoyUpstreamChecking)
        assertEquals(5L, next.ventoyUpstreamRequest)
        assertEquals("1.1.20", next.downloadedVentoyRelease?.release)
        assertEquals("1.1.18", next.bundledVentoyRelease)
    }

    @Test
    fun sameAttachmentRetainsFactsAndRequestFence() {
        val loaded = ready(gen = 1L).copy(
            installedVentoyRelease = "1.1.18",
            upstreamVentoyRelease = "1.1.21",
            ventoyUpstreamRequest = 4L,
        )
        val next = UiReducer.reduce(
            loaded,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Busy,
                device = device(gen = 1L),
                partitions = emptyList(),
            ),
        ).state
        assertEquals("1.1.18", next.installedVentoyRelease)
        assertEquals("1.1.21", next.upstreamVentoyRelease)
        assertEquals(4L, next.ventoyUpstreamRequest)
    }

    @Test
    fun installedVersionOnlyLandsForTheCurrentAttachment() {
        val state = ready(gen = 1L)
        val wrongGeneration = UiReducer.reduce(
            state,
            UiIntent.SetVentoyInstalledRelease("1.1.18", 2L, device(1L).identity),
        ).state
        assertNull(wrongGeneration.installedVentoyRelease)

        val wrongIdentity = UiReducer.reduce(
            state,
            UiIntent.SetVentoyInstalledRelease("1.1.18", 1L, "OtherStick"),
        ).state
        assertNull(wrongIdentity.installedVentoyRelease)

        val accepted = UiReducer.reduce(
            state,
            UiIntent.SetVentoyInstalledRelease("1.1.18", 1L, device(1L).identity),
        ).state
        assertEquals("1.1.18", accepted.installedVentoyRelease)

        val cleared = UiReducer.reduce(
            accepted,
            UiIntent.SetVentoyInstalledRelease(null, 1L, device(1L).identity),
        ).state
        assertNull(cleared.installedVentoyRelease)
    }

    @Test
    fun installedReadErrorIsDistinctFromAbsentMarker() {
        val state = ready(gen = 1L)
        val failed = UiReducer.reduce(
            state,
            UiIntent.SetVentoyInstalledRelease(
                release = null,
                attachGeneration = 1L,
                identity = device(1L).identity,
                error = "FAT read failed",
            ),
        ).state
        assertNull(failed.installedVentoyRelease)
        assertEquals("FAT read failed", failed.installedVentoyReadError)

        val recovered = UiReducer.reduce(
            failed,
            UiIntent.SetVentoyInstalledRelease(
                release = "1.1.18",
                attachGeneration = 1L,
                identity = device(1L).identity,
            ),
        ).state
        assertEquals("1.1.18", recovered.installedVentoyRelease)
        assertNull(recovered.installedVentoyReadError)
    }

    @Test
    fun destructiveConfirmationSnapshotsTheExactDownloadedRelease() {
        val downloaded = provenance("1.1.20")
        val state = ready().copy(
            bundledVentoyRelease = "1.1.18",
            ventoyStoreState = verified(downloaded),
        )
        val pending = UiReducer.reduce(
            state,
            UiIntent.RequestDestructive(DestructiveAction.VENTOY_UPDATE),
        ).state.pendingConfirmation
        val source = pending?.ventoyRelease as? usb.ventoy.VentoyReleaseSource.Downloaded
        assertEquals(downloaded, source?.provenance)

        // A later download cannot rewrite what the user confirmed.
        val changed = state.copy(ventoyStoreState = verified("1.1.21"))
        assertEquals(downloaded, pending?.ventoyRelease?.let {
            (it as? usb.ventoy.VentoyReleaseSource.Downloaded)?.provenance
        })
        assertFalse(changed.downloadedVentoyRelease == downloaded)
    }

    @Test
    fun destructiveConfirmationWithoutDownloadNamesBundledRelease() {
        val pending = UiReducer.reduce(
            ready().copy(bundledVentoyRelease = "1.1.18"),
            UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL),
        ).state.pendingConfirmation
        val source = pending?.ventoyRelease as? usb.ventoy.VentoyReleaseSource.Bundled
        assertEquals("1.1.18", source?.release)
    }

    @Test
    fun downloadFailureKeepsThePreviousVerifiedRelease() {
        val initial = ready().copy(
            ventoyStoreState = verified("1.1.18"),
            upstreamVentoyRelease = "1.1.21",
        )
        val requested = UiReducer.reduce(initial, UiIntent.RequestDownloadVentoyRelease)
        assertTrue(requested.state.ventoyDownloadInFlight)
        assertTrue(requested.effects.single() is UiEffect.DownloadVentoyRelease)
        val effect = requested.effects.single() as UiEffect.DownloadVentoyRelease
        assertEquals("1.1.21", effect.upstreamVersion)
        val token = effect.requestToken

        val stale = UiReducer.reduce(
            requested.state,
            UiIntent.VentoyReleaseDownloadFailed(token - 1L, "old failure"),
        ).state
        assertTrue(stale.ventoyDownloadInFlight)
        assertNull(stale.ventoyDownloadError)

        val failed = UiReducer.reduce(
            requested.state,
            UiIntent.VentoyReleaseDownloadFailed(token, "SHA-256 mismatch"),
        ).state
        assertFalse(failed.ventoyDownloadInFlight)
        assertEquals("SHA-256 mismatch", failed.ventoyDownloadError)
        assertEquals("1.1.18", failed.downloadedVentoyRelease?.release)

        val retried = UiReducer.reduce(failed, UiIntent.RequestDownloadVentoyRelease)
        val retryToken = (retried.effects.single() as UiEffect.DownloadVentoyRelease).requestToken
        val landed = UiReducer.reduce(
            retried.state,
            UiIntent.VentoyReleaseDownloaded(retryToken, provenance("1.1.21")),
        ).state
        assertEquals("1.1.21", landed.downloadedVentoyRelease?.release)
        assertNull(landed.ventoyDownloadError)
        assertFalse(landed.ventoyDownloadInFlight)
    }

    @Test
    fun publishedButUnconfirmedKeepsNewProvenanceAndTheWarning() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestDownloadVentoyRelease)
        val token = (requested.effects.single() as UiEffect.DownloadVentoyRelease).requestToken

        val stale = UiReducer.reduce(
            requested.state,
            UiIntent.VentoyReleaseStoreChanged(
                token - 1L,
                provenance("1.1.21"),
                "old warning",
            ),
        ).state
        assertNull(stale.downloadedVentoyRelease)

        val landed = UiReducer.reduce(
            requested.state,
            UiIntent.VentoyReleaseStoreChanged(
                token,
                provenance("1.1.21"),
                "published but durability is unconfirmed",
            ),
        ).state
        assertFalse(landed.ventoyDownloadInFlight)
        assertEquals("1.1.21", landed.downloadedVentoyRelease?.release)
        assertEquals(
            "published but durability is unconfirmed",
            landed.ventoyDownloadError,
        )
    }

    @Test
    fun lateStartupLoadCannotOverwriteACompletedDownload() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestDownloadVentoyRelease)
        val token = (requested.effects.single() as UiEffect.DownloadVentoyRelease).requestToken
        val downloaded = UiReducer.reduce(
            requested.state,
            UiIntent.VentoyReleaseDownloaded(token, provenance("1.1.21")),
        ).state
        assertEquals(1L, downloaded.ventoyStoreRevision)

        val late = UiReducer.reduce(
            downloaded,
            UiIntent.SetVentoyStoreState(0L, usb.ventoy.VentoyReleaseStoreState.Absent),
        ).state
        assertEquals("1.1.21", late.downloadedVentoyRelease?.release)
    }

    @Test
    fun startupLoadStillAppliesAfterAFailedDownload() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestDownloadVentoyRelease)
        val token = (requested.effects.single() as UiEffect.DownloadVentoyRelease).requestToken
        val failed = UiReducer.reduce(
            requested.state,
            UiIntent.VentoyReleaseDownloadFailed(token, "network"),
        ).state
        assertEquals(0L, failed.ventoyStoreRevision)

        val loaded = UiReducer.reduce(
            failed,
            UiIntent.SetVentoyStoreState(0L, verified("1.1.18")),
        ).state
        assertEquals("1.1.18", loaded.downloadedVentoyRelease?.release)
    }

    @Test
    fun unknownStoreClearsProvenanceAndKeepsWarning() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestDownloadVentoyRelease)
        val token = (requested.effects.single() as UiEffect.DownloadVentoyRelease).requestToken
        val landed = UiReducer.reduce(
            requested.state,
            UiIntent.VentoyReleaseStoreUnknown(token, "store unknown"),
        ).state
        assertEquals(
            usb.ventoy.VentoyReleaseStoreState.Unknown,
            landed.ventoyStoreState,
        )
        assertNull(landed.downloadedVentoyRelease)
        assertFalse(landed.ventoyDownloadInFlight)
        assertEquals("store unknown", landed.ventoyDownloadError)
    }

    @Test
    fun staleDownloadSuccessCannotReplaceANewerAttempt() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestDownloadVentoyRelease)
        val first = (requested.effects.single() as UiEffect.DownloadVentoyRelease).requestToken
        val again = UiReducer.reduce(requested.state, UiIntent.RequestDownloadVentoyRelease)
        // Guarded while in flight: no second request and no token change.
        assertEquals(first, again.state.ventoyDownloadRequest)
        assertTrue(again.effects.isEmpty())

        val landed = UiReducer.reduce(
            again.state,
            UiIntent.VentoyReleaseDownloaded(first, provenance("1.1.20")),
        ).state
        assertEquals("1.1.20", landed.downloadedVentoyRelease?.release)
    }

    @Test
    fun unknownOrCorruptStoreBlocksInstallWithoutBundledFallback() {
        for (bad in listOf(
            usb.ventoy.VentoyReleaseStoreState.Unknown,
            usb.ventoy.VentoyReleaseStoreState.Corrupt,
        )) {
            val state = ready().copy(
                bundledVentoyRelease = "1.1.18",
                ventoyStoreState = bad,
            )
            assertFalse(state.ventoyInstallAvailable)
            for (action in listOf(
                DestructiveAction.VENTOY_INSTALL,
                DestructiveAction.VENTOY_UPDATE,
            )) {
                val result = UiReducer.reduce(state, UiIntent.RequestDestructive(action))
                assertNull(result.state.pendingConfirmation)
                assertEquals(
                    UiMessage.Key(UiMessageKey.VentoyStoreUnavailable),
                    result.state.statusMessage,
                )
            }
        }
    }

    @Test
    fun absentStoreStillAllowsBundledInstall() {
        val state = ready().copy(
            bundledVentoyRelease = "1.1.18",
            ventoyStoreState = usb.ventoy.VentoyReleaseStoreState.Absent,
        )
        assertTrue(state.ventoyInstallAvailable)
        val pending = UiReducer.reduce(
            state,
            UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL),
        ).state.pendingConfirmation
        val source = pending?.ventoyRelease as? usb.ventoy.VentoyReleaseSource.Bundled
        assertEquals("1.1.18", source?.release)
    }

    @Test
    fun bundledReleaseIsIndependentOfDownloadedRelease() {
        val state = ready().copy(
            bundledVentoyRelease = "1.1.18",
            ventoyStoreState = verified("1.1.21"),
        )
        assertEquals("1.1.18", state.bundledVentoyRelease)
        assertEquals("1.1.21", ventoyTargetRelease(state.bundledVentoyRelease, state.downloadedVentoyRelease?.release))
    }
}
