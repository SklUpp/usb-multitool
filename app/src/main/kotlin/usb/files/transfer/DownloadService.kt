package usb.files.transfer

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import usb.files.BatchSafFolder
import usb.files.FilesOperationPolicy
import usb.files.OpenableSafHelpers
import usb.files.SafWriteException
import usb.files.SafWriter
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.saf.SafBackupFolder
import usb.saf.SafCleanupPolicy
import usb.saf.SafDocument
import usb.operation.ProviderRejectedException
import usb.operation.SizeMismatchException


/** Single-file and tree download from USB into SAF. */
object DownloadService {
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
                throw SizeMismatchException("source size changed: listed $expectedSize, now $statSize")
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
    internal fun streamVolume(
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
     * Walk [sourcePath] on USB into SAF [destParent] under [destTree].
     * Empty destination directories are reused; nonempty directories never replaced.
     * [TransferDecision.ReplaceFile] is valid for files only.
     */
    fun downloadTree(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        destTree: SafBackupFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
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
        conflict: (TransferConflict) -> TransferDecision,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val root = FilesOperationPolicy.normalizeVolumePathExact(sourcePath)
        try {
            downloadEntry(resolver, volume, root, destTree, destParent, operation, conflict, onProgress)
        } catch (failure: Throwable) {
            TransferSupport.markPartialIfMutated(operation, failure)
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
        conflict: (TransferConflict) -> TransferDecision,
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

    internal fun placeDownloadChild(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        sourceEntry: UsbFsDirEntry,
        childName: String,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
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
        var preflight: TransferDecision? = null
        if (hit != null && !hit.isDirectory) {
            preflight = TransferSupport.decideConflict(
                TransferConflict(pathLabel = sourcePath, name = childName, isDirectory = false),
                conflict,
            )
            if (preflight == TransferDecision.Skip) return
            if (preflight == TransferDecision.Cancel) throw CancellationException("Download cancelled")
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

    internal data class DownloadTarget(val uri: Uri, val created: Boolean)

    /**
     * Stream directly into SAF; only delete a destination this call created. A pre-existing
     * user document is retained because the provider cannot prove exclusive ownership.
     */
    internal fun streamDownload(
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
            if (size > 0L && OpenableSafHelpers.openableSizeBytes(resolver, target.uri) == 0L) {
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

    internal fun resolveDownloadDirectory(
        childName: String,
        pathLabel: String,
        hit: SafDocument?,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
    ): Uri? {
        if (hit == null) {
            operation.mutationAttempted = true
            return destTree.createExclusiveSubfolder(destParent, childName)
        }
        if (!hit.isDirectory) {
            when (
                TransferSupport.decideConflict(
                    TransferConflict(pathLabel = pathLabel, name = childName, isDirectory = false),
                    conflict,
                )
            ) {
                TransferDecision.Skip -> return null
                TransferDecision.Cancel -> throw CancellationException("Download cancelled")
                TransferDecision.ReplaceFile ->
                    throw ProviderRejectedException(
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
            TransferSupport.decideConflict(
                TransferConflict(
                    pathLabel = pathLabel,
                    name = childName,
                    isDirectory = true,
                    destinationEmpty = false,
                ),
                conflict,
            )
        ) {
            TransferDecision.Skip -> return null
            TransferDecision.Cancel -> throw CancellationException("Download cancelled")
            TransferDecision.ReplaceFile ->
                throw IOException(FilesOperationPolicy.nonemptyDirectoryRefuseMessage(pathLabel))
        }
    }

    internal fun resolveDownloadFile(
        childName: String,
        pathLabel: String,
        hit: SafDocument?,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
        preflight: TransferDecision? = null,
    ): DownloadTarget? {
        if (hit == null) {
            operation.mutationAttempted = true
            return DownloadTarget(
                uri = destTree.createExclusiveFile(destParent, childName, TransferSupport.OCTET_STREAM),
                created = true,
            )
        }
        if (hit.isDirectory) {
            val empty = destTree.listChildren(hit.uri).isEmpty()
            when (
                TransferSupport.decideConflict(
                    TransferConflict(
                        pathLabel = pathLabel,
                        name = childName,
                        isDirectory = true,
                        destinationEmpty = empty,
                    ),
                    conflict,
                )
            ) {
                TransferDecision.Skip -> return null
                TransferDecision.Cancel -> throw CancellationException("Download cancelled")
                TransferDecision.ReplaceFile ->
                    throw IOException(
                        if (empty) {
                            "Cannot replace directory \"$pathLabel\" with a file"
                        } else {
                            FilesOperationPolicy.nonemptyDirectoryRefuseMessage(pathLabel)
                        },
                    )
            }
        }
        when (preflight ?: TransferSupport.decideConflict(
            TransferConflict(pathLabel = pathLabel, name = childName, isDirectory = false),
            conflict,
        )) {
            TransferDecision.Skip -> return null
            TransferDecision.Cancel -> throw CancellationException("Download cancelled")
            TransferDecision.ReplaceFile ->
                throw ProviderRejectedException(
                    "Cannot safely replace existing SAF file \"$pathLabel\": " +
                        "provider atomic rename is unavailable; choose another name or Skip",
                )
        }
        return DownloadTarget(uri = hit.uri, created = false)
    }
}
