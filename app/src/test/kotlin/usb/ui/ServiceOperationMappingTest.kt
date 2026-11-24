package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import usb.operation.OperationState as ServiceOperationState
import usb.operation.SizeMismatchException

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
        assertNull(ServiceOperationState.Queued.toOperationResult().failure)
        assertNull(ServiceOperationState.Running.toOperationResult().failure)
    }

    @Test fun prefersFailedCauseThenHostFailure() {
        val host = SizeMismatchException("host")
        assertEquals(
            OperationFailure.SafIo,
            ServiceOperationState.Failed(IOException("terminal"))
                .toOperationResult(host)
                .failure,
        )
        assertEquals(
            OperationFailure.SizeMismatch,
            ServiceOperationState.Completed.toOperationResult(host).failure,
        )
        assertNull(ServiceOperationState.Completed.toOperationResult().failure)
        assertNull(
            ServiceOperationState.Failed(IllegalStateException("engine"))
                .toOperationResult(null)
                .failure,
        )
    }
}

private typealias IOException = java.io.IOException
