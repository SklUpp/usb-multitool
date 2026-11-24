package usb.ui

/**
 * Feature reducer extracted from [UiReducer]; behavior preserved verbatim.
 */
internal object DiagnosticsReducer {
    fun reduce(state: AppUiState, intent: UiIntent): ReduceResult? = when (intent) {
        is UiIntent.SetBackupFolder ->
            RescueBackupTransitions.setBackupFolder(state, intent)
        is UiIntent.SetBackupFile ->
            RescueBackupTransitions.setBackupFile(state, intent)
        UiIntent.StartReadScan -> RescueBackupTransitions.startReadScan(state)
        UiIntent.StartSmartRead -> RescueBackupTransitions.startSmartRead(state)

        UiIntent.StartRescueBackup -> RescueBackupTransitions.startRescueBackup(state)
        UiIntent.CaptureGptBackup -> RescueBackupTransitions.captureGptBackup(state)

        UiIntent.RunSelfTest ->
            if (state.operation?.state == OperationState.Queued || state.operation?.state == OperationState.Running) {
                ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.OperationInProgress)))
            } else {
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.SelfTestStarting)),
                    effects = listOf(UiEffect.RunSelfTest),
                )
            }

        is UiIntent.SetRescueArchiveTree ->
            ReduceResult(state.copy(rescueArchiveTreeUri = intent.treeUri))
        UiIntent.ResetRescueSelection -> ReduceResult(
            state.copy(
                rescueArchiveTreeUri = null,
                rescueImageUri = null,
                rescueImageInspection = null,
                rescueMapUri = null,
                rescueMapInspection = null,
                pendingConfirmation = null,
            ),
        )
        UiIntent.ResetGptSelection -> ReduceResult(
            state.copy(
                backupUri = null,
                backupInspection = null,
                pendingConfirmation = null,
            ),
        )
        is UiIntent.SetRescueImage ->
            RescueBackupTransitions.setRescueImage(state, intent)
        is UiIntent.SetRescueMap ->
            RescueBackupTransitions.setRescueMap(state, intent)

        else -> null
    }

    /**
     * Refuse the destructive GPT confirmation when the selected file failed bounded
     * validation or no longer matches the live geometry. A missing inspection is treated
     * as unavailable, not as a pass; the restore path still revalidates against the disk.
     */
    internal fun gptRestoreInspectionProblem(state: AppUiState): UiMessage? {
        val uri = state.backupUri ?: return null
        val inspection = state.backupInspection?.takeIf { it.uri == uri } ?: return null
        inspection.error?.let {
            return UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf(inspection.displayName, it),
            )
        }
        val summary = inspection.summary
            ?: return UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf(inspection.displayName, "backup details unavailable"),
            )
        if (summary.sectorSize != state.diskSectorSize) {
            return UiMessage.Key(
                UiMessageKey.GptBackupSectorSizeMismatch,
                listOf(summary.sectorSize.toString(), state.diskSectorSize.toString()),
            )
        }
        val liveSectors = liveSectorCount(state) ?: return null
        if (summary.originalSectorCount != liveSectors) {
            return UiMessage.Key(
                UiMessageKey.GptBackupDiskMismatch,
                listOf(summary.originalSectorCount.toString(), liveSectors.toString()),
            )
        }
        return null
    }

    /**
     * Refuse the destructive rescue confirmation when a bounded inspection failed or the
     * map cannot apply to the live disk. A raw ISO/IMG is a stream prefix: a known size
     * shorter than map sectors × sector size is blocked, extra trailing bytes are allowed.
     * Compressed sources never compare their stored (encoded) size. Overflow of
     * sectors × sector size is refused. This proves geometry agreement, never physical
     * media integrity.
     */
    internal fun rescueRestoreInspectionProblem(state: AppUiState): UiMessage? {
        val imageUri = state.rescueImageUri ?: return null
        val mapUri = state.rescueMapUri ?: return null
        val image = state.rescueImageInspection?.takeIf { it.uri == imageUri }
        val map = state.rescueMapInspection?.takeIf { it.uri == mapUri }
        if (image == null && map == null) return null
        map?.error?.let {
            return UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf(map.displayName, it),
            )
        }
        val summary = map?.summary
            ?: return map?.let {
                UiMessage.Key(
                    UiMessageKey.SourceInspectionRejected,
                    listOf(it.displayName, "map details unavailable"),
                )
            }
        if (summary.kind == usb.diagnostics.MapKind.COPY && summary.nonTriedSectors > 0UL) {
            return UiMessage.Key(
                UiMessageKey.RescueMapPartialCopy,
                listOf(summary.nonTriedSectors.toString()),
            )
        }
        val mapSectorSize = summary.sectorSize
            ?: return UiMessage.Key(UiMessageKey.RescueMapSectorSizeUnknown)
        if (mapSectorSize != state.diskSectorSize) {
            return UiMessage.Key(
                UiMessageKey.RescueMapSectorSizeMismatch,
                listOf(mapSectorSize.toString(), state.diskSectorSize.toString()),
            )
        }
        liveSectorCount(state)?.let { live ->
            if (summary.sectorCount > live) {
                return UiMessage.Key(
                    UiMessageKey.RescueMapDiskTooSmall,
                    listOf(summary.sectorCount.toString(), live.toString()),
                )
            }
        }
        // Magic decides raw vs compressed before SIZE is used. Compressed sources are
        // decoded by the stream; a raw image may be longer than the map requirement
        // (restore reads only mapped sectors), but must never be shorter.
        val sectorSize = summary.sectorSize ?: state.diskSectorSize.takeIf { it > 0 }
        if (sectorSize != null) {
            val actual = image?.sizeBytes?.takeIf { it >= 0L }
            when (rescueImageLengthIssue(actual, summary.sectorCount, sectorSize, image?.format)) {
                RescueImageLengthIssue.SHORT ->
                    return UiMessage.Key(
                        UiMessageKey.RescueMapImageTooShort,
                        listOf(
                            actual.toString(),
                            usb.diagnostics.BackupArchive.expectedImageBytes(
                                summary.sectorCount,
                                sectorSize,
                            ).toString(),
                        ),
                    )
                RescueImageLengthIssue.OVERFLOW ->
                    return UiMessage.Key(UiMessageKey.RescueMapImageGeometryOverflow)
                null -> Unit
            }
        }
        // A COPY map's identity is the source disk. Restoring onto a replacement disk is
        // the primary disaster-recovery path and RescueRestorePrep pins the destination,
        // discards inherited progress when identity differs, and rewrites all sectors.
        // The confirmation shows the source identity difference instead of blocking.
        return null
    }

    /** Live sector count when the attachment exposes it; null keeps the check unavailable. */
    internal fun liveSectorCount(state: AppUiState): ULong? {
        state.rawGeometry?.diskSectorCount?.let { return it }
        val capacity = state.device?.capacityBytes ?: return null
        val sectorSize = state.diskSectorSize
        if (capacity == 0UL || sectorSize <= 0) return null
        return capacity / sectorSize.toULong()
    }
}
