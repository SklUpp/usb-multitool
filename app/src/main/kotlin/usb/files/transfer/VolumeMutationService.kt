package usb.files.transfer

import kotlinx.coroutines.CancellationException
import usb.files.FilesOperationPolicy
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.operation.OperationState

/** Primitive volume mutations + recursive delete. */
object VolumeMutationService {
    fun list(volume: UsbFsVolume, path: String): List<UsbFsDirEntry> = volume.list(path)

    fun delete(volume: UsbFsVolume, target: String) {
        volume.delete(target)
        volume.sync()
    }

    fun rename(volume: UsbFsVolume, from: String, to: String) {
        volume.rename(from, to)
        volume.sync()
    }

    /**
     * Depth-first delete: children then self. Syncs once at the end of the top call
     * (unlike single [delete], which syncs per entry). On failure after any mutation,
     * best-effort sync still runs and the caller sees Partial via [operation].
     */
    fun deleteRecursively(
        volume: UsbFsVolume,
        target: String,
        operation: DiskOperation,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val path = FilesOperationPolicy.requireVolumePath(target)
        require(path != "/") {
            "refusing recursive delete of volume root"
        }
        try {
            deleteRecursivelyBody(volume, path, operation, onProgress)
            volume.sync()
        } catch (failure: Throwable) {
            if (operation.mutationAttempted) {
                runCatching { volume.sync() }.onFailure { failure.addSuppressed(it) }
                if (operation.state != OperationState.Partial) {
                    operation.state = OperationState.Partial
                    if (operation.failureCause == null) operation.failureCause = failure
                }
            }
            throw failure
        }
    }

    private fun deleteRecursivelyBody(
        volume: UsbFsVolume,
        target: String,
        operation: DiskOperation,
        onProgress: ((String) -> Unit)?,
    ) {
        if (!operation.isActive()) throw CancellationException("Delete cancelled")
        val entry = volume.stat(target)
        if (entry.isDirectory) {
            for (child in volume.list(target)) {
                val childName = FilesOperationPolicy.requireChildName(child.name, target)
                val childPath = FilesOperationPolicy.resolveInsideTree(target, childName)
                deleteRecursivelyBody(volume, childPath, operation, onProgress)
            }
        }
        if (!operation.isActive()) throw CancellationException("Delete cancelled")
        operation.mutationAttempted = true
        onProgress?.invoke(target)
        volume.delete(target)
    }
}
