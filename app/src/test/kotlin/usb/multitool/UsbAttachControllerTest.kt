package usb.multitool

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * [UsbAttachController] lifecycle: receiver start/stop, empty-device probe,
 * device-less detach clears the selection, and token-less permission
 * broadcasts stay ignored. No ShadowUsbManager gymnastics needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsbAttachControllerTest {
    private class RecordingListener : UsbAttachController.Listener {
        val detached = mutableListOf<UsbDevice?>()
        var selectionCleared = 0
        val permissionResults = mutableListOf<Pair<UsbDevice?, Boolean>>()

        override fun onAttached(device: UsbDevice, hasPermission: Boolean, manager: UsbManager) = Unit

        override fun onDetached(device: UsbDevice?) {
            detached += device
        }

        override fun onPermissionResult(device: UsbDevice?, granted: Boolean, manager: UsbManager?) {
            permissionResults += device to granted
        }

        override fun onSelectionCleared() {
            selectionCleared++
        }
    }

    private fun controller(listener: RecordingListener): UsbAttachController =
        UsbAttachController(RuntimeEnvironment.getApplication(), listener)

    @Test
    fun startStopIsIdempotent() {
        val listener = RecordingListener()
        val controller = controller(listener)
        controller.start()
        controller.start()
        controller.stop()
        controller.stop()
        assertTrue(listener.detached.isEmpty())
    }

    @Test
    fun probeWithEmptyDeviceListReturnsFalse() {
        val controller = controller(RecordingListener())
        controller.start()
        try {
            assertFalse(controller.probeAttachedDevice(null))
            assertFalse(controller.probeAttachedDevice(Intent()))
        } finally {
            controller.stop()
        }
    }

    @Test
    fun detachWithoutDeviceClearsSelection() {
        val listener = RecordingListener()
        val controller = controller(listener)
        controller.start()
        try {
            RuntimeEnvironment.getApplication().sendBroadcast(
                Intent(UsbManager.ACTION_USB_DEVICE_DETACHED),
            )
            ShadowLooper.idleMainLooper()
        } finally {
            controller.stop()
        }
        assertEquals(1, listener.selectionCleared)
        assertEquals(listOf(null), listener.detached)
    }

    @Test
    fun stalePermissionBroadcastWithoutTokenIsIgnored() {
        val listener = RecordingListener()
        val controller = controller(listener)
        controller.start()
        try {
            RuntimeEnvironment.getApplication().sendBroadcast(
                Intent(UsbAttachController.ACTION_USB_PERMISSION)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true),
            )
            ShadowLooper.idleMainLooper()
        } finally {
            controller.stop()
        }
        assertTrue(listener.permissionResults.isEmpty())
    }
}
