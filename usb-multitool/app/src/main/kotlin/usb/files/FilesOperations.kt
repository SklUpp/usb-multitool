package usb.files

import android.content.ContentResolver
import android.net.Uri
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.service.DiskOperation

/** Filesystem operation bodies. Mount/session ownership stays with coordinator. */
object FilesOperations {
    fun list(volume: UsbFsVolume, path: String): List<UsbFsDirEntry> = volume.list(path)

    fun delete(volume: UsbFsVolume, target: String) {
        volume.delete(target)
        volume.sync()
    }

    fun rename(volume: UsbFsVolume, from: String, to: String) {
        volume.rename(from, to)
        volume.sync()
    }

    fun upload(
        resolver: ContentResolver,
        uri: Uri,
        target: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        onCleanupFailure: (Throwable) -> Unit = {},
    ) {
        val input = resolver.openInputStream(uri) ?: error("cannot open $uri for reading")
        input.use { stream ->
            upload(stream, target, volume, operation, chunkBytes, onCleanupFailure)
        }
    }

    internal fun upload(
        input: java.io.InputStream,
        target: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        onCleanupFailure: (Throwable) -> Unit = {},
    ) {
        require(volume.statOrNull(target) == null) { FilesOperationPolicy.duplicateMessage(target) }
        val buffer = ByteArray(chunkBytes)
        var offset = 0L
        var created = false
        try {
            while (true) {
                if (!operation.isActive()) throw kotlinx.coroutines.CancellationException("Upload cancelled")
                val read = input.read(buffer)
                if (read <= 0) break
                val data = if (read == buffer.size) buffer else buffer.copyOf(read)
                val firstWrite = !created
                created = true
                volume.write(target, data, offset = offset, create = firstWrite)
                offset += read
                operation.report(offset)
            }
            if (!created) {
                created = true
                volume.write(target, ByteArray(0), create = true)
            }
            volume.sync()
        } catch (failure: Throwable) {
            if (created) {
                try {
                    if (volume.statOrNull(target) != null) runCatching { volume.delete(target); volume.sync() }
                        .onFailure { cleanup -> failure.addSuppressed(cleanup); onCleanupFailure(cleanup) }
                } catch (cleanupStat: Throwable) {
                    failure.addSuppressed(cleanupStat)
                    onCleanupFailure(cleanupStat)
                }
            }
            throw failure
        }
    }

    fun download(
        resolver: ContentResolver,
        target: String,
        destination: Uri,
        volume: UsbFsVolume,
        operation: DiskOperation,
    ) {
        val size = volume.stat(target).size
        operation.bytesTotal = size
        val output = resolver.openOutputStream(destination, "wt") ?: error("cannot open $destination for writing")
        output.use { stream ->
            var offset = 0L
            try {
                while (offset < size) {
                    if (!operation.isActive()) throw kotlinx.coroutines.CancellationException("Download cancelled")
                    val data = volume.read(target, offset, minOf(1024 * 1024L, size - offset).toInt())
                    if (data.isEmpty()) error("short read at $offset of $size bytes")
                    stream.write(data)
                    offset += data.size
                    operation.report(offset)
                }
                stream.flush()
            } catch (failure: Throwable) {
                operation.state = usb.service.OperationState.Partial
                operation.failureCause = usb.device.DiskException.IoFailure("Download destination truncated ($offset of $size bytes)", failure)
                throw failure
            }
        }
    }
}
