package usb.ui

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupFolderLaunchStateTest {
    private fun start(queue: BackupFolderLaunchQueue, action: BackupFolderAction): PendingBackupFolderAction =
        (queue.begin(action, "stick-a", 8L, "1:/dev/b", "SERIAL-A") as BackupFolderLaunch.Starting).pending

    @Test
    fun savedStateRoundTripsPendingPinAndDeadProcessMarksLostWrite() {
        val source = BackupFolderLaunchQueue("session-a")
        val pending = start(source, BackupFolderAction.GptCopy)
        val state = Bundle()
        source.saveState(state)

        val recreated = BackupFolderLaunchQueue("session-a")
        assertFalse(recreated.restoreState(state))
        assertEquals(pending, recreated.pending)

        val deadProcess = BackupFolderLaunchQueue("session-b")
        assertTrue(deadProcess.restoreState(state))
        assertNull(deadProcess.pending)
        assertTrue(deadProcess.takeLostAction())
        assertFalse(deadProcess.takeLostAction())
    }

    @Test
    fun restoredLostMarkerDoesNotReplayStatusOnConfigurationRecreation() {
        val state = Bundle().apply {
            putBoolean("usb.multitool.PENDING_FOLDER_LOST", true)
        }
        val recreated = BackupFolderLaunchQueue("session-a")
        assertFalse(recreated.restoreState(state))
        assertTrue(recreated.hasLostAction)
        assertFalse(recreated.restoreState(state))
        assertTrue(recreated.hasLostAction)
    }

    @Test
    fun savedManualFolderChangeFromDeadProcessDoesNotMarkLostAction() {
        val source = BackupFolderLaunchQueue("session-a")
        source.begin(BackupFolderAction.ChangeFolder, null, 0L, null, null)
        val state = Bundle()
        source.saveState(state)

        val deadProcess = BackupFolderLaunchQueue("session-b")
        assertFalse(deadProcess.restoreState(state))
        assertNull(deadProcess.pending)
        assertFalse(deadProcess.takeLostAction())
    }
}
