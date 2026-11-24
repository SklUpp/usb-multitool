package usb.files.transfer

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import usb.files.BatchSafFolder
import usb.files.FilesOperationPolicy
import usb.files.OpenableSafHelpers
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.saf.SafBackupFolder

/** Multi-root download cursor/resume and flat multi-document upload. */
object BatchTransferService {
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
        conflict: (TransferConflict) -> TransferDecision,
        onProgress: ((String) -> Unit)? = null,
        resume: DownloadCursor? = null,
        onCursor: (DownloadCursor) -> Unit = {},
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
        conflict: (TransferConflict) -> TransferDecision,
        onProgress: ((String) -> Unit)? = null,
        resume: DownloadCursor? = null,
        onCursor: (DownloadCursor) -> Unit = {},
    ) {
        val parent = FilesOperationPolicy.normalizeVolumePathExact(parentPath)
        val roots = ArrayDeque(resume?.remainingRoots ?: names)
        val park = DownloadParkState(roots.toList())
        val trackedConflict: (TransferConflict) -> TransferDecision = { c ->
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
                DownloadService.placeDownloadChild(
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
            TransferSupport.markPartialIfMutated(operation, failure)
            throw failure
        }
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
        conflict: (TransferConflict) -> TransferDecision,
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
            DownloadService.placeDownloadChild(
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
        conflict: (TransferConflict) -> TransferDecision,
        onCleanupFailure: (Throwable) -> Unit = {},
        onProgress: ((String) -> Unit)? = null,
        onDocumentSettled: ((Uri) -> Unit)? = null,
    ) {
        val parent = FilesOperationPolicy.normalizeVolumePathExact(destDir)
        try {
            for (uri in uris) {
                if (!operation.isActive()) throw CancellationException("Upload cancelled")
                val displayName = OpenableSafHelpers.openableDisplayName(resolver, uri)
                    ?: error("cannot resolve display name for $uri")
                val name = FilesOperationPolicy.requireChildName(displayName, parent)
                val destPath = FilesOperationPolicy.resolveInsideTree(parent, name)
                val existing = volume.statOrNull(destPath)
                if (!UploadService.resolveUploadFile(
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
                    UploadService.replaceUploadedFile(
                        resolver = resolver,
                        uri = uri,
                        target = destPath,
                        volume = volume,
                        operation = operation,
                        chunkBytes = chunkBytes,
                        onCleanupFailure = onCleanupFailure,
                    )
                } else {
                    UploadService.upload(
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
            TransferSupport.markPartialIfMutated(operation, failure)
            throw failure
        }
    }
}
