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
        var imageTouched = false
        try {
            RandomAccessFile(image, "rw").use { file ->
                Rescue.copy(
                    disk,
                    object : RescueSink {
                        override fun writeSector(lba: ULong, sector: ByteArray) =
                            writeBatch(lba, sector, sector.size, disk.sectorSize)

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
                            file.seek((lba * sectorSize.toULong()).toLong())
                            file.write(bytes, 0, length)
                        }
                    },
                    map,
                    checkpoint = { snapshot ->
                        file.fd.sync()
                        RescueCheckpoint.save(snapshot, mapFile)
                    },
                    operation = operation,
                )
                // Final image sync still inside use{} so close follows a durable flush.
                onFinalImageSync(file)
            }
            onFinalCheckpoint(map, mapFile)
            when (operation.state) {
                is OperationState.Partial, is OperationState.Failed -> Unit
                else -> {
                    if (map.failed.isEmpty() && !map.partial) {
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
        }
    }
}
