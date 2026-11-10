package usb.ui

import usb.operation.OperationState as ServiceOperationState

/** Maps read-only operation outcome to UI progress without touching operation ownership. */
fun readOnlyOperationProgress(
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
        errorMessage = outcome.cause.message ?: outcome.cause.javaClass.simpleName,
        cancelable = cancelable,
    )
    is ServiceOperationState.Cancelled -> OperationProgress(
        state = OperationState.Cancelled,
        title = title,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        readScanResult = readScanResult,
        cancelable = cancelable,
    )
    is ServiceOperationState.Partial -> OperationProgress(
        state = OperationState.Partial,
        title = title,
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
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        cancelable = cancelable,
    )
    is ServiceOperationState.Running -> OperationProgress(
        state = OperationState.Running,
        title = title,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        cancelable = cancelable,
    )
    is ServiceOperationState.Completed -> OperationProgress(
        state = OperationState.Completed,
        title = title,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        readScanResult = readScanResult,
        cancelable = cancelable,
    )
}
