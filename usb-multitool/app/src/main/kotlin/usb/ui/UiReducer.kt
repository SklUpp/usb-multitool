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
    /** Lift or restore write protection for exactly [partitionId] on the current attachment. */
    data class SetVtoyEfiWritable(val partitionId: String, val enabled: Boolean) : UiIntent()
    data class SetVtoyBypassChecks(val enabled: Boolean?) : UiIntent()
    data class SetVtoyLocalAccount(val enabled: Boolean?) : UiIntent()

    /** Choose filesystem for the next Format action (must be in FormatCatalog.supportedFormatIds). */
    data class SelectFormatFilesystem(val id: FilesystemId) : UiIntent()

    data class DeviceChanged(
        val presence: DevicePresence,
        val device: DeviceIdentity? = null,
        val partitions: List<PartitionTab> = emptyList(),
        val files: List<FileEntry> = emptyList(),
        val freeRegions: List<FreeRegionUi> = emptyList(),
        val sectorSize: Int = 512,
        val tableValid: Boolean = true,
        val partitionTableDamaged: Boolean = false,
        val partitionTableAmbiguous: Boolean = false,
    ) : UiIntent()

    data class OperationUpdated(val progress: OperationProgress?) : UiIntent()

    data class RequestDestructive(val action: DestructiveAction) : UiIntent()
    data class RequestCreatePartition(val startLba: ULong, val sectorCount: ULong) : UiIntent()

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

    /** Switch the in-app language; persisted by the host. */
    data class SetLanguage(val language: AppLanguage) : UiIntent()

    /** Run the automated self-test suite over all functional engines. */
    data object RunSelfTest : UiIntent()

    data class Status(val message: UiMessage?) : UiIntent()

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

    /** Rename entry [from] to [to] on exactly the captured device/partition. */
    data class RenameEntry(
        val from: String,
        val to: String,
        val binding: PartitionBinding,
    ) : UiIntent()

    /** Request confirmation to change the selected partition's on-disk type (ESP or data). */
    data class RequestSetPartitionRole(val role: PartitionRole) : UiIntent()

    /** Request confirmation to change the selected partition's legacy bootable flag. */
    data class RequestSetPartitionBootable(val bootable: Boolean) : UiIntent()

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

    /** Append a timestamped line to the bounded operation log. */
    data class AppendLog(val line: String) : UiIntent()
    /** Store the latest Ventoy release tag fetched from GitHub (null = check failed). */
    data class SetLatestVentoyRelease(val tag: String?) : UiIntent()
}

