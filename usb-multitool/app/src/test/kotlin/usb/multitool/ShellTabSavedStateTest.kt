package usb.multitool

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import usb.ui.DevicePresence
import usb.ui.ShellTab
import usb.ui.ShellTabPersistence
import usb.ui.UiIntent

/**
 * Process/Activity recreation restores only [ShellTab]. Attachment-scoped state stays empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShellTabSavedStateTest {
    @Before
    fun drainMain() {
        // MainViewModel init posts Ventoy work; keep tests deterministic.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun recreationRestoresSelectedTabWithoutDeviceSession() {
        val firstHandle = SavedStateHandle()
        val first = MainViewModel(RuntimeEnvironment.getApplication(), firstHandle)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        first.onIntent(UiIntent.SelectTab(ShellTab.Partitions))
        assertEquals(ShellTab.Partitions, first.state.tab)
        assertEquals(
            ShellTab.Partitions,
            ShellTabPersistence.read(firstHandle),
        )

        val restoredHandle =
            SavedStateHandle(
                mapOf(ShellTabPersistence.KEY to firstHandle.get<String>(ShellTabPersistence.KEY)),
            )
        val second = MainViewModel(RuntimeEnvironment.getApplication(), restoredHandle)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(ShellTab.Partitions, second.state.tab)
        assertEquals(DevicePresence.Absent, second.state.presence)
        assertNull(second.state.device)
        assertTrue(second.state.partitions.isEmpty())
        assertNull(second.state.selectedPartitionId)
        assertTrue(second.state.files.isEmpty())
        assertNull(second.state.pendingConfirmation)
        assertNull(second.state.windowsTemplatePath)
        assertTrue(second.state.windowsSelectedImages.isEmpty())
    }

    @Test
    fun corruptSavedTabFallsBackToFiles() {
        val handle = SavedStateHandle(mapOf(ShellTabPersistence.KEY to "legacy"))
        val vm = MainViewModel(RuntimeEnvironment.getApplication(), handle)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(ShellTab.Files, vm.state.tab)
        assertEquals(ShellTab.Files, ShellTabPersistence.read(handle))
    }
}
