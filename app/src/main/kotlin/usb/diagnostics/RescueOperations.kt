package usb.diagnostics

import usb.device.UsbDisk
import usb.image.ImageSource
import usb.operation.DiskOperation
import usb.operation.OperationState

/** Rescue mutation entry point; owns restore invocation and checkpoint persistence. */
object RescueOperations {
    data class RestoreResult(val copied: ULong, val failed: ULong, val partial: Boolean)

    fun restore(
        source: ImageSource,
        disk: UsbDisk,
        map: RescueMap,
        identity: String,
        confirmation: DestructiveConfirmation,
        checkpointFile: java.io.File? = null,
        checkpoint: ((RescueMap) -> Unit)? = null,
        operation: DiskOperation,
    ): RestoreResult {
        val persist = checkpoint ?: { mapToSave ->
            RescueCheckpoint.save(mapToSave, requireNotNull(checkpointFile) {
                "restore needs a checkpoint file or publisher"
            })
        }
        Rescue.restore(
            source = source,
            disk = disk,
            map = map,
            identity = identity,
            confirmation = confirmation,
            checkpoint = persist,
            operation = operation,
        )
        return RestoreResult(
            copied = map.copied.sumOf { it.last - it.first + 1UL },
            failed = map.failed.sumOf { it.last - it.first + 1UL },
            partial = map.partial || operation.state is OperationState.Partial,
        )
    }
}
