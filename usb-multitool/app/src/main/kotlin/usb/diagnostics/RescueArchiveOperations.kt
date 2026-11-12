package usb.diagnostics

import usb.device.UsbDisk
import usb.operation.DiskOperation

/**
 * Destination of one folder-backed rescue archive. The host creates the SAF folder and
 * documents; this interface only exposes the placement-specific sync/checkpoint/close
 * callbacks. Format, identity, engine and durable ordering stay in
 * [RescueBackupOperations]/[Rescue].
 */
interface RescueArchiveTarget {
    val sink: RescueSink

    fun syncImage()

    /** Rewrite the map document and sync it after [syncImage]. */
    fun writeCheckpoint(map: RescueMap)

    /** Flush and close the image; idempotent. Never deletes a partial archive. */
    fun closeArchive()

    /**
     * Publish the canonical map only after a clean copy and successful close. Called by
     * the durable boundary's success path, never from close/failure/cancel.
     */
    fun publishCanonical()
}

object RescueArchiveOperations {
    /**
     * SAF churn budget: each checkpoint is a new document + fsync + read-back (+ optional
     * delete). 256 MiB between checkpoints keeps a 64 GiB disk at ~256 publications and a
     * 1 TiB disk at ~4096 instead of tens/hundreds of thousands. The engine still performs
     * a final checkpoint on success, cancel and abort, so progress at those boundaries is
     * always durable; only the recovery granularity of a process crash is 256 MiB.
     */
    const val SAF_CHECKPOINT_TARGET_BYTES: Long = 256L * 1024 * 1024

    fun checkpointEverySectors(
        sectorSize: Int,
        targetBytes: Long = SAF_CHECKPOINT_TARGET_BYTES,
    ): ULong {
        require(sectorSize > 0) { "sector size must be positive" }
        require(targetBytes > 0) { "checkpoint target must be positive" }
        return maxOf(4096UL, (targetBytes / sectorSize).toULong())
    }

    /**
     * Folder/document placement around the existing backup engine. Same durable order as
     * [RescueBackupOperations.copy] for files; a failure after the first image byte is
     * Partial, never a silent Completed.
     *
     * [engine] is a host test seam; `null` keeps production engine. Append-only SAF
     * images disable retries; positional images retain retries for recovery.
     */
    fun copy(
        disk: UsbDisk,
        target: RescueArchiveTarget,
        map: RescueMap,
        operation: DiskOperation,
        checkpointTargetBytes: Long = SAF_CHECKPOINT_TARGET_BYTES,
        engine: (
            (UsbDisk, RescueSink, RescueMap, (RescueMap) -> Unit, DiskOperation) -> RescueMap
        )? = null,
    ): RescueMap =
        RescueBackupOperations.copy(
            disk = disk,
            sink = target.sink,
            map = map,
            operation = operation,
            onCheckpoint = { snapshot ->
                target.syncImage()
                target.writeCheckpoint(snapshot)
            },
            onFinalImageSync = target::syncImage,
            onFinalCheckpoint = target::writeCheckpoint,
            onFinalClose = target::closeArchive,
            onPublishCanonical = target::publishCanonical,
            checkpointEverySectors = checkpointEverySectors(disk.sectorSize, checkpointTargetBytes),
            maxRetries = if (target.sink.positional) 1 else 0,
            engine = engine,
        )
}