data class UiEffect(
    val kind: Kind,
    val action: DestructiveAction? = null,
    val isoName: String? = null,
    val isoAction: IsoAction? = null,
    val path: String? = null,
    val name: String? = null,
    /** Target name for [Kind.RenameEntry]. */
    val newName: String? = null,
    /** Exact device/partition target captured for this mutation. */
    val binding: PartitionBinding? = null,
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
        RenameEntry,
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
    /**
     * Actions that rewrite the partition layout. Ambiguity blocks them unconditionally:
     * unlike an EFI read-only partition, no override may lift this.
     */
    private val layoutMutations = setOf(
        DestructiveAction.CREATE_PARTITION,
        DestructiveAction.DELETE_PARTITION,
        DestructiveAction.FORMAT_PARTITION,
        DestructiveAction.SET_PARTITION_ROLE,
        DestructiveAction.SET_PARTITION_BOOTABLE,
        DestructiveAction.GPT_REPAIR,
    )

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
                            filesError = null,
                        ),
                        effects = listOf(UiEffect(UiEffect.Kind.LoadFiles, path = "/")),
                    )
                }

            is UiIntent.SetVtoyEfiWritable ->
                when {
                    !intent.enabled ->
                        if (state.vtoyEfiWritePartitionId == null ||
                            state.vtoyEfiWritePartitionId == intent.partitionId
                        ) {
                            ReduceResult(
                                state.copy(
                                    vtoyEfiWritePartitionId = null,
                                    vtoyEfiWriteStartLba = null,
                                    vtoyEfiWriteSectorCount = null,
                                ),
                            )
                        } else {
                            ReduceResult(state)
                        }
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    else -> {
                        val target = state.partitions.firstOrNull {
                            it.id == intent.partitionId && it.readOnly
                        }
                        val start = target?.startLba
                        val count = target?.sectorCount
                        when {
                            target == null ->
                                ReduceResult(
                                    state.copy(
                                        statusMessage = message(
                                            UiMessageKey.EfiPartitionMissing,
                                            intent.partitionId,
                                        ),
                                    ),
                                )
                            start == null || count == null ->
                                ReduceResult(
                                    state.copy(
                                        statusMessage = UiMessage.Key(UiMessageKey.EfiGeometryUnknown),
                                    ),
                                )
                            else ->
                                ReduceResult(
                                    state.copy(
                                        vtoyEfiWritePartitionId = target.id,
                                        vtoyEfiWriteStartLba = start,
                                        vtoyEfiWriteSectorCount = count,
                                    ),
                                )
                        }
                    }
                }
            is UiIntent.SetVtoyBypassChecks ->
                ReduceResult(state.copy(vtoyBypassChecks = intent.enabled))
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
                                statusMessage = message(UiMessageKey.FormatUnsupported, FormatCatalog.label(id)),
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
                        intent.device?.identity == state.linkOverwriteCandidateIdentity &&
                        intent.device?.attachGeneration == state.linkOverwriteCandidateGeneration
                // The EFI write override belongs to one physical attachment: a partition
                // refresh keeps it, a new attach generation / other stick / detach drops it.
                val incoming = intent.device
                val sameAttachment =
                    intent.presence == DevicePresence.Ready &&
                        incoming != null &&
                        incoming.identity == state.device?.identity &&
                        incoming.attachGeneration == state.device?.attachGeneration
                val retainedEfi = state.vtoyEfiWritePartitionId?.takeIf { sameAttachment }?.let { id ->
                    intent.partitions.firstOrNull {
                        it.id == id &&
                            it.readOnly &&
                            it.startLba == state.vtoyEfiWriteStartLba &&
                            it.sectorCount == state.vtoyEfiWriteSectorCount
                    }
                }
                ReduceResult(
                    state.copy(
                        presence = intent.presence,
                        device = intent.device,
                        vtoyEfiWritePartitionId = retainedEfi?.id,
                        vtoyEfiWriteStartLba = retainedEfi?.startLba,
                        vtoyEfiWriteSectorCount = retainedEfi?.sectorCount,
                        partitions = intent.partitions,
                        freeRegions = intent.freeRegions,
                        diskSectorSize = intent.sectorSize,
                        tableValid = intent.tableValid,
                        partitionTableDamaged = intent.partitionTableDamaged,
                        partitionTableAmbiguous = intent.partitionTableAmbiguous,
                        selectedPartitionId = selected,
                        currentPath = "/",
                        files = intent.files,
                        filesError = null,
                        pendingConfirmation = null,
                        linkOverwriteCandidate = state.linkOverwriteCandidate.takeIf { candidateMatchesDevice },
                        linkOverwriteCandidateIdentity = state.linkOverwriteCandidateIdentity.takeIf { candidateMatchesDevice },
                        linkOverwriteCandidateGeneration = state.linkOverwriteCandidateGeneration.takeIf { candidateMatchesDevice },
                        statusMessage =
                            if (intent.presence == DevicePresence.Ready) {
                                state.statusMessage
                            } else {
                                presenceMessage(intent.presence)
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
                                    // Keep a more specific status (for example a staged-image
                                    // hint) when the operation has no result message.
                                    intent.progress.resultMessage?.let(UiMessage::Raw)
                                        ?: state.statusMessage?.takeIf { !it.isDefaultStateStatus() }
                                        ?: UiMessage.Key(UiMessageKey.Success)
                                OperationState.Cancelled ->
                                    UiMessage.Key(UiMessageKey.CancelledStatus)
                                OperationState.Failed ->
                                    intent.progress.errorMessage?.let(UiMessage::Raw)
                                        ?: UiMessage.Key(UiMessageKey.FailedStatus)
                                OperationState.Partial ->
                                    (intent.progress.resultMessage ?: intent.progress.errorMessage)
                                        ?.let(UiMessage::Raw)
                                        ?: UiMessage.Key(UiMessageKey.PartialStatus)
                                OperationState.Running -> UiMessage.Key(UiMessageKey.RunningStatus)
                                OperationState.Queued -> UiMessage.Key(UiMessageKey.QueuedStatus)
                                null -> state.statusMessage
                            },
                    ),
                )

            is UiIntent.RequestCreatePartition -> {
                val device = state.device
                val valid = intent.sectorCount > 0UL && intent.startLba <= ULong.MAX_VALUE - (intent.sectorCount - 1UL) &&
                    state.diskSectorSize > 0 && (1024 * 1024) % state.diskSectorSize == 0 &&
                    intent.startLba % (1024 * 1024 / state.diskSectorSize).toULong() == 0UL && state.freeRegions.any { region ->
                    intent.startLba >= region.startLba &&
                        intent.startLba - region.startLba < region.sectorCount &&
                        intent.sectorCount <= region.sectorCount - (intent.startLba - region.startLba)
                }
                if (state.partitionTableAmbiguous) {
                    ReduceResult(
                        state.copy(
                            statusMessage = UiMessage.Key(UiMessageKey.PartitionTableAmbiguous),
                        ),
                    )
                } else if (state.partitionTableDamaged) {
                    ReduceResult(
                        state.copy(statusMessage = UiMessage.Key(UiMessageKey.GptPrimaryDamaged)),
                    )
                } else if (device == null || !state.actionsEnabled || !state.tableValid || !valid) {
                    ReduceResult(
                        state.copy(statusMessage = UiMessage.Key(UiMessageKey.InvalidFreeRegion)),
                    )
                } else {
                    val base = DestructiveConfirmation.request(device, DestructiveAction.CREATE_PARTITION)
                    ReduceResult(state.copy(pendingConfirmation = base.copy(
                        createStartLba = intent.startLba,
                        createSectorCount = intent.sectorCount,
                    )))
                }
            }

            is UiIntent.RequestDestructive -> {
                val device = state.device
                val blockedByAmbiguity =
                    state.partitionTableAmbiguous && intent.action in layoutMutations
                val blockedByDamagedGpt =
                    state.partitionTableDamaged &&
                        intent.action in setOf(
                            DestructiveAction.CREATE_PARTITION,
                            DestructiveAction.DELETE_PARTITION,
                            DestructiveAction.GPT_REPAIR,
                        )
                val missingInput =
                    when {
                        blockedByAmbiguity ->
                            UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
                        blockedByDamagedGpt -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
                        intent.action == DestructiveAction.RAW_WRITE &&
                            state.imageUri == null && state.stagedRawImagePath == null ->
                            UiMessage.Key(UiMessageKey.SelectImageFirst)
                        intent.action == DestructiveAction.GPT_RESTORE && state.backupUri == null ->
                            UiMessage.Key(UiMessageKey.ChooseGptBackup)
                        intent.action == DestructiveAction.RESCUE_RESTORE &&
                            (state.rescueImageUri == null || state.rescueMapUri == null) ->
                            UiMessage.Key(UiMessageKey.ChooseRescueFiles)
                        (intent.action == DestructiveAction.DELETE_PARTITION || intent.action == DestructiveAction.FORMAT_PARTITION) &&
                            state.selectedPartitionId == null ->
                            UiMessage.Key(UiMessageKey.SelectPartitionFirst)
                        (intent.action == DestructiveAction.DELETE_PARTITION || intent.action == DestructiveAction.FORMAT_PARTITION) ->
                            writeBlockMessage(state)
                        intent.action == DestructiveAction.CREATE_PARTITION && !state.tableValid ->
                            UiMessage.Key(UiMessageKey.CorruptTableCreate)
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
                                    state.isRunning -> UiMessage.Key(UiMessageKey.WaitForOperation)
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

            is UiIntent.RequestSetPartitionRole ->
                requestPartitionEdit(
                    state,
                    DestructiveAction.SET_PARTITION_ROLE,
                    role = intent.role,
                )

            is UiIntent.RequestSetPartitionBootable ->
                requestPartitionEdit(
                    state,
                    DestructiveAction.SET_PARTITION_BOOTABLE,
                    bootable = intent.bootable,
                )

            UiIntent.ConfirmDestructive -> {
                val pending = state.pendingConfirmation
                val device = state.device
                val action = pending?.action
                val invalidState = when {
                    pending == null || device == null -> true
                    state.presence != DevicePresence.Ready -> true
                    state.isRunning -> true
                    action == DestructiveAction.CREATE_PARTITION && !state.tableValid -> true
                    state.partitionTableAmbiguous &&
                        action != null && action in layoutMutations -> true
                    state.partitionTableDamaged &&
                        action in setOf(
                            DestructiveAction.CREATE_PARTITION,
                            DestructiveAction.DELETE_PARTITION,
                            DestructiveAction.GPT_REPAIR,
                            DestructiveAction.SET_PARTITION_ROLE,
                            DestructiveAction.SET_PARTITION_BOOTABLE,
                        ) -> true
                    action == DestructiveAction.SET_PARTITION_ROLE &&
                        (pending?.partitionRole == null || !state.tableValid) -> true
                    action == DestructiveAction.SET_PARTITION_BOOTABLE &&
                        (pending?.partitionBootable == null || !state.tableValid) -> true
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
                                statusMessage = UiMessage.Key(UiMessageKey.NoIsoForLink),
                            ),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                statusMessage = message(
                                    UiMessageKey.WritingXml,
                                    iso.substringAfterLast('/'),
                                ),
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
                val candidateMatches =
                    state.linkOverwriteCandidate == intent.fileName &&
                        state.linkOverwriteCandidateIdentity == device?.identity &&
                        state.linkOverwriteCandidateGeneration == device?.attachGeneration
                if (!state.actionsEnabled || device == null) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.NoDeviceForLink)))
                } else if (!candidateMatches) {
                    ReduceResult(state.copy(
                        linkOverwriteCandidate = null,
                        linkOverwriteCandidateIdentity = null,
                        linkOverwriteCandidateGeneration = null,
                        statusMessage = UiMessage.Key(UiMessageKey.LinkMismatch),
                    ))
                } else {
                    ReduceResult(state.copy(
                        pendingConfirmation = DestructiveConfirmation.request(
                            device,
                            DestructiveAction.VENTOY_LINK_XML,
                            isoName = intent.fileName,
                            linkOverwrite = true,
                        ),
                        linkOverwriteCandidate = null,
                        linkOverwriteCandidateIdentity = null,
                        linkOverwriteCandidateGeneration = null,
                        statusMessage = null,
                    ))
                }
            }
            is UiIntent.LinkOverwriteAvailable -> {
                val device = state.device
                if (!state.actionsEnabled || device == null) {
                    ReduceResult(state.copy(
                        linkOverwriteCandidate = null,
                        linkOverwriteCandidateIdentity = null,
                        linkOverwriteCandidateGeneration = null,
                    ))
                } else {
                    ReduceResult(state.copy(
                        linkOverwriteCandidate = intent.fileName,
                        linkOverwriteCandidateIdentity = device.identity,
                        linkOverwriteCandidateGeneration = device.attachGeneration,
                        statusMessage = UiMessage.Key(UiMessageKey.LinkOverwriteNeeded),
                    ))
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
                            state.copy(statusMessage = message(UiMessageKey.StagingFile, name)),
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
                                state.copy(statusMessage = UiMessage.Key(UiMessageKey.NoDeviceForLink)),
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

            is UiIntent.SetImage -> {
                val uri = intent.uri
                if (!state.actionsEnabled) {
                    // Busy / pending confirm — do not clear staged source under a live raw write.
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            imageUri = uri,
                            stagedRawImagePath = if (uri != null) null else state.stagedRawImagePath,
                            stagedRawImageLabel = if (uri != null) null else state.stagedRawImageLabel,
                            statusMessage =
                                if (uri == null) {
                                    UiMessage.Key(UiMessageKey.ImageCleared)
                                } else {
                                    UiMessage.Key(UiMessageKey.ImageReady, listOf(uri))
                                },
                        ),
                    )
                }
            }

            is UiIntent.SetStagedRawImage ->
                if (state.isRunning || state.operation?.state == OperationState.Queued || state.pendingConfirmation != null) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.CannotChangeStaged)))
                } else {
                    ReduceResult(
                        state.copy(
                            stagedRawImagePath = intent.path,
                            stagedRawImageLabel = intent.label,
                            imageUri = null,
                            statusMessage = message(UiMessageKey.StagedImageReady, intent.label),
                        ),
                    )
                }

            is UiIntent.ClearStagedRawImage ->
                if (state.isRunning || state.operation?.state == OperationState.Queued || state.pendingConfirmation != null) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.CannotClearStaged)))
                } else {
                    ReduceResult(
                        state.copy(
                            stagedRawImagePath = null,
                            stagedRawImageLabel = null,
                            statusMessage = UiMessage.Key(UiMessageKey.StagedCleared),
                        ),
                    )
                }
            is UiIntent.SetBackupFile ->
                ReduceResult(
                    state.copy(
                        backupUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) {
                                UiMessage.Key(UiMessageKey.BackupCleared)
                            } else {
                                message(
                                    UiMessageKey.BackupReady,
                                    intent.uri.substringAfterLast('/'),
                                )
                            },
                    ),
                )

            UiIntent.StartReadScan -> nonDestructiveEffect(state, UiEffect.Kind.ReadScan)

            UiIntent.StartRescueBackup -> nonDestructiveEffect(state, UiEffect.Kind.RescueBackup)

            UiIntent.CaptureGptBackup -> nonDestructiveEffect(state, UiEffect.Kind.CaptureGptBackup)

            is UiIntent.SetLanguage -> ReduceResult(state.copy(language = intent.language))

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
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.OperationInProgress)))
                } else {
                    ReduceResult(
                        state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelfTestStarting)),
                        effects = listOf(UiEffect(UiEffect.Kind.RunSelfTest)),
                    )
                }

            is UiIntent.SetRescueImage ->
                ReduceResult(
                    state.copy(
                        rescueImageUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) {
                                UiMessage.Key(UiMessageKey.RescueImageCleared)
                            } else {
                                message(
                                    UiMessageKey.RescueImageReady,
                                    intent.uri.substringAfterLast('/'),
                                )
                            },
                    ),
                )

            is UiIntent.SetRescueMap ->
                ReduceResult(
                    state.copy(
                        rescueMapUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) {
                                UiMessage.Key(UiMessageKey.RescueMapCleared)
                            } else {
                                message(
                                    UiMessageKey.RescueMapReady,
                                    intent.uri.substringAfterLast('/'),
                                )
                            },
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
                    state.copy(currentPath = target, files = emptyList(), filesError = null),
                    effects = listOf(UiEffect(UiEffect.Kind.LoadFiles, path = target)),
                )
            }

            UiIntent.NavigateUp -> {
                val target = fatParentPath(state.currentPath)
                if (target == state.currentPath) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(currentPath = target, files = emptyList(), filesError = null),
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
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.InvalidFolderName)))
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
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectEntryToDelete)))
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

            is UiIntent.RenameEntry -> {
                val block = writeBlockMessage(state)
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    bindingProblem(state, intent.binding) != null ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.TargetChanged)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    !validEntryName(intent.from) || !validEntryName(intent.to) ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.InvalidEntryName)))
                    intent.from == intent.to ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.RenameSameName)))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect(
                                        UiEffect.Kind.RenameEntry,
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
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectFileToDownload)))
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
                    ReduceResult(state.copy(files = intent.files, filesError = null))
                }

            is UiIntent.FilesListFailed ->
                if (intent.path != state.currentPath) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            files = emptyList(),
                            filesError = intent.message,
                            statusMessage = UiMessage.Raw(intent.message),
                        ),
                    )
                }

            is UiIntent.AppendLog -> {
                val trimmed = intent.line.take(500) // guard runaway lines
                val updated =
                    if (state.logLines.size >= AppUiState.LOG_MAX_LINES) {
                        state.logLines.drop(state.logLines.size - AppUiState.LOG_MAX_LINES + 1) + trimmed
                    } else {
                        state.logLines + trimmed
                    }
                ReduceResult(state.copy(logLines = updated))
            }

            is UiIntent.SetLatestVentoyRelease ->
                ReduceResult(state.copy(latestVentoyRelease = intent.tag))
        }

    /** Read-only disk work still needs a ready device; otherwise report why nothing ran. */
    private fun nonDestructiveEffect(state: AppUiState, kind: UiEffect.Kind): ReduceResult =
        if (!state.actionsEnabled) {
            ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
        } else {
            ReduceResult(state, effects = listOf(UiEffect(kind)))
        }

    fun presenceMessage(presence: DevicePresence): UiMessage =
        when (presence) {
            DevicePresence.Absent -> UiMessage.Key(UiMessageKey.NoDevice)
            DevicePresence.PermissionRequired -> UiMessage.Key(UiMessageKey.PermissionRequired)
            DevicePresence.Busy -> UiMessage.Key(UiMessageKey.DeviceBusy)
            DevicePresence.Disconnected -> UiMessage.Key(UiMessageKey.DeviceDisconnected)
            DevicePresence.Ready -> UiMessage.Key(UiMessageKey.Ready)
        }

    /** Small builder for keyed messages with format arguments. */
    fun message(key: UiMessageKey, vararg args: Any): UiMessage =
        UiMessage.Key(key, args.toList())

    private fun UiMessage.isDefaultStateStatus(): Boolean =
        this is UiMessage.Key &&
            key in setOf(
                UiMessageKey.RunningStatus,
                UiMessageKey.QueuedStatus,
            )

    /** Null when [binding] still names the selected device, partition and geometry. */
    private fun bindingProblem(state: AppUiState, binding: PartitionBinding): UiMessage? =
        if (state.device == null) {
            presenceMessage(state.presence)
        } else if (!binding.matches(state)) {
            UiMessage.Key(UiMessageKey.TargetChanged)
        } else {
            null
        }

    /**
     * Build a confirmation for a partition-table edit. The payload pins the selected
     * partition ID and geometry so the host can re-check them against the live table.
     */
    private fun requestPartitionEdit(
        state: AppUiState,
        action: DestructiveAction,
        role: PartitionRole? = null,
        bootable: Boolean? = null,
    ): ReduceResult {
        val device = state.device
        val target = state.selectedPartition()
        val problem = when {
            device == null || !state.actionsEnabled -> presenceMessage(state.presence)
            target == null -> UiMessage.Key(UiMessageKey.SelectPartitionFirst)
            state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
            state.partitionTableDamaged -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
            !state.tableValid -> UiMessage.Key(UiMessageKey.TableDamaged)
            target.startLba == null || target.sectorCount == null ->
                UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
            !state.isEfiWriteAllowed(target) ->
                message(UiMessageKey.ReadOnlyPartition, target.name)
            else -> null
        }
        if (problem != null || device == null || target == null) {
            return ReduceResult(state.copy(statusMessage = problem))
        }
        val base = DestructiveConfirmation.request(device, action, target.name, target = target)
        return ReduceResult(
            state.copy(
                pendingConfirmation = base.copy(
                    partitionRole = role,
                    partitionBootable = bootable,
                ),
            ),
        )
    }

    /**
     * Explicit reason writes to the selected partition are refused, or null when allowed.
     * An EFI partition stays untouched unless the override names that exact partition;
     * lifting it for one ESP never unlocks another.
     */
    fun writeBlockMessage(state: AppUiState): UiMessage? {
        val partition = state.selectedPartition()
            ?: return UiMessage.Key(UiMessageKey.SelectPartitionFirst)
        // Unconditional before the EFI override: in an ambiguous layout the MBR record may
        // own the sectors under the selected GPT partition, so no override may lift the block.
        if (state.partitionTableAmbiguous) {
            return UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
        }
        if (state.isEfiWriteAllowed(partition)) return null
        return message(UiMessageKey.ReadOnlyPartition, partition.name)
    }

    /** A single FAT entry name: no separators, no dot entries. */
    private fun validEntryName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name
}
