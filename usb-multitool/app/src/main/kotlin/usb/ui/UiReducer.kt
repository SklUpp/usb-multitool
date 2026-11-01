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

    data class Status(val message: String?) : UiIntent()

    data object CancelOperation : UiIntent()

    data class AddFile(val entry: FileEntry) : UiIntent()

    data class DeleteFile(val entry: FileEntry) : UiIntent()

    data class UpdateFiles(val files: List<FileEntry>) : UiIntent()
}

data class UiEffect(
    val kind: Kind,
    val action: DestructiveAction? = null,
    val isoName: String? = null,
    val isoAction: IsoAction? = null,
) {
    enum class Kind {
        SubmitDestructive,
        CancelOperation,
        LinkXml,
        SendToRawWriter,
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
                ReduceResult(state.copy(selectedPartitionId = intent.partitionId))

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
                                OperationState.Completed -> "Success"
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
                if (device != null &&
                    state.actionsEnabled &&
                    intent.action == DestructiveAction.RAW_WRITE &&
                    state.imageUri == null
                ) {
                    ReduceResult(state.copy(statusMessage = "Select an ISO/IMG file first"))
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

            is UiIntent.Status -> ReduceResult(state.copy(statusMessage = intent.message))

            UiIntent.CancelOperation ->
                ReduceResult(
                    state,
                    effects = listOf(UiEffect(UiEffect.Kind.CancelOperation)),
                )

            is UiIntent.AddFile -> {
                val updated = state.files.filterNot { it.name == intent.entry.name } + intent.entry
                val baseName = intent.entry.name.substringAfterLast('/')
                val status =
                    if (intent.entry.isDirectory) {
                        "Created folder $baseName"
                    } else {
                        "Uploaded $baseName"
                    }
                ReduceResult(
                    state.copy(
                        files = updated,
                        statusMessage = status,
                    ),
                )
            }

            is UiIntent.DeleteFile -> {
                val updated = state.files.filterNot { it.name == intent.entry.name }
                val baseName = intent.entry.name.substringAfterLast('/')
                ReduceResult(
                    state.copy(
                        files = updated,
                        statusMessage = "Deleted $baseName",
                    ),
                )
            }

            is UiIntent.UpdateFiles ->
                ReduceResult(
                    state.copy(files = intent.files),
                )
        }

    fun presenceMessage(presence: DevicePresence): String =
        when (presence) {
            DevicePresence.Absent -> "No USB mass-storage device"
            DevicePresence.PermissionRequired -> "USB permission required"
            DevicePresence.Busy -> "Device busy"
            DevicePresence.Disconnected -> "Device disconnected"
            DevicePresence.Ready -> "Ready"
        }
}
