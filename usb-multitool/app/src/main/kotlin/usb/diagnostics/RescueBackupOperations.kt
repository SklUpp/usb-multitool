package usb.diagnostics

import java.io.File
import java.io.RandomAccessFile
import usb.device.UsbDisk
import usb.operation.DiskOperation
import usb.operation.OperationState

object RescueBackupOperations {
    /**
     * Owns the durable backup boundary: image bytes, final fd.sync/close, and checkpoint.
     * [Rescue.copy] may mark Completed when the map is clean, but [commitReached] stays
     * false until this wrapper finishes — so a late sync/close/checkpoint failure is Partial.
     *
     * [onFinalImageSync] / [onFinalCheckpoint] are production defaults; tests inject faults
     * after image bytes exist without mocking the whole SCSI stack.
     *
     * [engine] is a test seam only: the production default (`null`) is [Rescue.copy]. Host
     * tests replace it with a plain-Kotlin sink writer so the durable wrapper below (real
     * file writes, ensureLength, fsync, checkpoint publish) runs without the native sf-core.
     */
    fun copy(
        disk: UsbDisk,
        image: File,
        mapFile: File,
        map: RescueMap,
        operation: DiskOperation,
        onFinalImageSync: (RandomAccessFile) -> Unit = { it.fd.sync() },
        onFinalCheckpoint: (RescueMap, File) -> Unit = { snapshot, file ->
            RescueCheckpoint.save(snapshot, file)
        },
        engine: (
            (UsbDisk, RescueSink, RescueMap, (RescueMap) -> Unit, DiskOperation) -> RescueMap
        )? = null,
    ) {
        val file = RandomAccessFile(image, "rw")
        // Extending the image is a durable side-effect (Partial) but not a disk write,
        // so it is tracked separately from [DiskOperation.mutationAttempted].
        var extentGrew = false
        try {
            copy(
                disk = disk,
                sink = object : RescueSink {
                    /** Seekable image file: a damaged chunk may be parked and retried later. */
                    override val positional = true

                    override fun ensureLength(bytes: Long) {
                        if (file.length() < bytes) {
                            extentGrew = true
                            file.setLength(bytes)
                        }
                    }

                    override fun writeSector(lba: ULong, sector: ByteArray) =
                        writeBatch(lba, sector, sector.size, disk.sectorSize)

                    override fun writeBatch(
                        lba: ULong,
                        bytes: ByteArray,
                        length: Int,
                        sectorSize: Int,
                    ) {
                        operation.mutationAttempted = true
                        file.seek((lba * sectorSize.toULong()).toLong())
                        file.write(bytes, 0, length)
                    }
                },
                map = map,
                operation = operation,
                onCheckpoint = { snapshot ->
                    // The map may only claim sectors already durable in the image.
                    file.fd.sync()
                    RescueCheckpoint.save(snapshot, mapFile)
                },
                onFinalImageSync = { onFinalImageSync(file) },
                onFinalCheckpoint = { snapshot -> onFinalCheckpoint(snapshot, mapFile) },
                onFinalClose = {
                    // Final sync still inside the boundary so close follows a durable flush.
                    file.fd.sync()
                    file.close()
                },
                extentGrew = { extentGrew },
                engine = engine,
            )
        } finally {
            runCatching { file.close() }
        }
    }

    /**
     * Generic durable boundary for any [RescueSink]: [onCheckpoint] must sync the image
     * before persisting the map, [onFinalClose] must close (and if possible sync) the
     * destination. Completion is set only after the final close succeeds.
     *
     * [engine] overrides [Rescue.copy] for host fault tests; `null` keeps the production
     * engine and its [checkpointEverySectors] cadence. [extentGrew] reports that the
     * destination only grew (no sector written): that is Partial but not a disk write.
     */
    fun copy(
        disk: UsbDisk,
        sink: RescueSink,
        map: RescueMap,
        operation: DiskOperation,
        onCheckpoint: (RescueMap) -> Unit,
        onFinalImageSync: () -> Unit,
        onFinalCheckpoint: (RescueMap) -> Unit,
        onFinalClose: () -> Unit,
        onPublishCanonical: () -> Unit = {},
        checkpointEverySectors: ULong = 4096UL,
        maxRetries: Int = 1,
        extentGrew: () -> Boolean = { false },
        engine: (
            (UsbDisk, RescueSink, RescueMap, (RescueMap) -> Unit, DiskOperation) -> RescueMap
        )? = null,
    ): RescueMap {
        // A sink write is both a disk mutation and a durable side-effect; an image that
        // only grew is a side-effect alone and comes in through [extentGrew].
        var imageTouched = operation.mutationAttempted
        var closed = false
        val destination =
            object : RescueSink {
                override val positional: Boolean get() = sink.positional

                override fun ensureLength(bytes: Long) = sink.ensureLength(bytes)

                override fun completeLength(bytes: Long) = sink.completeLength(bytes)
                override fun writeSector(lba: ULong, sector: ByteArray) {
                    if (!imageTouched) {
                        imageTouched = true
                        operation.mutationAttempted = true
                    }
                    sink.writeSector(lba, sector)
                }

                override fun writeBatch(
                    lba: ULong,
                    bytes: ByteArray,
                    length: Int,
                    sectorSize: Int,
                ) {
                    if (!imageTouched) {
                        imageTouched = true
                        operation.mutationAttempted = true
                    }
                    sink.writeBatch(lba, bytes, length, sectorSize)
                }
            }
        try {
            val override = engine
            if (override != null) {
                override(disk, destination, map, onCheckpoint, operation)
            } else {
                Rescue.copy(
                    disk,
                    destination,
                    map,
                    checkpointEverySectors = checkpointEverySectors,
                    checkpoint = onCheckpoint,
                    operation = operation,
                    maxRetries = maxRetries,
                )
            }
            imageTouched = true
            operation.mutationAttempted = true
            destination.completeLength(disk.geometry.capacityBytes.toLong())
            onFinalImageSync()
            onFinalCheckpoint(map)
            onFinalClose()
            closed = true
            when (operation.state) {
                is OperationState.Partial, is OperationState.Failed -> Unit
                else -> {
                    if (map.failed.isEmpty() && !map.partial) {
                        // Canonical publication is a success-only step; on failure/cancel
                        // only the verified checkpoint generations remain.
                        onPublishCanonical()
                        operation.commitReached = true
                        operation.state = OperationState.Completed
                    }
                }
            }
        } catch (failure: Throwable) {
            if (imageTouched || extentGrew()) {
                when (operation.state) {
                    is OperationState.Partial, is OperationState.Failed -> Unit
                    else -> {
                        operation.state = OperationState.Partial
                        if (operation.failureCause == null) operation.failureCause = failure
                    }
                }
            }
            throw failure
        } finally {
            if (!closed) {
                runCatching { onFinalClose() }
            }
        }
        return map
    }
}
