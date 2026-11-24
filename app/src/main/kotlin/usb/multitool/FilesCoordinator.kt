package usb.multitool

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import usb.device.TransferTuning
import usb.files.AttachmentPin
import usb.files.FilesOperationPolicy
import usb.files.FilesOperationRunner
import usb.files.FilesOperations
import usb.files.UsbVolume
import usb.fs.UsbFsDirEntry
import usb.operation.DiskOperation
import usb.saf.SafBackupFolder
import usb.service.OperationHandle
import usb.service.OperationSubmitter
import usb.service.OperationToken
import usb.ui.AppUiState
import usb.ui.FileEntry
import usb.ui.FilesBatchSelection
import usb.ui.FilesConflict
import usb.ui.FilesConflictDecision
import usb.ui.FilesDownloadBinding
import usb.ui.OperationFailure
import usb.ui.OperationProgress
import usb.ui.OperationState
import usb.ui.toOperationFailure
import usb.ui.OperationTitle
import usb.ui.PartitionBinding
import usb.ui.UiAttachmentPin
import usb.ui.UiEffect
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey
import usb.ui.UiReducer
import usb.ui.fatChildPath
import usb.ui.matches
import usb.ui.matchesAttachment

/**
 * Owns Files-tab disk work: list/mutate/upload/download/batch park.
 * Volume access goes through [UsbVolume]; disk enqueue through [OperationSubmitter].
 */
