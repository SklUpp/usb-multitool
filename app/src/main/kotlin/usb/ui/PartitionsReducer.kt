package usb.ui

import usb.fs.FormatCatalog

/**
 * Feature reducer extracted from [UiReducer]; behavior preserved verbatim.
 */
internal object PartitionsReducer {
    fun reduce(state: AppUiState, intent: UiIntent): ReduceResult? = when (intent) {
        is UiIntent.SetVtoyEfiWritable ->
            VentoyTransitions.setVtoyEfiWritable(state, intent)
        is UiIntent.RequestSetVtoyEfiWritable ->
            VentoyTransitions.requestSetVtoyEfiWritable(state, intent)
        is UiIntent.ConfirmSetVtoyEfiWritable ->
            VentoyTransitions.confirmSetVtoyEfiWritable(state, intent)
        UiIntent.DismissEfiWriteConfirmation ->
            ReduceResult(state.copy(pendingEfiWriteBinding = null))
        is UiIntent.SelectFormatFilesystem -> {
            if (!state.actionsEnabled) {
                ReduceResult(state)
            } else {
                val id = intent.id
                if (id !in FormatCatalog.supportedFormatIds()) {
                    ReduceResult(
                        state.withStatus(UiReducer.message(UiMessageKey.FormatUnsupported, FormatCatalog.label(id))),
                    )
                } else {
                    ReduceResult(state.copy(selectedFormatId = id).withStatus(null))
                }
            }
        }

        is UiIntent.RequestCreatePartition ->
            PartitionTransitions.requestCreatePartition(state, intent)
        is UiIntent.RequestRenamePartition ->
            PartitionTransitions.requestRenamePartition(state, intent)
        is UiIntent.RequestRenameVolumeLabel ->
            PartitionTransitions.requestRenameVolumeLabel(state, intent)
        is UiIntent.RequestInitializePartitionTable ->
            PartitionTransitions.requestInitializePartitionTable(state, intent)

        is UiIntent.RequestRawWrite ->
            RawTransitions.requestRawWrite(state, intent)
        is UiIntent.RequestRangeProbe ->
            RawTransitions.requestRangeProbe(state, intent)
        is UiIntent.RangeProbeResulted ->
            RawTransitions.rangeProbeResulted(state, intent)

        is UiIntent.RequestSetPartitionRole ->
            PartitionTransitions.requestSetPartitionRole(state, intent)
        is UiIntent.RequestSetPartitionBootable ->
            PartitionTransitions.requestSetPartitionBootable(state, intent)

        is UiIntent.SetImage -> {
            val uri = intent.uri
            val sourceLocked = state.isRunning ||
                state.operation?.state == OperationState.Queued ||
                state.pendingConfirmation != null
            if (sourceLocked) {
                // Only a live destructive operation owns the source. A transient
                // re-probe Busy after the SAF picker returns must not drop the pick.
                ReduceResult(state)
            } else if (uri == null) {
                // Clearing the SAF pick keeps an existing staged copy as the source.
                val key = state.stagedRawImagePath
                ReduceResult(
                    state.copy(
                        imageUri = null,
                        rangeProbe = null,
                        rangeProbeRequestedRange = null,
                        imageName = null,
                        imageSizeBytes = null,
                        rawDecodedSizeBytes = state.rawDecodedSizeBytes.takeIf {
                            key != null && state.rawDecodedSizeSource == key
                        },
                        rawDecodedSizeSource = key,
                        rawDecodedSizeError = state.rawDecodedSizeError.takeIf { key != null },
                        rawDecodedErrorKind = state.rawDecodedErrorKind.takeIf { key != null },
                        rawDecodedSha256 = state.rawDecodedSha256.takeIf { key != null },
                        rawSizeMeasuring = false,
                        rawMeasurementPending = state.rawMeasurementPending && key != null,
                        rawMeasurementWaitingForPrevious =
                            state.rawMeasurementWaitingForPrevious && key != null,
                    ).withStatus(UiMessage.Key(UiMessageKey.ImageCleared)),
                    effects =
                        if (key == null) {
                            listOf(UiEffect.CancelRawSourceMeasure)
                        } else {
                            emptyList()
                        },
                )
            } else {
                val generation = state.rawSizeGeneration + 1L
                // Fast mode (readback verification off) trusts the stored size and skips
                // the digest pass. Safe mode measures so a same-size provider change
                // cannot slip past confirmation.
                val cheap =
                    if (state.rawVerifyReadback) null
                    else UiReducer.cheapRawDecodedBytes(intent.name, intent.sizeBytes)
                val limit = if (cheap == null) UiReducer.rawMeasureLimit(state)?.takeIf { it > 0L } else null
                ReduceResult(
                    state.copy(
                        imageUri = uri,
                        rangeProbe = null,
                        rangeProbeRequestedRange = null,
                        imageName = intent.name,
                        imageSizeBytes = intent.sizeBytes,
                        stagedRawImagePath = null,
                        stagedRawImageLabel = null,
                        stagedRawImageSizeBytes = null,
                        rawDecodedSizeBytes = cheap,
                        rawDecodedSizeSource = uri,
                        rawDecodedSizeError = null,
                        rawDecodedErrorKind = null,
                        rawDecodedSha256 = null,
                        rawSizeMeasuring = cheap == null && limit != null,
                        rawMeasurementPending = cheap == null && limit == null,
                        rawMeasurementWaitingForPrevious = false,
                        rawSizeGeneration = generation,
                    ).withStatus(UiMessage.Key(
                            UiMessageKey.ImageReady,
                            listOf(intent.name ?: uri),
                        )),
                    effects =
                        if (cheap == null && limit != null) {
                            listOf(
                                UiEffect.MeasureRawSource(
                                    uri,
                                    staged = false,
                                    generation = generation,
                                    limitBytes = limit,
                                ),
                            )
                        } else {
                            emptyList()
                        },
                )
            }
        }

        is UiIntent.SetStagedRawImage ->
            RawTransitions.setStagedRawImage(state, intent)
        is UiIntent.ClearStagedRawImage ->
            RawTransitions.clearStagedRawImage(state, intent)
        is UiIntent.RawSourceMeasured ->
            RawTransitions.rawSourceMeasured(state, intent)
        is UiIntent.RawSourceMeasurementWaiting ->
            RawTransitions.rawSourceMeasurementWaiting(state, intent)
        is UiIntent.RawSourceMeasurementStarted ->
            RawTransitions.rawSourceMeasurementStarted(state, intent)
        is UiIntent.SetRawVerifyReadback -> {
            if (state.rawVerifyReadback == intent.enabled) {
                ReduceResult(state)
            } else if (!intent.enabled) {
                val nonSnapshot = state.imageUri != null && state.stagedRawImagePath == null
                ReduceResult(
                    state.copy(
                        rawVerifyReadback = false,
                        // Drop the digest so the writer does not perform a preflight
                        // pass in fast mode. A measured size remains usable.
                        rawDecodedSha256 =
                            if (nonSnapshot) null else state.rawDecodedSha256,
                    ),
                )
            } else {
                val nonSnapshot = state.imageUri != null && state.stagedRawImagePath == null
                if (!nonSnapshot || state.rawDecodedSha256 != null) {
                    ReduceResult(state.copy(rawVerifyReadback = true))
                } else {
                    val generation = state.rawSizeGeneration + 1L
                    val limit = UiReducer.rawMeasureLimit(state)?.takeIf { it > 0L }
                    ReduceResult(
                        state.copy(
                            rawVerifyReadback = true,
                            rawDecodedSizeBytes = null,
                            rawDecodedSizeError = null,
                            rawDecodedErrorKind = null,
                            rawDecodedSha256 = null,
                            rawSizeMeasuring = limit != null,
                            rawMeasurementPending = limit == null,
                            rawMeasurementWaitingForPrevious = false,
                            rawSizeGeneration = generation,
                        ),
                        effects =
                            if (limit != null) {
                                listOf(
                                    UiEffect.MeasureRawSource(
                                        state.imageUri,
                                        staged = false,
                                        generation = generation,
                                        limitBytes = limit,
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                    )
                }
            }
        }
        is UiIntent.CancelRawSourceMeasurement ->
            RawTransitions.cancelRawSourceMeasurement(state)
        UiIntent.RetryRawSourceMeasurement -> retryRawSourceMeasurement(state)
        is UiIntent.LoadRawSourceCandidates ->
            RawTransitions.loadRawSourceCandidates(state, intent)
        is UiIntent.RawSourceCandidatesLoaded ->
            RawTransitions.rawSourceCandidatesLoaded(state, intent)
        is UiIntent.RawSourceCandidatesFailed ->
            RawTransitions.rawSourceCandidatesFailed(state, intent)
        is UiIntent.SelectRawSource ->
            RawTransitions.selectRawSource(state, intent)
        UiIntent.CancelRawSourceLoad -> RawTransitions.cancelRawSourceLoad(state)

        else -> null
    }

    /** Restart a terminal failed/cancelled pass without changing the selected source. */
    internal fun retryRawSourceMeasurement(state: AppUiState): ReduceResult {
        if (!state.actionsEnabled) {
            return ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
        }
        val key = state.rawSourceKey ?: return ReduceResult(state)
        val limit = UiReducer.rawMeasureLimit(state)?.takeIf { it > 0L } ?: return ReduceResult(state)
        val generation = state.rawSizeGeneration + 1L
        return ReduceResult(
            state.copy(
                rawDecodedSizeBytes = null,
                rawDecodedSizeSource = key,
                rawDecodedSizeError = null,
                rawDecodedErrorKind = null,
                rawDecodedSha256 = null,
                rawSizeMeasuring = true,
                rawMeasurementPending = false,
                rawMeasurementWaitingForPrevious = false,
                rawSizeGeneration = generation,
            ),
            effects = listOf(
                UiEffect.MeasureRawSource(
                    source = key,
                    staged = state.stagedRawImagePath != null,
                    generation = generation,
                    limitBytes = limit,
                ),
            ),
        )
    }

    /** Start a queued bounded pass once capacity/geometry is known. */
    internal fun resumeRawMeasurement(
        state: AppUiState,
    ): Pair<AppUiState, UiEffect>? {
        if (!state.rawMeasurementPending) return null
        val key = state.rawSourceKey ?: return null
        val limit = UiReducer.rawMeasureLimit(state)?.takeIf { it > 0L } ?: return null
        val generation = state.rawSizeGeneration + 1L
        val staged = state.stagedRawImagePath != null
        return state.copy(
            rawSizeMeasuring = true,
            rawMeasurementPending = false,
            rawSizeGeneration = generation,
        ) to
            UiEffect.MeasureRawSource(
                source = key,
                staged = staged,
                generation = generation,
                limitBytes = limit,
            )
    }
}
