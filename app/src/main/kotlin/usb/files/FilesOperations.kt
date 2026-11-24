package usb.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import usb.fs.FilesystemSpace
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsUsageVolume
import usb.fs.UsbFsVolume
import usb.image.MeasurementHandle
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.saf.SafBackupFolder
import usb.saf.SafCleanupPolicy
import usb.saf.SafDocument
import usb.ui.FilesConflict
import usb.ui.FilesConflictDecision


/** Filesystem operation bodies. Mount/session ownership stays with coordinator. */
object FilesOperations {
    /**
     * Park/resume cursor for [downloadChildren]. [remainingRoots] are not-yet-started
     * sibling roots; when [activeRoot] is set, [remainingRelative] continues inside that
     * root (including the conflicted entry) without re-placing the nonempty dest dir.
     */
    data class BatchDownloadCursor(
        val remainingRoots: List<String>,
        val activeRoot: String? = null,
        val remainingRelative: List<String> = emptyList(),
    )

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
        // Providers often report 0 for documents whose size is not known yet. A real
        // empty file still works through the unknown-size path (stream ends at once).
        val expected = openableSizeBytes(resolver, uri)?.takeIf { it > 0L }
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
        requireFreeSpaceBeforeWrite(volume, knownSizeBytes)
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


    private fun uniqueUploadSibling(volume: UsbFsVolume, parent: String, kind: String): String {
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
    private fun pathOccupied(volume: UsbFsVolume, path: String): Boolean {
        if (volume.statOrNull(path) != null) return true
        val name = path.substringAfterLast('/')
        val parent = parentOf(path)
        return volume.list(parent).any { it.name.equals(name, ignoreCase = true) }
    }

    private fun parentOf(path: String): String {
        val normalized = FilesOperationPolicy.normalizeVolumePathExact(path)
        if (normalized == "/") return "/"
        val cut = normalized.lastIndexOf('/')
        return if (cut <= 0) "/" else normalized.substring(0, cut)
    }

    /**
     * Stream a replacement into an operation-owned temp, then swap through a backup.
     * Original survives open/read/space/cancel failures; the temp is removed on failure.
     */
    private fun replaceUploadedFile(
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
     * Streams USB bytes into a caller-owned SAF destination without a full local copy. On
     * failure the freshly created document is deleted unless the provider refuses, in which
     * case the Partial error says the destination was retained.
     */
    fun download(
        resolver: ContentResolver,
        target: String,
        destination: Uri,
        volume: UsbFsVolume,
        operation: DiskOperation,
        expectedSize: Long? = null,
    ) {
        var offset = 0L
        var size = 0L
        try {
            val source = volume.stat(target)
            require(!source.isDirectory) { "download source is a directory: $target" }
            val statSize = source.size
            // A stale driver stat must not turn a nonempty source into an empty export.
            size = if (statSize == 0L && expectedSize != null && expectedSize > 0L) expectedSize else statSize
            operation.beginCurrentFile(size)
            if (expectedSize != null && expectedSize > 0L && statSize > 0L && statSize != expectedSize) {
                error("source size changed: listed $expectedSize, now $statSize")
            }
            if (!operation.isActive()) throw CancellationException("Download cancelled")
            operation.mutationAttempted = true
            offset = SafWriter(resolver).write(destination, expectedSize = size) { stream ->
                streamVolume(volume, target, size, stream, operation)
            }
        } catch (failure: SafWriteException) {
            val original = failure.cause ?: failure
            operation.state = OperationState.Partial
            operation.failureCause = usb.device.DiskException.IoFailure(
                "Download failed after ${failure.bytesWritten} of $size bytes; ${failure.cleanupNote}",
                original,
            )
            throw original
        } catch (failure: Throwable) {
            val cleanupNote = SafCleanupPolicy.emptyDestinationNote(
                offset == 0L && SafWriter.deleteDocument(resolver, destination, failure),
            )
            operation.state = OperationState.Partial
            operation.failureCause = usb.device.DiskException.IoFailure(
                "Download failed after $offset of $size bytes; $cleanupNote",
                failure,
            )
            throw failure
        }
    }

    /** Copy [size] bytes from [volume] to [stream], reporting progress. */
    private fun streamVolume(
        volume: UsbFsVolume,
        sourcePath: String,
        size: Long,
        stream: OutputStream,
        operation: DiskOperation,
    ): Long {
        require(size >= 0L) { "negative source size: $size" }
        val bufferBytes = usb.device.TransferTuning.jniCapBytes
        var offset = 0L
        while (offset < size) {
            if (!operation.isActive()) throw CancellationException("Download cancelled")
            val want = minOf(bufferBytes.toLong(), size - offset).toInt()
            val data = volume.read(sourcePath, offset, want)
            if (data.isEmpty()) {
                throw IOException("short read at $offset of $size bytes: $sourcePath")
            }
            stream.write(data, 0, data.size)
            offset += data.size
            operation.report(offset)
        }
        return offset
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

    /**
     * Walk [sourcePath] on USB into SAF [destParent] under [destTree].
     * Empty destination directories are reused; nonempty directories never replaced.
     * [FilesConflictDecision.ReplaceFile] is valid for files only.
     */
    fun downloadTree(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        destTree: SafBackupFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
        onProgress: ((String) -> Unit)? = null,
    ) {
        downloadTree(
            resolver = resolver,
            volume = volume,
            sourcePath = sourcePath,
            destTree = BatchSafFolder.of(destTree),
            destParent = destParent,
            operation = operation,
            conflict = conflict,
            onProgress = onProgress,
        )
    }

    /** Testable overload: same contract as [downloadTree] against [BatchSafFolder]. */
    internal fun downloadTree(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val root = FilesOperationPolicy.normalizeVolumePathExact(sourcePath)
        try {
            downloadEntry(resolver, volume, root, destTree, destParent, operation, conflict, onProgress)
        } catch (failure: Throwable) {
            markPartialIfMutated(operation, failure)
            throw failure
        }
    }

    private fun downloadEntry(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
        onProgress: ((String) -> Unit)?,
    ) {
        if (!operation.isActive()) throw CancellationException("Download cancelled")
        val entry = volume.stat(sourcePath)
        // Root download of "/" is not a single named child — callers pass file/dir paths.
        val leaf = if (sourcePath == "/") {
            error("downloadTree refuses volume root; pass a named entry")
        } else {
            FilesOperationPolicy.requireChildName(
                sourcePath.trim('/').substringAfterLast('/'),
                sourcePath,
            )
        }
        placeDownloadChild(
            resolver = resolver,
            volume = volume,
            sourcePath = sourcePath,
            sourceEntry = entry,
            childName = leaf,
            destTree = destTree,
            destParent = destParent,
            operation = operation,
            conflict = conflict,
            onProgress = onProgress,
        )
    }

    /**
     * Download each selected USB child of [parentPath] into [destParent], preserving names.
     * Used for multi-root batch selections that share one destination folder.
     * [resume] continues inside a root that already created its dest dir; [onCursor]
     * publishes park position before each conflict so callers can resume without
     * re-placing a nonempty root.
     */
    fun downloadChildren(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        parentPath: String,
        names: List<String>,
        destTree: SafBackupFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
        onProgress: ((String) -> Unit)? = null,
        resume: BatchDownloadCursor? = null,
        onCursor: (BatchDownloadCursor) -> Unit = {},
    ) {
        downloadChildren(
            resolver,
            volume,
            parentPath,
            names,
            BatchSafFolder.of(destTree),
            destParent,
            operation,
            conflict,
            onProgress,
            resume,
            onCursor,
        )
    }

    internal fun downloadChildren(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        parentPath: String,
        names: List<String>,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
        onProgress: ((String) -> Unit)? = null,
        resume: BatchDownloadCursor? = null,
        onCursor: (BatchDownloadCursor) -> Unit = {},
    ) {
        val parent = FilesOperationPolicy.normalizeVolumePathExact(parentPath)
        val roots = ArrayDeque(resume?.remainingRoots ?: names)
        val park = DownloadParkState(roots.toList())
        val trackedConflict: (FilesConflict) -> FilesConflictDecision = { c ->
            onCursor(park.snapshot())
            conflict(c)
        }
        try {
            if (resume?.activeRoot != null) {
                val active = resume.activeRoot
                require(roots.firstOrNull() == active) {
                    "resume activeRoot \"$active\" must head remainingRoots"
                }
                resumeInsideRoot(
                    resolver = resolver,
                    volume = volume,
                    parent = parent,
                    rootName = active,
                    pendingRelative = resume.remainingRelative,
                    rootsAfter = roots.drop(1).toList(),
                    destTree = destTree,
                    destParent = destParent,
                    operation = operation,
                    conflict = trackedConflict,
                    onProgress = onProgress,
                    park = park,
                )
                roots.removeFirst()
                park.remainingRoots = roots.toList()
                park.activeRoot = null
                park.remainingRelative = emptyList()
                onCursor(park.snapshot())
            }
            while (roots.isNotEmpty()) {
                if (!operation.isActive()) throw CancellationException("Download cancelled")
                val rootName = roots.first()
                park.remainingRoots = roots.toList()
                park.activeRoot = null
                park.remainingRelative = emptyList()
                onCursor(park.snapshot())
                val childName = FilesOperationPolicy.requireChildName(rootName, parent)
                val sourcePath = FilesOperationPolicy.resolveInsideTree(parent, childName)
                val entry = volume.stat(sourcePath)
                placeDownloadChild(
                    resolver = resolver,
                    volume = volume,
                    sourcePath = sourcePath,
                    sourceEntry = entry,
                    childName = childName,
                    destTree = destTree,
                    destParent = destParent,
                    operation = operation,
                    conflict = trackedConflict,
                    onProgress = onProgress,
                    park = park,
                    batchRootName = childName,
                    atBatchRoot = true,
                    relPrefix = "",
                    rootsAfter = roots.drop(1).toList(),
                )
                roots.removeFirst()
                park.remainingRoots = roots.toList()
                park.activeRoot = null
                park.remainingRelative = emptyList()
                onCursor(park.snapshot())
            }
        } catch (failure: Throwable) {
            markPartialIfMutated(operation, failure)
            throw failure
        }
    }

    private class DownloadParkState(
        var remainingRoots: List<String>,
        var activeRoot: String? = null,
        var remainingRelative: List<String> = emptyList(),
    ) {
        fun snapshot(): BatchDownloadCursor =
            BatchDownloadCursor(
                remainingRoots = remainingRoots,
                activeRoot = activeRoot,
                remainingRelative = remainingRelative,
            )
    }

    private fun resumeInsideRoot(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        parent: String,
        rootName: String,
        pendingRelative: List<String>,
        rootsAfter: List<String>,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
        onProgress: ((String) -> Unit)?,
        park: DownloadParkState,
    ) {
        val childName = FilesOperationPolicy.requireChildName(rootName, parent)
        val sourceRoot = FilesOperationPolicy.resolveInsideTree(parent, childName)
        val existing = destTree.listChildren(destParent).filter { it.name == childName }
        if (existing.size > 1) {
            error("ambiguous SAF children named \"$childName\" under $destParent")
        }
        val hit = existing.singleOrNull()
            ?: error("resume dest directory missing for \"$childName\"")
        require(hit.isDirectory) { "resume dest for \"$childName\" is not a directory" }
        // Dest already created before nested park — reuse even when nonempty.
        val destRootUri = hit.uri
        park.remainingRoots = listOf(childName) + rootsAfter
        park.activeRoot = childName
        val pending = pendingRelative.toMutableList()
        while (pending.isNotEmpty()) {
            if (!operation.isActive()) throw CancellationException("Download cancelled")
            park.remainingRelative = pending.toList()
            val rel = pending.first()
            require(FilesOperationPolicy.isSafeRelativePath(rel)) { "unsafe resume path $rel" }
            val segments = rel.split('/')
            val leaf = segments.last()
            var sourceParent = sourceRoot
            var destParentUri = destRootUri
            for (seg in segments.dropLast(1)) {
                sourceParent = FilesOperationPolicy.resolveInsideTree(sourceParent, seg)
                val nestedHits = destTree.listChildren(destParentUri).filter { it.name == seg }
                if (nestedHits.size > 1) {
                    error("ambiguous SAF children named \"$seg\" under $destParentUri")
                }
                val nested = nestedHits.singleOrNull()
                    ?: error("resume missing intermediate SAF dir \"$seg\"")
                require(nested.isDirectory) { "resume intermediate \"$seg\" is not a directory" }
                destParentUri = nested.uri
            }
            val sourcePath = FilesOperationPolicy.resolveInsideTree(sourceParent, leaf)
            val entry = volume.stat(sourcePath)
            placeDownloadChild(
                resolver = resolver,
                volume = volume,
                sourcePath = sourcePath,
                sourceEntry = entry,
                childName = leaf,
                destTree = destTree,
                destParent = destParentUri,
                operation = operation,
                conflict = conflict,
                onProgress = onProgress,
                park = park,
                batchRootName = childName,
                atBatchRoot = false,
                relPrefix = segments.dropLast(1).joinToString("/"),
                rootsAfter = rootsAfter,
                siblingTail = pending.drop(1),
            )
            pending.removeAt(0)
        }
        onProgress?.invoke(sourceRoot)
    }

    private fun placeDownloadChild(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        sourceEntry: UsbFsDirEntry,
        childName: String,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
        onProgress: ((String) -> Unit)?,
        park: DownloadParkState? = null,
        batchRootName: String? = null,
        atBatchRoot: Boolean = false,
        relPrefix: String = "",
        rootsAfter: List<String> = emptyList(),
        siblingTail: List<String> = emptyList(),
    ) {
        if (!operation.isActive()) throw CancellationException("Download cancelled")
        val existing = destTree.listChildren(destParent).filter { it.name == childName }
        if (existing.size > 1) {
            error("ambiguous SAF children named \"$childName\" under $destParent")
        }
        val hit = existing.singleOrNull()
        // Nested dirs may share the batch root's name (foo/foo); never use name equality.
        val relHere =
            when {
                batchRootName == null || atBatchRoot -> null
                relPrefix.isEmpty() -> childName
                else -> "$relPrefix/$childName"
            }
        if (park != null && batchRootName != null) {
            park.remainingRoots = listOf(batchRootName) + rootsAfter
            if (relHere == null) {
                park.activeRoot = null
                park.remainingRelative = emptyList()
            } else {
                park.activeRoot = batchRootName
                park.remainingRelative = listOf(relHere) + siblingTail
            }
        }
        if (sourceEntry.isDirectory) {
            val destDirUri = resolveDownloadDirectory(
                childName = childName,
                pathLabel = sourcePath,
                hit = hit,
                destTree = destTree,
                destParent = destParent,
                operation = operation,
                conflict = conflict,
            ) ?: return
            val children = volume.list(sourcePath).map { it.name }
            val remaining = children.toMutableList()
            while (remaining.isNotEmpty()) {
                if (!operation.isActive()) throw CancellationException("Download cancelled")
                val nestedName = FilesOperationPolicy.requireChildName(remaining.first(), sourcePath)
                val nestedPath = FilesOperationPolicy.resolveInsideTree(sourcePath, nestedName)
                val nestedEntry = volume.stat(nestedPath)
                val childRel =
                    if (batchRootName == null) {
                        null
                    } else if (relHere == null) {
                        // Placing children of the batch root itself.
                        nestedName
                    } else {
                        "$relHere/$nestedName"
                    }
                // Local remaining siblings, then ancestor tails so a park inside
                // root/a still resumes root/b after a's subtree.
                val localTail =
                    remaining.drop(1).map { sib ->
                        when {
                            relHere == null && batchRootName != null -> sib
                            relHere != null -> "$relHere/$sib"
                            else -> sib
                        }
                    }
                val tailRels = localTail + siblingTail
                if (park != null && batchRootName != null && childRel != null) {
                    park.remainingRoots = listOf(batchRootName) + rootsAfter
                    park.activeRoot = batchRootName
                    park.remainingRelative = listOf(childRel) + tailRels
                }
                placeDownloadChild(
                    resolver = resolver,
                    volume = volume,
                    sourcePath = nestedPath,
                    sourceEntry = nestedEntry,
                    childName = nestedName,
                    destTree = destTree,
                    destParent = destDirUri,
                    operation = operation,
                    conflict = conflict,
                    onProgress = onProgress,
                    park = park,
                    batchRootName = batchRootName,
                    atBatchRoot = false,
                    relPrefix = relHere ?: "",
                    rootsAfter = rootsAfter,
                    siblingTail = tailRels,
                )
                remaining.removeAt(0)
            }
            onProgress?.invoke(sourcePath)
            return
        }
        var preflight: FilesConflictDecision? = null
        if (hit != null && !hit.isDirectory) {
            preflight = decideConflict(
                FilesConflict(pathLabel = sourcePath, name = childName, isDirectory = false),
                conflict,
            )
            if (preflight == FilesConflictDecision.Skip) return
            if (preflight == FilesConflictDecision.Cancel) throw CancellationException("Download cancelled")
        }
        operation.beginCurrentFile(sourceEntry.size)
        val target = resolveDownloadFile(
            childName,
            sourcePath,
            hit,
            destTree,
            destParent,
            operation,
            conflict,
            preflight,
        ) ?: return
        operation.mutationAttempted = true
        onProgress?.invoke(sourcePath)
        streamDownload(
            resolver = resolver,
            volume = volume,
            sourcePath = sourcePath,
            size = sourceEntry.size,
            target = target,
            destTree = destTree,
            operation = operation,
        )
    }

    private data class DownloadTarget(val uri: Uri, val created: Boolean)

    /**
     * Stream directly into SAF; only delete a destination this call created. A pre-existing
     * user document is retained because the provider cannot prove exclusive ownership.
     */
    private fun streamDownload(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        size: Long,
        target: DownloadTarget,
        destTree: BatchSafFolder,
        operation: DiskOperation,
    ) {
        var offset = 0L
        try {
            SafWriter(resolver).writeSynced(target.uri) { stream ->
                streamVolume(volume, sourcePath, size, stream, operation).also { offset = it }
            }
            if (size > 0L && openableSizeBytes(resolver, target.uri) == 0L) {
                error("provider reports empty destination after $size bytes")
            }
        } catch (failure: Throwable) {
            val cleanupNote =
                if (SafCleanupPolicy.shouldDeleteCreated(target.created)) {
                    runCatching { destTree.deleteDocument(target.uri) }
                        .onFailure(failure::addSuppressed)
                        .getOrDefault(false)
                        .let(SafCleanupPolicy::createdDestinationNote)
                } else {
                    SafCleanupPolicy.createdDestinationNote(deleted = false)
                }
            operation.state = OperationState.Partial
            operation.failureCause = usb.device.DiskException.IoFailure(
                "Download failed after $offset of $size bytes; $cleanupNote",
                failure,
            )
            throw failure
        }
    }

    private fun resolveDownloadDirectory(
        childName: String,
        pathLabel: String,
        hit: SafDocument?,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
    ): Uri? {
        if (hit == null) {
            operation.mutationAttempted = true
            return destTree.createExclusiveSubfolder(destParent, childName)
        }
        if (!hit.isDirectory) {
            when (
                decideConflict(
                    FilesConflict(pathLabel = pathLabel, name = childName, isDirectory = false),
                    conflict,
                )
            ) {
                FilesConflictDecision.Skip -> return null
                FilesConflictDecision.Cancel -> throw CancellationException("Download cancelled")
                FilesConflictDecision.ReplaceFile ->
                    throw IOException(
                        "Cannot safely replace existing SAF file \"$pathLabel\" with a directory: " +
                            "provider atomic rename is unavailable; choose another name or Skip",
                    )
            }
        }

        val children = destTree.listChildren(hit.uri)
        if (children.isEmpty()) {
            // Verified empty: reuse container, not a replace/merge of contents.
            return hit.uri
        }
        when (
            decideConflict(
                FilesConflict(
                    pathLabel = pathLabel,
                    name = childName,
                    isDirectory = true,
                    destinationEmpty = false,
                ),
                conflict,
            )
        ) {
            FilesConflictDecision.Skip -> return null
            FilesConflictDecision.Cancel -> throw CancellationException("Download cancelled")
            FilesConflictDecision.ReplaceFile ->
                throw IOException(FilesOperationPolicy.nonemptyDirectoryRefuseMessage(pathLabel))
        }
    }

    private fun resolveDownloadFile(
        childName: String,
        pathLabel: String,
        hit: SafDocument?,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
        preflight: FilesConflictDecision? = null,
    ): DownloadTarget? {
        if (hit == null) {
            operation.mutationAttempted = true
            return DownloadTarget(
                uri = destTree.createExclusiveFile(destParent, childName, OCTET_STREAM),
                created = true,
            )
        }
        if (hit.isDirectory) {
            val empty = destTree.listChildren(hit.uri).isEmpty()
            when (
                decideConflict(
                    FilesConflict(
                        pathLabel = pathLabel,
                        name = childName,
                        isDirectory = true,
                        destinationEmpty = empty,
                    ),
                    conflict,
                )
            ) {
                FilesConflictDecision.Skip -> return null
                FilesConflictDecision.Cancel -> throw CancellationException("Download cancelled")
                FilesConflictDecision.ReplaceFile ->
                    throw IOException(
                        if (empty) {
                            "Cannot replace directory \"$pathLabel\" with a file"
                        } else {
                            FilesOperationPolicy.nonemptyDirectoryRefuseMessage(pathLabel)
                        },
                    )
            }
        }
        when (preflight ?: decideConflict(
            FilesConflict(pathLabel = pathLabel, name = childName, isDirectory = false),
            conflict,
        )) {
            FilesConflictDecision.Skip -> return null
            FilesConflictDecision.Cancel -> throw CancellationException("Download cancelled")
            FilesConflictDecision.ReplaceFile ->
                throw IOException(
                    "Cannot safely replace existing SAF file \"$pathLabel\": " +
                        "provider atomic rename is unavailable; choose another name or Skip",
                )
        }
        return DownloadTarget(uri = hit.uri, created = false)
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
        conflict: (FilesConflict) -> FilesConflictDecision,
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
        conflict: (FilesConflict) -> FilesConflictDecision,
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
            markPartialIfMutated(operation, failure)
            throw failure
        }
    }

    private fun uploadSafNode(
        resolver: ContentResolver,
        source: SafDocument,
        sourceTree: BatchSafFolder,
        destPath: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        conflict: (FilesConflict) -> FilesConflictDecision,
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

    private fun resolveUploadDirectory(
        destPath: String,
        name: String,
        existing: UsbFsDirEntry?,
        volume: UsbFsVolume,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
    ): String? {
        if (existing == null) {
            operation.mutationAttempted = true
            volume.mkdir(destPath)
            return destPath
        }
        if (!existing.isDirectory) {
            when (
                decideConflict(
                    FilesConflict(pathLabel = destPath, name = name, isDirectory = false),
                    conflict,
                )
            ) {
                FilesConflictDecision.Skip -> return null
                FilesConflictDecision.Cancel -> throw CancellationException("Upload cancelled")
                FilesConflictDecision.ReplaceFile ->
                    throw IOException("Cannot safely replace file \"$destPath\" with a directory")
            }
        }

        val children = volume.list(destPath)
        if (children.isEmpty()) {
            return destPath
        }
        when (
            decideConflict(
                FilesConflict(
                    pathLabel = destPath,
                    name = name,
                    isDirectory = true,
                    destinationEmpty = false,
                ),
                conflict,
            )
        ) {
            FilesConflictDecision.Skip -> return null
            FilesConflictDecision.Cancel -> throw CancellationException("Upload cancelled")
            FilesConflictDecision.ReplaceFile ->
                throw IOException(FilesOperationPolicy.nonemptyDirectoryRefuseMessage(destPath))
        }
    }

    private fun resolveUploadFile(
        destPath: String,
        name: String,
        existing: UsbFsDirEntry?,
        volume: UsbFsVolume,
        operation: DiskOperation,
        conflict: (FilesConflict) -> FilesConflictDecision,
    ): Boolean {
        if (existing == null) return true
        if (existing.isDirectory) {
            val empty = volume.list(destPath).isEmpty()
            when (
                decideConflict(
                    FilesConflict(
                        pathLabel = destPath,
                        name = name,
                        isDirectory = true,
                        destinationEmpty = empty,
                    ),
                    conflict,
                )
            ) {
                FilesConflictDecision.Skip -> return false
                FilesConflictDecision.Cancel -> throw CancellationException("Upload cancelled")
                FilesConflictDecision.ReplaceFile ->
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
            decideConflict(
                FilesConflict(pathLabel = destPath, name = name, isDirectory = false),
                conflict,
            )
        ) {
            FilesConflictDecision.Skip -> return false
            FilesConflictDecision.Cancel -> throw CancellationException("Upload cancelled")
            FilesConflictDecision.ReplaceFile -> return true
        }
    }

    /**
     * Flat multi-document upload (GetMultipleContents) into [destDir].
     * Same file-conflict policy as [uploadTree]; directories in the URI list are refused.
     * [onDocumentSettled] fires after each URI is uploaded or skipped so park/resume can
     * drop finished sources from the remaining list.
     */
    fun uploadDocuments(
        resolver: ContentResolver,
        uris: List<Uri>,
        destDir: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        conflict: (FilesConflict) -> FilesConflictDecision,
        onCleanupFailure: (Throwable) -> Unit = {},
        onProgress: ((String) -> Unit)? = null,
        onDocumentSettled: ((Uri) -> Unit)? = null,
    ) {
        val parent = FilesOperationPolicy.normalizeVolumePathExact(destDir)
        try {
            for (uri in uris) {
                if (!operation.isActive()) throw CancellationException("Upload cancelled")
                val displayName = openableDisplayName(resolver, uri)
                    ?: error("cannot resolve display name for $uri")
                val name = FilesOperationPolicy.requireChildName(displayName, parent)
                val destPath = FilesOperationPolicy.resolveInsideTree(parent, name)
                val existing = volume.statOrNull(destPath)
                if (!resolveUploadFile(
                        destPath = destPath,
                        name = name,
                        existing = existing,
                        volume = volume,
                        operation = operation,
                        conflict = conflict,
                    )
                ) {
                    onDocumentSettled?.invoke(uri)
                    continue
                }
                onProgress?.invoke(destPath)
                if (existing != null && !existing.isDirectory) {
                    replaceUploadedFile(
                        resolver = resolver,
                        uri = uri,
                        target = destPath,
                        volume = volume,
                        operation = operation,
                        chunkBytes = chunkBytes,
                        onCleanupFailure = onCleanupFailure,
                    )
                } else {
                    upload(
                        resolver = resolver,
                        uri = uri,
                        target = destPath,
                        volume = volume,
                        operation = operation,
                        chunkBytes = chunkBytes,
                        onCleanupFailure = onCleanupFailure,
                    )
                }
                onDocumentSettled?.invoke(uri)
            }
            if (operation.mutationAttempted) volume.sync()
        } catch (failure: Throwable) {
            if (operation.mutationAttempted) {
                runCatching { volume.sync() }.onFailure { failure.addSuppressed(it) }
            }
            markPartialIfMutated(operation, failure)
            throw failure
        }
    }


    internal fun openableDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0 || cursor.isNull(index)) return null
                cursor.getString(index)
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun decideConflict(
        conflict: FilesConflict,
        decide: (FilesConflict) -> FilesConflictDecision,
    ): FilesConflictDecision {
        val decision = decide(conflict)
        if (conflict.isDirectory && decision == FilesConflictDecision.ReplaceFile) {
            throw IOException(
                FilesOperationPolicy.directoryConflictSkipOnlyMessage(conflict.pathLabel),
            )
        }
        return decision
    }

    private fun markPartialIfMutated(operation: DiskOperation, failure: Throwable) {
        if (!operation.mutationAttempted) return
        if (operation.state != OperationState.Partial) {
            operation.state = OperationState.Partial
            if (operation.failureCause == null) operation.failureCause = failure
        }
    }

    private const val OCTET_STREAM = "application/octet-stream"
}

/** SAF tree surface for batch copy; production wraps [SafBackupFolder]. */
internal interface BatchSafFolder {
    fun listChildren(parent: Uri): List<SafDocument>
    fun createExclusiveFile(parent: Uri, name: String, mimeType: String): Uri
    fun createExclusiveSubfolder(parent: Uri, name: String): Uri
    fun deleteDocument(document: Uri): Boolean

    companion object {
        fun of(folder: SafBackupFolder): BatchSafFolder =
            object : BatchSafFolder {
                override fun listChildren(parent: Uri): List<SafDocument> = folder.listChildren(parent)
                override fun createExclusiveFile(parent: Uri, name: String, mimeType: String): Uri =
                    folder.createExclusiveFile(parent, name, mimeType)
                override fun createExclusiveSubfolder(parent: Uri, name: String): Uri =
                    folder.createExclusiveSubfolder(parent, name)
                override fun deleteDocument(document: Uri): Boolean = folder.deleteDocument(document)
            }
    }
}
