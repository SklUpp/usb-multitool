package usb.files

import usb.fs.CloseAfterSuccessException
import usb.fs.UsbFsVolume
import usb.fs.mergeCloseFailure
import usb.operation.DiskOperation
import usb.operation.OperationState

/**
 * Owns exactly one [MountedFilesVolume] and the coarse monitor that serializes
 * mount, close, usage probes, and every files operation.
 *
 * Ceiling of the lock: detach/onCleared can wait for one in-flight FAT call;
 * upgrade only if a single filesystem call becomes unbounded.
 *
 * [openMounted] supplies a fresh disk+volume pair. Session never opens USB itself
 * so JVM tests inject fakes without Android seams.
 *
 * Close semantics:
 * - [close] returns the failure (or null); callers choose fail-closed vs swallow.
 * - Mode/partition change refuses remount when prior close/sync is unconfirmed.
 * - [withWritableVolume]: body ok + close fail → [OperationState.Partial] +
 *   [CloseAfterSuccessException]; body fail keeps primacy, close suppressed.
 */
class FilesVolumeSession(
    private val openMounted: (partitionId: String, writable: Boolean) -> MountedFilesVolume,
) {
    private val lock = Any()
    private var filesVolume: MountedFilesVolume? = null

    /** Run [body] holding the session monitor (re-entrant with [close]/[volumeForLocked]). */
    fun <T> withLock(body: () -> T): T = synchronized(lock, body)

    /**
     * Drop the mounted volume and its USB claim.
     * Null = nothing open or close confirmed. Non-null = close/sync failure for the gate.
     */
    fun close(): Throwable? =
        synchronized(lock) {
            val current = filesVolume
            filesVolume = null
            try {
                current?.close()
                null
            } catch (t: Throwable) {
                t
            }
        }

    /**
     * Mounted volume of [partitionId], opened on first use.
     * Must run under [withLock] (or nested re-entrant from a holder of the lock).
     *
     * [writable]=false → RO browse. Mutations remount RW for the call duration via
     * [withWritableVolume], which then drops the volume so the next browse is RO again.
     */
    fun volumeForLocked(partitionId: String, writable: Boolean = false): UsbFsVolume {
        val current = filesVolume
        if (current != null && current.partitionId == partitionId && current.writable == writable) {
            return current.volume
        }
        // Mode change (RO↔RW) or partition change: close before reopen.
        filesVolume = null
        try {
            current?.close()
        } catch (closeErr: Throwable) {
            // Do not remount and claim success — close/sync was not confirmed.
            throw IllegalStateException(
                "Volume close/sync was not confirmed; refusing remount: " +
                    (closeErr.message ?: closeErr.javaClass.simpleName) +
                    ". If the stick will not remount, run e2fsck on a PC",
                closeErr,
            )
        }

        val mounted = openMounted(partitionId, writable)
        filesVolume = mounted
        return mounted.volume
    }

    /**
     * RW mount for a mutation. Close/umount failure after a successful body →
     * [OperationState.Partial] + [CloseAfterSuccessException] (never Completed).
     * Body failure keeps primacy; close error is suppressed onto it.
     * Must run under [withLock].
     */
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
            val closeErr = close()
            if (closeErr != null) {
                if (primary != null) {
                    mergeCloseFailure(primary, closeErr)
                } else if (operation.state != OperationState.Partial) {
                    val wrapped = CloseAfterSuccessException(closeErr)
                    operation.state = OperationState.Partial
                    operation.failureCause = wrapped
                    throw wrapped
                }
            }
        }
    }
}
