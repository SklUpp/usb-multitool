package usb.ventoy

import usb.device.BlockDevice
import usb.operation.DiskOperation

/**
 * Coordinates Ventoy asset loading and mutation, preserving progress and Partial semantics.
 * Chosen global settings are written only after the layout operation succeeded: a settings
 * failure after install/update is a Partial with the real cause, never a clean success.
 */
object VentoyOperations {
    fun install(
        disk: BlockDevice,
        assets: VentoyAssets,
        operation: DiskOperation,
        controls: List<VentoyControlSetting> = emptyList(),
    ) = mutate(operation) {
        VentoyInstaller.install(disk, assets, isActive = operation::isActive) { done, total ->
            operation.bytesTotal = total
            operation.report(done)
        }
        persistControls(disk, controls, operation, after = "installed")
    }

    fun update(
        disk: BlockDevice,
        assets: VentoyAssets,
        operation: DiskOperation,
        controls: List<VentoyControlSetting> = emptyList(),
    ) = update(disk, assets, operation, controls, VentoyLayout::readVentoyVersionFromPart2)

    /** Same update with an injected version reader (tests only). */
    internal fun update(
        disk: BlockDevice,
        assets: VentoyAssets,
        operation: DiskOperation,
        controls: List<VentoyControlSetting> = emptyList(),
        versionReader: (BlockDevice, ULong, ULong) -> Result<String>,
    ) = mutate(operation) {
        VentoyInstaller.updateChecked(
            disk = disk,
            assets = assets,
            isActive = operation::isActive,
            onProgress = { done, total ->
                operation.bytesTotal = total
                operation.report(done)
            },
            versionReader = versionReader,
        )
        // No controls means no touch of partition 1: an update keeps settings byte-for-byte.
        persistControls(disk, controls, operation, after = "updated")
    }

    internal fun persistControls(
        disk: BlockDevice,
        controls: List<VentoyControlSetting>,
        operation: DiskOperation,
        after: String,
        apply: (BlockDevice, List<VentoyControlSetting>, () -> Unit) -> Boolean =
            { target, settings, onMutation ->
                VentoySetup.applyGlobalControls(target, settings, onMutation)
            },
    ): Boolean {
        if (controls.isEmpty()) return false
        try {
            return apply(disk, controls) { operation.mutationAttempted = true }
        } catch (failure: Throwable) {
            val wrapped =
                VentoyMutationFailedException(
                    "Ventoy was $after but saving the chosen global settings failed: " +
                        (failure.message ?: failure.javaClass.simpleName) +
                        ". Retry with Apply settings; any previous ventoy.json is kept.",
                    failure,
                )
            operation.state = usb.operation.OperationState.Partial
            operation.failureCause = wrapped
            throw wrapped
        }
    }

    private inline fun <T> mutate(operation: DiskOperation, body: () -> T): T {
        try {
            return body()
        } catch (failure: VentoyMutationFailedException) {
            operation.state = usb.operation.OperationState.Partial
            operation.failureCause = failure
            throw failure
        }
    }
}
