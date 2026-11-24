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
    fun requestSetVtoyEfiWritable(state: AppUiState, intent: UiIntent.RequestSetVtoyEfiWritable): ReduceResult {
        val target = state.partitions.firstOrNull { it.id == intent.target.partitionId && it.readOnly }
        val valid = state.actionsEnabled && state.tableValid && !state.partitionTableAmbiguous &&
            !state.partitionTableDamaged && target?.startLba != null && target.sectorCount != null &&
            intent.target.matches(state)
        return if (valid) ReduceResult(state.copy(pendingEfiWriteBinding = intent.target))
        else ReduceResult(state.copy(pendingEfiWriteBinding = null))
    }

    fun confirmSetVtoyEfiWritable(state: AppUiState, intent: UiIntent.ConfirmSetVtoyEfiWritable): ReduceResult {
        val pending = state.pendingEfiWriteBinding
        val target = state.partitions.firstOrNull { it.id == intent.target.partitionId && it.readOnly }
        val valid = pending == intent.target && target?.startLba != null && target.sectorCount != null &&
            intent.target.matches(state) && state.actionsEnabled && state.tableValid &&
            !state.partitionTableAmbiguous && !state.partitionTableDamaged
        return if (valid) {
            setVtoyEfiWritable(
                state.copy(pendingEfiWriteBinding = null),
                UiIntent.SetVtoyEfiWritable(intent.target.partitionId, true),
            )
        } else ReduceResult(state.copy(pendingEfiWriteBinding = null))
    }


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
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            state.partitionTableAmbiguous ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)),
                )
            state.partitionTableDamaged ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.GptPrimaryDamaged)),
                )
            !state.tableValid ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.TableDamaged)),
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
                            state.withStatus(UiReducer.message(
                                    UiMessageKey.EfiPartitionMissing,
                                    intent.partitionId,
                                )),
                        )
                    start == null || count == null ->
                        ReduceResult(
                            state.withStatus(UiMessage.Key(UiMessageKey.EfiGeometryUnknown)),
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
        val source = state.ventoySettingsSource
        // Phone documents never touch stick geometry.
        if (source is VentoySettingsSource.Document) {
            return startSettingsSourceRead(
                state = state,
                source = source,
                device = state.device,
            )
        }
        val pinsPresent =
            state.ventoySettingsSourceStartLba != null &&
                state.ventoySettingsSourceSectorCount != null
        val geometryOk = pinsPresent && state.settingsSourceGeometryMatches()
        return when {
            // Missing or stale pins after install/repartition: re-bind to the live data
            // partition when the UI already knows it. A hard SettingsSourceStale here left
            // ventoyGlobalSettings null until the user tapped Retry by hand.
            !geometryOk -> {
                val dataPartition = state.ventoyDataPartition()
                when {
                    dataPartition != null ->
                        startSettingsSourceRead(
                            state = state,
                            source = source,
                            device = state.device,
                        )
                    // Install/update just rewrote the table; refreshPartitions has not
                    // landed yet. Default path is resolved by the host via VentoyLayout.probe,
                    // so the read can proceed without UI pins. Custom USB paths cannot.
                    source.isDefaultPath() -> fireUnpinnedSettingsRead(state, source)
                    else ->
                        ReduceResult(
                            state.withStatus(UiMessage.Key(UiMessageKey.VentoyDataPartitionMissing)),
                        )
                }
            }
            else -> {
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
                            source = source,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Read the default stick config without UI partition pins. Used right after a layout
     * mutation while [AppUiState.partitions] is still empty/stale; the host probes the live
     * disk. Pins are filled on the next load once part1 is known.
     */
    private fun fireUnpinnedSettingsRead(
        state: AppUiState,
        source: VentoySettingsSource,
    ): ReduceResult {
        val token = state.ventoyGlobalSettingsRequest + 1L
        val device = state.device
        return ReduceResult(
            state.copy(
                ventoyGlobalSettingsRequest = token,
                ventoySettingsSource = source,
                ventoySettingsSourceLoading = true,
                ventoySettingsSourceIdentity = device?.identity,
                ventoySettingsSourceAttachGeneration = device?.attachGeneration,
                ventoySettingsSourcePartitionId = null,
                ventoySettingsSourceStartLba = null,
                ventoySettingsSourceSectorCount = null,
            ).withStatus(null),
            effects = listOf(
                UiEffect.LoadVentoyGlobalSettings(requestToken = token, source = source),
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
                    state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceInvalid)),
                )
            !state.actionsEnabled ->
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            device == null ->
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            intent.identity != null && intent.identity != device.identity ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceStale)),
                )
            intent.attachGeneration != null &&
                intent.attachGeneration != device.attachGeneration ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceStale)),
                )
            intent.partitionId != null && pinnedPartition == null ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceStale)),
                )
            pinnedPartition != null && pinnedStart != null &&
                pinnedStart != pinnedPartition.startLba ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceStale)),
                )
            pinnedPartition != null && pinnedCount != null &&
                pinnedCount != pinnedPartition.sectorCount ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceStale)),
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
    ): ReduceResult {
        // A phone document is read through SAF and never touches the stick, so a missing
        // attachment must not discard it: the phone picker stays usable with no USB drive.
        // A stick that is present but Busy/PermissionRequired/unconfirmed still refuses,
        // exactly as before, so a pick cannot race an operation on that attachment.
        val stickAttached =
            state.device != null &&
                state.presence != DevicePresence.Absent &&
                state.presence != DevicePresence.Disconnected
        return when {
            intent.uri.isBlank() ->
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceInvalid)),
                )
            !state.actionsEnabled && stickAttached ->
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            intent.identity != null && intent.identity != state.device?.identity ->
                ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceStale)))
            intent.attachGeneration != null && intent.attachGeneration != state.device?.attachGeneration ->
                ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceStale)))
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
    }

    fun useDefaultVentoySettingsFile(state: AppUiState): ReduceResult =
        if (!state.actionsEnabled) {
            ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
        } else {
            startSettingsSourceRead(
                state = state,
                source = VentoySettingsSource.Default,
                device = state.device,
            )
        }

    /**
     * Settings pins are valid only while the partition they name still has the same
     * geometry. Install/update rewrites part1; keeping old start/count must not block a
     * reload forever or let Apply address pre-mutation bytes. Applies to Default and
     * UsbFile alike — Default previously skipped this check and left Apply fail-closed
     * after repartition until a manual refresh.
     */
    private fun AppUiState.settingsSourceGeometryMatches(): Boolean {
        val partitionId = ventoySettingsSourcePartitionId ?: return true
        val partition =
            partitions.firstOrNull { it.id == partitionId } ?: return false
        if (partition.readOnly || partition.isEfiType) return false
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
        // A phone document is read from SAF, not from the stick, so a partition table that is
        // not probed yet (a re-probe right after the picker returns) must not discard the
        // picked file. The write path resolves the data partition when it actually runs.
        val dataPartition = state.ventoyDataPartition()
        if (dataPartition == null && source !is VentoySettingsSource.Document) {
            return ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.VentoyDataPartitionMissing)))
        }
        val token = state.ventoyGlobalSettingsRequest + 1L
        val pinnedPartitionId = partitionId ?: dataPartition?.id
        val pinnedStart = partitionStartLba ?: dataPartition?.startLba
        val pinnedCount = partitionSectorCount ?: dataPartition?.sectorCount
        return ReduceResult(
            state.copy(
                ventoyGlobalSettingsRequest = token,
                ventoySettingsSource = source,
                // Refresh keeps displayed settings until newest answer lands. A different
                // source must not inherit rows from previous file.
                ventoyGlobalSettings =
                    state.ventoyGlobalSettings.takeIf {
                        state.ventoySettingsSource.sameTarget(source)
                    },
                ventoySettingsSourceLoading = true,
                ventoySettingsSourceIdentity = device?.identity,
                ventoySettingsSourceAttachGeneration = device?.attachGeneration,
                ventoySettingsSourcePartitionId = pinnedPartitionId,
                ventoySettingsSourceStartLba = pinnedStart,
                ventoySettingsSourceSectorCount = pinnedCount,
                pendingVentoyControls =
                    if (state.ventoySettingsSource.sameTarget(source)) {
                        // Re-selecting the same target (URI, or the same stick path) must not
                        // discard staged edits; a changed displayName is not a new target.
                        state.pendingVentoyControls
                    } else {
                        emptyList()
                    },
            ).withStatus(null),
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
                ).withStatus(if (state.presence == DevicePresence.Ready) {
                            UiMessage.Key(UiMessageKey.WaitForOperation)
                        } else {
                            UiReducer.presenceMessage(state.presence)
                        }),
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
                        state.withStatus(UiMessage.Key(UiMessageKey.FolderListChanged)),
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
                    ventoySettingsSource = intent.source,
                    ventoySettingsSourceLoading = false,
                    // Host posts live geometry when it rebinds after install/reprobe.
                    ventoySettingsSourcePartitionId =
                        intent.partitionId ?: state.ventoySettingsSourcePartitionId,
                    ventoySettingsSourceStartLba =
                        intent.partitionStartLba ?: state.ventoySettingsSourceStartLba,
                    ventoySettingsSourceSectorCount =
                        intent.partitionSectorCount ?: state.ventoySettingsSourceSectorCount,
                ).let { nextState ->
                    val loaded = intent.settings
                    if (loaded is VentoyGlobalSettings.ReadError && !loaded.damagedLayout) {
                        nextState.withStatus(UiMessage.Raw(loaded.message))
                    } else {
                        // Carried forward, not a new emission: no sequence bump.
                        nextState.copy(statusMessage = state.statusMessage)
                    }
                },
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
            ReduceResult(state.withStatus(UiMessage.Raw(problem)))
        } else {
            val staged = LinkedHashMap<String, VentoyControlSetting>()
            state.pendingVentoyControls.forEach { staged[it.key] = it }
            staged[intent.key] = VentoyControlSetting(intent.key, intent.value)
            ReduceResult(
                state.copy(
                    pendingVentoyControls = staged.values.toList(),
                ).withStatus(null),
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
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            !state.settingsSourceGeometryMatches() -> {
                // After install/reprobe the retained pins can lag the live part1. For the
                // Default stick path, rebind and apply against the live geometry instead of
                // locking the user into SettingsSourceStale. Document/UsbFile keep the pin
                // fence so a mismatched stick cannot be overwritten by accident.
                val live = state.ventoyDataPartition()
                if (state.ventoySettingsSource.isDefaultPath() && live != null) {
                    emitApplyVentoyControls(
                        state.copy(
                            ventoySettingsSourcePartitionId = live.id,
                            ventoySettingsSourceStartLba = live.startLba,
                            ventoySettingsSourceSectorCount = live.sectorCount,
                        ),
                    )
                } else {
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceStale)))
                }
            }
            !state.ventoySettingsSource.isDefaultPath() &&
                state.ventoySettingsSource !is VentoySettingsSource.Document &&
                state.ventoyGlobalSettings !is VentoyGlobalSettings.Present &&
                state.ventoyGlobalSettings !is VentoyGlobalSettings.Empty ->
                ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.SettingsSourceUnavailable)))
            (state.ventoySettingsSource is VentoySettingsSource.Document ||
                state.ventoySettingsSource is VentoySettingsSource.UsbFile) &&
                (state.ventoyGlobalSettings is VentoyGlobalSettings.Present ||
                    state.ventoyGlobalSettings is VentoyGlobalSettings.Empty) ->
                // A validated phone or USB JSON is an import source. Apply copies its whole
                // document to the stick's standard config; pending rows remain optional edits.
                emitApplyVentoyControls(state)
            state.pendingVentoyControls.isEmpty() ->
                ReduceResult(state.withStatus(UiMessage.Raw("No Ventoy setting changes to apply")))
            else ->
                emitApplyVentoyControls(state)
        }

    private fun emitApplyVentoyControls(state: AppUiState): ReduceResult =
        ReduceResult(
            state,
            effects = listOf(
                UiEffect.ApplyVentoyControls(
                    settings = state.pendingVentoyControls,
                    source = state.ventoySettingsSource,
                    identity = state.ventoySettingsSourceIdentity,
                    attachGeneration = state.ventoySettingsSourceAttachGeneration,
                    partitionId = state.ventoySettingsSourcePartitionId ?: state.ventoyDataPartition()?.id,
                    partitionStartLba = state.ventoySettingsSourceStartLba ?: state.ventoyDataPartition()?.startLba,
                    partitionSectorCount = state.ventoySettingsSourceSectorCount ?: state.ventoyDataPartition()?.sectorCount,
                ),
            ),
        )
}
