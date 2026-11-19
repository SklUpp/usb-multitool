package usb.ui

import usb.ventoy.AutounattendState

/**
 * Pure operation lifecycle transitions. [UiReducer.reduce] remains the entry point; this object
 * owns token fencing, operation status mapping, cancellation, and terminal cache refreshes.
 */
internal object OperationTransitions {
    fun progress(
        state: AppUiState,
        progress: OperationProgress,
        begin: Boolean,
    ): ReduceResult {
        val previous = state.operation
        if (!begin) {
            if (previous == null || previous.token != progress.token || previous.state.isTerminal()) {
                return ReduceResult(state)
            }
            if (progress.state == OperationState.Queued && previous.state == OperationState.Running) {
                return ReduceResult(state)
            }
        }
        val terminal = progress.state.isTerminal()
        val base = state.copy(
            operation = progress,
            // New token/begin only: ticks must keep the scoped log intact.
            operationLogLines = if (begin) emptyList() else state.operationLogLines,
            pendingVentoyControls =
                if (terminal && progress.state == OperationState.Completed &&
                    progress.title.clearsPendingVentoyControls() &&
                    (progress.title == OperationTitle.SetVentoyGlobalSetting ||
                        state.ventoySettingsSource.isDefaultPath())
                ) emptyList() else state.pendingVentoyControls,
            filesystemUsage = if (terminal) null else state.filesystemUsage,
            presence = when {
                progress.state == OperationState.Running &&
                    (state.presence == DevicePresence.Ready || state.presence == DevicePresence.Busy) ->
                    DevicePresence.Busy
                terminal && state.presence == DevicePresence.Busy -> DevicePresence.Ready
                else -> state.presence
            },
        ).let { next ->
            val carriedPrevious = if (progress.state == OperationState.Completed) {
                state.statusMessage?.takeUnless { it.isDefaultStateStatus() }
            } else {
                null
            }
            val completedHasNew =
                progress.readScanResult != null || progress.resultMessage != null
            if (progress.state == OperationState.Completed && !completedHasNew &&
                carriedPrevious != null
            ) {
                // Carried forward, not a new emission: no sequence bump.
                next.copy(statusMessage = carriedPrevious)
            } else {
                val message = when (progress.state) {
                    OperationState.Completed ->
                        progress.readScanResult?.let(UiMessage::ReadScan)
                            ?: progress.resultMessage?.let(UiMessage::Raw)
                            ?: carriedPrevious
                            ?: UiMessage.Key(UiMessageKey.Success)
                    OperationState.Cancelled ->
                        progress.readScanResult?.let(UiMessage::ReadScan)
                            ?: UiMessage.Key(UiMessageKey.CancelledStatus)
                    OperationState.Failed -> progress.errorMessage?.let(UiMessage::Raw)
                        ?: UiMessage.Key(UiMessageKey.FailedStatus)
                    OperationState.Partial ->
                        progress.readScanResult?.let(UiMessage::ReadScan)
                            ?: (progress.resultMessage ?: progress.errorMessage)
                                ?.let(UiMessage::Raw)
                            ?: UiMessage.Key(UiMessageKey.PartialStatus)
                    OperationState.Running -> UiMessage.Key(UiMessageKey.RunningStatus)
                    OperationState.Queued -> UiMessage.Key(UiMessageKey.QueuedStatus)
                }
                // Tickers post Running/Queued every 120 ms. Only the transition into that
                // state is a new emission; repeated ticks must not restart the snackbar.
                val sameStateTick = progress.state == previous?.state &&
                    (progress.state == OperationState.Running || progress.state == OperationState.Queued)
                if (sameStateTick) next.copy(statusMessage = message) else next.withStatus(message)
            }
        }
        return ReduceResult(base)
    }

    fun cancel(state: AppUiState, token: usb.service.OperationToken): ReduceResult =
        if (state.operation?.token == token && state.operation.cancelable &&
            (state.operation.state == OperationState.Running || state.operation.state == OperationState.Queued)
        ) {
            ReduceResult(state, effects = listOf(UiEffect.CancelOperation(token)))
        } else {
            ReduceResult(state)
        }

    fun completion(state: AppUiState, intent: UiIntent.OperationCompletionEffects): ReduceResult {
        if (!intent.state.isTerminal() || state.device?.attachGeneration != intent.attachmentGeneration) {
            return ReduceResult(state)
        }
        val invalidatesCaches = intent.title.invalidatesVentoyStickCaches()
        val invalidatesFiles = intent.title.invalidatesFilesListing()
        val invalidated =
            if (invalidatesCaches) {
                dropVentoyStickCaches(state.copy(filesystemUsage = null), keepTemplatePath = true)
            } else {
                state.copy(filesystemUsage = null)
            }
        return when {
            invalidatesFiles -> filesReload(invalidated)
            invalidated.filesListingState == FilesListingState.Stale -> filesStart(invalidated)
            invalidatesCaches -> ReduceResult(invalidated)
            else -> ReduceResult(state)
        }
    }