class FilesCoordinator(
    private val scope: CoroutineScope,
    private val volume: UsbVolume,
    private val runner: FilesOperationRunner,
    private val submitter: OperationSubmitter,
    private val pin: () -> AttachmentPin?,
    private val state: () -> AppUiState,
    private val intents: (UiIntent) -> Unit,
    private val log: (String) -> Unit,
    private val onHandleStarted: (OperationHandle, makeCurrent: Boolean) -> Unit,
    private val onHandleFinished: (OperationHandle) -> Unit,
    private val resolver: () -> ContentResolver,
    private val nextToken: () -> OperationToken,
) {
    companion object {
        private const val PROGRESS_TICK_MS = 120L
        private const val LOG_TICK_MS = 1_000L
    }

    private var parkedBatchWork: ParkedFilesBatch? = null
    private var currentHandle: OperationHandle? = null

    fun clearParked() {
        parkedBatchWork = null
    }

    private sealed class ParkedFilesBatch {
        abstract val selection: FilesBatchSelection?
        data class Download(
            override val selection: FilesBatchSelection,
            val treeUri: String,
            val cursor: usb.files.transfer.DownloadCursor,
            val conflict: FilesConflict,
        ) : ParkedFilesBatch()
        data class UploadDocs(
            val directory: String,
            val remainingUris: List<String>,
            val attachment: UiAttachmentPin,
            val conflict: FilesConflict,
        ) : ParkedFilesBatch() {
            override val selection: FilesBatchSelection? get() = null
        }
    }

    private fun ParkedFilesBatch.UploadDocs.matchesAttachment(state: AppUiState): Boolean =
        attachment.matches(state)

    private class FilesBatchConflictPark(
        val conflict: FilesConflict,
        val cursor: usb.files.transfer.DownloadCursor? = null,
        val remainingUris: List<String> = emptyList(),
    ) : Exception("batch conflict parked: ${conflict.pathLabel}")

    private inline fun withPartitionId(action: (String) -> Unit) {
        val partitionId = state().selectedPartitionId
        if (partitionId == null) {
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.SelectPartitionFirst)))
        } else {
            action(partitionId)
        }
    }

    /** Runs [action] with the selected partition, refusing writes it must not take. */
    private inline fun withWritablePartitionId(action: (String) -> Unit) =
        withPartitionId { partitionId ->
            val block = UiReducer.writeBlockMessage(state())
            if (block == null) action(partitionId) else intents(UiIntent.Status(block))
        }

    fun list(path: String) = withPartitionId { partitionId ->
        val generation = state().filesListGeneration
        val identity = state().device?.identity
        val attachGen = state().device?.attachGeneration ?: 0L
        val pin = pin()
        submitFilesOperation(
            title = OperationTitle.ListPath(path),
            foregroundService = false,
            listFailure = { failure, diagnostic ->
                UiIntent.FilesListFailed(
                    path = path,
                    failure = failure,
                    diagnostic = diagnostic,
                    generation = generation,
                    identity = identity,
                    partitionId = partitionId,
                    attachGeneration = attachGen,
                )
            },
        ) {
            val entries =
                usb.files.FilesOperations.list(
                    volume.read(pin, partitionId),
                    path,
                )
            post(
                UiIntent.FilesListed(
                    path = path,
                    files = entries.map { it.toFileEntry() },
                    generation = generation,
                    identity = identity,
                    partitionId = partitionId,
                    attachGeneration = attachGen,
                ),
            )
        }
    }


    fun createFolder(path: String, name: String) {
        val target = fatChildPath(path, name)
        withWritablePartitionId { partitionId ->
            val pin = pin()
            submitFilesOperation(
                title = OperationTitle.NewFolder(name),
                onSuccess = {
                    // The terminal mutation already bumped the list generation and asked the
                    // Files tab for one re-list; only the concrete result line is added here.
                    intents(
                        UiIntent.Status(
                            UiMessage.Key(UiMessageKey.CreatedFolder, listOf(target)),
                        ),
                    )
                },
            ) { operation ->
                volume.write(pin, partitionId, operation) { volume ->
                    operation.mutationAttempted = true
                    volume.mkdir(target)
                    volume.sync()
                    operation.commitReached = true
                }
            }
        }
    }

    fun deleteEntry(path: String, name: String) {
        val target = fatChildPath(path, name)
        withWritablePartitionId { partitionId ->
            val pin = pin()
            submitFilesOperation(
                title = OperationTitle.DeleteEntry(name),
                onSuccess = {
                    intents(
                        UiIntent.Status(
                            UiMessage.Key(UiMessageKey.DeletedPath, listOf(target)),
                        ),
                    )
                },
            ) { operation ->
                volume.write(pin, partitionId, operation) { volume ->
                    try {
                        operation.mutationAttempted = true
                        usb.files.FilesOperations.delete(volume, target)
                        operation.commitReached = true
                    } catch (failure: Throwable) {
                        throw userVisibleDeleteError(name, target, failure)
                    }
                }
            }
        }
    }

    fun deleteBatch(selection: FilesBatchSelection) {
        if (!selection.matches(state())) {
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return
        }
        withWritablePartitionId { partitionId ->
            if (partitionId != selection.partitionId) {
                intents(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                return@withWritablePartitionId
            }
            val pin = pin()
            submitFilesOperation(
                title = OperationTitle.DeleteBatch(selection.items.size),
                onSuccess = {
                    intents(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.DeletedPath,
                                listOf("${selection.items.size} entries"),
                            ),
                        ),
                    )
                },
            ) { operation ->
                require(selection.matches(state())) { "batch delete target changed" }
                volume.write(pin, partitionId, operation) { volume ->
                    for (item in selection.items) {
                        if (!operation.isActive()) {
                            throw kotlinx.coroutines.CancellationException("Delete cancelled")
                        }
                        val target = fatChildPath(selection.directory, item.name)
                        try {
                            FilesOperations.deleteRecursively(
                                volume = volume,
                                target = target,
                                operation = operation,
                            )
                        } catch (failure: Throwable) {
                            throw userVisibleDeleteError(item.name, target, failure)
                        }
                    }
                    // Commit only after every selected root deleted; mutationAttempted
                    // from first delete keeps mid-cancel as Partial, not Completed.
                    operation.commitReached = true
                }
            }
        }
    }
    fun renameEntry(path: String, from: String, to: String, binding: PartitionBinding) {
        val target = fatChildPath(path, from)
        val renamed = fatChildPath(path, to)
        withWritablePartitionId { partitionId ->
            val pin = pin()
            submitFilesOperation(
                title = OperationTitle.RenameEntry(from),
                onSuccess = {
                    intents(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.RenamedPath,
                                listOf(from, to),
                            ),
                        ),
                    )
                },
            ) { operation ->
                require(partitionId == binding.partitionId) { "rename target partition changed" }
                require(binding.matches(state())) { "rename target changed" }
                volume.write(pin, partitionId, operation) { volume ->
                    // The only mutation below; mark it after binding/policy checks and
                    // volume acquisition so a rename or post-rename sync failure is Partial.
                    operation.mutationAttempted = true
                    usb.files.FilesOperations.rename(volume, target, renamed)
                    operation.commitReached = true
                }
            }
        }
    }

    /** Map engine delete failures to a clear status line (nonempty dir, missing, …). */
    private fun userVisibleDeleteError(name: String, target: String, failure: Throwable): Throwable {
        val friendly = FilesOperationPolicy.deleteError(name, target, failure.message.orEmpty())
        return if (friendly != null) IllegalStateException(friendly, failure) else failure
    }

    /** Stream a SAF document into [path] in fixed-size chunks, reporting real bytes. */
    fun uploadFile(path: String, uri: String) {
        val resolver = resolver()
        var uploaded: String? = null
        withWritablePartitionId { partitionId ->
            val pin = pin()
            submitFilesOperation(
                title = OperationTitle.Upload,
                onSuccess = {
                    val name = uploaded
                    if (name != null) {
                        intents(
                            UiIntent.Status(
                                UiMessage.Key(UiMessageKey.UploadedFile, listOf(name, path)),
                            ),
                        )
                    }
                },
            ) { operation ->
                val document = Uri.parse(uri)
                val (reportedName, reportedSize) = contentInfo(resolver, document)
                val rawName = reportedName ?: document.lastPathSegment ?: uri
                val name = FilesOperationPolicy.requireChildName(rawName, uri)
                uploaded = name
                val target = fatChildPath(path, name)
                operation.bytesTotal = reportedSize?.takeIf { it > 0L } ?: -1L

                volume.write(pin, partitionId, operation) { volume ->
                    FilesOperations.upload(
                        resolver = resolver,
                        uri = document,
                        target = target,
                        volume = volume,
                        operation = operation,
                        chunkBytes = TransferTuning.jniCapBytes,
                        onCleanupFailure = { cleanupError ->
                            // Engine already adds the cleanup failure as suppressed to the
                            // primary upload error; keep a persistent log line as well so the
                            // warning survives the terminal status.
                            val detail = cleanupError.message ?: cleanupError.javaClass.simpleName
                            post(UiIntent.AppendLog(logLine("Upload cleanup failed for $name: $detail")))
                            post(
                                UiIntent.Status(
                                    UiMessage.Key(
                                        UiMessageKey.UploadCleanupFailed,
                                        listOf(name, detail),
                                    ),
                                ),
                            )
                        },
                    )
                    operation.commitReached = true
                }
            }
        }
    }

    private fun deletePickedDownload(uri: Uri) {
        val deleted =
            runCatching { DocumentsContract.deleteDocument(resolver(), uri) }.getOrDefault(false)
        if (!deleted) runCatching { resolver().delete(uri, null, null) }
    }

    fun downloadFile(binding: FilesDownloadBinding, uri: String) {
        val resolver = resolver()
        val pin = pin()
        submitFilesOperation(
            title = OperationTitle.DownloadEntry(binding.name),
            onSuccess = {
                intents(UiIntent.Status(UiMessage.Key(UiMessageKey.SavedFile, listOf(binding.name))))
            },
        ) { operation ->
            val destination = Uri.parse(uri)
            try {
                require(binding.matchesAttachment(state())) { "download attachment changed" }
                val volume = volume.read(pin, binding.attachment.partitionId)
                FilesOperations.download(
                    resolver = resolver,
                    target = binding.targetPath,
                    destination = destination,
                    volume = volume,
                    operation = operation,
                    expectedSize = binding.sizeBytes.toLong(),
                )
            } catch (failure: Throwable) {
                if (!operation.mutationAttempted) deletePickedDownload(destination)
                throw failure
            }
        }
    }

    fun downloadBatch(selection: FilesBatchSelection, treeUri: String) {
        if (!selection.matches(state())) {
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return
        }
        parkedBatchWork = null
        val resolver = resolver()
        val pin = pin()
        val rootNames = selection.items.map { it.name }
        var parkCursor = usb.files.transfer.DownloadCursor(remainingRoots = rootNames)
        val lastPark = java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>(null)
        submitFilesOperation(
            title = OperationTitle.DownloadBatch(selection.items.size),
            onSuccess = {
                intents(
                    UiIntent.Status(
                        UiMessage.Key(UiMessageKey.SavedFile, listOf("${selection.items.size} entries")),
                    ),
                )
            },
            onTerminal = { _, _ -> Unit },
        ) { operation ->
            require(selection.matches(state())) { "batch download target changed" }
            val volume = volume.read(pin, selection.partitionId)
            val tree = Uri.parse(treeUri)
            val folder = SafBackupFolder(resolver, tree)
            val destParent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            try {
                FilesOperations.downloadChildren(
                    resolver = resolver,
                    volume = volume,
                    parentPath = selection.directory,
                    names = rootNames,
                    destTree = folder,
                    destParent = destParent,
                    operation = operation,
                    conflict = { conflict ->
                        val park =
                            FilesBatchConflictPark(
                                conflict = conflict,
                                cursor = parkCursor,
                            )
                        lastPark.set(park)
                        throw park
                    },
                    onCursor = { parkCursor = it },
                )
            } catch (park: FilesBatchConflictPark) {
                lastPark.compareAndSet(null, park)
                throw park
            }
        }.also { handle ->
            watchBatchPark(
                handle = handle,
                lastPark = lastPark,
                stillValid = { selection.matches(state()) },
                onPark = { park ->
                    parkedBatchWork =
                        ParkedFilesBatch.Download(
                            selection = selection,
                            treeUri = treeUri,
                            cursor = park.cursor ?: parkCursor,
                            conflict = park.conflict,
                        )
                    intents(UiIntent.FilesBatchConflict(park.conflict))
                },
            )
        }
    }

    fun uploadBatchDocs(effect: UiEffect.UploadBatchDocs) {
        if (!effect.matchesAttachment(state())) {
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
            return
        }
        parkedBatchWork = null
        val resolver = resolver()
        val remaining = effect.uris.toMutableList()
        val lastPark = java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>(null)
        withWritablePartitionId { partitionId ->
            if (partitionId != effect.attachment.partitionId) {
                intents(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                return@withWritablePartitionId
            }
            val pin = pin()
            submitFilesOperation(
                title = OperationTitle.UploadBatch(effect.uris.size),
                onTerminal = { _, _ -> intents(UiIntent.RefreshFiles) },
                onSuccess = {
                    intents(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.UploadedFile,
                                listOf("${effect.uris.size}", effect.directory),
                            ),
                        ),
                    )
                },
            ) { operation ->
                require(effect.matchesAttachment(state())) { "batch upload target changed" }
                volume.write(pin, partitionId, operation) { volume ->
                    try {
                        FilesOperations.uploadDocuments(
                            resolver = resolver,
                            uris = remaining.map(Uri::parse),
                            destDir = effect.directory,
                            volume = volume,
                            operation = operation,
                            chunkBytes = TransferTuning.jniCapBytes,
                            conflict = { conflict ->
                                val park =
                                    FilesBatchConflictPark(
                                        conflict = conflict,
                                        remainingUris = remaining.toList(),
                                    )
                                lastPark.set(park)
                                throw park
                            },
                            onDocumentSettled = { uri ->
                                remaining.remove(uri.toString())
                            },
                        )
                    } catch (park: FilesBatchConflictPark) {
                        lastPark.compareAndSet(null, park)
                        throw park
                    }
                }
            }.also { handle ->
                watchBatchPark(
                    handle = handle,
                    lastPark = lastPark,
                    stillValid = { effect.matchesAttachment(state()) },
                    onPark = { park ->
                        parkedBatchWork =
                            ParkedFilesBatch.UploadDocs(
                                directory = effect.directory,
                                remainingUris = park.remainingUris.ifEmpty { remaining.toList() },
                                attachment = effect.attachment,
                                conflict = park.conflict,
                            )
                        intents(UiIntent.FilesBatchConflict(park.conflict))
                    },
                )
            }
        }
    }

    fun continueBatch(decision: FilesConflictDecision) {
        val parked = parkedBatchWork
        parkedBatchWork = null
        if (parked == null) return
        when (parked) {
            is ParkedFilesBatch.Download -> {
                if (!parked.selection.matches(state())) {
                    intents(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
                    return
                }
                when (decision) {
                    FilesConflictDecision.Cancel -> {
                        intents(UiIntent.Status(UiMessage.Key(UiMessageKey.CancelledStatus)))
                    }
                    FilesConflictDecision.Skip,
                    FilesConflictDecision.ReplaceFile,
                    -> resumeParkedDownload(parked, decision)
                }
            }
            is ParkedFilesBatch.UploadDocs -> {
                when (decision) {
                    FilesConflictDecision.Cancel -> {
                        intents(UiIntent.Status(UiMessage.Key(UiMessageKey.CancelledStatus)))
                    }
                    FilesConflictDecision.Skip,
                    FilesConflictDecision.ReplaceFile,
                    -> resumeParkedUpload(parked, decision)
                }
            }
        }
    }

    private fun resumeParkedDownload(
        parked: ParkedFilesBatch.Download,
        decision: FilesConflictDecision,
    ) {
        val selection = parked.selection
        val resolver = resolver()
        val pin = pin()
        var parkCursor = parked.cursor
        val conflictLabel = parked.conflict.pathLabel
        val lastPark = java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>(null)
        submitFilesOperation(
            title = OperationTitle.DownloadBatch(selection.items.size),
            onSuccess = {
                intents(
                    UiIntent.Status(
                        UiMessage.Key(UiMessageKey.SavedFile, listOf("${selection.items.size} entries")),
                    ),
                )
            },
        ) { operation ->
            require(selection.matches(state())) { "batch download target changed" }
            val volume = volume.read(pin, selection.partitionId)
            val tree = Uri.parse(parked.treeUri)
            val folder = SafBackupFolder(resolver, tree)
            val destParent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            var first = true
            FilesOperations.downloadChildren(
                resolver = resolver,
                volume = volume,
                parentPath = selection.directory,
                names = parkCursor.remainingRoots,
                destTree = folder,
                destParent = destParent,
                operation = operation,
                resume = parkCursor,
                conflict = { conflict ->
                    if (first && conflict.pathLabel == conflictLabel) {
                        first = false
                        if (conflict.isDirectory && decision == FilesConflictDecision.ReplaceFile) {
                            throw IOException(
                                FilesOperationPolicy.directoryConflictSkipOnlyMessage(conflict.pathLabel),
                            )
                        }
                        decision
                    } else {
                        val park =
                            FilesBatchConflictPark(
                                conflict = conflict,
                                cursor = parkCursor,
                            )
                        lastPark.set(park)
                        throw park
                    }
                },
                onCursor = { parkCursor = it },
            )
        }.also { handle ->
            watchBatchPark(
                handle = handle,
                lastPark = lastPark,
                stillValid = { selection.matches(state()) },
                onPark = { park ->
                    parkedBatchWork =
                        ParkedFilesBatch.Download(
                            selection = selection,
                            treeUri = parked.treeUri,
                            cursor = park.cursor ?: parkCursor,
                            conflict = park.conflict,
                        )
                    intents(UiIntent.FilesBatchConflict(park.conflict))
                },
            )
        }
    }

    private fun resumeParkedUpload(
        parked: ParkedFilesBatch.UploadDocs,
        decision: FilesConflictDecision,
    ) {
        if (!parked.matchesAttachment(state())) {
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
            return
        }
        val resolver = resolver()
        val remaining = parked.remainingUris.toMutableList()
        // Capture before onDocumentSettled mutates remaining; success must not report 0.
        val resumeBatchCount = remaining.size
        val conflictLabel = parked.conflict.pathLabel
        val lastPark = java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>(null)
        withWritablePartitionId { partitionId ->
            if (partitionId != parked.attachment.partitionId) {
                intents(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                return@withWritablePartitionId
            }
            val pin = pin()
            submitFilesOperation(
                title = OperationTitle.UploadBatch(resumeBatchCount),
                onSuccess = {
                    intents(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.UploadedFile,
                                listOf("$resumeBatchCount", parked.directory),
                            ),
                        ),
                    )
                },
            ) { operation ->
                require(parked.matchesAttachment(state())) { "batch upload target changed" }
                volume.write(pin, partitionId, operation) { volume ->
                    var first = true
                    FilesOperations.uploadDocuments(
                        resolver = resolver,
                        uris = remaining.map(Uri::parse),
                        destDir = parked.directory,
                        volume = volume,
                        operation = operation,
                        chunkBytes = TransferTuning.jniCapBytes,
                        conflict = { conflict ->
                            if (first && conflict.pathLabel == conflictLabel) {
                                first = false
                                if (conflict.isDirectory && decision == FilesConflictDecision.ReplaceFile) {
                                    throw IOException(
                                        FilesOperationPolicy.directoryConflictSkipOnlyMessage(
                                            conflict.pathLabel,
                                        ),
                                    )
                                }
                                decision
                            } else {
                                val park =
                                    FilesBatchConflictPark(
                                        conflict = conflict,
                                        remainingUris = remaining.toList(),
                                    )
                                lastPark.set(park)
                                throw park
                            }
                        },
                        onDocumentSettled = { uri ->
                            remaining.remove(uri.toString())
                        },
                    )
                }
            }.also { handle ->
                watchBatchPark(
                    handle = handle,
                    lastPark = lastPark,
                    stillValid = { parked.matchesAttachment(state()) },
                    onPark = { park ->
                        parkedBatchWork =
                            ParkedFilesBatch.UploadDocs(
                                directory = parked.directory,
                                remainingUris = park.remainingUris.ifEmpty { remaining.toList() },
                                attachment = parked.attachment,
                                conflict = park.conflict,
                            )
                        intents(UiIntent.FilesBatchConflict(park.conflict))
                    },
                )
            }
        }
    }

    /**
     * Parallel observer for parked conflicts. Park may land as Failed (no prior mutation)
     * or Partial (after mutationAttempted via markPartialIfMutated). Cancelled with a park
     * cause is also recovered. Dialog must appear; Partial honesty for real failures stays.
     * [lastPark] is filled before the park exception escapes the op body so Partial
     * terminals still surface the dialog without reflecting into OperationHandle.
     * [stillValid] rejects stale parks after detach/partition change cleared the pin.
     */
    private fun watchBatchPark(
        handle: OperationHandle,
        lastPark: java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>,
        stillValid: () -> Boolean,
        onPark: (FilesBatchConflictPark) -> Unit,
    ) {
        scope.launch {
            val outcome = handle.await()
            fun fromThrowable(cause: Throwable?): FilesBatchConflictPark? {
                var cur: Throwable? = cause
                var depth = 0
                while (cur != null && depth < 8) {
                    if (cur is FilesBatchConflictPark) return cur
                    cur = cur.cause
                    depth++
                }
                return null
            }
            // lastPark is primary (filled before park escapes). Failed may also carry park
            // as cause. Partial/Cancelled rely on lastPark — sealed outcomes have no cause.
            val park =
                lastPark.get()
                    ?: (outcome as? usb.operation.OperationState.Failed)?.let {
                        fromThrowable(it.cause)
                    }
            if (park != null && stillValid()) {
                onPark(park)
            }
        }
    }

    private fun contentInfo(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = try {
            resolver.query(uri, columns, null, null, null)
        } catch (_: RuntimeException) {
            // Metadata is optional; the stream itself is still opened (and may fail loudly).
            null
        } ?: return Pair(null, null)
        return cursor.use { query ->
            if (!query.moveToFirst()) {
                Pair(null, null)
            } else {
                val nameIndex = query.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = query.getColumnIndex(OpenableColumns.SIZE)
                val name =
                    if (nameIndex >= 0 && !query.isNull(nameIndex)) {
                        query.getString(nameIndex)
                    } else {
                        null
                    }
                val size =
                    if (sizeIndex >= 0 && !query.isNull(sizeIndex)) {
                        query.getLong(sizeIndex)
                    } else {
                        null
                    }
                Pair(name, size)
            }
        }
    }

    /**
     * Enqueue one real FAT operation on the shared disk thread. The mounted volume and
     * the volume side of a mutation are serialized by the files session lock; [onSuccess] re-lists
     * the affected directory after a mutation so the UI never shows invented entries.
     *
     * O2: on body failure after a real [FilesVolumeSession.volumeForLocked] acquire, the mount
     * is closed under the same lock before unlock — never a deferred launch that could close B.
     * Fail-before-acquire leaves the RO cache of another op untouched. Success keeps RO cache.
     */
    private fun submitFilesOperation(
        title: OperationTitle,
        bytesTotal: Long = -1L,
        /**
         * Bound failure intent (FilesListFailed / RawSourceCandidatesFailed).
         * Quiet work must supply this so errors never land as free Status on op B.
         */
        listFailure: ((OperationFailure?, String?) -> UiIntent)? = null,
        onSuccess: (() -> Unit)? = null,
        onSuccessWithToken: ((OperationToken) -> Unit)? = null,
        onTerminal: ((usb.operation.OperationState, OperationToken) -> Unit)? = null,
        quiet: Boolean = listFailure != null,
        foregroundService: Boolean = true,
        body: (DiskOperation) -> Unit,
    ): OperationHandle {
        val label = title.logLabel
        val submission = runner.create(
            name = label,
            bytesTotal = bytesTotal,
            foregroundService = foregroundService,
            body = body,
        )
        val operation = submission.operation
        val token = nextToken()
        val handle = submitter.submit(operation, token)
        onHandleStarted(handle, makeCurrent = !quiet)
        if (!quiet) currentHandle = handle
        if (FilesOperationPolicy.publishOperationProgress(quiet)) {
            intents(
                UiIntent.OperationBegan(
                    OperationProgress(
                        state = OperationState.Queued,
                        title = title,
                        token = token,
                        bytesTotal = bytesTotal,
                    ),
                ),
            )
        }
        scope.launch {
            val ticker = launch {
                var lastLogAt = android.os.SystemClock.elapsedRealtime()
                var lastLogged = -1L
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    if (handle.state == usb.operation.OperationState.Running) {
                        if (FilesOperationPolicy.publishOperationProgress(quiet)) {
                            intents(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Running,
                                        title = title,
                                        token = token,
                                        bytesDone = operation.bytesDone,
                                        bytesTotal = operation.bytesTotal,
                                        bytesPerSecond = operation.currentBytesPerSecond(),
                                    ),
                                ),
                            )
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastLogAt >= LOG_TICK_MS && operation.bytesDone != lastLogged && state().operation?.token == token) {
                            lastLogAt = now
                            lastLogged = operation.bytesDone
                            val total = operation.bytesTotal
                            val detail = if (total > 0) " ${(lastLogged.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else " $lastLogged bytes"
                            val message = "$label$detail"
                            log(message)
                            intents(UiIntent.AppendLog(logLine(message)))
                        }
                    }
                }
            }
            try {
                val outcome = handle.await()
                ticker.cancel()
                val plan =
                    FilesOperationPolicy.planTerminal(
                        quiet = quiet,
                        hasBoundFailure = listFailure != null,
                    )
                fun publishCloseDiagnostics(cause: Throwable?) {
                    val stillCurrent = quiet || currentHandle === handle
                    when (
                        val d =
                            FilesOperationPolicy.planCloseDiagnostic(
                                primary = cause,
                                quiet = quiet,
                                hasBoundFailure = listFailure != null,
                                opStillCurrent = stillCurrent,
                                explicitClose = submission.closeFailure,
                            )
                    ) {
                        usb.files.CloseDiagnosticPublish.None -> Unit
                        is usb.files.CloseDiagnosticPublish.LogOnly ->
                            intents(UiIntent.AppendLog(logLine(d.message)))
                    }
                }
                when (outcome) {
                    is usb.operation.OperationState.Failed -> {
                        val cause = outcome.cause
                        val failure = cause.toOperationFailure()
                        val message = FilesOperationPolicy.technicalDetail(cause)
                        if (plan.postBoundFailure) {
                            intents(listFailure!!(failure, message))
                        } else if (state().operation?.token == token) {
                            if (plan.postStatus) {
                                intents(UiIntent.Status(UiMessage.Raw(message)))
                            } else {
                                intents(UiIntent.AppendLog(logLine("$label failed: $message")))
                            }
                        }
                        if (plan.postOperationProgress) {
                            intents(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Failed,
                                        title = title,
                                        token = token,
                                        failure = failure,
                                    ),
                                )
                            )
                        }
                        publishCloseDiagnostics(cause)
                        onTerminal?.invoke(outcome, token)
                    }
                    is usb.operation.OperationState.Cancelled -> {
                        if (plan.postBoundFailure) {
                            intents(listFailure!!("Cancelled"))
                        }
                        if (plan.postOperationProgress) {
                            intents(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Cancelled,
                                        title = title,
                                        token = token,
                                    ),
                                ),
                            )
                        }
                        // RO cancel: executor drops failureCause; close still logged via closeDiagnostic.
                        // Terminal stays Cancelled — never promote to Failed for close-on-cancel.
                        publishCloseDiagnostics(operation.failureCause)
                        onTerminal?.invoke(outcome, token)
                    }
                    is usb.operation.OperationState.Partial -> {
                        // withWritableVolume already closed under lock; do not close again.
                        val cause = operation.failureCause
                        val failure = cause?.toOperationFailure()
                        val message =
                            FilesOperationPolicy.technicalDetail(
                                cause ?: Exception("Incomplete operation"),
                            )
                        if (plan.postBoundFailure) {
                            intents(listFailure!!(failure, message))
                        } else if (state().operation?.token == token && plan.postStatus) {
                            intents(UiIntent.Status(UiMessage.Raw(message)))
                        }
                        if (plan.postOperationProgress) {
                            intents(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Partial,
                                        title = title,
                                        token = token,
                                        bytesDone = operation.bytesDone,
                                        bytesTotal = operation.bytesTotal,
                                        failure = failure,
                                    ),
                                ),
                            )
                        }
                        publishCloseDiagnostics(cause)
                        onTerminal?.invoke(outcome, token)
                    }
                    else -> {
                        if (plan.postOperationProgress) {
                            intents(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Completed,
                                        title = title,
                                        token = token,
                                        bytesDone = operation.bytesDone,
                                        bytesTotal = operation.bytesTotal,
                                    ),
                                ),
                            )
                        }
                        if (state().operation?.token == token) onSuccess?.invoke()
                        onSuccessWithToken?.invoke(token)
                        onTerminal?.invoke(outcome, token)
                    }
                }
            } finally {
                ticker.cancel()
                if (currentHandle === handle) currentHandle = null
                onHandleFinished(handle)
            }
        }
        return handle
    }

    private fun logLine(message: String): String {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        return "[$ts] $message"
    }

    /** Disk thread -> main thread hop for result intents. */
    private fun post(intent: UiIntent) {
        scope.launch { intents(intent) }
    }

    private fun UsbFsDirEntry.toFileEntry(): FileEntry =
        FileEntry(
            name = name,
            isDirectory = isDirectory,
            sizeBytes = size.coerceAtLeast(0L).toULong(),
        )
    }
