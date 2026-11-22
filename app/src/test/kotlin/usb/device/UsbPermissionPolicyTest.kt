package usb.device

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsbPermissionPolicyTest {
    @Test fun callbackCarriesGrantedDeviceAndRejectsForeignOrRepluggedRequest() {
        val grantedDevice = mockk<UsbDevice>()
        val callback = Intent("usb.multitool.USB_PERMISSION")
            .putExtra(UsbManager.EXTRA_DEVICE, grantedDevice)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)
        assertTrue(callback.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        assertEquals(grantedDevice, callback.getParcelableExtra(UsbManager.EXTRA_DEVICE))
        assertFalse(UsbPermissionPolicy.accepts("2:/b", "1:/a", "2:/b", "request-b", "request-a"))
        assertFalse(UsbPermissionPolicy.accepts("2:/b", "2:/b", "2:/b", "request-b", "request-a"))
        assertTrue(UsbPermissionPolicy.accepts("2:/b", "2:/b", "2:/b", "request-b", "request-b"))
    }

    @Test fun callbackWithoutRequestTokenIsRejected() {
        assertFalse(UsbPermissionPolicy.accepts("2:/b", "2:/b", "2:/b", "request-b", null))
    }
}
