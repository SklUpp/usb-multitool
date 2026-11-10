package usb.ventoy

import usb.device.BlockDevice
import usb.operation.DiskOperation

/** Coordinates Ventoy asset loading and mutation, preserving progress and Partial semantics. */
object VentoyOperations {
    fun install(
        disk: BlockDevice,
        assets: VentoyAssets,
        operation: DiskOperation,
    ) = mutate(operation) {
        VentoyInstaller.install(disk, assets, isActive = operation::isActive) { done, total ->
            operation.bytesTotal = total
            operation.report(done)
        }
    }

    fun update(
        disk: BlockDevice,
        assets: VentoyAssets,
        operation: DiskOperation,
    ) = mutate(operation) {
        VentoyInstaller.update(disk, assets, isActive = operation::isActive) { done, total ->
            operation.bytesTotal = total
            operation.report(done)
        }
    }

    private inline fun mutate(operation: DiskOperation, body: () -> Unit) {
        try {
            body()
        } catch (failure: VentoyMutationFailedException) {
            operation.state = usb.operation.OperationState.Partial
            operation.failureCause = failure
            throw failure
        }
    }
}
