package usb.files.transfer

import java.io.IOException
import usb.files.FilesOperationPolicy
import usb.operation.DiskOperation
import usb.operation.OperationState

internal object TransferSupport {
    const val OCTET_STREAM = "application/octet-stream"

    fun decideConflict(
        conflict: TransferConflict,
        decide: (TransferConflict) -> TransferDecision,
    ): TransferDecision {
        val decision = decide(conflict)
        if (conflict.isDirectory && decision == TransferDecision.ReplaceFile) {
            throw IOException(
                FilesOperationPolicy.directoryConflictSkipOnlyMessage(conflict.pathLabel),
            )
        }
        return decision
    }

    fun markPartialIfMutated(operation: DiskOperation, failure: Throwable) {
        if (!operation.mutationAttempted) return
        if (operation.state != OperationState.Partial) {
            operation.state = OperationState.Partial
            if (operation.failureCause == null) operation.failureCause = failure
        }
    }
}

/** Mutable park position published via [DownloadCursor] snapshots. */
internal class DownloadParkState(
    var remainingRoots: List<String>,
    var activeRoot: String? = null,
    var remainingRelative: List<String> = emptyList(),
) {
    fun snapshot(): DownloadCursor =
        DownloadCursor(
            remainingRoots = remainingRoots,
            activeRoot = activeRoot,
            remainingRelative = remainingRelative,
        )
}

