package usb.ui

import usb.fs.FilesystemId
import usb.fs.FormatCatalog

/**
 * Pure state transitions for the four-screen shell.
 * Compose and JVM tests drive the same reducer; no USB I/O here.
 */
sealed class UiIntent {
    data class SelectTab(val tab: ShellTab) : UiIntent()

    data class SelectPartition(val partitionId: String) : UiIntent()
    data class SetVtoyEfiWritable(val enabled: Boolean) : UiIntent()
    data class SetVtoyBypassTpm(val enabled: Boolean) : UiIntent()
    data class SetVtoyBypassRam(val enabled: Boolean) : UiIntent()
    data class SetVtoyLocalAccount(val enabled: Boolean) : UiIntent()

    /** Choose filesystem for the next Format action (must be in FormatCatalog.supportedFormatIds). */
    data class SelectFormatFilesystem(val id: FilesystemId) : UiIntent()

    data class DeviceChanged(
        val presence: DevicePresence,
        val device: DeviceIdentity? = null,
        val partitions: List<PartitionTab> = emptyList(),
        val files: List<FileEntry> = emptyList(),
        val tableValid: Boolean = true,
    ) : UiIntent()

    data class OperationUpdated(val progress: OperationProgress?) : UiIntent()

    data class RequestDestructive(val action: DestructiveAction) : UiIntent()

    data object ConfirmDestructive : UiIntent()

    data object DismissConfirmation : UiIntent()

    data class RequestLinkOverwrite(val fileName: String) : UiIntent()

    data class LinkOverwriteAvailable(val fileName: String) : UiIntent()

    data class IsoActionRequested(
        val fileName: String,
        val action: IsoAction,
    ) : UiIntent()

    data class SetImage(val uri: String?) : UiIntent()
    data class SetStagedRawImage(val path: String, val label: String) : UiIntent()
    data class ClearStagedRawImage(val deleteFile: Boolean = true) : UiIntent()

