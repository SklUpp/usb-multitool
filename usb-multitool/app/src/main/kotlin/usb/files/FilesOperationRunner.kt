package usb.files

import java.util.concurrent.atomic.AtomicReference
import usb.operation.DiskOperation

/**
 * Owns one files operation's session-critical execution boundary.
 *
 * OperationExecutor remains authoritative for queueing, cancellation, disconnect,
 * mutation, and terminal classification. This owner only keeps session acquisition,
 * epoch recording, and error-close ordering inside the operation's lock.
 */
class FilesOperationRunner(
    private val session: FilesVolumeSession,
) {
    fun create(
        name: String,
        bytesTotal: Long = -1L,
        body: (DiskOperation) -> Unit,
    ): FilesOperation {
        val closeDiagnostic = AtomicReference<Throwable?>(null)
        val operation =
            object : DiskOperation(name = name, bytesTotal = bytesTotal) {
                override suspend fun run() {
                    session.withLock {
                        var acquireToken = 0L
                        var bodyError: Throwable? = null
                        try {
                            session.withAcquireRecorder({ token -> acquireToken = token }) {
                                body(this)
                            }
                        } catch (failure: Throwable) {
                            bodyError = failure
                            throw failure
                        } finally {
                            val primary = bodyError
                            if (primary != null && acquireToken != 0L) {
                                when (val released = session.releaseAcquiredAfterErrorLocked(acquireToken, primary)) {
                                    is CloseEpochResult.ClosedFailed -> closeDiagnostic.set(released.error)
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        return FilesOperation(operation, closeDiagnostic)
    }
}

class FilesOperation internal constructor(
    val operation: DiskOperation,
    private val closeDiagnostic: AtomicReference<Throwable?>,
) {
    val closeFailure: Throwable?
        get() = closeDiagnostic.get()
}
