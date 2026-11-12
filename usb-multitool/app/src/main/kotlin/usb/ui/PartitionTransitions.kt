package usb.ui

import usb.fs.FormatCatalog
import usb.fs.VolumeLabel
import usb.partition.PartitionName
import usb.partition.RawSchemeProblem
import usb.partition.TableScheme

/**
 * Pure transitions that build a partition-table edit confirmation: create, rename,
 * reinitialize, role and bootable changes. [UiReducer.reduce] stays the only entry point;
 * this object holds no state and performs no I/O.
 */
internal object PartitionTransitions {
    fun requestCreatePartition(
        state: AppUiState,
        intent: UiIntent.RequestCreatePartition,
    ): ReduceResult {
        val device = state.device
        val valid = intent.sectorCount > 0UL && intent.startLba <= ULong.MAX_VALUE - (intent.sectorCount - 1UL) &&
            state.diskSectorSize > 0 && (1024 * 1024) % state.diskSectorSize == 0 &&
            intent.startLba % (1024 * 1024 / state.diskSectorSize).toULong() == 0UL && state.freeRegions.any { region ->
            intent.startLba >= region.startLba &&
                intent.startLba - region.startLba < region.sectorCount &&
                intent.sectorCount <= region.sectorCount - (intent.startLba - region.startLba)
        }
        val requestedName = intent.name?.let(PartitionName::normalize)?.takeIf { it.isNotEmpty() }
        val nameProblem: UiMessage? = when {
            requestedName == null -> null
            // GPT is the only scheme with a name field; unknown counts as unsupported.
            state.partitionScheme != TableScheme.GPT ->
                UiMessage.Key(UiMessageKey.PartitionNameUnsupported)
            else -> PartitionName.problem(requestedName)?.let {
                UiMessage.Key(UiMessageKey.InvalidPartitionName)
            }
        }
        val formatId = intent.formatId
        val formatProblem: UiMessage? = when {
            formatId == null -> null
            formatId !in FormatCatalog.supportedFormatIds() ->
                UiReducer.message(UiMessageKey.FormatUnsupported, FormatCatalog.label(formatId))
            else -> null
        }
        val labelProblem: UiMessage? = when {
            intent.volumeLabel.isBlank() -> null
            formatId == null -> UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
            else -> VolumeLabel.problem(intent.volumeLabel, formatId)?.let {
                UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
            }
        }
        return when {
            state.partitionTableAmbiguous ->
                ReduceResult(
                    state.copy(
                        statusMessage = UiMessage.Key(UiMessageKey.PartitionTableAmbiguous),
                    ),
                )
            state.partitionTableDamaged ->
                ReduceResult(
                    state.copy(statusMessage = UiMessage.Key(UiMessageKey.GptPrimaryDamaged)),
                )
            device == null || !state.actionsEnabled || !state.tableValid || !valid ->
                ReduceResult(
                    state.copy(statusMessage = UiMessage.Key(UiMessageKey.InvalidFreeRegion)),
                )
            nameProblem != null -> ReduceResult(state.copy(statusMessage = nameProblem))
            formatProblem != null -> ReduceResult(state.copy(statusMessage = formatProblem))
            labelProblem != null -> ReduceResult(state.copy(statusMessage = labelProblem))
            else -> {
                val base = DestructiveConfirmation.request(device, DestructiveAction.CREATE_PARTITION)
                ReduceResult(state.copy(pendingConfirmation = base.copy(
                    createStartLba = intent.startLba,
                    createSectorCount = intent.sectorCount,
                    createName = requestedName,
                    createFormatId = formatId,
                    volumeLabel = VolumeLabel.normalize(intent.volumeLabel),
                )))
            }
        }
    }

    fun requestRenamePartition(
        state: AppUiState,
        intent: UiIntent.RequestRenamePartition,
    ): ReduceResult {
        val device = state.device
        val target = state.selectedPartition()
        val normalized = PartitionName.normalize(intent.name)
        val problem = when {
            device == null || !state.actionsEnabled -> UiReducer.presenceMessage(state.presence)
            target == null -> UiMessage.Key(UiMessageKey.SelectPartitionFirst)
            state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
            state.partitionTableDamaged -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
            !state.tableValid -> UiMessage.Key(UiMessageKey.TableDamaged)
            // Unknown or MBR scheme cannot store a GPT name; never drop it silently.
            state.partitionScheme != TableScheme.GPT ->
                UiMessage.Key(UiMessageKey.PartitionNameUnsupported)
            // Renaming a protected ESP/VTOYEFI entry would drop the name-based write
            // protection, so the EFI override does not lift this refusal.
            target.readOnly -> UiReducer.message(UiMessageKey.ReadOnlyPartition, target.name)
            target.startLba == null || target.sectorCount == null ->
                UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
            normalized.isEmpty() -> UiMessage.Key(UiMessageKey.InvalidPartitionName)
            else -> PartitionName.problem(normalized)?.let {
                UiMessage.Key(UiMessageKey.InvalidPartitionName)
            } ?: if (normalized == target.name) {
                UiMessage.Key(UiMessageKey.RenameSameName)
            } else {
                null
            }
        }
        return if (problem != null || device == null || target == null) {
            ReduceResult(state.copy(statusMessage = problem))
        } else {
            val base = DestructiveConfirmation.request(
                device,
                DestructiveAction.RENAME_PARTITION,
                target.name,
                target = target,
            )
            ReduceResult(
                state.copy(pendingConfirmation = base.copy(partitionName = normalized)),
            )
        }
    }

