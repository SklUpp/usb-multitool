package usb.ui

/**
 * Pure transitions of the Rescue/Backup screen: chosen archive and map files, plus the
 * read-only disk work behind them. [UiReducer.reduce] stays the only entry point; this
 * object holds no state and performs no I/O.
 */
internal object RescueBackupTransitions {
    fun setBackupFolder(state: AppUiState, intent: UiIntent.SetBackupFolder): ReduceResult =
        ReduceResult(
            state.copy(
                backupFolderUri = intent.uri,
                backupFolderName = intent.name,
                backupFolderUsable = intent.uri != null && intent.usable,
                statusMessage =
                    when {
                        intent.uri == null -> UiMessage.Key(UiMessageKey.ChooseBackupFolder)
                        intent.usable -> UiReducer.message(
                            UiMessageKey.BackupFolderReady,
                            intent.name ?: intent.uri.orEmpty(),
                        )
                        else -> UiMessage.Key(UiMessageKey.BackupFolderPermissionLost)
                    },
            ),
        )

    fun setBackupFile(state: AppUiState, intent: UiIntent.SetBackupFile): ReduceResult {
        if (intent.requestId < state.backupSelectionGeneration) {
            ReduceResult(state)
        } else {
            val clearConfirm =
                state.pendingConfirmation?.action == DestructiveAction.GPT_RESTORE
            val inspection = intent.inspection?.takeIf { it.uri == intent.uri }
            val inspectionError = inspection?.error
            ReduceResult(
                state.copy(
                    backupUri = intent.uri,
                    backupInspection = inspection,
                    backupSelectionGeneration = intent.requestId,
                    pendingConfirmation =
                        if (clearConfirm) null else state.pendingConfirmation,
                    statusMessage =
                        when {
                            intent.uri == null -> UiMessage.Key(UiMessageKey.BackupCleared)
                            inspection != null && inspectionError != null -> UiReducer.message(
                                UiMessageKey.SourceInspectionRejected,
                                inspection.displayName,
                                inspectionError,
                            )
                            else -> UiReducer.message(
                                UiMessageKey.BackupReady,
                                DestructiveConfirmation.fileLabel(intent.uri) ?: intent.uri,
                            )
                        },
                ),
            )
        }
    }

    fun startReadScan(state: AppUiState): ReduceResult =
        nonDestructiveEffect(state, UiEffect.ReadScan)

    fun startRescueBackup(state: AppUiState): ReduceResult =
        nonDestructiveEffect(state, UiEffect.RescueBackup)

    fun captureGptBackup(state: AppUiState): ReduceResult =
        nonDestructiveEffect(state, UiEffect.CaptureGptBackup)

    fun setRescueImage(state: AppUiState, intent: UiIntent.SetRescueImage): ReduceResult {
        if (intent.requestId < state.rescueImageSelectionGeneration) {
            ReduceResult(state)
        } else {
            val clearConfirm =
                state.pendingConfirmation?.action == DestructiveAction.RESCUE_RESTORE
            ReduceResult(
                state.copy(
                    rescueImageUri = intent.uri,
                    rescueImageInspection =
                        intent.inspection?.takeIf { it.uri == intent.uri },
                    rescueImageSelectionGeneration = intent.requestId,
                    pendingConfirmation =
                        if (clearConfirm) null else state.pendingConfirmation,
                    statusMessage =
                        if (intent.uri == null) {
                            UiMessage.Key(UiMessageKey.RescueImageCleared)
                        } else {
                            UiReducer.message(
                                UiMessageKey.RescueImageReady,
                                DestructiveConfirmation.fileLabel(intent.uri) ?: intent.uri,
                            )
                        },
                ),
            )
        }
    }

    fun setRescueMap(state: AppUiState, intent: UiIntent.SetRescueMap): ReduceResult {
        if (intent.requestId < state.rescueMapSelectionGeneration) {
            ReduceResult(state)
        } else {
            val clearConfirm =
                state.pendingConfirmation?.action == DestructiveAction.RESCUE_RESTORE
            val inspection = intent.inspection?.takeIf { it.uri == intent.uri }
            val inspectionError = inspection?.error
            ReduceResult(
                state.copy(
                    rescueMapUri = intent.uri,
                    rescueMapInspection = inspection,
                    rescueMapSelectionGeneration = intent.requestId,
                    pendingConfirmation =
                        if (clearConfirm) null else state.pendingConfirmation,
                    statusMessage =
                        when {
                            intent.uri == null -> UiMessage.Key(UiMessageKey.RescueMapCleared)
                            inspection != null && inspectionError != null -> UiReducer.message(
                                UiMessageKey.SourceInspectionRejected,
                                inspection.displayName,
                                inspectionError,
                            )
                            else -> UiReducer.message(
                                UiMessageKey.RescueMapReady,
                                DestructiveConfirmation.fileLabel(intent.uri) ?: intent.uri,
                            )
                        },
                ),
            )
        }
    }

    /** Read-only disk work still needs a ready device; otherwise report why nothing ran. */
    private fun nonDestructiveEffect(state: AppUiState, effect: UiEffect): ReduceResult =
        if (!state.actionsEnabled) {
            ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
        } else {
            ReduceResult(state, effects = listOf(effect))
        }
}
