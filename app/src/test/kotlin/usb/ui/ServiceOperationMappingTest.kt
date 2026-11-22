package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.operation.OperationState as ServiceOperationState

class ServiceOperationMappingTest {
    @Test fun mapsTerminalStates() {
        assertEquals(OperationState.Completed, serviceOperationUiState(ServiceOperationState.Completed))
        assertEquals(OperationState.Cancelled, serviceOperationUiState(ServiceOperationState.Cancelled))
        assertEquals(OperationState.Partial, serviceOperationUiState(ServiceOperationState.Partial))
        assertEquals(
            OperationState.Failed,
            serviceOperationUiState(ServiceOperationState.Failed(IllegalStateException("x"))),
        )
    }

    @Test fun mapsNonTerminalStatesWithoutClaimingCompletion() {
        assertEquals(OperationState.Queued, serviceOperationUiState(ServiceOperationState.Queued))
        assertEquals(OperationState.Running, serviceOperationUiState(ServiceOperationState.Running))
        assertNull(serviceOperationErrorMessage(ServiceOperationState.Queued))
        assertNull(serviceOperationErrorMessage(ServiceOperationState.Running))
    }

    @Test fun prefersFailureMessageThenOperationFailure() {
        val failure = IllegalArgumentException("operation")
        assertEquals(
            "terminal",
            serviceOperationErrorMessage(
                ServiceOperationState.Failed(IllegalStateException("terminal")),
                failure,
            ),
        )
        assertEquals(
            "operation",
            serviceOperationErrorMessage(ServiceOperationState.Completed, failure),
        )
        assertNull(serviceOperationErrorMessage(ServiceOperationState.Completed))
        assertTrue(
            serviceOperationErrorMessage(ServiceOperationState.Failed(IllegalStateException()), null)
                != null,
        )
    }
}