    /** SAF [uri] of the GPT critical-sector backup chosen for the next restore. */
    data class SetBackupFile(val uri: String?) : UiIntent()
    data class SetRescueImage(val uri: String?) : UiIntent()
    data class SetRescueMap(val uri: String?) : UiIntent()

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
    val formatId: FilesystemId? = null,
    val linkOverwrite: Boolean = false,
    val confirmation: DestructiveConfirmation? = null,
) {
    enum class Kind {
        SubmitDestructive,
        CancelOperation,
        LinkXml,
        LoadFiles,
        CreateFolder,
        DeleteEntry,
        UploadFile,
        DownloadFile,
        StageUsbImageForRawWrite,
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
            is UiIntent.SetVtoyBypassTpm ->
                ReduceResult(state.copy(vtoyBypassTpm = intent.enabled))
            is UiIntent.SetVtoyBypassRam ->
                ReduceResult(state.copy(vtoyBypassRam = intent.enabled))
            is UiIntent.SetVtoyLocalAccount ->
                ReduceResult(state.copy(vtoyLocalAccount = intent.enabled))

            is UiIntent.SelectFormatFilesystem -> {
                if (!state.actionsEnabled) {
                    ReduceResult(state)
                } else {
                    val id = intent.id
                    if (id !in FormatCatalog.supportedFormatIds()) {
                        ReduceResult(
                            state.copy(
                                statusMessage = "Format as ${FormatCatalog.label(id)} is not supported",
                            ),
                        )
                    } else {
                        ReduceResult(state.copy(selectedFormatId = id, statusMessage = null))
                    }
                }
            }

            is UiIntent.DeviceChanged -> {
                val selected =
                    intent.partitions
                        .firstOrNull { it.id == state.selectedPartitionId }
                        ?.id
                        ?: intent.partitions.firstOrNull()?.id
                val candidateMatchesDevice =
                    intent.presence == DevicePresence.Ready &&
                        intent.device?.identity == state.linkOverwriteCandidateIdentity
                ReduceResult(
                    state.copy(
                        presence = intent.presence,
                        device = intent.device,
                        partitions = intent.partitions,
                        tableValid = intent.tableValid,
                        selectedPartitionId = selected,
                        currentPath = "/",
                        files = intent.files,
                        pendingConfirmation = null,
                        linkOverwriteCandidate = state.linkOverwriteCandidate.takeIf { candidateMatchesDevice },
                        linkOverwriteCandidateIdentity = state.linkOverwriteCandidateIdentity.takeIf { candidateMatchesDevice },
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
                                    // Keep a more specific status (e.g. staged-image hint) when
                                    // the operation itself has no resultMessage.
                                    intent.progress.resultMessage
                                        ?: state.statusMessage?.takeIf {
                                            it.isNotBlank() && it != "Running" && it != "Queued"
                                        }
                                        ?: "Success"
                                OperationState.Cancelled -> "Cancelled"
                                OperationState.Failed ->
                                    intent.progress.errorMessage ?: "Failed"
                                OperationState.Partial ->
                                    intent.progress.resultMessage ?: intent.progress.errorMessage ?: "Partial result"
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
                        intent.action == DestructiveAction.RAW_WRITE &&
                            state.imageUri == null && state.stagedRawImagePath == null ->
                            "Select or stage an ISO/IMG file first"
                        intent.action == DestructiveAction.GPT_RESTORE && state.backupUri == null ->
                            "Choose a GPT backup file first"
                        intent.action == DestructiveAction.RESCUE_RESTORE &&
                            (state.rescueImageUri == null || state.rescueMapUri == null) ->
                            "Choose a rescue image and its map first"
                        (intent.action == DestructiveAction.DELETE_PARTITION || intent.action == DestructiveAction.FORMAT_PARTITION) &&
                            state.selectedPartitionId == null ->
                            "Select a partition first"
                        (intent.action == DestructiveAction.DELETE_PARTITION || intent.action == DestructiveAction.FORMAT_PARTITION) ->
                            writeBlockMessage(state)
                        intent.action == DestructiveAction.CREATE_PARTITION && !state.tableValid ->
                            "Cannot create partition on corrupt table. Wipe or restore first."
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
                    val targetPart = if (intent.action == DestructiveAction.DELETE_PARTITION ||
                        intent.action == DestructiveAction.FORMAT_PARTITION
                    ) {
                        state.selectedPartition()?.name ?: state.selectedPartitionId
                    } else {
                        null
                    }
                    val formatId =
                        if (intent.action == DestructiveAction.FORMAT_PARTITION) {
                            state.selectedFormatId
                        } else {
                            null
                        }
                    ReduceResult(
                        state.copy(
                            pendingConfirmation =
                                DestructiveConfirmation.request(
                                    device,
                                    intent.action,
                                    targetPart,
                                    formatId = formatId,
                                    target = state.selectedPartition(),
                                ),
                        ),
                    )
                }
            }

            UiIntent.ConfirmDestructive -> {
                val pending = state.pendingConfirmation
                val device = state.device
                val action =
                    pending?.let { p ->
                        DestructiveAction.entries.firstOrNull { it.displayName() == p.action }
                    }
                val invalidState = when {
                    pending == null || device == null -> true
                    state.presence != DevicePresence.Ready -> true
                    state.isRunning -> true
                    action == DestructiveAction.CREATE_PARTITION && !state.tableValid -> true
                    (action == DestructiveAction.DELETE_PARTITION || action == DestructiveAction.FORMAT_PARTITION) &&
                        state.selectedPartitionId == null -> true
                    else -> false
                }
                if (invalidState || action == null) {
                    ReduceResult(state.copy(pendingConfirmation = null))
                } else if (action == DestructiveAction.VENTOY_LINK_XML) {
                    val iso = pending.isoName
                    if (iso.isNullOrBlank()) {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                statusMessage = "No ISO selected for the Windows setup link",
                            ),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                statusMessage =
                                    "Writing autounattend.xml and ventoy.json for " +
                                        "${iso.substringAfterLast('/')}…",
                            ),
                            effects = listOf(
                                UiEffect(
                                    UiEffect.Kind.LinkXml,
                                    isoName = iso,
                                    isoAction = IsoAction.LinkXml,
                                    linkOverwrite = pending.linkOverwrite,
                                    confirmation = pending,
                                ),
                            ),
                        )
                    }
                } else {
                    ReduceResult(
                        state.copy(pendingConfirmation = null),
                        effects = listOf(
                            UiEffect(
                                UiEffect.Kind.SubmitDestructive,
                                action = action,
                                formatId = pending.formatId,
                                confirmation = pending,
                                isoName = pending.isoName,
                                linkOverwrite = pending.linkOverwrite,
                            ),
                        ),
                    )
                }
            }

            UiIntent.DismissConfirmation ->
                ReduceResult(state.copy(pendingConfirmation = null))
            is UiIntent.RequestLinkOverwrite -> {
                val device = state.device
                val candidateMatches = state.linkOverwriteCandidate == intent.fileName && state.linkOverwriteCandidateIdentity == device?.identity
                if (!state.actionsEnabled || device == null) {
                    ReduceResult(state.copy(statusMessage = "No USB device for Ventoy setup link"))
                } else if (!candidateMatches) {
                    ReduceResult(state.copy(linkOverwriteCandidate = null, linkOverwriteCandidateIdentity = null, statusMessage = "Existing Ventoy link no longer matches connected device"))
                } else {
                    ReduceResult(state.copy(pendingConfirmation = DestructiveConfirmation.request(device, DestructiveAction.VENTOY_LINK_XML, isoName = intent.fileName, linkOverwrite = true), linkOverwriteCandidate = null, linkOverwriteCandidateIdentity = null, statusMessage = null))
                }
            }
            is UiIntent.LinkOverwriteAvailable -> {
                val device = state.device
                if (!state.actionsEnabled || device == null) {
                    ReduceResult(state.copy(linkOverwriteCandidate = null, linkOverwriteCandidateIdentity = null))
                } else {
                    ReduceResult(state.copy(linkOverwriteCandidate = intent.fileName, linkOverwriteCandidateIdentity = device.identity, statusMessage = "Existing Ventoy link detected; choose explicit overwrite"))
                }
            }

            is UiIntent.IsoActionRequested -> {
                if (!state.actionsEnabled) {
                    ReduceResult(
                        state.copy(statusMessage = presenceMessage(state.presence)),
                    )
                } else when (intent.action) {
                    IsoAction.SendToRawWriter -> {
                        val name = intent.fileName.substringAfterLast('/')
                        ReduceResult(
                            state.copy(statusMessage = "Staging $name for raw write…"),
                            listOf(
                                UiEffect(
                                    UiEffect.Kind.StageUsbImageForRawWrite,
                                    name = name,
                                    path = state.currentPath,
                                    isoName = name,
                                    isoAction = intent.action,
                                ),
                            ),
                        )
                    }
                    IsoAction.LinkXml -> {
                        val device = state.device
                        if (device == null) {
                            ReduceResult(
                                state.copy(statusMessage = "No USB device for Ventoy setup link"),
                            )
                        } else {
                            ReduceResult(
                                state.copy(
                                    pendingConfirmation =
                                        DestructiveConfirmation.request(
                                            device = device,
                                            action = DestructiveAction.VENTOY_LINK_XML,
                                            isoName = intent.fileName,
                                            linkOverwrite = false,
                                        ),
                                    statusMessage = null,
                                ),
                            )
                        }
                    }
                }
            }

            is UiIntent.SetImage ->
                if (!state.actionsEnabled) {
                    // Busy / pending confirm — do not clear staged source under a live raw write.
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            imageUri = intent.uri,
                            stagedRawImagePath = if (intent.uri != null) null else state.stagedRawImagePath,
                            stagedRawImageLabel = if (intent.uri != null) null else state.stagedRawImageLabel,
                            statusMessage =
                                if (intent.uri == null) "Image cleared" else "Image ready for raw write",
                        ),
                    )
                }

            is UiIntent.SetStagedRawImage ->
                if (state.isRunning || state.operation?.state == OperationState.Queued || state.pendingConfirmation != null) {
                    ReduceResult(state.copy(statusMessage = "Cannot change staged image while operation is active"))
                } else {
                    ReduceResult(
                        state.copy(
                            stagedRawImagePath = intent.path,
                            stagedRawImageLabel = intent.label,
                            imageUri = null,
                            statusMessage = "Staged ${intent.label} — confirm raw write on Partitions",
                        ),
                    )
                }

            is UiIntent.ClearStagedRawImage ->
                if (state.isRunning || state.operation?.state == OperationState.Queued || state.pendingConfirmation != null) {
                    ReduceResult(state.copy(statusMessage = "Cannot clear staged image while operation is active"))
                } else {
                    ReduceResult(
                        state.copy(
                            stagedRawImagePath = null,
                            stagedRawImageLabel = null,
                            statusMessage = "Staged raw image cleared",
                        ),
                    )
                }
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
                if (state.isRunning) {
                    ReduceResult(state.copy(statusMessage = "Operation already in progress"))
                } else {
                    ReduceResult(
                        state.copy(statusMessage = "Starting automated self-test..."),
                        effects = listOf(UiEffect(UiEffect.Kind.RunSelfTest)),
                    )
                }

            is UiIntent.SetRescueImage ->
                ReduceResult(
                    state.copy(
                        rescueImageUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) "Rescue image cleared"
                            else "Rescue image ready: ${intent.uri.substringAfterLast('/')}",
                    ),
                )

            is UiIntent.SetRescueMap ->
                ReduceResult(
                    state.copy(
                        rescueMapUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) "Rescue map cleared"
                            else "Rescue map ready: ${intent.uri.substringAfterLast('/')}",
                    ),
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
