package usb.multitool

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import usb.ui.AppUiState
import usb.ui.DeviceIdentity
import usb.ui.DevicePresence
import usb.ui.FileEntry
import usb.ui.FilesDownloadBinding
import usb.ui.FilesListingState
import usb.ui.PartitionTab
import usb.ui.UiAttachmentPin
import usb.ui.UiIntent

/**
 * [SafPickerCoordinator] slot fences: busy stage, single claim, stale claim,
 * save/restore round-trip, and restore refused while occupied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafPickerCoordinatorTest {
    private lateinit var resolver: ContentResolver
    private val intents = mutableListOf<UiIntent>()

    private fun binding(name: String = "a.bin", size: ULong = 10UL): FilesDownloadBinding =
        FilesDownloadBinding(
            directory = "/",
            name = name,
            sizeBytes = size,
            attachment = UiAttachmentPin("Stick", 7L, "p1", 100UL, 200UL),
        )

    private fun liveState(): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = DeviceIdentity(
                label = "Stick",
                capacityBytes = 8000000000UL,
                attachGeneration = 7L,
            ),
            partitions = listOf(
                PartitionTab(
                    id = "p1",
                    name = "DATA",
                    readOnly = false,
                    startLba = 100UL,
                    sectorCount = 200UL,
                ),
            ),
            selectedPartitionId = "p1",
            currentPath = "/",
            files = listOf(FileEntry("a.bin", false, 10UL)),
            filesListingState = FilesListingState.Loaded,
        )

    private fun coordinator(): SafPickerCoordinator =
        SafPickerCoordinator(resolver) { intents += it }

    @Before
    fun setUp() {
        resolver = RuntimeEnvironment.getApplication().contentResolver
        intents.clear()
    }

    @Test
    fun secondStageWhileBusyReturnsNullAndSignals() {
        val coord = coordinator()
        val first = binding()
        val requestId = coord.stageDownload(first, liveState())
        assertNotNull(requestId)
        assertNull(coord.stageDownload(binding("b.bin"), liveState()))
        assertTrue(intents.any { it is UiIntent.Status })
    }

    @Test
    fun claimLaunchSucceedsOnceThenStaleFails() {
        val coord = coordinator()
        val staged = binding()
        val requestId = coord.stageDownload(staged, liveState()) ?: error("stage failed")
        assertTrue(coord.claimLaunch(requestId, staged))
        assertFalse(coord.claimLaunch(requestId, staged))
        assertFalse(coord.claimLaunch(requestId + 1000, staged))
    }

    @Test
    fun saveRestoreRoundTrip() {
        val coord = coordinator()
        val staged = binding()
        assertNotNull(coord.stageDownload(staged, liveState()))
        val saved = Bundle()
        coord.save(saved)

        val restored = coordinator()
        assertFalse(restored.restore(saved))
        assertEquals(staged, restored.pendingDownloadBinding)
    }

    @Test
    fun restoreRefusedWhenOccupied() {
        val first = binding()
        val coord = coordinator()
        assertNotNull(coord.stageDownload(first, liveState()))

        val other = binding("other.bin")
        assertFalse(coord.restorePending(other))
        assertEquals(first, coord.pendingDownloadBinding)

        val saved = Bundle()
        SafPickerCoordinator(resolver) { }.apply {
            assertNotNull(stageDownload(other, liveState()))
            save(saved)
        }
        assertFalse(coord.restore(saved))
        assertEquals(first, coord.pendingDownloadBinding)
    }

    @Test
    fun staleNameWithoutBindingReportsStale() {
        val saved = Bundle()
        saved.putString(SafPickerCoordinator.STATE_PENDING_DOWNLOAD_NAME, "orphan.bin")
        val coord = coordinator()
        assertTrue(coord.restore(saved))
        assertNull(coord.pendingDownloadBinding)
    }

    @Test
    fun submitResultEmitsDownloadIntent() {
        val coord = coordinator()
        val staged = binding()
        assertNotNull(coord.stageDownload(staged, liveState()))
        val uri = Uri.parse("content://usb.saf.test/doc/a.bin")
        assertTrue(coord.submitResult(staged, uri, liveState()))
        val emitted = intents.filterIsInstance<UiIntent.DownloadFile>().single()
        assertEquals(uri.toString(), emitted.uri)
        assertTrue(emitted.fromPicker)
        assertEquals(staged, emitted.binding)
        assertNull(coord.pendingDownloadBinding)
    }
}
