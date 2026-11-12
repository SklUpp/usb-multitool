package usb.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import usb.fs.FilesystemSpace
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsUsageVolume
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation

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
        val knownSize = openableSizeBytes(resolver, uri)
        val input = resolver.openInputStream(uri) ?: error("cannot open $uri for reading")
        input.use { stream ->
            upload(
                stream,
                target,
                volume,
                operation,
                chunkBytes,
                knownSizeBytes = knownSize,
                onCleanupFailure = onCleanupFailure,
            )
        }
    }

    internal fun upload(
        input: java.io.InputStream,
        target: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        knownSizeBytes: Long? = null,
        onCleanupFailure: (Throwable) -> Unit = {},
    ) {
        require(volume.statOrNull(target) == null) { FilesOperationPolicy.duplicateMessage(target) }
        // Reject only when both size and free space are known. Unknown size or an
        // unreliable space() query skips preflight rather than inventing a failure.
        requireFreeSpaceBeforeWrite(volume, knownSizeBytes)
        val plannedTotal =
            knownSizeBytes?.takeIf { it >= 0L } ?: -1L
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
                val planned =
                    if (plannedTotal >= 0L) maxOf(plannedTotal, offset + data.size.toLong())
                    else offset + data.size.toLong()
                volume.write(target, data, offset = offset, create = firstWrite, plannedTotalSize = planned)
                offset += read
                operation.report(offset)
            }
            if (!created) {
                created = true
                volume.write(
                    target,
                    ByteArray(0),
                    create = true,
                    plannedTotalSize = if (plannedTotal >= 0L) plannedTotal else 0L,
                )
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
        // Snapshot once: a Diagnostics edit must not resize an in-flight transfer.
        val readCap = usb.device.TransferTuning.jniCapBytes
        val output = resolver.openOutputStream(destination, "wt") ?: error("cannot open $destination for writing")
        output.use { stream ->
            var offset = 0L
            try {
                while (offset < size) {
                    if (!operation.isActive()) throw kotlinx.coroutines.CancellationException("Download cancelled")
                    val data = volume.read(target, offset, minOf(readCap.toLong(), size - offset).toInt())
                    if (data.isEmpty()) error("short read at $offset of $size bytes")
                    stream.write(data)
                    offset += data.size
                    operation.report(offset)
                }
                stream.flush()
            } catch (failure: Throwable) {
                operation.state = usb.operation.OperationState.Partial
                operation.failureCause = usb.device.DiskException.IoFailure("Download destination truncated ($offset of $size bytes)", failure)
                throw failure
            }
        }
    }

    /**
     * SAF/content size when the provider reports a non-negative value; null when
     * unknown so upload can skip free-space preflight instead of guessing.
     */
    internal fun openableSizeBytes(resolver: ContentResolver, uri: Uri): Long? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) return null
                cursor.getLong(index).takeIf { it >= 0L }
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * When [needBytes] is known and [volume] can report free space, refuse before the
     * first write. Missing usage support, failed space queries, or unknown size leave
     * the stream path unchanged (no invented reject).
     */
    internal fun requireFreeSpaceBeforeWrite(volume: UsbFsVolume, needBytes: Long?) {
        if (needBytes == null || needBytes < 0L) return
        if (needBytes == 0L) return
        val usage = volume as? UsbFsUsageVolume ?: return
        val space: FilesystemSpace =
            try {
                usage.space()
            } catch (_: Throwable) {
                return
            }
        if (space.freeBytes < needBytes.toULong()) {
            throw IOException(FilesOperationPolicy.insufficientSpaceMessage(space.freeBytes, needBytes))
        }
    }
}
