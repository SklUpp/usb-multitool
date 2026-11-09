package usb.ui

import usb.service.OperationState as ServiceOperationState

/** Maps read-only operation outcome to UI progress without touching operation ownership. */
fun readOnlyOperationProgress(
    outcome: ServiceOperationState,
    label: String,
    bytesDone: Long,
    bytesTotal: Long,
    resultMessage: String?,
    failureCause: Throwable?,
    cancelable: Boolean,
): OperationProgress = when (outcome) {
    is ServiceOperationState.Failed -> OperationProgress(
        state = OperationState.Failed,
        label = label,
        errorMessage = outcome.cause.message ?: outcome.cause.javaClass.simpleName,
        cancelable = cancelable,
    )
    is ServiceOperationState.Cancelled -> OperationProgress(
        state = OperationState.Cancelled,
        label = label,
        cancelable = cancelable,
    )
    is ServiceOperationState.Partial -> OperationProgress(
        state = OperationState.Partial,
        label = label,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        errorMessage = failureCause?.message ?: failureCause?.javaClass?.simpleName,
        cancelable = cancelable,
    )
    else -> OperationProgress(
        state = OperationState.Completed,
        label = label,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        cancelable = cancelable,
    )
}
