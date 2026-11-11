package usb.files

import usb.fs.CloseAfterSuccessException
import usb.fs.UsbFsVolume
import usb.fs.mergeCloseFailure
import usb.operation.DiskOperation
import usb.operation.OperationState

/**
 * Result of closing the session mount under an ownership check.
 */
sealed class CloseEpochResult {
    /** No token, already closed, or epoch no longer live. */
    data object NotOwned : CloseEpochResult()

    data object ClosedOk : CloseEpochResult()

    data class ClosedFailed(val error: Throwable) : CloseEpochResult()
}

/**
 * Owns exactly one [MountedFilesVolume] and the coarse monitor that serializes
 * mount, close, usage probes, and every files operation.
 *
 * O2 ownership: a files-op body that fails **after** [volumeForLocked] must
 * [releaseAcquiredAfterErrorLocked] under the **same** [withLock] before unlock,
 * so the next op never reuses a mount the failed op still "owns". Fail-before-acquire
 * leaves the token empty and does not close a foreign mount.
 *
 * Success leaves the RO cache open for the next browse.
 * [withWritableVolume] Partial/close semantics are unchanged.
 */
class FilesVolumeSession(
    private val openMounted: (partitionId: String, writable: Boolean) -> MountedFilesVolume,
) {
    private val lock = Any()
    private var filesVolume: MountedFilesVolume? = null
    private var mountEpoch: Long = 0L
    private var acquireSink: ((Long) -> Unit)? = null

    fun <T> withLock(body: () -> T): T = synchronized(lock, body)

    /**
     * Records each successful [volumeForLocked] acquire/reuse. Used so fail-before-acquire
     * does not close another op's mount. Must run under [withLock].
     */
    fun <T> withAcquireRecorder(onAcquire: (Long) -> Unit, body: () -> T): T {
        val previous = acquireSink
        acquireSink = onAcquire
        try {
            return body()
        } finally {
            acquireSink = previous
        }
    }

    fun mountEpochLocked(): Long? = if (filesVolume != null) mountEpoch else null

    fun close(): Throwable? =
        synchronized(lock) {
            when (val result = closeLockedResult()) {
                is CloseEpochResult.ClosedFailed -> result.error
                else -> null
            }
        }

    fun closeIfEpochLocked(epoch: Long): CloseEpochResult {
        if (epoch == 0L) return CloseEpochResult.NotOwned
        if (filesVolume == null || mountEpoch != epoch) return CloseEpochResult.NotOwned
        return closeLockedResult()
    }

    /**
     * O2: after a body error, drop the mount this op acquired (if still live).
     * [primary] keeps primacy; close failure is [mergeCloseFailure]-suppressed onto it.
     * Must run under [withLock], still inside the failed op's execution scope.
     */
    fun releaseAcquiredAfterErrorLocked(token: Long, primary: Throwable): CloseEpochResult {
        val result = closeIfEpochLocked(token)
        if (result is CloseEpochResult.ClosedFailed) {
            mergeCloseFailure(primary, result.error)
        }
        return result
    }

    private fun closeLockedResult(): CloseEpochResult {
        val current = filesVolume ?: return CloseEpochResult.NotOwned
        filesVolume = null
        return try {
            current.close()
            CloseEpochResult.ClosedOk
        } catch (t: Throwable) {
            CloseEpochResult.ClosedFailed(t)
        }
    }

    fun volumeForLocked(partitionId: String, writable: Boolean = false): UsbFsVolume {
        val current = filesVolume
        if (current != null && current.partitionId == partitionId && current.writable == writable) {
            acquireSink?.invoke(mountEpoch)
            return current.volume
        }
        filesVolume = null
        try {
            current?.close()
        } catch (closeErr: Throwable) {
            throw IllegalStateException(
                "Volume close/sync was not confirmed; refusing remount: " +
                    (closeErr.message ?: closeErr.javaClass.simpleName) +
                    ". If the stick will not remount, run e2fsck on a PC",
                closeErr,
            )
        }

        val mounted = openMounted(partitionId, writable)
        filesVolume = mounted
        mountEpoch += 1L
        acquireSink?.invoke(mountEpoch)
        return mounted.volume
    }

    fun <T> withWritableVolume(
        partitionId: String,
        operation: DiskOperation,
        body: (UsbFsVolume) -> T,
    ): T {
        val volume = volumeForLocked(partitionId, writable = true)
        var primary: Throwable? = null
        try {
            return body(volume)
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            when (val closeResult = closeLockedResult()) {
                is CloseEpochResult.ClosedFailed -> {
                    val closeErr = closeResult.error
                    if (primary != null) {
                        mergeCloseFailure(primary, closeErr)
                    } else if (operation.state != OperationState.Partial) {
                        val wrapped = CloseAfterSuccessException(closeErr)
                        operation.state = OperationState.Partial
                        operation.failureCause = wrapped
                        throw wrapped
                    }
                }
                else -> Unit
            }
        }
    }
}
