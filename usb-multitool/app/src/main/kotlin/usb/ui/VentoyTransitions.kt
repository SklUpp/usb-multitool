package usb.ui

import usb.ventoy.VentoyControlCatalog
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.folderParent
import usb.ventoy.normalizeFolderPath

/**
 * Pure transitions of the Ventoy tab: install/update flags, folder browsing, global
 * settings and staged control edits. [UiReducer.reduce] stays the only entry point; this
 * object holds no state and performs no I/O.
 */
internal object VentoyTransitions {
    fun setVtoyEfiWritable(state: AppUiState, intent: UiIntent.SetVtoyEfiWritable): ReduceResult =
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
                ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
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
                                statusMessage = UiReducer.message(
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

    fun setVtoyBypassChecks(state: AppUiState, intent: UiIntent.SetVtoyBypassChecks): ReduceResult =
        ReduceResult(state.copy(vtoyBypassChecks = intent.enabled))

    fun setVtoyLocalAccount(state: AppUiState, intent: UiIntent.SetVtoyLocalAccount): ReduceResult =
        ReduceResult(state.copy(vtoyLocalAccount = intent.enabled))

    fun loadVentoyGlobalSettings(state: AppUiState): ReduceResult {
        val token = state.ventoyGlobalSettingsRequest + 1L
        return ReduceResult(
            state.copy(ventoyGlobalSettingsRequest = token),
            effects = listOf(UiEffect.LoadVentoyGlobalSettings(requestToken = token)),
        )
    }

    fun loadVentoyFolders(state: AppUiState): ReduceResult =
        // Refresh keeps the current rows until the new answer lands; the token still
        // disowns every older read. A busy/confirming UI marks the request as pending
        // so the release pass can start it once USB work is allowed; it must not
        // create an endless retry by itself.
        if (!state.actionsEnabled) {
            ReduceResult(
                state.copy(
                    ventoyFoldersPending = true,
                    statusMessage =
                        if (state.presence == DevicePresence.Ready) {
                            UiMessage.Key(UiMessageKey.WaitForOperation)
                        } else {
                            UiReducer.presenceMessage(state.presence)
                        },
                ),
            )
        } else {
            UiReducer.startFolderRead(state, state.ventoyFolderPath, keepList = true)
        }

    fun openVentoyFolder(state: AppUiState, intent: UiIntent.OpenVentoyFolder): ReduceResult {
        return if (!state.actionsEnabled) {
            ReduceResult(state)
        } else {
            val target =
                try {
                    normalizeFolderPath(intent.path)
                } catch (_: IllegalArgumentException) {
                    null
                }
            when {
                target == null || target !in state.ventoyFolders.orEmpty() ->
                    // The row came from a list that no longer matches the state.
                    ReduceResult(
                        state.copy(
                            statusMessage = UiMessage.Key(UiMessageKey.FolderListChanged),
                        ),
                    )
                else -> UiReducer.startFolderRead(state, target, keepList = false)
            }
        }
    }

    fun ventoyFolderUp(state: AppUiState): ReduceResult =
        if (!state.actionsEnabled) {
            ReduceResult(state)
        } else {
            when (val parent = folderParent(state.ventoyFolderPath)) {
                null -> ReduceResult(state)
                else -> UiReducer.startFolderRead(state, parent, keepList = false)
            }
        }

    fun ventoyFoldersLoaded(state: AppUiState, intent: UiIntent.VentoyFoldersLoaded): ReduceResult =
        if (intent.epoch != state.ventoyCacheEpoch ||
            intent.requestToken != state.ventoyFoldersRequest ||
            intent.path != state.ventoyFolderPath
        ) {
            // Invalidation, navigation or a newer refresh already owns the answer.
            ReduceResult(state)
        } else {
            ReduceResult(
                state.copy(
                    ventoyFolders = intent.folders,
                    ventoyFoldersError = intent.error,
                    ventoyFoldersLoading = false,
                    ventoyFoldersPending = false,
                ),
            )
        }

    fun ventoyGlobalSettingsLoaded(
        state: AppUiState,
        intent: UiIntent.VentoyGlobalSettingsLoaded,
    ): ReduceResult =
        if (intent.epoch != state.ventoyCacheEpoch ||
            intent.requestToken != state.ventoyGlobalSettingsRequest
        ) {
            ReduceResult(state)
        } else {
            ReduceResult(
                state.copy(
                    ventoyGlobalSettings = intent.settings,
                    // Only fires while Ventoy tab open (or manual refresh); surface
                    // a real read/parse failure without turning it into "empty".
                    statusMessage =
                        when (val loaded = intent.settings) {
                            is VentoyGlobalSettings.ReadError ->
                                if (loaded.damagedLayout) {
                                    // The card has a localized explanation; do not leak
                                    // an English technical string into the status bar.
                                    state.statusMessage
                                } else {
                                    UiMessage.Raw(loaded.message)
                                }
                            else -> state.statusMessage
                        },
                ),
            )
        }

    fun stageVentoyControl(state: AppUiState, intent: UiIntent.StageVentoyControl): ReduceResult {
        val problem =
            if (intent.value == null) {
                // Removal matches an existing key verbatim; no creation pattern.
                VentoyControlCatalog.problem(
                    listOf(VentoyControlSetting(intent.key, null)),
                )
            } else {
                VentoyControlCatalog.keyProblem(intent.key)
                    ?: VentoyControlCatalog.valueProblem(intent.key, intent.value)
            }
        return if (problem != null) {
            ReduceResult(state.copy(statusMessage = UiMessage.Raw(problem)))
        } else {
            val staged = LinkedHashMap<String, VentoyControlSetting>()
            state.pendingVentoyControls.forEach { staged[it.key] = it }
            staged[intent.key] = VentoyControlSetting(intent.key, intent.value)
            ReduceResult(
                state.copy(
                    pendingVentoyControls = staged.values.toList(),
                    statusMessage = null,
                ),
            )
        }
    }

    fun discardStagedVentoyControl(
        state: AppUiState,
        intent: UiIntent.DiscardStagedVentoyControl,
    ): ReduceResult =
        ReduceResult(
            state.copy(
                pendingVentoyControls =
                    state.pendingVentoyControls.filterNot { it.key == intent.key },
            ),
        )

    fun requestApplyVentoyControls(state: AppUiState): ReduceResult =
        when {
            !state.actionsEnabled ->
                ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
            state.pendingVentoyControls.isEmpty() ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Raw("No Ventoy setting changes to apply"),
                    ),
                )
            else ->
                ReduceResult(
                    state,
                    effects =
                        listOf(
                            UiEffect.ApplyVentoyControls(
                                settings = state.pendingVentoyControls,
                            ),
                        ),
                )
        }

    fun setLatestVentoyRelease(
        state: AppUiState,
        intent: UiIntent.SetLatestVentoyRelease,
    ): ReduceResult =
        ReduceResult(state.copy(latestVentoyRelease = intent.tag))
}