    fun requestRenameVolumeLabel(
        state: AppUiState,
        intent: UiIntent.RequestRenameVolumeLabel,
    ): ReduceResult {
        val device = state.device
        val target = state.selectedPartition()
        val filesystemId = target?.filesystemId
        val normalized = VolumeLabel.normalize(intent.label)
        val writeBlock = if (state.partitionTableAmbiguous) null else UiReducer.writeBlockMessage(state)
        val problem = when {
            device == null || !state.actionsEnabled -> UiReducer.presenceMessage(state.presence)
            target == null -> UiMessage.Key(UiMessageKey.SelectPartitionFirst)
            state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
            writeBlock != null -> writeBlock
            target.startLba == null || target.sectorCount == null ->
                UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
            filesystemId == null -> UiMessage.Key(UiMessageKey.FilesystemUnknown)
            !FormatCatalog.supportsVolumeLabelRename(filesystemId) ->
                UiReducer.message(UiMessageKey.VolumeLabelUnsupported, FormatCatalog.label(filesystemId))
            normalized.isEmpty() -> UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
            else -> VolumeLabel.problem(normalized, filesystemId)?.let {
                UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
            }
        }
        return if (problem != null || device == null || target == null) {
            ReduceResult(state.copy(statusMessage = problem))
        } else {
            val base = DestructiveConfirmation.request(
                device,
                DestructiveAction.RENAME_VOLUME_LABEL,
                target.name,
                target = target,
            )
            ReduceResult(
                state.copy(pendingConfirmation = base.copy(volumeLabel = normalized)),
            )
        }
    }

    fun requestInitializePartitionTable(
        state: AppUiState,
        intent: UiIntent.RequestInitializePartitionTable,
    ): ReduceResult {
        val device = state.device
        val currentScheme = state.partitionScheme?.toPartitionScheme()
        val snapshot = state.partitions.map { tab ->
            val start = tab.startLba
            val count = tab.sectorCount
            if (start == null || count == null) null else PartitionRef(start, count)
        }
        val snapshotNames = state.partitions.mapNotNull { tab ->
            val start = tab.startLba
            val count = tab.sectorCount
            if (start == null || count == null) null else tab.name.ifBlank { tab.id }
        }
        val problem = when {
            device == null || !state.actionsEnabled -> UiReducer.presenceMessage(state.presence)
            state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
            state.partitionTableDamaged -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
            !state.tableValid -> UiMessage.Key(UiMessageKey.TableDamaged)
            state.rawGeometry?.metadataProblem ==
                RawSchemeProblem.GptBackupMetadataUnknown ->
                UiMessage.Key(UiMessageKey.SchemeBackupMetadataUnknown)
            currentScheme == null -> UiMessage.Key(UiMessageKey.SchemeUnknown)
            currentScheme == intent.scheme -> UiMessage.Key(UiMessageKey.SchemeAlreadyCurrent)
            snapshot.any { it == null } -> UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
            intent.scheme == PartitionScheme.Mbr &&
                device.capacityBytes /
                state.diskSectorSize.coerceAtLeast(1).toULong() >
                UiReducer.mbrAddressLimitSectors() ->
                UiMessage.Key(UiMessageKey.SchemeMbrTooLarge)
            currentScheme == PartitionScheme.Gpt && state.rawGeometry == null ->
                UiMessage.Key(UiMessageKey.SchemeBackupUnverified)
            else -> null
        }
        return if (problem != null || device == null) {
            ReduceResult(state.copy(statusMessage = problem))
        } else {
            val base = DestructiveConfirmation.request(
                device,
                DestructiveAction.INITIALIZE_PARTITION_TABLE,
            )
            ReduceResult(
                state.copy(
                    pendingConfirmation = base.copy(
                        initialScheme = currentScheme,
                        targetScheme = intent.scheme,
                        initialPartitions = snapshot.filterNotNull(),
                        initialPartitionNames = snapshotNames,
                    ),
                ),
            )
        }
    }

    fun requestSetPartitionRole(
        state: AppUiState,
        intent: UiIntent.RequestSetPartitionRole,
    ): ReduceResult =
        requestPartitionEdit(
            state,
            DestructiveAction.SET_PARTITION_ROLE,
            role = intent.role,
        )

    fun requestSetPartitionBootable(
        state: AppUiState,
        intent: UiIntent.RequestSetPartitionBootable,
    ): ReduceResult =
        requestPartitionEdit(
            state,
            DestructiveAction.SET_PARTITION_BOOTABLE,
            bootable = intent.bootable,
        )

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
            device == null || !state.actionsEnabled -> UiReducer.presenceMessage(state.presence)
            target == null -> UiMessage.Key(UiMessageKey.SelectPartitionFirst)
            state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
            state.partitionTableDamaged -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
            !state.tableValid -> UiMessage.Key(UiMessageKey.TableDamaged)
            target.startLba == null || target.sectorCount == null ->
                UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
            !state.isEfiWriteAllowed(target) ->
                UiReducer.message(UiMessageKey.ReadOnlyPartition, target.name)
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
}
