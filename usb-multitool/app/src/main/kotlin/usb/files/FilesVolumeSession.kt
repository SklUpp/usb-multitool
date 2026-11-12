package usb.files

import usb.device.UsbExclusiveClaim
import usb.fs.CloseAfterSuccessException
import usb.fs.UsbFsVolume
import usb.fs.mergeCloseFailure
import usb.operation.DiskOperation
import usb.operation.OperationState

/**
 * Immutable attachment a files operation is pinned to: the USB attach generation and the
 * device key captured when the operation was accepted. A mount/open must match the live
 * attachment or fail instead of touching another stick.
 */
data class AttachmentPin(val attachGeneration: Long, val deviceKey: String)

/** The live USB attachment no longer matches the operation's pin; no volume was touched. */
class AttachmentChangedException : IllegalStateException("USB attachment changed")

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
 *
 * Attachment pin: every files operation passes the [AttachmentPin] captured when it was
 * accepted. [volumeForLocked] / [withWritableVolume] require it to equal
 * [currentAttachment] before use, include it in the cache key, and re-check it after
 * [openMounted], so a paused operation can neither mount nor reuse another stick's volume.
 *
 * Detach-style close is epoch-fenced: [mountFence] captures the high-water epoch
 * synchronously and [closeIfEpochAtMostLocked] closes only mounts at or below it. A
 * volume mounted later (new attach) has a larger epoch, so a stale close neither leaks
 * the old mount nor closes the new one.
 *
 * Unconfirmed close (umount/sync threw): error is latched for the process lifetime
 * of this session. Second [close] returns the same error (never null-success).
 * [volumeForLocked] / writable ops refuse remount until a new session (process /
 * ViewModel recreate). Fail-closed: no new mount after half-closed EXT/NTFS.
 *
 * USB ownership: every mount opens its disk under [claim], and raw/partition sessions take
 * the same claim through [openAfterCloseClaimed]. That method closes the previous mount and
 * takes the claim under [withLock], so a concurrent usage probe or files operation cannot
 * mount the stick between the close and the raw claim; the USB open itself then runs outside
 * the lock, with the claim (not the lock) keeping other openers out. A loser gets
 * [UsbExclusiveClaim.BusyException] instead of a second `claimInterface`.
 */