    fun dropVentoyStickCaches(state: AppUiState, keepTemplatePath: Boolean): AppUiState =
        state.copy(
            windowsIsoCandidates = null,
            windowsIsoCandidatesGeneration = state.windowsIsoCandidatesGeneration + 1,
            windowsIsoCandidatesIdentity = null,
            windowsIsoCandidatesError = null,
            windowsImageLinks = null,
            windowsImageLinksGeneration = state.windowsImageLinksGeneration + 1,
            windowsImageLinksIdentity = null,
            windowsSelectedImages = emptyList(),
            windowsTemplatePath = if (keepTemplatePath) state.windowsTemplatePath else null,
            autounattendState = AutounattendState.Missing,
            autounattendBindings = null,
            autounattendCandidates = null,
            autounattendRequestGeneration = state.autounattendRequestGeneration + 1,
            autounattendLoadInFlight = false,
            diskLayoutConfirmation = null,
            pendingAutounattendGeneration = null,
            ventoyGlobalSettings = null,
            ventoySettingsSourceLoading = false,
            ventoyFolderPath = "/",
            ventoyFolders = null,
            ventoyFoldersError = null,
            ventoyFoldersLoading = false,
            ventoyFoldersPending = false,
            ventoyCacheEpoch = state.ventoyCacheEpoch + 1,

            linkOverwriteCandidatePaths = emptyList(),
            linkOverwriteCandidateTemplatePath = null,
            pendingIsoImport = null,
            deferredIsoImport = null,
            isoImportError = null,
            isoImportGeneration = state.isoImportGeneration + 1,
        )

    internal fun filesInvalidate(state: AppUiState): AppUiState =
        state.copy(
            files = emptyList(),
            filesError = null,
            filesListGeneration = state.filesListGeneration + 1L,
            filesListingState = FilesListingState.Stale,
        )

    internal fun filesStart(state: AppUiState): ReduceResult {
        val canStart =
            state.presence == DevicePresence.Ready &&
                state.actionsEnabled &&
                state.tab == ShellTab.Files &&
                state.selectedPartition() != null
        return if (!canStart || state.filesListingState == FilesListingState.Loading) {
            ReduceResult(state)
        } else {
            ReduceResult(
                state.copy(filesListingState = FilesListingState.Loading),
                effects = listOf(UiEffect.LoadFiles(path = state.currentPath)),
            )
        }
    }

    internal fun filesReload(state: AppUiState): ReduceResult = filesStart(filesInvalidate(state))
}

internal fun OperationState.isTerminal(): Boolean = when (this) {
    OperationState.Queued, OperationState.Running -> false
    OperationState.Completed, OperationState.Cancelled, OperationState.Partial -> true
    OperationState.Failed -> true
}

private fun UiMessage.isDefaultStateStatus(): Boolean =
    this is UiMessage.Key && key in setOf(UiMessageKey.RunningStatus, UiMessageKey.QueuedStatus)

private fun OperationTitle.clearsPendingVentoyControls(): Boolean =
    when (this) {
        is OperationTitle.Destructive ->
            action == DestructiveAction.VENTOY_INSTALL || action == DestructiveAction.VENTOY_UPDATE
        OperationTitle.SetVentoyGlobalSetting -> true
        else -> false
    }

private fun OperationTitle.invalidatesVentoyStickCaches(): Boolean =
    when (this) {
        is OperationTitle.Destructive ->
            when (action) {
                DestructiveAction.WIPE,
                DestructiveAction.VENTOY_INSTALL,
                DestructiveAction.VENTOY_UPDATE,
                DestructiveAction.RAW_WRITE,
                DestructiveAction.FORMAT_PARTITION,
                DestructiveAction.DELETE_PARTITION,
                DestructiveAction.CREATE_PARTITION,
                DestructiveAction.GPT_REPAIR,
                DestructiveAction.GPT_RESTORE,
                DestructiveAction.RESCUE_RESTORE,
                DestructiveAction.INITIALIZE_PARTITION_TABLE,
                DestructiveAction.SET_PARTITION_ROLE,
                DestructiveAction.SET_PARTITION_BOOTABLE,
                DestructiveAction.RENAME_PARTITION,
                DestructiveAction.RENAME_VOLUME_LABEL,
                DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
                DestructiveAction.VENTOY_UNATTEND_DISK_LAYOUT,
                DestructiveAction.VENTOY_LINK_XML,
                -> true
            }
        is OperationTitle.ImportTarget,
        is OperationTitle.FormatTarget,
        is OperationTitle.DeleteEntry,
        is OperationTitle.DeleteBatch,
        is OperationTitle.RenameEntry,
        is OperationTitle.NewFolder,
        OperationTitle.Upload,
        is OperationTitle.UploadBatch,
        OperationTitle.BindImagesToUnattendXml,
        OperationTitle.UnbindImageFromUnattendXml,
        OperationTitle.GenerateUnattendXml,
        OperationTitle.WriteUnattendXml,
        OperationTitle.RemoveXmlBindings,
        OperationTitle.SetVentoyGlobalSetting,
        OperationTitle.SaveGlobalWindowsControls,
        -> true
        else -> false
    }

private fun OperationTitle.invalidatesFilesListing(): Boolean =
    invalidatesVentoyStickCaches() ||
        when (this) {
            OperationTitle.AutounattendXmlLink,
            OperationTitle.RemoveXmlBindings,
            OperationTitle.SaveGlobalWindowsControls,
            OperationTitle.GenerateUnattendXml,
            OperationTitle.WriteUnattendXml,
            OperationTitle.SetVentoyGlobalSetting,
            OperationTitle.BindImagesToUnattendXml,
            OperationTitle.UnbindImageFromUnattendXml,
            OperationTitle.Upload,
            is OperationTitle.UploadBatch,
            is OperationTitle.ImportTarget,
            is OperationTitle.FormatTarget,
            is OperationTitle.DeleteEntry,
            is OperationTitle.DeleteBatch,
            is OperationTitle.RenameEntry,
            is OperationTitle.NewFolder,
            -> true
            else -> false
        }
