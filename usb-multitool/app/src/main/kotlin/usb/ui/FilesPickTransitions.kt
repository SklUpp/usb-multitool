package usb.ui

/**
 * Pure transitions of the shared Files picker: the install image, a raw-write source and a
 * Ventoy config file all browse through one session. [UiReducer.reduce] stays the only
 * entry point; this object holds no state and performs no I/O.
 *
 * A [FilesPickMode] pins identity, attach generation, partition and geometry at Begin.
 * Every exit replaces the session id, so a late SAF or copy result can never join the next
 * session, and a pin that no longer matches aborts without writing.
 */
internal object FilesPickTransitions {
    fun begin(state: AppUiState, intent: UiIntent.BeginFilesPick): ReduceResult {
        if (state.filesPickMode != null) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
            )
        }
        if (!state.actionsEnabled) {
            return ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
        }
        if (state.pendingIsoImport != null ||
            state.isoImportSafInFlight != null ||
            state.isoImportUsbInFlight != null
        ) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.WaitForOperation)),
            )
        }
        val device =
            state.device
                ?: return ReduceResult(
                    state.copy(statusMessage = UiReducer.presenceMessage(state.presence)),
                )
        val explicit = intent.partitionId
        val partition =
            when {
                explicit != null -> state.partitions.firstOrNull { it.id == explicit }
                intent.purpose == FilesPickPurpose.RawSource ->
                    state.selectedPartitionId?.let { id ->
                        state.partitions.firstOrNull { it.id == id }
                    }
                else -> state.ventoyDataPartition()
            }
        val path = safePickPath(intent.initialPath)
        return when {
            partition == null ->
                ReduceResult(
                    state.copy(
                        statusMessage =
                            UiMessage.Key(
                                if (intent.purpose == FilesPickPurpose.RawSource) {
                                    UiMessageKey.SelectPartitionFirst
                                } else {
                                    UiMessageKey.VentoyDataPartitionMissing
                                },
                            ),
                    ),
                )
            partition.startLba == null || partition.sectorCount == null ->
                ReduceResult(
                    state.copy(statusMessage = UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)),
                )
            intent.purpose == FilesPickPurpose.WindowsImage && partition.readOnly ->
                ReduceResult(
                    state.copy(
                        statusMessage =
                            UiReducer.message(UiMessageKey.ReadOnlyPartition, partition.name),
                    ),
                )
            path == null ->
                ReduceResult(
                    state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                )
            else -> {
                val session = newFilesPickSession()
                val mode =
                    FilesPickMode(
                        sessionId = session,
                        purpose = intent.purpose,
                        returnTab = intent.returnTab,
                        target = intent.target,
                        identity = device.identity,
                        attachGeneration = device.attachGeneration,
                        partitionId = partition.id,
                        startLba = partition.startLba,
                        sectorCount = partition.sectorCount,
                    )
                ReduceResult(
                    state.copy(
                        tab = ShellTab.Files,
                        filesPickSession = session,
                        filesPickMode = mode,
                        selectedPartitionId = partition.id,
                        currentPath = path,
                        files = emptyList(),
                        filesError = null,
                        filesListGeneration = state.filesListGeneration + 1L,
                        filesPending = true,
                        pendingIsoImport = null,
                        deferredIsoImport = null,
                        isoImportError = null,
                        isoImportSafInFlight = null,
                        isoImportGeneration = state.isoImportGeneration + 1,
                        statusMessage = null,
                    ),
                    effects = listOf(UiEffect.LoadFiles(path = path)),
                )
            }
        }
    }

    fun cancel(state: AppUiState): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        val blocked = filesPickExitBlock(state)
        if (blocked != null) {
            return ReduceResult(state.copy(statusMessage = blocked))
        }
        val hostInspection =
            state.isoImportSafInFlight != null || state.isoImportUsbInFlight != null
        return ReduceResult(
            state.copy(
                tab = mode.returnTab,
                filesPickSession = newFilesPickSession(),
                filesPickMode = null,
                statusMessage =
                    if (hostInspection) {
                        UiMessage.Key(UiMessageKey.ImportInspectionCancelling)
                    } else {
                        null
                    },
                pendingIsoImport = null,
                deferredIsoImport = null,
                isoImportError = null,
                isoImportSafInFlight = null,
                // The USB guard ends only when the host reports the closed disk (Finished).
                isoImportGeneration = state.isoImportGeneration + 1,
            ),
            effects =
                buildList {
                    if (hostInspection) add(UiEffect.CancelIsoImportInspection)
                    add(UiEffect.CancelPendingWindowsImport(mode.sessionId))
                },
        )
    }

    /** The pin no longer names the live attachment/partition: close it, write nothing. */
    fun abortPinLost(state: AppUiState): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        val hostInspection =
            state.isoImportSafInFlight != null || state.isoImportUsbInFlight != null
        return ReduceResult(
            state.copy(
                tab = mode.returnTab,
                filesPickSession = newFilesPickSession(),
                filesPickMode = null,
                statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
                pendingIsoImport = null,
                deferredIsoImport = null,
                isoImportError = null,
                isoImportSafInFlight = null,
                isoImportGeneration = state.isoImportGeneration + 1,
            ),
            effects =
                buildList {
                    if (hostInspection) add(UiEffect.CancelIsoImportInspection)
                    add(UiEffect.CancelPendingWindowsImport(mode.sessionId))
                },
        )
    }

    fun confirmFile(state: AppUiState, name: String): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        if (mode.target != FilesPickTarget.File) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        if (!mode.matches(state)) return abortPinLost(state)
        if (!state.actionsEnabled) {
            return ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
        }
        // A stale row must not be confirmed while the listing is loading or failed.
        if (state.filesPending || state.filesError != null) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        val entry = state.files.firstOrNull { it.name == name && !it.isDirectory }
        if (entry == null) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        val path = fatChildPath(state.currentPath, entry.name)
        val selection = mode.selection(path, entry.name, entry.sizeBytes, isDirectory = false)
        if (mode.purpose == FilesPickPurpose.RawSource) {
            val device =
                state.device
                    ?: return abortPinLost(state)
            val binding =
                PartitionBinding(
                    device = device,
                    partitionId = mode.partitionId,
                    startLba = mode.startLba,
                    sectorCount = mode.sectorCount,
                )
            return finishFilesPick(
                state = state,
                mode = mode,
                selection = selection,
                status = UiMessage.Key(UiMessageKey.StagingFile, listOf(entry.name)),
                effect =
                    UiEffect.StageUsbImageForRawWrite(
                        path = state.currentPath,
                        name = entry.name,
                        binding = binding,
                    ),
            )
        }
        return finishFilesPick(state, mode, selection)
    }

    fun confirmFolder(state: AppUiState): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        if (mode.target != FilesPickTarget.Folder) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        if (!mode.matches(state)) return abortPinLost(state)
        if (!state.actionsEnabled) {
            return ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
        }
        val path = safePickPath(state.currentPath)
        if (path == null || state.filesPending || state.filesError != null) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        val name = if (path == "/") "/" else path.substringAfterLast('/')
        return finishFilesPick(
            state = state,
            mode = mode,
            selection = mode.selection(path, name, sizeBytes = 0UL, isDirectory = true),
        )
    }

    fun copySucceeded(
        state: AppUiState,
        rawPath: String,
        sizeBytes: Long?,
        pickSessionId: String?,
    ): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        if (mode.purpose != FilesPickPurpose.WindowsImage ||
            mode.target != FilesPickTarget.File
        ) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
            )
        }
        if (pickSessionId != mode.sessionId) {
            // A copy started by an older session must never select into this one.
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        if (!mode.matches(state)) return abortPinLost(state)
        val path = safePickPath(rawPath)
        val leaf = path?.substringAfterLast('/').orEmpty()
        if (path == null ||
            leaf.isEmpty() ||
            fatChildPath(state.currentPath, leaf) != path
        ) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        return finishFilesPick(
            state = state,
            mode = mode,
            selection =
                mode.selection(
                    path = path,
                    name = leaf,
                    sizeBytes = sizeBytes?.takeIf { it >= 0L }?.toULong() ?: 0UL,
                    isDirectory = false,
                ),
        )
    }

    /** Results are kept per purpose, so choosing one picker target never erases another. */
    fun clearSelection(state: AppUiState, purpose: FilesPickPurpose?): ReduceResult =
        if (state.filesPickSelections.isEmpty()) {
            ReduceResult(state)
        } else {
            ReduceResult(
                state.copy(
                    filesPickSelections =
                        if (purpose == null) {
                            emptyMap()
                        } else {
                            state.filesPickSelections - purpose
                        },
                ),
            )
        }

    private fun finishFilesPick(
        state: AppUiState,
        mode: FilesPickMode,
        selection: FilesPickSelection,
        status: UiMessage? = null,
        effect: UiEffect? = null,
    ): ReduceResult {
        if (!mode.matches(state)) return abortPinLost(state)
        val hostInspection =
            state.isoImportSafInFlight != null || state.isoImportUsbInFlight != null
        val staleInspection =
            hostInspection ||
                state.pendingIsoImport != null ||
                state.deferredIsoImport != null ||
                state.isoImportError != null
        return ReduceResult(
            state.copy(
                tab = mode.returnTab,
                filesPickSession = newFilesPickSession(),
                filesPickMode = null,
                filesPickSelections = state.filesPickSelections + (mode.purpose to selection),
                statusMessage = status,
                pendingIsoImport = null,
                deferredIsoImport = null,
                isoImportError = null,
                isoImportSafInFlight = null,
                // The USB guard itself is released only by host Finished after disk close.
                isoImportGeneration =
                    if (staleInspection) {
                        state.isoImportGeneration + 1
                    } else {
                        state.isoImportGeneration
                    },
            ),
            effects =
                buildList {
                    effect?.let(::add)
                    if (hostInspection) add(UiEffect.CancelIsoImportInspection)
                },
        )
    }
}

