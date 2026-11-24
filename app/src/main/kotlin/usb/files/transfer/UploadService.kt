package usb.files.transfer

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.CancellationException
import usb.files.BatchSafFolder
import usb.files.FilesOperationPolicy
import usb.files.OpenableSafHelpers
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.image.MeasurementHandle
import usb.operation.DiskOperation
import usb.saf.SafBackupFolder
import usb.saf.SafDocument

/** Single-file and tree upload into a USB volume. */
object UploadService {
    fun upload(
        resolver: ContentResolver,
        uri: Uri,
        target: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        onCleanupFailure: (Throwable) -> Unit = {},
    ) {
        // Providers often report 0 for documents whose size is not known yet. A real
        // empty file still works through the unknown-size path (stream ends at once).
        val expected = OpenableSafHelpers.openableSizeBytes(resolver, uri)?.takeIf { it > 0L }
        val handle = MeasurementHandle()
        val cancelSource: () -> Unit = { handle.cancel() }
        operation.onInactive(cancelSource)
        var source: java.io.InputStream? = null
        var primary: Throwable? = null
        try {
            if (!handle.isActive()) throw CancellationException("Upload cancelled")
            source = resolver.openInputStream(uri) ?: error("cannot open $uri for reading")
            handle.attach(source)
            upload(source, target, volume, operation, chunkBytes, expected, onCleanupFailure)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            operation.removeInactiveListener(cancelSource)
            try {
                source?.let(handle::releaseOrThrow)
            } catch (closeFailure: Throwable) {
                // A close failure after a complete upload must not turn a committed
                // file into a reported failure; keep it only as a diagnostic.
                primary?.addSuppressed(closeFailure)
            } finally {
                handle.abandon()
            }
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
        targetAlreadyOwned: Boolean = false,
    ) {
        if (!targetAlreadyOwned) {
            require(volume.statOrNull(target) == null) { FilesOperationPolicy.duplicateMessage(target) }
        }
        OpenableSafHelpers.requireFreeSpaceBeforeWrite(volume, knownSizeBytes)
        val plannedTotal = knownSizeBytes?.takeIf { it >= 0L } ?: -1L
        operation.beginCurrentFile(plannedTotal)
        val buffer = ByteArray(chunkBytes)
        var offset = 0L
        var created = targetAlreadyOwned
        try {
            while (true) {
                if (!operation.isActive()) throw CancellationException("Upload cancelled")
                val read = input.read(buffer)
                if (!operation.isActive()) throw CancellationException("Upload cancelled")
                if (read <= 0) break
                if (plannedTotal >= 0L && offset + read > plannedTotal) {
                    throw IOException(
                        "upload size mismatch: expected $plannedTotal bytes, stream exceeded it",
                    )
                }
                val data = if (read == buffer.size) buffer else buffer.copyOf(read)
                val firstWrite = !created
                val planned = if (plannedTotal >= 0L) maxOf(plannedTotal, offset + data.size.toLong())
                else offset + data.size.toLong()
                operation.mutationAttempted = true
                volume.write(target, data, offset = offset, create = firstWrite, plannedTotalSize = planned, exclusiveCreate = firstWrite)
                if (firstWrite) {
                    created = true
                }
                offset += read
                operation.report(offset)
            }
            if (plannedTotal >= 0L && offset != plannedTotal) {
                throw IOException(
                    "upload size mismatch: expected $plannedTotal bytes, got $offset",
                )
            }
            if (!created) {
                operation.mutationAttempted = true
                volume.write(
                    target,
                    ByteArray(0),
                    create = true,
                    plannedTotalSize = if (plannedTotal >= 0L) plannedTotal else 0L,
                    exclusiveCreate = true,
                )
                created = true
            }
            if (!operation.isActive()) throw CancellationException("Upload cancelled")
            volume.sync()
        } catch (failure: Throwable) {
            if (created && !targetAlreadyOwned) {
                try {
                    if (volume.statOrNull(target) != null) {
                        runCatching { volume.delete(target); volume.sync() }
                            .onFailure { cleanup -> failure.addSuppressed(cleanup); onCleanupFailure(cleanup) }
                    }
                } catch (cleanupStat: Throwable) {
                    failure.addSuppressed(cleanupStat)
                    onCleanupFailure(cleanupStat)
                }
            }
            throw failure
        }
    }


    internal fun uniqueUploadSibling(volume: UsbFsVolume, parent: String, kind: String): String {
        val base = if (parent.isEmpty() || parent == "/") "/" else parent
        val nonce = "${System.currentTimeMillis().toString(36)}-${System.nanoTime().toString(36)}"
        fun path(suffix: String): String {
            val name = ".up-$kind-$nonce$suffix"
            return if (base == "/") "/$name" else "$base/$name"
        }
        var candidate = path("")
        var n = 0
        while (pathOccupied(volume, candidate)) {
            if (++n > 16) throw IOException("cannot allocate temporary upload name under $base")
            candidate = path("-$n")
        }
        return candidate
    }

    /** Case-insensitive occupancy; true for file or directory. */
    internal fun pathOccupied(volume: UsbFsVolume, path: String): Boolean {
        if (volume.statOrNull(path) != null) return true
        val name = path.substringAfterLast('/')
        val parent = parentOf(path)
        return volume.list(parent).any { it.name.equals(name, ignoreCase = true) }
    }

    internal fun parentOf(path: String): String {
        val normalized = FilesOperationPolicy.normalizeVolumePathExact(path)
        if (normalized == "/") return "/"
        val cut = normalized.lastIndexOf('/')
        return if (cut <= 0) "/" else normalized.substring(0, cut)
    }

    /**
     * Stream a replacement into an operation-owned temp, then swap through a backup.
     * Original survives open/read/space/cancel failures; the temp is removed on failure.
     */
    internal fun replaceUploadedFile(
        resolver: ContentResolver,
        uri: Uri,
        target: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        onCleanupFailure: (Throwable) -> Unit,
    ) {
        val parent = parentOf(target)
        val temp = uniqueUploadSibling(volume, parent, "t")
        val backup = uniqueUploadSibling(volume, parent, "b")
        val mutationBefore = operation.mutationAttempted
        var tempOwned = false
        var backedUp = false
        var moved = false
        try {
            upload(
                resolver = resolver,
                uri = uri,
                target = temp,
                volume = volume,
                operation = operation,
                chunkBytes = chunkBytes,
                onCleanupFailure = onCleanupFailure,
            )
            tempOwned = true
            if (!operation.isActive()) throw CancellationException("Upload cancelled")
            volume.sync()
            if (volume.statOrNull(target) != null) {
                volume.rename(target, backup)
                backedUp = true
                volume.sync()
                if (!operation.isActive()) throw CancellationException("Upload cancelled")
            }
            volume.rename(temp, target)
            moved = true
            tempOwned = false
            volume.sync()
        } catch (failure: Throwable) {
            if (moved) runCatching { volume.delete(target) }.onFailure { failure.addSuppressed(it) }
            if (backedUp) runCatching { volume.rename(backup, target) }.onFailure { failure.addSuppressed(it) }
            if (tempOwned) {
                runCatching {
                    if (volume.statOrNull(temp) != null) volume.delete(temp)
                }.onFailure { cleanup -> failure.addSuppressed(cleanup); onCleanupFailure(cleanup) }
            }
            var cleanupSynced = true
            runCatching { volume.sync() }.onFailure {
                failure.addSuppressed(it)
                cleanupSynced = false
            }
            if (!moved && !backedUp && cleanupSynced &&
                runCatching { volume.statOrNull(temp) == null }.getOrDefault(false)
            ) {
                // The temp is gone and the original was never renamed: no user-visible
                // mutation remains, so an earlier batch failure is not made Partial here.
                operation.mutationAttempted = mutationBefore
            }
            throw failure
        }
        if (backedUp) {
            try {
                volume.delete(backup)
                volume.sync()
            } catch (failure: Throwable) {
                onCleanupFailure(failure)
                throw IOException("replacement committed but old backup cleanup failed", failure)
            }
        }
    }

    /**
     * Upload SAF tree roots into USB [destDir]. Empty USB directories are reused;
     * nonempty USB directories are fail-closed. ReplaceFile is file-only.
     */
    fun uploadTree(
        resolver: ContentResolver,
        sources: List<SafDocument>,
        sourceTree: SafBackupFolder,
        destDir: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        conflict: (TransferConflict) -> TransferDecision,
        onCleanupFailure: (Throwable) -> Unit = {},
        onProgress: ((String) -> Unit)? = null,
    ) {
        uploadTree(
            resolver = resolver,
            sources = sources,
            sourceTree = BatchSafFolder.of(sourceTree),
            destDir = destDir,
            volume = volume,
            operation = operation,
            chunkBytes = chunkBytes,
            conflict = conflict,
            onCleanupFailure = onCleanupFailure,
            onProgress = onProgress,
        )
    }

    internal fun uploadTree(
        resolver: ContentResolver,
        sources: List<SafDocument>,
        sourceTree: BatchSafFolder,
        destDir: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        conflict: (TransferConflict) -> TransferDecision,
        onCleanupFailure: (Throwable) -> Unit = {},
        onProgress: ((String) -> Unit)? = null,
    ) {
        val parent = FilesOperationPolicy.normalizeVolumePathExact(destDir)
        try {
            for (source in sources) {
                if (!operation.isActive()) throw CancellationException("Upload cancelled")
                val name = FilesOperationPolicy.requireChildName(source.name, parent)
                uploadSafNode(
                    resolver = resolver,
                    source = source,
                    sourceTree = sourceTree,
                    destPath = FilesOperationPolicy.resolveInsideTree(parent, name),
                    volume = volume,
                    operation = operation,
                    chunkBytes = chunkBytes,
                    conflict = conflict,
                    onCleanupFailure = onCleanupFailure,
                    onProgress = onProgress,
                )
            }
            if (operation.mutationAttempted) volume.sync()
        } catch (failure: Throwable) {
            if (operation.mutationAttempted) {
                runCatching { volume.sync() }.onFailure { failure.addSuppressed(it) }
            }
            TransferSupport.markPartialIfMutated(operation, failure)
            throw failure
        }
    }

    internal fun uploadSafNode(
        resolver: ContentResolver,
        source: SafDocument,
        sourceTree: BatchSafFolder,
        destPath: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        conflict: (TransferConflict) -> TransferDecision,
        onCleanupFailure: (Throwable) -> Unit,
        onProgress: ((String) -> Unit)?,
    ) {
        if (!operation.isActive()) throw CancellationException("Upload cancelled")
        val name = FilesOperationPolicy.requireChildName(
            destPath.trim('/').substringAfterLast('/'),
            destPath,
        )
        val existing = volume.statOrNull(destPath)
        if (source.isDirectory) {
            val destDirReady = resolveUploadDirectory(
                destPath = destPath,
                name = name,
                existing = existing,
                volume = volume,
                operation = operation,
                conflict = conflict,
            ) ?: return
            for (child in sourceTree.listChildren(source.uri)) {
                if (!operation.isActive()) throw CancellationException("Upload cancelled")
                val childName = FilesOperationPolicy.requireChildName(child.name, destDirReady)
                uploadSafNode(
                    resolver = resolver,
                    source = child,
                    sourceTree = sourceTree,
                    destPath = FilesOperationPolicy.resolveInsideTree(destDirReady, childName),
                    volume = volume,
                    operation = operation,
                    chunkBytes = chunkBytes,
                    conflict = conflict,
                    onCleanupFailure = onCleanupFailure,
                    onProgress = onProgress,
                )
            }
            onProgress?.invoke(destPath)
            return
        }
        if (!resolveUploadFile(
                destPath = destPath,
                name = name,
                existing = existing,
                volume = volume,
                operation = operation,
                conflict = conflict,
            )
        ) {
            return
        }
        onProgress?.invoke(destPath)
        if (existing != null && !existing.isDirectory) {
            replaceUploadedFile(
                resolver = resolver,
                uri = source.uri,
                target = destPath,
                volume = volume,
                operation = operation,
                chunkBytes = chunkBytes,
                onCleanupFailure = onCleanupFailure,
            )
        } else {
            upload(
                resolver = resolver,
                uri = source.uri,
                target = destPath,
                volume = volume,
                operation = operation,
                chunkBytes = chunkBytes,
                onCleanupFailure = onCleanupFailure,
            )
        }
    }

    internal fun resolveUploadDirectory(
        destPath: String,
        name: String,
        existing: UsbFsDirEntry?,
        volume: UsbFsVolume,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
    ): String? {
        if (existing == null) {
            operation.mutationAttempted = true
            volume.mkdir(destPath)
            return destPath
        }
        if (!existing.isDirectory) {
            when (
                TransferSupport.decideConflict(
                    TransferConflict(pathLabel = destPath, name = name, isDirectory = false),
                    conflict,
                )
            ) {
                TransferDecision.Skip -> return null
                TransferDecision.Cancel -> throw CancellationException("Upload cancelled")
                TransferDecision.ReplaceFile ->
                    throw IOException("Cannot safely replace file \"$destPath\" with a directory")
            }
        }

        val children = volume.list(destPath)
        if (children.isEmpty()) {
            return destPath
        }
        when (
            TransferSupport.decideConflict(
                TransferConflict(
                    pathLabel = destPath,
                    name = name,
                    isDirectory = true,
                    destinationEmpty = false,
                ),
                conflict,
            )
        ) {
            TransferDecision.Skip -> return null
            TransferDecision.Cancel -> throw CancellationException("Upload cancelled")
            TransferDecision.ReplaceFile ->
                throw IOException(FilesOperationPolicy.nonemptyDirectoryRefuseMessage(destPath))
        }
    }

    internal fun resolveUploadFile(
        destPath: String,
        name: String,
        existing: UsbFsDirEntry?,
        volume: UsbFsVolume,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
    ): Boolean {
        if (existing == null) return true
        if (existing.isDirectory) {
            val empty = volume.list(destPath).isEmpty()
            when (
                TransferSupport.decideConflict(
                    TransferConflict(
                        pathLabel = destPath,
                        name = name,
                        isDirectory = true,
                        destinationEmpty = empty,
                    ),
                    conflict,
                )
            ) {
                TransferDecision.Skip -> return false
                TransferDecision.Cancel -> throw CancellationException("Upload cancelled")
                TransferDecision.ReplaceFile ->
                    throw IOException(
                        if (empty) {
                            "Cannot replace directory \"$destPath\" with a file"
                        } else {
                            FilesOperationPolicy.nonemptyDirectoryRefuseMessage(destPath)
                        },
                    )
            }
        }
        when (
            TransferSupport.decideConflict(
                TransferConflict(pathLabel = destPath, name = name, isDirectory = false),
                conflict,
            )
        ) {
            TransferDecision.Skip -> return false
            TransferDecision.Cancel -> throw CancellationException("Upload cancelled")
            TransferDecision.ReplaceFile -> return true
        }
    }

}
