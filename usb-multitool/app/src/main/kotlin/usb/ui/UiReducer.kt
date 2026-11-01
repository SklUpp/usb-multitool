package usb.ui

/**
 * Pure state transitions for the four-screen shell.
 * Compose and JVM tests drive the same reducer; no USB I/O here.
 */
sealed class UiIntent {
    data class SelectTab(val tab: ShellTab) : UiIntent()

    data class SelectPartition(val partitionId: String) : UiIntent()

    data class SetVtoyEfiWritable(val enabled: Boolean) : UiIntent()

    data class DeviceChanged(
        val presence: DevicePresence,
        val device: DeviceIdentity? = null,
        val partitions: List<PartitionTab> = emptyList(),
        val files: List<FileEntry> = emptyList(),
    ) : UiIntent()

    data class OperationUpdated(val progress: OperationProgress?) : UiIntent()

    data class RequestDestructive(val action: DestructiveAction) : UiIntent()

    data object ConfirmDestructive : UiIntent()

    data object DismissConfirmation : UiIntent()

    data class IsoActionRequested(
        val fileName: String,
        val action: IsoAction,
    ) : UiIntent()

    data class SetImage(val uri: String?) : UiIntent()

    /** SAF [uri] of the GPT critical-sector backup chosen for the next restore. */
    data class SetBackupFile(val uri: String?) : UiIntent()

    /** Read-only scan of every sector; reports unreadable ranges. */
    data object StartReadScan : UiIntent()

    /** Read-only full image plus rescue map into the app's external files dir. */
    data object StartRescueBackup : UiIntent()

    /** Capture the GPT critical sectors into the app's external files dir. */
    data object CaptureGptBackup : UiIntent()

    /** Cycle dark theme: System (null) -> Dark (true) -> Light (false) -> System (null). */
    data object ToggleTheme : UiIntent()

    /** Run the automated self-test suite over all functional engines. */
    data object RunSelfTest : UiIntent()

    data class Status(val message: String?) : UiIntent()

    data object CancelOperation : UiIntent()

    /**
     * Enter the absolute FAT directory [path]. Clears [AppUiState.files] and emits a
     * [UiEffect.Kind.LoadFiles] effect, so nothing is displayed for an unlisted path.
     */
    data class OpenDirectory(val path: String) : UiIntent()

    /** Go to the parent of [AppUiState.currentPath]; no-op at the root. */
    data object NavigateUp : UiIntent()

    /** Create a real folder named [name] inside [AppUiState.currentPath]. */
    data class CreateFolder(val name: String) : UiIntent()

    /** Delete entry [name] from [AppUiState.currentPath] on the volume. */
    data class DeleteEntry(val name: String) : UiIntent()

    /** Stream the SAF [uri] into [AppUiState.currentPath] in chunks. */
    data class UploadFile(val uri: String) : UiIntent()

    /** Copy entry [name] from [AppUiState.currentPath] to the SAF destination [uri]. */
    data class DownloadFile(
        val name: String,
        val uri: String,
    ) : UiIntent()

    /** Result of a real FAT list of [path]; applied only while that path is displayed. */
    data class FilesListed(
        val path: String,
        val files: List<FileEntry>,
    ) : UiIntent()

    /** Real list failure for [path]; displayed entries are voided, never faked. */
    data class FilesListFailed(
        val path: String,
        val message: String,
    ) : UiIntent()
}

data class UiEffect(
    val kind: Kind,
    val action: DestructiveAction? = null,
    val isoName: String? = null,
    val isoAction: IsoAction? = null,
    val path: String? = null,
    val name: String? = null,
    val uri: String? = null,
) {
    enum class Kind {
        SubmitDestructive,
        CancelOperation,
        LinkXml,
        SendToRawWriter,
        LoadFiles,
        CreateFolder,
        DeleteEntry,
        UploadFile,
        DownloadFile,
        ReadScan,
        RescueBackup,
        CaptureGptBackup,
        RunSelfTest,
    }
}

data class ReduceResult(
    val state: AppUiState,
    val effects: List<UiEffect> = emptyList(),
)

