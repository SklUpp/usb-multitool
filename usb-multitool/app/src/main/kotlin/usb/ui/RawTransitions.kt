package usb.ui

/**
 * Pure transitions of the Raw tab: staging a source image, discovering whole-partition
 * images and arming a range write together with its probe. [UiReducer.reduce] stays the
 * only entry point; this object holds no state and performs no I/O.
 */
internal object RawTransitions {
    fun requestWriteRawRange(
        state: AppUiState,
        intent: UiIntent.RequestWriteRawRange,
    ): ReduceResult {
        val device = state.device
        val geometry = state.rawGeometry
        val hasSource = state.imageUri != null || state.stagedRawImagePath != null
        return when {
            device == null || !state.actionsEnabled ->
                ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
            !hasSource ->
                ReduceResult(
                    state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectImageFirst)),
                )
            geometry == null ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.CannotReadPartitionTable),
                    ),
                )
            else -> {
                val check =
                    usb.partition.RawRangePlan.check(
                        intent.startLba,
                        intent.sectorCount,
                        geometry,
                    )
                if (check is usb.partition.RawRangeCheck.Rejected) {
                    ReduceResult(
                        state.copy(
                            statusMessage = UiMessage.Key(UiMessageKey.RawRangeInvalid),
                        ),
                    )
                } else {
                    ReduceResult(
                        state.copy(
                            pendingConfirmation =
                                DestructiveConfirmation.request(
                                    device,
                                    DestructiveAction.RAW_WRITE,
                                    rawStartLba = intent.startLba,
                                    rawSectorCount = intent.sectorCount,
                                    diskSectorCount = geometry.diskSectorCount,
                                    diskSectorSize = geometry.sectorSize,
                                    rawSourceName = UiReducer.rawImageSourceName(state),
                                ),
                            rangeProbe =
                                state.rangeProbe?.takeIf {
                                    it.range.startLba == intent.startLba &&
                                        it.range.sectorCount == intent.sectorCount
                                },
                        ),
                    )
                }
            }
        }
    }

    fun requestRangeProbe(state: AppUiState, intent: UiIntent.RequestRangeProbe): ReduceResult {
        val geometry = state.rawGeometry
        return if (geometry == null ||
            state.presence != DevicePresence.Ready ||
            !state.actionsEnabled ||
            intent.sectorCount == 0UL
        ) {
            ReduceResult(
                state.copy(
                    rangeProbe = null,
                    rangeProbeRequestedRange = null,
                ),
            )
        } else {
            val requestId = (state.rangeProbeRequestId ?: 0L) + 1L
            val requested =
                usb.partition.RawWriteRange(intent.startLba, intent.sectorCount)
            ReduceResult(
                state.copy(
                    rangeProbe = null,
                    rangeProbeRequestId = requestId,
                    rangeProbeRequestedRange = requested,
                ),
                effects = listOf(
                    UiEffect.RangeProbe(
                        startLba = intent.startLba,
                        sectorCount = intent.sectorCount,
                        probeRequestId = requestId,
                    ),
                ),
            )
        }
    }

    fun rangeProbeResulted(state: AppUiState, intent: UiIntent.RangeProbeResulted): ReduceResult {
        val device = state.device
        val geometry = state.rawGeometry
        val requestedRange = state.rangeProbeRequestedRange
        val requestId = state.rangeProbeRequestId
        val attachment =
            device?.let {
                usb.image.RangeProbeAttachment(it.identity, it.attachGeneration)
            }
        val accepted =
            requestId != null &&
                requestedRange != null &&
                attachment != null &&
                geometry != null &&
                intent.result.requestId == requestId &&
                intent.result.matches(
                    requestedRange,
                    geometry.diskSectorCount,
                    geometry.sectorSize,
                    attachment,
                )
        // A late answer for another request, range, attachment or geometry describes
        // something the user no longer sees: it is ignored, never allowed to clear a
        // newer probe that is already on screen.
        return if (accepted) {
            ReduceResult(state.copy(rangeProbe = intent.result))
        } else {
            ReduceResult(state)
        }
    }

    fun setStagedRawImage(state: AppUiState, intent: UiIntent.SetStagedRawImage): ReduceResult =
        if (state.isRunning || state.operation?.state == OperationState.Queued || state.pendingConfirmation != null) {
            ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.CannotChangeStaged)))
        } else {
            ReduceResult(
                state.copy(
                    stagedRawImagePath = intent.path,
                    stagedRawImageLabel = intent.label,
                    imageUri = null,
                    rangeProbe = null,
                    rangeProbeRequestedRange = null,
                    imageName = null,
                    imageSizeBytes = null,
                    statusMessage = UiReducer.message(UiMessageKey.StagedImageReady, intent.label),
                ),
            )
        }

    fun clearStagedRawImage(state: AppUiState, intent: UiIntent.ClearStagedRawImage): ReduceResult =
        if (state.isRunning || state.operation?.state == OperationState.Queued || state.pendingConfirmation != null) {
            ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.CannotClearStaged)))
        } else {
            ReduceResult(
                state.copy(
                    stagedRawImagePath = null,
                    stagedRawImageLabel = null,
                    rangeProbe = null,
                    rangeProbeRequestedRange = null,
                    statusMessage = UiMessage.Key(UiMessageKey.StagedCleared),
                ),
            )
        }

    fun loadRawSourceCandidates(
        state: AppUiState,
        intent: UiIntent.LoadRawSourceCandidates,
    ): ReduceResult {
        val device = state.device
        val partition = state.selectedPartition()
        return when {
            !state.actionsEnabled ->
                ReduceResult(
                    state.copy(
                        statusMessage =
                            if (state.isRunning ||
                                state.operation?.state == OperationState.Queued ||
                                state.pendingConfirmation != null
                            ) {
                                UiMessage.Key(UiMessageKey.WaitForOperation)
                            } else {
                                UiReducer.presenceMessage(state.presence)
                            },
                    ),
                )
            device == null ->
                ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
            partition == null ->
                ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectPartitionFirst)))
            else -> {
                val generation = state.rawSourceCandidatesGeneration + 1
                ReduceResult(
                    state.copy(
                        rawSourceCandidates = null,
                        rawSourceCandidatesGeneration = generation,
                        rawSourceCandidatesIdentity = device.identity,
                        rawSourceCandidatesTruncated = false,
                        rawSourceCandidatesError = null,
                    ),
                    effects = listOf(
                        UiEffect.LoadRawSources(
                            binding = PartitionBinding(
                                device = device,
                                partitionId = partition.id,
                                startLba = partition.startLba,
                                sectorCount = partition.sectorCount,
                            ),
                            requestGeneration = generation,
                        ),
                    ),
                )
            }
        }
    }

    fun rawSourceCandidatesLoaded(
        state: AppUiState,
        intent: UiIntent.RawSourceCandidatesLoaded,
    ): ReduceResult {
        val device = state.device
        val partitionId = state.selectedPartition()?.id
        return if (device == null ||
            intent.generation != state.rawSourceCandidatesGeneration ||
            intent.identity != device.identity
        ) {
            // Late result for another attachment, partition, or request.
            ReduceResult(state)
        } else {
            val accepted =
                intent.candidates.filter {
                    it.partitionId == partitionId &&
                        it.path.startsWith("/") &&
                        it.path != "/" &&
                        it.name.isNotBlank()
                }
            val notice = when {
                intent.truncated ->
                    UiMessage.Raw(
                        "Raw source list truncated at ${accepted.size} files; " +
                            "use Files to pick from the rest",
                    )
                intent.unreadable > 0 ->
                    UiMessage.Raw(
                        "${intent.unreadable} folder(s) could not be read; " +
                            "files in them are not listed",
                    )
                else -> state.statusMessage
            }
            ReduceResult(
                state.copy(
                    rawSourceCandidates = accepted,
                    rawSourceCandidatesTruncated = intent.truncated,
                    rawSourceCandidatesError = null,
                    statusMessage = notice,
                ),
            )
        }
    }

    fun rawSourceCandidatesFailed(
        state: AppUiState,
        intent: UiIntent.RawSourceCandidatesFailed,
    ): ReduceResult {
        val device = state.device
        return if (device == null ||
            intent.generation != state.rawSourceCandidatesGeneration ||
            intent.identity != device.identity
        ) {
            // Stale chooser A — keep B status/candidates; log so close/I/O is not silent.
            ReduceResult(appendLogLine(state, "Raw sources (stale): ${intent.message}"))
        } else {
            // Bound chooser error always; global status only when no other op owns the slot.
            val opOwnsStatus =
                state.operation?.state == OperationState.Running ||
                    state.operation?.state == OperationState.Queued
            val next =
                state.copy(
                    rawSourceCandidates = emptyList(),
                    rawSourceCandidatesError = intent.message,
                    rawSourceCandidatesTruncated = false,
                    statusMessage =
                        if (opOwnsStatus) {
                            state.statusMessage
                        } else {
                            UiMessage.Raw(intent.message)
                        },
                )
            if (opOwnsStatus) {
                ReduceResult(appendLogLine(next, "Raw sources: ${intent.message}"))
            } else {
                ReduceResult(next)
            }
        }
    }

    fun selectRawSource(state: AppUiState, intent: UiIntent.SelectRawSource): ReduceResult {
        val device = state.device
        val partition = state.selectedPartition()
        val candidate =
            state.rawSourceCandidates?.firstOrNull {
                it.path == intent.path &&
                    it.name == intent.name &&
                    it.partitionId == partition?.id
            }
        return when {
            !state.actionsEnabled ->
                ReduceResult(
                    state.copy(
                        statusMessage =
                            if (state.pendingConfirmation != null) {
                                UiMessage.Key(UiMessageKey.WaitForOperation)
                            } else {
                                UiReducer.presenceMessage(state.presence)
                            },
                    ),
                )
            device == null ->
                ReduceResult(state.copy(statusMessage = UiReducer.presenceMessage(state.presence)))
            partition == null ->
                ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectPartitionFirst)))
            candidate == null ->
                ReduceResult(
                    state.copy(
                        statusMessage =
                            UiMessage.Raw("Selected source is no longer listed; reload the list"),
                    ),
                )
            else -> {
                val name = candidate.path.substringAfterLast('/')
                val parent = candidate.path.substringBeforeLast('/', "").ifEmpty { "/" }
                if (name != candidate.name || name.isEmpty()) {
                    ReduceResult(
                        state.copy(statusMessage = UiMessage.Raw("Invalid source path ${candidate.path}")),
                    )
                } else {
                    ReduceResult(
                        state.copy(statusMessage = UiReducer.message(UiMessageKey.StagingFile, name)),
                        effects = listOf(
                            UiEffect.StageUsbImageForRawWrite(
                                path = parent,
                                name = name,
                                binding = PartitionBinding(
                                    device = device,
                                    partitionId = partition.id,
                                    startLba = partition.startLba,
                                    sectorCount = partition.sectorCount,
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun cancelRawSourceLoad(state: AppUiState): ReduceResult =
        ReduceResult(
            state.copy(
                rawSourceCandidates = null,
                rawSourceCandidatesGeneration = state.rawSourceCandidatesGeneration + 1,
                rawSourceCandidatesIdentity = null,
                rawSourceCandidatesTruncated = false,
                rawSourceCandidatesError = null,
            ),
            effects = listOf(UiEffect.CancelRawSources),
        )

    /** Ring-append one log line without clobbering status or bound fields. */
    private fun appendLogLine(state: AppUiState, line: String): AppUiState {
        val trimmed = line.take(500)
        val updated =
            if (state.logLines.size >= AppUiState.LOG_MAX_LINES) {
                state.logLines.drop(state.logLines.size - AppUiState.LOG_MAX_LINES + 1) + trimmed
            } else {
                state.logLines + trimmed
            }
        return state.copy(logLines = updated)
    }
}
