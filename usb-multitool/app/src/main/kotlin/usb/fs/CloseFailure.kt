package usb.fs

/**
 * Combine a primary files-operation failure with a subsequent volume-close failure.
 * Close errors must never erase the original cause: attach as [Throwable.addSuppressed].
 * When there is no primary error, returns [closeError] so the caller can decide whether
 * to surface it as status (success path) or rethrow.
 */
internal fun mergeCloseFailure(
    primary: Throwable?,
    closeError: Throwable,
    cleanupError: Throwable? = null,
): Throwable {
    val merged = if (primary != null) {
        primary.addSuppressed(closeError)
        primary
    } else {
        closeError
    }
    if (cleanupError != null) merged.addSuppressed(cleanupError)
    return merged
}

/**
 * Body succeeded (file created/deleted/synced) but umount/sync of the volume handle failed.
 * Must surface as [usb.service.OperationState.Partial] — never Completed / green success.
 */
class CloseAfterSuccessException(cause: Throwable) : Exception(
    "Change applied, but volume close/sync was not confirmed: " +
        (cause.message ?: cause.javaClass.simpleName) +
        ". If the stick will not remount, run e2fsck on a PC — this app does not clear dirty flags",
    cause,
)
