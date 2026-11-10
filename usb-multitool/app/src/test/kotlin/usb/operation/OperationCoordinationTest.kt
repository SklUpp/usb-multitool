package usb.operation

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import usb.ui.OperationState as UiState

class OperationCoordinationTest {
    @Test fun mapsTerminalStates() {
        assertEquals(UiState.Completed, OperationCoordination.uiState(OperationState.Completed))
        assertEquals(UiState.Cancelled, OperationCoordination.uiState(OperationState.Cancelled))
        assertEquals(UiState.Partial, OperationCoordination.uiState(OperationState.Partial))
        assertEquals(UiState.Failed, OperationCoordination.uiState(OperationState.Failed(IllegalStateException("x"))))
    }

    @Test fun mapsNonTerminalStatesWithoutClaimingCompletion() {
        assertEquals(UiState.Queued, OperationCoordination.uiState(OperationState.Queued))
        assertEquals(UiState.Running, OperationCoordination.uiState(OperationState.Running))
        assertNull(OperationCoordination.errorMessage(OperationState.Queued))
        assertNull(OperationCoordination.errorMessage(OperationState.Running))
    }

    @Test fun prefersFailureMessageThenOperationFailure() {
        val failure = IllegalArgumentException("operation")
        assertEquals("terminal", OperationCoordination.errorMessage(OperationState.Failed(IllegalStateException("terminal")), failure))
        assertEquals("operation", OperationCoordination.errorMessage(OperationState.Completed, failure))
        assertNull(OperationCoordination.errorMessage(OperationState.Completed))
        assertTrue(OperationCoordination.errorMessage(OperationState.Failed(IllegalStateException()), null) != null)
    }
}
