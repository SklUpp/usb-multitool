package usb.operation

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import usb.ui.DestructiveAction

class OperationLifecyclePolicyTest {
    @Test fun cooperativeActionsMatchCancellationContract() {
        assertTrue(OperationLifecyclePolicy.isCooperative(DestructiveAction.RAW_WRITE))
        assertTrue(OperationLifecyclePolicy.isCooperative(DestructiveAction.RESCUE_RESTORE))
        assertTrue(OperationLifecyclePolicy.isCooperative(DestructiveAction.VENTOY_DOWNLOAD_INSTALL))
        assertFalse(OperationLifecyclePolicy.isCooperative(DestructiveAction.WIPE))
    }
}
