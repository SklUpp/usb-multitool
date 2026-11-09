package usb.operation

import usb.service.OperationState
import usb.ui.OperationState as UiOperationState

/** Maps service terminal state to UI state without coordinator-specific branching. */
object OperationCoordination {
    fun uiState(state: OperationState): UiOperationState = when (state) {
        is OperationState.Completed -> UiOperationState.Completed
        is OperationState.Cancelled -> UiOperationState.Cancelled
        is OperationState.Partial -> UiOperationState.Partial
        is OperationState.Failed -> UiOperationState.Failed
        else -> UiOperationState.Completed
    }

    fun errorMessage(state: OperationState, operationFailure: Throwable? = null): String? =
        (state as? OperationState.Failed)?.cause?.message
            ?: operationFailure?.message
            ?: (state as? OperationState.Failed)?.cause?.javaClass?.simpleName
            ?: operationFailure?.javaClass?.simpleName
}
