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
    ) {
        val file = RandomAccessFile(image, "rw")
        try {
            copy(
                disk = disk,
                sink = object : RescueSink {
                    override fun writeSector(lba: ULong, sector: ByteArray) =
                        writeBatch(lba, sector, sector.size, disk.sectorSize)

                    override fun writeBatch(
                        lba: ULong,
                        bytes: ByteArray,
                        length: Int,
                        sectorSize: Int,
                    ) {
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
            )
        } finally {
            runCatching { file.close() }
        }
    }

    /**
     * Generic durable boundary for any [RescueSink]: [onCheckpoint] must sync the image
     * before persisting the map, [onFinalClose] must close (and if possible sync) the
     * destination. Completion is set only after the final close succeeds.
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
    ): RescueMap {
        var imageTouched = false
        var closed = false
        try {
            Rescue.copy(
                disk,
                object : RescueSink {
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
                },
                map,
                checkpointEverySectors = checkpointEverySectors,
                checkpoint = onCheckpoint,
                operation = operation,
            )
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
            if (imageTouched) {
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
