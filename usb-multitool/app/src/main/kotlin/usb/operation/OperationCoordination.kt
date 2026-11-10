package usb.operation

import usb.ui.OperationState as UiOperationState

/** Maps every service state to its UI state without coordinator-specific branching. */
object OperationCoordination {
    fun uiState(state: OperationState): UiOperationState = when (state) {
        is OperationState.Queued -> UiOperationState.Queued
        is OperationState.Running -> UiOperationState.Running
        is OperationState.Completed -> UiOperationState.Completed
        is OperationState.Cancelled -> UiOperationState.Cancelled
        is OperationState.Partial -> UiOperationState.Partial
        is OperationState.Failed -> UiOperationState.Failed
    }

    fun errorMessage(state: OperationState, operationFailure: Throwable? = null): String? =
        (state as? OperationState.Failed)?.cause?.message
            ?: operationFailure?.message
            ?: (state as? OperationState.Failed)?.cause?.javaClass?.simpleName
            ?: operationFailure?.javaClass?.simpleName
}
