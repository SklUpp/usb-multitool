package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionRetryGateTest {
    @Test
    fun firstRequestAllowed() {
        val decision = UsbPermissionRetryGate.prepareRequest("1:dev", UsbPermissionRetryGate.State())
        assertTrue(decision.shouldRequest)
        assertNull(decision.state.deniedKey)
        assertNull(decision.state.retriedKey)
    }

    @Test
    fun pendingBlocksDuplicate() {
        val state = UsbPermissionRetryGate.State(pendingKey = "1:dev")
        val decision = UsbPermissionRetryGate.prepareRequest("1:dev", state)
        assertFalse(decision.shouldRequest)
    }

    @Test
    fun deniedAllowsOneRetryThenBlocks() {
        val denied = UsbPermissionRetryGate.State(deniedKey = "1:dev")
        val first = UsbPermissionRetryGate.prepareRequest("1:dev", denied)
        assertTrue(first.shouldRequest)
        assertNull(first.state.deniedKey)
        assertEquals("1:dev", first.state.retriedKey)

        val afterDenyAgain = first.state.copy(deniedKey = "1:dev")
        val second = UsbPermissionRetryGate.prepareRequest("1:dev", afterDenyAgain)
        assertFalse(second.shouldRequest)
        assertEquals("1:dev", second.state.deniedKey)
        assertEquals("1:dev", second.state.retriedKey)
    }

    @Test
    fun detachClearsRetryBudget() {
        val spent = UsbPermissionRetryGate.State(
            deniedKey = "1:dev",
            retriedKey = "1:dev",
        )
        val cleared = UsbPermissionRetryGate.onDetachedOrReselect(spent)
        val again = UsbPermissionRetryGate.prepareRequest("1:dev", cleared)
        assertTrue(again.shouldRequest)
    }

    @Test
    fun grantClearsDenialAndRetry() {
        val spent = UsbPermissionRetryGate.State(
            pendingKey = "1:dev",
            deniedKey = "1:dev",
            retriedKey = "1:dev",
        )
        val granted = UsbPermissionRetryGate.onGranted(spent)
        assertNull(granted.pendingKey)
        assertNull(granted.deniedKey)
        assertNull(granted.retriedKey)
    }
}