class FilesVolumeSession(
    /** Immutable identity of the attachment that is currently live, or null when detached. */
    private val currentAttachment: () -> AttachmentPin?,
    private val openMounted: (
        partitionId: String,
        writable: Boolean,
        pin: AttachmentPin,
        lease: UsbExclusiveClaim.Lease,
    ) -> MountedFilesVolume,
    /** Exclusive USB device ownership shared by this session's mounts and raw opens. */
    val claim: UsbExclusiveClaim = UsbExclusiveClaim(),
) {
    private val lock = Any()

    /** Immutable (volume, epoch, pin, claim lease); one volatile read is a consistent snapshot. */
    private class Mounted(
        val volume: MountedFilesVolume,
        val epoch: Long,
        val pin: AttachmentPin,
        val lease: UsbExclusiveClaim.Lease,
    )

    @Volatile private var mounted: Mounted? = null
    private var nextEpoch: Long = 0L
    /** Highest epoch ever allocated; allocated before a mount opens, published for fences. */
    @Volatile private var epochHighWater: Long = 0L
    private var acquireSink: ((Long) -> Unit)? = null
    /** First close/sync failure; retained so retry cannot clear or remount. */
    private var unconfirmedClose: Throwable? = null

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

    fun mountEpochLocked(): Long? = mounted?.epoch

    /**
     * Epoch fence for detach/new-attach: highest epoch allocated so far. Read without
     * [withLock]; an epoch is allocated **before** its volume opens, so any mount that can
     * still succeed after this read has a strictly larger epoch. A close bounded by this
     * fence therefore drops the previous mount (even one still opening) and never a newer one.
     */
    fun mountFence(): Long = epochHighWater

    fun close(): Throwable? =
        synchronized(lock) {
            when (val result = closeLockedResult()) {
                is CloseEpochResult.ClosedFailed -> result.error
                else -> null
            }
        }

    /**
     * Opens a raw/partition session with the exclusive USB claim held.
     *
     * Close and claim happen in **one** critical section: the mounted volume is closed and
     * [claim] is acquired while [withLock] is held, so a concurrent mount/probe (which takes
     * the same lock and the same claim) can never open the device between the close and this
     * claim. A raced opener gets [UsbExclusiveClaim.BusyException].
     *
     * [open] then runs **outside** the lock with the claim held, so the slow USB open does
     * not block a UI-thread close/probe; the claim, not the lock, keeps other openers out.
     * If [open] throws before a [UsbDisk] binds the lease, the lease is released here. Once a
     * disk owns it, Lease.close() is guarded and only the disk releases after confirmed close.
     * An unconfirmed close throws through [requireFilesVolumeClosed] before anything opens.
     */
    fun <T> openAfterCloseClaimed(open: (UsbExclusiveClaim.Lease) -> T): T {
        val lease =
            withLock {
                requireFilesVolumeClosed(close())
                claim.tryAcquire()
            }
        return try {
            open(lease)
        } catch (t: Throwable) {
            lease.close()
            throw t
        }
    }

    /**
     * Closes only the mount captured as [epoch]; a newer mount (different epoch) is
     * [CloseEpochResult.NotOwned] and stays open. Must run under [withLock].
     */
    fun closeIfEpochLocked(epoch: Long): CloseEpochResult {
        if (epoch == 0L) return CloseEpochResult.NotOwned
        val current = mounted ?: return CloseEpochResult.NotOwned
        if (current.epoch != epoch) return CloseEpochResult.NotOwned
        return closeLockedResult()
    }

    /**
     * Closes the live mount only if it was allocated at or before [fence] (see [mountFence]).
     * A mount opened after the fence (new attach) is [CloseEpochResult.NotOwned] and stays
     * open. Must run under [withLock]; a close/sync failure is reported as
     * [CloseEpochResult.ClosedFailed] and latched exactly like [close].
     */
    fun closeIfEpochAtMostLocked(fence: Long): CloseEpochResult {
        if (fence <= 0L) return CloseEpochResult.NotOwned
        val current = mounted ?: return CloseEpochResult.NotOwned
        if (current.epoch > fence) return CloseEpochResult.NotOwned
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
        unconfirmedClose?.let { return CloseEpochResult.ClosedFailed(it) }
        val current = mounted ?: return CloseEpochResult.NotOwned
        mounted = null
        return try {
            current.volume.close()
            CloseEpochResult.ClosedOk
        } catch (t: Throwable) {
            unconfirmedClose = t
            CloseEpochResult.ClosedFailed(t)
        } finally {
            // Bound UsbDisk owns release. Mark failed volume close so all external cleanup stays blocked.
            if (unconfirmedClose != null) current.lease.retainAfterCloseFailure()
            current.lease.close()
        }
    }

    /**
     * Live attachment must equal [pin] before any mount/open or cached-volume reuse:
     * an operation accepted for one stick can never observe or touch another one.
     */
    private fun requireLiveAttachmentLocked(pin: AttachmentPin?): AttachmentPin {
        if (pin == null || currentAttachment() != pin) throw AttachmentChangedException()
        return pin
    }

    fun volumeForLocked(
        pin: AttachmentPin?,
        partitionId: String,
        writable: Boolean = false,
    ): UsbFsVolume {
        unconfirmedClose?.let { closeErr ->
            throw refuseRemount(closeErr)
        }
        val pinned = requireLiveAttachmentLocked(pin)
        val current = mounted
        if (current != null &&
            current.pin == pinned &&
            current.volume.partitionId == partitionId &&
            current.volume.writable == writable
        ) {
            acquireSink?.invoke(current.epoch)
            return current.volume.volume
        }
        if (current != null && current.pin != pinned) {
            // A mount of a newer attachment must never be closed or reused by an older op.
            if (current.pin.attachGeneration > pinned.attachGeneration) {
                throw AttachmentChangedException()
            }
        }
        // Allocate the epoch before any slow step: a detach fence taken while this mount is
        // being replaced/opened must include it (epoch <= fence), never a mount opened later.
        nextEpoch += 1L
        val epoch = nextEpoch
        epochHighWater = epoch
        mounted = null
        try {
            current?.volume?.close()
        } catch (closeErr: Throwable) {
            unconfirmedClose = closeErr
            throw refuseRemount(closeErr)
        } finally {
            current?.lease?.close()
        }

        // Claim the device only after the previous mount is fully closed under this lock:
        // no second session can hold the interface while this one opens.
        val lease = claim.tryAcquire()
        val opened =
            try {
                openMounted(partitionId, writable, pinned, lease)
            } catch (t: Throwable) {
                lease.close()
                throw t
            }
        // The stick can be swapped while the open runs (USB claim / table read): never
        // publish a volume for an attachment that is no longer the pinned one.
        if (currentAttachment() != pinned) {
            try {
                opened.close()
            } catch (closeErr: Throwable) {
                unconfirmedClose = closeErr
            }
            throw AttachmentChangedException()
        }
        mounted = Mounted(opened, epoch, pinned, lease)
        acquireSink?.invoke(epoch)
        return opened.volume
    }

    private fun refuseRemount(closeErr: Throwable): IllegalStateException =
        IllegalStateException(
            "Volume close/sync was not confirmed; refusing remount: " +
                (closeErr.message ?: closeErr.javaClass.simpleName) +
                ". If the stick will not remount, run e2fsck on a PC",
            closeErr,
        )

    fun <T> withWritableVolume(
        pin: AttachmentPin?,
        partitionId: String,
        operation: DiskOperation,
        body: (UsbFsVolume) -> T,
    ): T {
        val volume = volumeForLocked(pin, partitionId, writable = true)
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
