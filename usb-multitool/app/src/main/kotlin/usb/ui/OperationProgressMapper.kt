package usb.ui

import usb.service.OperationToken
import usb.operation.OperationState as ServiceOperationState

/** Maps read-only operation outcome to UI progress without touching operation ownership. */
fun readOnlyOperationProgress(
    token: OperationToken,
    outcome: ServiceOperationState,
    title: OperationTitle,
    bytesDone: Long,
    bytesTotal: Long,
    resultMessage: String?,
    failureCause: Throwable?,
    cancelable: Boolean,
    readScanResult: ReadScanOutcome? = null,
): OperationProgress = when (outcome) {
    is ServiceOperationState.Failed -> OperationProgress(
        state = OperationState.Failed,
        title = title,
        token = token,
        errorMessage = outcome.cause.message ?: outcome.cause.javaClass.simpleName,
        cancelable = cancelable,
    )
    is ServiceOperationState.Cancelled -> OperationProgress(
        state = OperationState.Cancelled,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        readScanResult = readScanResult,
        cancelable = cancelable,
    )
    is ServiceOperationState.Partial -> OperationProgress(
        state = OperationState.Partial,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        errorMessage = failureCause?.message ?: failureCause?.javaClass?.simpleName,
        readScanResult = readScanResult,
        cancelable = cancelable,
    )
    is ServiceOperationState.Queued -> OperationProgress(
        state = OperationState.Queued,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        cancelable = cancelable,
    )
    is ServiceOperationState.Running -> OperationProgress(
        state = OperationState.Running,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        cancelable = cancelable,
    )
    is ServiceOperationState.Completed -> OperationProgress(
        state = OperationState.Completed,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        readScanResult = readScanResult,
        cancelable = cancelable,
    )
}

/** Maps every service state to its UI state without coordinator-specific branching. */
fun serviceOperationUiState(state: ServiceOperationState): OperationState = when (state) {
    is ServiceOperationState.Queued -> OperationState.Queued
    is ServiceOperationState.Running -> OperationState.Running
    is ServiceOperationState.Completed -> OperationState.Completed
    is ServiceOperationState.Cancelled -> OperationState.Cancelled
    is ServiceOperationState.Partial -> OperationState.Partial
    is ServiceOperationState.Failed -> OperationState.Failed
}

/** Prefer terminal failure message, then operation.failureCause, then simple class name. */
fun serviceOperationErrorMessage(
    state: ServiceOperationState,
    operationFailure: Throwable? = null,
): String? =
    (state as? ServiceOperationState.Failed)?.cause?.message
        ?: operationFailure?.message
        ?: (state as? ServiceOperationState.Failed)?.cause?.javaClass?.simpleName
        ?: operationFailure?.javaClass?.simpleName

