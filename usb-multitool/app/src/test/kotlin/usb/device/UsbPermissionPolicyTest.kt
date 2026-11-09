package usb.device

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class UsbPermissionPolicyTest {
    @Test fun delayedAIsRejectedWhenPendingB() {
        assertFalse(UsbPermissionPolicy.accepts("2:/b", "1:/a", "2:/b"))
        assertFalse(UsbPermissionPolicy.accepts(null, "2:/b", "2:/b"))
        assertTrue(UsbPermissionPolicy.accepts("2:/b", "2:/b", "2:/b"))
    }
}
