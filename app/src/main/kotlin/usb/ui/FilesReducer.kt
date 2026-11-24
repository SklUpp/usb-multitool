package usb.ui

import usb.fs.FR_NO_FILE
import usb.fs.FR_NO_PATH
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsagePhase
import usb.fs.FilesystemUsageSnapshot
import usb.fs.FilesystemAccess

/**
 * Feature reducer extracted from [UiReducer]; behavior preserved verbatim.
 */
internal object FilesReducer {
    fun reduce(state: AppUiState, intent: UiIntent): ReduceResult? = when (intent) {
        is UiIntent.BeginFilesPick -> FilesPickTransitions.begin(state, intent)
        is UiIntent.SelectFilesPickPartition -> FilesPickTransitions.selectPartition(state, intent.partitionId)

        is UiIntent.WindowsImportPickerFinished -> {
            val mode = state.filesPickMode
            if (mode == null || mode.sessionId != intent.pickSessionId) {
                ReduceResult(state)
            } else if (intent.launchSource == FilesPickLaunchSource.Phone) {
                ReduceResult(
                    state.copy(
                        tab = mode.returnTab,
                        filesPickSession = newFilesPickSession(),
                        filesPickMode = null,
                    ).withStatus(intent.failure?.let(UiMessage::Key)),
                )
            } else if (intent.failure != null && !intent.cancelled) {
                ReduceResult(state.withStatus(UiMessage.Key(intent.failure)))
            } else {
                ReduceResult(state)
            }
        }

        UiIntent.CancelFilesPick -> FilesPickTransitions.cancel(state)

        is UiIntent.ConfirmFilesPickFile -> FilesPickTransitions.confirmFile(state, intent.name)
        UiIntent.ConfirmFilesPickFolder -> FilesPickTransitions.confirmFolder(state)

        is UiIntent.FilesPickCopySucceeded ->
            FilesPickTransitions.copySucceeded(
                state,
                intent.path,
                intent.sizeBytes,
                intent.pickSessionId,
            )

        is UiIntent.ClearFilesPickSelection ->
            FilesPickTransitions.clearSelection(state, intent.purpose)

        is UiIntent.OpenDirectory -> {
            val target = safePickPath(intent.path)
            val mode = state.filesPickMode
            when {
                target == null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                    )
                mode != null && !mode.matches(state) ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                    )
                else -> {
                    val invalidated = OperationReducer.filesInvalidate(state).copy(
                        currentPath = target,
                        filesListingState = FilesListingState.Loading,
                    )
                    ReduceResult(
                        invalidated,
                        effects = listOf(UiEffect.LoadFiles(path = target)),
                    )
                }
            }
        }

        UiIntent.NavigateUp -> {
            val mode = state.filesPickMode
            if (mode != null && !mode.matches(state)) {
                FilesPickTransitions.abortPinLost(state)
            } else {
                val target = fatParentPath(state.currentPath)
                if (target == state.currentPath) {
                    ReduceResult(state)
                } else {
                    val invalidated = OperationReducer.filesInvalidate(state).copy(
                        currentPath = target,
                        filesListingState = FilesListingState.Loading,
                    )
                    ReduceResult(
                        invalidated,
                        effects = listOf(UiEffect.LoadFiles(path = target)),
                    )
                }
            }
        }

        UiIntent.RefreshFiles -> {
            val mode = state.filesPickMode
            if (mode != null && !mode.matches(state)) {
                FilesPickTransitions.abortPinLost(state)
            } else if (!state.actionsEnabled) {
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            } else {
                // A manual refresh preempts an in-flight list: its older answer is
                // dropped by the generation bump.
                OperationReducer.filesReload(state)
            }
        }

        is UiIntent.CreateFolder -> {
            val name = intent.name
            val block = UiReducer.writeBlockMessage(state)
            val pinProblem = bindingProblem(state, intent.binding)
            when {
                state.filesPickMode != null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                pinProblem != null ->
                    ReduceResult(state.withStatus(pinProblem))
                state.currentPath != intent.path ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.TargetChanged)))
                block != null ->
                    ReduceResult(state.withStatus(block))
                !validEntryName(name) ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.InvalidFolderName)))
                else ->
                    ReduceResult(
                        state,
                        effects =
                            listOf(
                                UiEffect.CreateFolder(
                                    path = intent.path,
                                    name = name,
                                ),
                            ),
                    )
            }
        }

        is UiIntent.DeleteEntry -> {
            val block = UiReducer.writeBlockMessage(state)
            when {
                state.filesPickMode != null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                block != null ->
                    ReduceResult(state.withStatus(block))
                !validEntryName(intent.name) ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.SelectEntryToDelete)))
                else ->
                    ReduceResult(
                        state,
                        effects =
                            listOf(
                                UiEffect.DeleteEntry(
                                    path = state.currentPath,
                                    name = intent.name,
                                ),
                            ),
                    )
            }
        }

        is UiIntent.RenameEntry -> {
            val block = UiReducer.writeBlockMessage(state)
            when {
                state.filesPickMode != null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                bindingProblem(state, intent.binding) != null ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.TargetChanged)))
                block != null ->
                    ReduceResult(state.withStatus(block))
                !validEntryName(intent.from) || !validEntryName(intent.to) ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.InvalidEntryName)))
                intent.from == intent.to ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.RenameSameName)))
                else ->
                    ReduceResult(
                        state,
                        effects =
                            listOf(
                                UiEffect.RenameEntry(
                                    path = state.currentPath,
                                    name = intent.from,
                                    newName = intent.to,
                                    binding = intent.binding,
                                ),
                            ),
                    )
            }
        }

        is UiIntent.UploadFile -> {
            val block = UiReducer.writeBlockMessage(state)
            when {
                state.filesPickMode != null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                block != null ->
                    ReduceResult(state.withStatus(block))
                else ->
                    ReduceResult(
                        state,
                        effects =
                            listOf(
                                UiEffect.UploadFile(
                                    path = state.currentPath,
                                    uri = intent.uri,
                                ),
                            ),
                    )
            }
        }

        is UiIntent.DownloadFile -> {
            val bindingValid =
                if (intent.fromPicker) {
                    // The picker result may race a listing refresh. The download service
                    // re-stats this exact path and reports a real mismatch itself.
                    intent.binding.matchesAttachment(state)
                } else {
                    intent.binding.matches(state)
                }
            when {
                state.filesPickMode != null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                !bindingValid ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                    )
                else ->
                    ReduceResult(
                        state,
                        effects = listOf(UiEffect.DownloadFile(intent.binding, intent.uri))
                    )
            }
        }

        is UiIntent.DeleteBatch -> {
            val block = UiReducer.writeBlockMessage(state)
            when {
                state.filesPickMode != null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                !intent.selection.matches(state) ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                    )
                block != null ->
                    ReduceResult(state.withStatus(block))
                else ->
                    ReduceResult(
                        state.copy(pendingFilesBatchConflict = null),
                        effects = listOf(UiEffect.DeleteBatch(intent.selection)),
                    )
            }
        }

        is UiIntent.DownloadBatch ->
            when {
                state.filesPickMode != null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                !intent.selection.matches(state) ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                    )
                else ->
                    ReduceResult(
                        state.copy(pendingFilesBatchConflict = null),
                        effects = listOf(UiEffect.DownloadBatch(intent.selection, intent.treeUri)),
                    )
            }

        is UiIntent.UploadBatchDocs -> {
            val block = UiReducer.writeBlockMessage(state)
            val pinOk = intent.matchesAttachment(state)
            when {
                state.filesPickMode != null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                !pinOk ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.TargetChanged)),
                    )
                block != null ->
                    ReduceResult(state.withStatus(block))
                intent.uris.isEmpty() ->
                    ReduceResult(state)
                else ->
                    ReduceResult(
                        state.copy(pendingFilesBatchConflict = null),
                        effects =
                            listOf(
                                UiEffect.UploadBatchDocs(
                                    directory = intent.directory,
                                    uris = intent.uris,
                                    attachment = intent.attachment,
                                ),
                            ),
                    )
            }
        }

        is UiIntent.FilesBatchConflict ->
            ReduceResult(state.copy(pendingFilesBatchConflict = intent.conflict))

        is UiIntent.ResolveFilesBatchConflict -> {
            val pending = state.pendingFilesBatchConflict
            when {
                pending == null ->
                    ReduceResult(state)
                intent.decision == FilesConflictDecision.ReplaceFile && pending.isDirectory ->
                    ReduceResult(
                        state.withStatus(UiMessage.Raw(
                                    usb.files.FilesOperationPolicy.directoryConflictSkipOnlyMessage(
                                        pending.pathLabel,
                                    ),
                                )),
                    )
                else ->
                    ReduceResult(
                        state.copy(pendingFilesBatchConflict = null),
                        effects = listOf(UiEffect.ContinueFilesBatch(intent.decision)),
                    )
            }
        }

        is UiIntent.FilesListed ->
            if (!filesListMatches(
                    state,
                    intent.path,
                    intent.generation,
                    intent.identity,
                    intent.partitionId,
                    intent.attachGeneration,
                )
            ) {
                // Late result for another path, partition, attachment or request.
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        files = intent.files,
                        filesFailure = null,
                        filesListingState = FilesListingState.Loaded,
                    ),
                )
            }

        is UiIntent.FilesListFailed ->
            if (!filesListMatches(
                    state,
                    intent.path,
                    intent.generation,
                    intent.identity,
                    intent.partitionId,
                    intent.attachGeneration,
                )
            ) {
                ReduceResult(state)
            } else if (
                intent.path != "/" &&
                intent.path.isNotEmpty() &&
                isMissingDirectoryListError(intent.diagnostic.orEmpty())
            ) {
                // Only a proven absent directory falls back to root. Unknown I/O
                // stays visible so a disk error is never painted as "empty folder".
                val generation = state.filesListGeneration + 1L
                ReduceResult(
                    state.copy(
                        currentPath = "/",
                        files = emptyList(),
                        filesFailure = null,
                        filesListGeneration = generation,
                        filesListingState = FilesListingState.Loading,
                    ).withStatus(
                        intent.failure?.let(UiMessage::Failure)
                            ?: UiMessage.Key(UiMessageKey.FailedStatus),
                    ),
                    effects = listOf(UiEffect.LoadFiles(path = "/")),
                )
            } else {
                ReduceResult(
                    state.copy(
                        files = emptyList(),
                        filesFailure = intent.failure,
                        filesListingState = FilesListingState.Failed,
                    ).withStatus(
                        intent.failure?.let(UiMessage::Failure)
                            ?: UiMessage.Key(UiMessageKey.FailedStatus),
                    ),
                )
            }

        is UiIntent.RequestFilesystemUsageBatch -> {
            // One batch owns measurement; the generation fences every step answer.
            val generation = state.filesystemUsageBatchGeneration + 1L
            val steps = state.measurableFilesystemUsageKeys().filter { (_, key) ->
                state.partitionUsage[key]?.matches(key) != true
            }
            val phases =
                steps.associate { (partitionId, _) ->
                    partitionId to
                        if (state.actionsEnabled) {
                            FilesystemUsagePhase.Queued
                        } else {
                            // A claim/user operation owns the session: wait, never Unavailable.
                            FilesystemUsagePhase.Waiting
                        }
                }
            val requested = state.copy(
                filesystemUsageBatchGeneration = generation,
                partitionUsagePhases = phases,
            )
            ReduceResult(
                requested,
                effects =
                    if (state.actionsEnabled && steps.isNotEmpty()) {
                        listOf(UiEffect.LoadFilesystemUsageBatch(generation, steps))
                    } else {
                        emptyList()
                    },
            )
        }

        is UiIntent.CancelFilesystemUsageBatch ->
            ReduceResult(
                state.copy(
                    filesystemUsageBatchGeneration = state.filesystemUsageBatchGeneration + 1L,
                    partitionUsagePhases = state.partitionUsagePhases.mapValues {
                        FilesystemUsagePhase.Waiting
                    },
                ),
            )

        is UiIntent.FilesystemUsageStepCalculating ->
            stepPhase(state, intent.generation, intent.partitionId, intent.key, FilesystemUsagePhase.Calculating)

        // Waiting-before-lock and Waiting-after-defer are the same visible fact.
        is UiIntent.FilesystemUsageStepWaiting ->
            stepPhase(state, intent.generation, intent.partitionId, intent.key, FilesystemUsagePhase.Waiting)

        // A deferred batch has stopped: every unfinished step is waiting, not failed.
        is UiIntent.FilesystemUsageStepDeferred ->
            if (intent.generation != state.filesystemUsageBatchGeneration ||
                state.partitions.firstOrNull { it.id == intent.partitionId }
                    ?.let { state.filesystemUsageKey(it) } != intent.key
            ) {
                ReduceResult(state)
            } else {
                ReduceResult(state.copy(partitionUsagePhases = state.partitionUsagePhases.mapValues {
                    FilesystemUsagePhase.Waiting
                }))
            }

        is UiIntent.FilesystemUsageStepResulted ->
            when {
                intent.generation != state.filesystemUsageBatchGeneration -> ReduceResult(state)
                // Live geometry/attachment/filesystem must still match this exact key.
                state.partitions
                    .firstOrNull { it.id == intent.partitionId }
                    ?.let { state.filesystemUsageKey(it) } != intent.key ->
                    ReduceResult(state)
                else -> {
                    val snapshot = FilesystemUsageSnapshot(
                        intent.key,
                        intent.usage,
                        intent.access,
                    )
                    ReduceResult(
                        state.copy(
                            partitionUsage = state.partitionUsage + (intent.key to snapshot),
                            // Selected row/write state reads the same fact from one place.
                            filesystemUsage =
                                if (state.selectedPartitionId == intent.partitionId) {
                                    snapshot
                                } else {
                                    state.filesystemUsage
                                },
                            partitionUsagePhases =
                                state.partitionUsagePhases - intent.partitionId,
                        ),
                    )
                }
            }

        is UiIntent.RequestFilesystemUsage -> {
            val generation = state.filesystemUsageGeneration + 1L
            val requested = state.copy(filesystemUsageGeneration = generation)
            ReduceResult(requested, effects = filesystemUsageEffects(requested, generation))
        }

        is UiIntent.FilesystemUsageResulted ->
            when {
                // A newer request is in flight; this answer describes an older selection.
                intent.generation != state.filesystemUsageGeneration -> ReduceResult(state)
                // Attachment, geometry or filesystem changed after the request: the value
                // belongs to something else, so it is dropped rather than published.
                state.selectedPartition()?.let { state.filesystemUsageKey(it) } != intent.key ->
                    ReduceResult(state)
                else -> {
                    val snapshot = FilesystemUsageSnapshot(
                        intent.key,
                        intent.usage,
                        intent.access,
                    )
                    ReduceResult(
                        state.copy(
                            filesystemUsage = snapshot,
                            partitionUsage = state.partitionUsage + (intent.key to snapshot),
                            partitionUsagePhases =
                                state.selectedPartitionId
                                    ?.let { state.partitionUsagePhases - it }
                                    ?: state.partitionUsagePhases,
                        ),
                    )
                }
            }
        else -> null
    }

    /**
     * Phase-only batch update. Stale generation, changed live key, or a cancelled batch
     * (phase entry already gone) leaves state untouched — a late Calculating/Deferred can
     * never revive or mislabel a finished or cancelled pass.
     */
    internal fun stepPhase(
        state: AppUiState,
        generation: Long,
        partitionId: String,
        key: FilesystemUsageKey,
        phase: FilesystemUsagePhase,
    ): ReduceResult {
        val live = state.partitions.firstOrNull { it.id == partitionId }
        return when {
            generation != state.filesystemUsageBatchGeneration -> ReduceResult(state)
            live == null || state.filesystemUsageKey(live) != key -> ReduceResult(state)
            !state.partitionUsagePhases.containsKey(partitionId) -> ReduceResult(state)
            state.partitionUsagePhases[partitionId] == phase -> ReduceResult(state)
            else -> ReduceResult(
                state.copy(partitionUsagePhases = state.partitionUsagePhases + (partitionId to phase)),
            )
        }
    }

    /**
     * One measurement request for the live selection, or nothing when it cannot be bound:
     * a running/queued operation would make the free-space number meaningless, and an
     * incomplete key (no device, geometry or filesystem) cannot be validated later.
     */
    internal fun filesystemUsageEffects(state: AppUiState, generation: Long): List<UiEffect> {
        if (!state.actionsEnabled) return emptyList()
        val partition = state.selectedPartition() ?: return emptyList()
        val key = state.filesystemUsageKey(partition) ?: return emptyList()
        return listOf(
            UiEffect.LoadFilesystemUsage(
                partitionId = partition.id,
                usageKey = key,
                usageGeneration = generation,
            ),
        )
    }

    /** Null when [binding] still names the selected device, partition and geometry. */
    internal fun bindingProblem(state: AppUiState, binding: PartitionBinding): UiMessage? =
        if (state.device == null) {
            UiReducer.presenceMessage(state.presence)
        } else if (!binding.matches(state)) {
            UiMessage.Key(UiMessageKey.TargetChanged)
        } else {
            null
        }

    /**
     * True only when a directory-list answer still names the displayed path, the
     * selected partition, the live attachment and the newest request id. A quiet list
     * finished after the user switched partition or the stick reattached must not paint
     * foreign entries or clear a fresher error.
     */
    internal fun filesListMatches(
        state: AppUiState,
        path: String,
        generation: Long,
        identity: String?,
        partitionId: String,
        attachGeneration: Long,
    ): Boolean {
        val device = state.device ?: return false
        return state.filesListingState == FilesListingState.Loading &&
            path == state.currentPath &&
            generation == state.filesListGeneration &&
            identity == device.identity &&
            attachGeneration == device.attachGeneration &&
            partitionId == state.selectedPartitionId
    }

    /**
     * Directory-list failures that mean the path is gone (safe to fall back to root).
     * FatFs absence is the exact nativeList shape `list failed: FR_NO_FILE|FR_NO_PATH`
     * from fat_jni — never a bare trailing ": 5" from an unrelated I/O exception.
     * EXT/NTFS absence is the exact jni message `Path not found` (ENOENT only).
     * Generic `directory listing failed` / EIO stay as [AppUiState.filesFailure].
     */
    internal fun isMissingDirectoryListError(message: String): Boolean {
        val m = message
        // FatFs R0.16 via fat_jni.cpp throw_fat("list failed: " + rc)
        if (m == "list failed: $FR_NO_FILE" || m == "list failed: $FR_NO_PATH") return true
        // EXT / NTFS list ENOENT: ext_jni.cpp / ntfs_jni.cpp throw exactly
        // "Path not found"; the Kotlin stat wrappers append ": <path>".
        if (m == "Path not found" || m.startsWith("Path not found: ")) return true
        // Exact allowlist only: no engine emits "No such file" / "ENOENT" text for a
        // listing fault, so substring matching stays out (it would hide EIO as absence).
        return false
    }

    /**
     * A single FAT entry name: no separators, no dot entries.
     * Policy twin: [usb.files.FilesOperationPolicy.requireChildName].
     */
    internal fun validEntryName(name: String): Boolean =
        name.isNotBlank() &&
            name == name.trim() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name
}