/**
 * A picker may not disappear under a running/queued operation or a destructive
 * confirmation: the copy progress and its outcome must stay visible.
 */
internal fun filesPickExitBlock(state: AppUiState): UiMessage? =
    when {
        state.operation?.state == OperationState.Running ||
            state.operation?.state == OperationState.Queued ->
            UiMessage.Key(UiMessageKey.WaitForOperation)
        state.pendingConfirmation != null -> UiMessage.Key(UiMessageKey.WaitForOperation)
        else -> null
    }

/**
 * UUID rather than a process counter: a SAF result can survive process death, while a
 * new process would restart a counter and accidentally reuse its token.
 *
 * Deliberately the only non-deterministic value in a pick state transition: tests must
 * assert token equality/inequality or pinned fields, never a concrete UUID. Everything
 * else (path, identity, geometry, selection) stays reproducible.
 */
internal fun newFilesPickSession(): String = java.util.UUID.randomUUID().toString()

/**
 * Paths are absolute on the pinned partition. `.`/`..` and NUL are refused so a picker
 * cannot climb out of the listed folder even if an intent is forged.
 */
internal fun safePickPath(path: String): String? {
    if ('\u0000' in path) return null
    val normalized = normalizeFatPath(path)
    val segments = normalized.split('/').filter { it.isNotEmpty() }
    if (segments.any { it == "." || it == ".." }) return null
    return normalized
}
