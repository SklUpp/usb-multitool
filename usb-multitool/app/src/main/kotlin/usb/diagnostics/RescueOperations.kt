package usb.diagnostics

import usb.device.UsbDisk
import usb.image.ImageSource
import usb.operation.DiskOperation

/** Rescue mutation entry point; owns restore invocation and checkpoint persistence. */
object RescueOperations {
    data class RestoreResult(val copied: ULong, val failed: ULong, val partial: Boolean)

    fun restore(
        source: ImageSource,
        disk: UsbDisk,
        map: RescueMap,
        identity: String,
        confirmation: DestructiveConfirmation,
        checkpointFile: java.io.File,
        operation: DiskOperation,
    ): RestoreResult {
        Rescue.restore(
            source = source,
            disk = disk,
            map = map,
            identity = identity,
            confirmation = confirmation,
            checkpoint = { RescueCheckpoint.save(it, checkpointFile) },
            operation = operation,
        )
        return RestoreResult(
            copied = map.copied.sumOf { it.last - it.first + 1UL },
            failed = map.failed.sumOf { it.last - it.first + 1UL },
            partial = map.partial,
        )
    }
}
