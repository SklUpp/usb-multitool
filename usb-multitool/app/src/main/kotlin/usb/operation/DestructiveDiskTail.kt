package usb.operation

import usb.device.UsbDisk

/**
 * MainActivity destructive-runner post-body sync boundary (before close).
 * Sync failure with [DiskOperation.mutationAttempted] → [OperationState.Partial].
 *
 * Close stays in the caller's `finally` so body failures still release the USB claim.
 */
fun DiskOperation.syncDestructiveDisk(disk: UsbDisk) {
    try {
        disk.sync()
        // Final sync after mutation body: durable commit boundary.
        if (state != OperationState.Partial && state !is OperationState.Failed) {
            commitReached = true
        }
    } catch (syncFailure: Throwable) {
        if (mutationAttempted) {
            state = OperationState.Partial
            failureCause = syncFailure
        }
        throw syncFailure
    }
}