object UiReducer {
    fun reduce(state: AppUiState, intent: UiIntent): ReduceResult =
        when (intent) {
            is UiIntent.SelectTab ->
                ReduceResult(state.copy(tab = intent.tab, statusMessage = null))

            is UiIntent.SelectPartition ->
                if (!state.actionsEnabled) {
                    // Switching mid-operation would post a Running LoadFiles over the live
                    // operation and orphan it from Cancel; report only why nothing changed.
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    ReduceResult(
                        state.copy(
                            selectedPartitionId = intent.partitionId,
                            currentPath = "/",
                            files = emptyList(),
                        ),
                        effects = listOf(UiEffect(UiEffect.Kind.LoadFiles, path = "/")),
                    )
                }

            is UiIntent.SetVtoyEfiWritable ->
                ReduceResult(state.copy(vtoyEfiWriteEnabled = intent.enabled))

            is UiIntent.DeviceChanged -> {
                val selected =
                    intent.partitions
                        .firstOrNull { it.id == state.selectedPartitionId }
                        ?.id
                        ?: intent.partitions.firstOrNull()?.id
                ReduceResult(
                    state.copy(
                        presence = intent.presence,
                        device = intent.device,
                        partitions = intent.partitions,
                        selectedPartitionId = selected,
                        currentPath = "/",
                        files = intent.files,
                        pendingConfirmation =
                            if (intent.presence == DevicePresence.Disconnected ||
                                intent.presence == DevicePresence.Absent
                            ) {
                                null
                            } else {
                                state.pendingConfirmation
                            },
                        statusMessage =
                            when (intent.presence) {
                                DevicePresence.Absent -> "No USB mass-storage device"
                                DevicePresence.PermissionRequired -> "USB permission required"
                                DevicePresence.Disconnected -> "Device disconnected"
                                DevicePresence.Busy -> "Device busy"
                                DevicePresence.Ready -> state.statusMessage
                            },
                    ),
                )
            }

            is UiIntent.OperationUpdated ->
                ReduceResult(
                    state.copy(
                        operation = intent.progress,
                        presence =
                            when {
                                intent.progress?.state == OperationState.Running ->
                                    if (state.presence == DevicePresence.Ready ||
                                        state.presence == DevicePresence.Busy
                                    ) {
                                        DevicePresence.Busy
                                    } else {
                                        state.presence
                                    }
                                intent.progress?.state == OperationState.Completed ||
                                    intent.progress?.state == OperationState.Cancelled ||
                                    intent.progress?.state == OperationState.Failed ||
                                    intent.progress?.state == OperationState.Partial ->
                                    if (state.presence == DevicePresence.Busy) {
                                        DevicePresence.Ready
                                    } else {
                                        state.presence
                                    }
                                else -> state.presence
                            },
                        statusMessage =
                            when (intent.progress?.state) {
                                OperationState.Completed ->
                                    intent.progress.resultMessage ?: "Success"
                                OperationState.Cancelled -> "Cancelled"
                                OperationState.Failed ->
                                    intent.progress.errorMessage ?: "Failed"
                                OperationState.Partial ->
                                    intent.progress.errorMessage ?: "Partial result"
                                OperationState.Running -> "Running"
                                OperationState.Queued -> "Queued"
                                null -> state.statusMessage
                            },
                    ),
                )

            is UiIntent.RequestDestructive -> {
                val device = state.device
                val missingInput =
                    when {
                        intent.action == DestructiveAction.RAW_WRITE && state.imageUri == null ->
                            "Select an ISO/IMG file first"
                        intent.action == DestructiveAction.GPT_RESTORE && state.backupUri == null ->
                            "Choose a GPT backup file first"
                        else -> null
                    }
                if (device != null && state.actionsEnabled && missingInput != null) {
                    ReduceResult(state.copy(statusMessage = missingInput))
                } else if (device == null || !state.actionsEnabled) {
                    ReduceResult(
                        state.copy(
                            statusMessage =
                                when {
                                    device == null -> presenceMessage(state.presence)
                                    state.isRunning -> "Busy: wait for the running operation"
                                    else -> presenceMessage(state.presence)
                                },
                        ),
                    )
                } else {
                    ReduceResult(
                        state.copy(
                            pendingConfirmation =
                                DestructiveConfirmation.request(device, intent.action),
                        ),
                    )
                }
            }

            UiIntent.ConfirmDestructive -> {
                val pending = state.pendingConfirmation
                if (pending == null) {
                    ReduceResult(state)
                } else {
                    val action =
                        DestructiveAction.entries.firstOrNull {
                            it.displayName() == pending.action
                        }
                    ReduceResult(
                        state.copy(pendingConfirmation = null),
                        effects =
                            if (action != null) {
                                listOf(UiEffect(UiEffect.Kind.SubmitDestructive, action = action))
                            } else {
                                emptyList()
                            },
                    )
                }
            }

            UiIntent.DismissConfirmation ->
                ReduceResult(state.copy(pendingConfirmation = null))

            is UiIntent.IsoActionRequested -> {
                if (!state.actionsEnabled) {
                    ReduceResult(
                        state.copy(statusMessage = presenceMessage(state.presence)),
                    )
                } else {
                    val effect =
                        when (intent.action) {
                            IsoAction.LinkXml ->
                                UiEffect(
                                    UiEffect.Kind.LinkXml,
                                    isoName = intent.fileName,
                                    isoAction = intent.action,
                                )
                            IsoAction.SendToRawWriter ->
                                UiEffect(
                                    UiEffect.Kind.SendToRawWriter,
                                    isoName = intent.fileName,
                                    isoAction = intent.action,
                                )
                        }
                    val next =
                        if (intent.action == IsoAction.SendToRawWriter) {
                            // Route to Partitions/Raw; still requires destructive confirm upstream.
                            state.copy(
                                tab = ShellTab.Partitions,
                                statusMessage = "Send ${intent.fileName} to raw writer",
                            )
                        } else {
                            state.copy(
                                statusMessage =
                                    "Writing autounattend.xml and ventoy.json for " +
                                        "${intent.fileName.substringAfterLast('/')}…",
                            )
                        }
                    ReduceResult(next, listOf(effect))
                }
            }

            is UiIntent.SetImage ->
                ReduceResult(
                    state.copy(
                        imageUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) "Image cleared" else "Image ready for raw write",
                    ),
                )

