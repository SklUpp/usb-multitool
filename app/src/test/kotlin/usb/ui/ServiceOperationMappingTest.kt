package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.operation.OperationState as ServiceOperationState

class ServiceOperationMappingTest {
    @Test fun mapsTerminalStates() {
        assertEquals(OperationState.Completed, ServiceOperationState.Completed.toOperationResult().state)
        assertEquals(OperationState.Cancelled, ServiceOperationState.Cancelled.toOperationResult().state)
        assertEquals(OperationState.Partial, ServiceOperationState.Partial.toOperationResult().state)
        assertEquals(
            OperationState.Failed,
            ServiceOperationState.Failed(IllegalStateException("x")).toOperationResult().state,
        )
    }

    @Test fun mapsNonTerminalStatesWithoutClaimingCompletion() {
        assertEquals(OperationState.Queued, ServiceOperationState.Queued.toOperationResult().state)
        assertEquals(OperationState.Running, ServiceOperationState.Running.toOperationResult().state)
        assertNull(ServiceOperationState.Queued.toOperationResult().toMessage())
        assertNull(ServiceOperationState.Running.toOperationResult().toMessage())
    }

    @Test fun prefersFailureMessageThenOperationFailure() {
        val failure = IllegalArgumentException("operation")
        assertEquals(
            "terminal",
            ServiceOperationState.Failed(IllegalStateException("terminal"))
                .toOperationResult(failure)
                .toMessage(),
        )
        assertEquals(
            "operation",
            ServiceOperationState.Completed.toOperationResult(failure).toMessage(),
        )
        assertNull(ServiceOperationState.Completed.toOperationResult().toMessage())
        assertTrue(
            ServiceOperationState.Failed(IllegalStateException()).toOperationResult(null)
                .toMessage() != null,
        )
    }
}
