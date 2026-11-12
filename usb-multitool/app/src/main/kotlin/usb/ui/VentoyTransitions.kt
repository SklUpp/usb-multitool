package usb.ui

import usb.ventoy.VentoyControlCatalog
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoySettingsSource
import usb.ventoy.folderParent
import usb.ventoy.normalizeFolderPath
import usb.ventoy.normalizeVentoyFilePath

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
            state.partitionTableAmbiguous ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.PartitionTableAmbiguous),
                    ),
                )
            state.partitionTableDamaged ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.GptPrimaryDamaged),
                    ),
                )
            !state.tableValid ->
                ReduceResult(
                    state.copy(statusMessage = UiMessage.Key(UiMessageKey.TableDamaged)),
                )
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

    fun loadVentoyGlobalSettings(state: AppUiState): ReduceResult =
        if (!state.settingsSourceGeometryMatches()) {
            ReduceResult(
                state.copy(
                    ventoySettingsSourceLoading = false,
                    statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale),
                ),
            )
        } else {
            val token = state.ventoyGlobalSettingsRequest + 1L
            val device = state.device
            ReduceResult(
                state.copy(
                    ventoyGlobalSettingsRequest = token,
                    ventoySettingsSourceLoading = true,
                    ventoySettingsSourceIdentity = device?.identity,
                    ventoySettingsSourceAttachGeneration = device?.attachGeneration,
                ),
                effects = listOf(
                    UiEffect.LoadVentoyGlobalSettings(
                        requestToken = token,
                        source = state.ventoySettingsSource,
                    ),
                ),
            )
        }

    fun selectVentoySettingsUsbFile(
        state: AppUiState,
        intent: UiIntent.SelectVentoySettingsUsbFile,
    ): ReduceResult {
        val normalized =
            try {
                normalizeVentoyFilePath(intent.path)
            } catch (_: IllegalArgumentException) {
                null
            }
        val device = state.device
        val pinnedPartition =
            intent.partitionId?.let { id -> state.partitions.firstOrNull { it.id == id } }
        val pinnedStart = intent.partitionStartLba
        val pinnedCount = intent.partitionSectorCount
        return when {
            normalized == null ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceInvalid),
                    ),
                )
            !state.actionsEnabled ->
                ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
            device == null ->
                ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
            intent.identity != null && intent.identity != device.identity ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale),
                    ),
                )
            intent.attachGeneration != null &&
                intent.attachGeneration != device.attachGeneration ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale),
                    ),
                )
            intent.partitionId != null && pinnedPartition == null ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale),
                    ),
                )
            pinnedPartition != null && pinnedStart != null &&
                pinnedStart != pinnedPartition.startLba ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale),
                    ),
                )
            pinnedPartition != null && pinnedCount != null &&
                pinnedCount != pinnedPartition.sectorCount ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale),
                    ),
                )
            else -> {
                val picked = VentoySettingsSource.UsbFile(normalized)
                startSettingsSourceRead(
                    state = state,
                    // The standard file picked on the stick is the same default file;
                    // keeping a UsbFile wrapper would bypass default create/clear rules.
                    source =
                        if (picked.isDefaultPath()) {
                            VentoySettingsSource.Default
                        } else {
                            picked
                        },
                    device = device,
                    partitionId = intent.partitionId,
                    partitionStartLba = pinnedStart,
                    partitionSectorCount = pinnedCount,
                )
            }
        }
    }

    fun selectVentoySettingsDocument(
        state: AppUiState,
        intent: UiIntent.SelectVentoySettingsDocument,
    ): ReduceResult =
        when {
            intent.uri.isBlank() ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceInvalid),
                    ),
                )
            !state.actionsEnabled ->
                ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
            intent.identity != null && intent.identity != state.device?.identity ->
                ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale)))
            intent.attachGeneration != null && intent.attachGeneration != state.device?.attachGeneration ->
                ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale)))
            else ->
                startSettingsSourceRead(
                    state = state,
                    source = VentoySettingsSource.Document(
                        uri = intent.uri,
                        displayName = intent.displayName?.takeIf { it.isNotBlank() },
                    ),
                    device = state.device,
                )
        }

    fun useDefaultVentoySettingsFile(state: AppUiState): ReduceResult =
        if (!state.actionsEnabled) {
            ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
        } else {
            startSettingsSourceRead(
                state = state,
                source = VentoySettingsSource.Default,
                device = state.device,
            )
        }

    /**
     * A chosen USB file is valid only while the partition it was picked on still has the same
     * geometry. Repartitioning the same stick must not make a stale path address new bytes.
     */
    private fun AppUiState.settingsSourceGeometryMatches(): Boolean {
        if (ventoySettingsSource !is VentoySettingsSource.UsbFile) return true
        val partitionId = ventoySettingsSourcePartitionId ?: return true
        val partition =
            partitions.firstOrNull { it.id == partitionId } ?: return false
        val expectedStart = ventoySettingsSourceStartLba
        val expectedCount = ventoySettingsSourceSectorCount
        if (expectedStart != null && partition.startLba != expectedStart) return false
        if (expectedCount != null && partition.sectorCount != expectedCount) return false
        return true
    }

    /**
     * Select exactly [source] and start its read. Staged writes belong to the previous file,
     * so they are dropped: applying them to a different file would be a silent cross-file
     * edit. Identity/generation and the picked partition geometry pin the choice.
     */
    private fun startSettingsSourceRead(
        state: AppUiState,
        source: VentoySettingsSource,
        device: DeviceIdentity?,
        partitionId: String? = null,
        partitionStartLba: ULong? = null,
        partitionSectorCount: ULong? = null,
    ): ReduceResult {
        val token = state.ventoyGlobalSettingsRequest + 1L
        val pinGeometry = source is VentoySettingsSource.UsbFile && !source.isDefaultPath()
        return ReduceResult(
            state.copy(
                ventoyGlobalSettingsRequest = token,
                ventoySettingsSource = source,
                ventoySettingsSourceLoading = true,
                ventoySettingsSourceIdentity = device?.identity,
                ventoySettingsSourceAttachGeneration = device?.attachGeneration,
                ventoySettingsSourcePartitionId = if (pinGeometry) partitionId else null,
                ventoySettingsSourceStartLba = if (pinGeometry) partitionStartLba else null,
                ventoySettingsSourceSectorCount =
                    if (pinGeometry) partitionSectorCount else null,
                pendingVentoyControls =
                    if (state.ventoySettingsSource.sameTarget(source)) {
                        // Re-selecting the same target (URI, or the same stick path) must not
                        // discard staged edits; a changed displayName is not a new target.
                        state.pendingVentoyControls
                    } else {
                        emptyList()
                    },
                statusMessage = null,
            ),
            effects = listOf(
                UiEffect.LoadVentoyGlobalSettings(requestToken = token, source = source),
            ),
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
            intent.requestToken != state.ventoyGlobalSettingsRequest ||
            !intent.source.sameTarget(state.ventoySettingsSource)
        ) {
            ReduceResult(state)
        } else {
            ReduceResult(
                state.copy(
                    ventoyGlobalSettings = intent.settings,
                    ventoySettingsSourceLoading = false,
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
            !state.settingsSourceGeometryMatches() ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.SettingsSourceStale),
                    ),
                )
            !state.ventoySettingsSource.isDefaultPath() &&
                state.ventoyGlobalSettings !is VentoyGlobalSettings.Present &&
                state.ventoyGlobalSettings !is VentoyGlobalSettings.Empty ->
                // A chosen file must be read and valid now. Missing, a directory, or a
                // parse error is never turned into a create/overwrite attempt.
                ReduceResult(
                    state.copy(
                        statusMessage =
                            UiMessage.Key(UiMessageKey.SettingsSourceUnavailable),
                    ),
                )
            else ->
                ReduceResult(
                    state,
                    effects =
                        listOf(
                            UiEffect.ApplyVentoyControls(
                                settings = state.pendingVentoyControls,
                                source = state.ventoySettingsSource,
                                identity = state.ventoySettingsSourceIdentity,
                                attachGeneration =
                                    state.ventoySettingsSourceAttachGeneration,
                            ),
                        ),
                )
        }
}