            is UiIntent.SetBackupFile ->
                ReduceResult(
                    state.copy(
                        backupUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) {
                                "GPT backup cleared"
                            } else {
                                "GPT backup ready: ${intent.uri.substringAfterLast('/')}"
                            },
                    ),
                )

            UiIntent.StartReadScan -> nonDestructiveEffect(state, UiEffect.Kind.ReadScan)

            UiIntent.StartRescueBackup -> nonDestructiveEffect(state, UiEffect.Kind.RescueBackup)

            UiIntent.CaptureGptBackup -> nonDestructiveEffect(state, UiEffect.Kind.CaptureGptBackup)

            UiIntent.ToggleTheme -> {
                val next = when (state.darkTheme) {
                    null -> true
                    true -> false
                    false -> null
                }
                ReduceResult(state.copy(darkTheme = next))
            }

            UiIntent.RunSelfTest ->
                ReduceResult(
                    state.copy(statusMessage = "Starting automated self-test..."),
                    effects = listOf(UiEffect(UiEffect.Kind.RunSelfTest)),
                )

            is UiIntent.Status -> ReduceResult(state.copy(statusMessage = intent.message))

            UiIntent.CancelOperation ->
                ReduceResult(
                    state,
                    effects = listOf(UiEffect(UiEffect.Kind.CancelOperation)),
                )

            is UiIntent.OpenDirectory -> {
                val target = normalizeFatPath(intent.path)
                ReduceResult(
                    state.copy(currentPath = target, files = emptyList()),
                    effects = listOf(UiEffect(UiEffect.Kind.LoadFiles, path = target)),
                )
            }

            UiIntent.NavigateUp -> {
                val target = fatParentPath(state.currentPath)
                if (target == state.currentPath) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(currentPath = target, files = emptyList()),
                        effects = listOf(UiEffect(UiEffect.Kind.LoadFiles, path = target)),
                    )
                }
            }

            is UiIntent.CreateFolder -> {
                val name = intent.name.trim()
                val block = writeBlockMessage(state)
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    !validEntryName(name) ->
                        ReduceResult(state.copy(statusMessage = "Enter a folder name without / or \\"))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect(
                                        UiEffect.Kind.CreateFolder,
                                        path = state.currentPath,
                                        name = name,
                                    ),
                                ),
                        )
                }
            }

            is UiIntent.DeleteEntry -> {
                val block = writeBlockMessage(state)
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    !validEntryName(intent.name) ->
                        ReduceResult(state.copy(statusMessage = "Select a valid entry to delete"))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect(
                                        UiEffect.Kind.DeleteEntry,
                                        path = state.currentPath,
                                        name = intent.name,
                                    ),
                                ),
                        )
                }
            }

            is UiIntent.UploadFile -> {
                val block = writeBlockMessage(state)
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect(
                                        UiEffect.Kind.UploadFile,
                                        path = state.currentPath,
                                        uri = intent.uri,
                                    ),
                                ),
                        )
                }
            }

            is UiIntent.DownloadFile ->
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    !validEntryName(intent.name) ->
                        ReduceResult(state.copy(statusMessage = "Select a file to download"))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect(
                                        UiEffect.Kind.DownloadFile,
                                        path = state.currentPath,
                                        name = intent.name,
                                        uri = intent.uri,
                                    ),
                                ),
                        )
                }

            is UiIntent.FilesListed ->
                if (intent.path != state.currentPath) {
                    // Late result for a directory the user already left.
                    ReduceResult(state)
                } else {
                    ReduceResult(state.copy(files = intent.files))
                }

            is UiIntent.FilesListFailed ->
                if (intent.path != state.currentPath) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(files = emptyList(), statusMessage = intent.message),
                    )
                }
        }

    /** Read-only disk work still needs a ready device; otherwise report why nothing ran. */
    private fun nonDestructiveEffect(state: AppUiState, kind: UiEffect.Kind): ReduceResult =
        if (!state.actionsEnabled) {
            ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
        } else {
            ReduceResult(state, effects = listOf(UiEffect(kind)))
        }

    fun presenceMessage(presence: DevicePresence): String =
        when (presence) {
            DevicePresence.Absent -> "No USB mass-storage device"
            DevicePresence.PermissionRequired -> "USB permission required"
            DevicePresence.Busy -> "Device busy"
            DevicePresence.Disconnected -> "Device disconnected"
            DevicePresence.Ready -> "Ready"
        }

    /**
     * Explicit reason writes to the selected partition are refused, or null when allowed.
     * A read-only partition (VTOYEFI) stays untouched until the user enables writes.
     */
    fun writeBlockMessage(state: AppUiState): String? {
        val partition = state.selectedPartition() ?: return "Select a partition first"
        if (!partition.readOnly || state.vtoyEfiWriteEnabled) return null
        return "${partition.name} is read-only: enable VTOYEFI writes first"
    }

    /** A single FAT entry name: no separators, no dot entries. */
    private fun validEntryName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name
}
