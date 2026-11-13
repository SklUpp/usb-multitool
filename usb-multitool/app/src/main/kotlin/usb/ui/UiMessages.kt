package usb.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import usb.formatBinaryBytesPerSecond
import usb.fs.FormatCatalog
import usb.multitool.R
import usb.ventoy.AutounattendProblem

/** Localized text for authored reducer/status messages. [UiMessage.Raw] is shown as-is. */
@Composable
fun UiMessage.localized(): String =
    when (this) {
        is UiMessage.Raw -> text
        is UiMessage.ReadScan -> readScanSummary(outcome)
        is UiMessage.InvalidTargetLayout -> {
            val detail = StringBuilder()
            for ((index, problem) in problems.distinct().withIndex()) {
                if (index > 0) detail.append("; ")
                detail.append(autounattendProblemText(problem))
            }
            stringResource(R.string.msg_target_layout_invalid, detail.toString())
        }
        is UiMessage.Key -> when (key) {
            UiMessageKey.NoDevice -> stringResource(R.string.msg_no_device)
            UiMessageKey.PermissionRequired -> stringResource(R.string.msg_permission_required)
            UiMessageKey.DeviceBusy -> stringResource(R.string.msg_device_busy)
            UiMessageKey.DeviceDisconnected -> stringResource(R.string.msg_device_disconnected)
            UiMessageKey.Ready -> stringResource(R.string.msg_ready)
            UiMessageKey.WaitForOperation -> stringResource(R.string.msg_wait_for_operation)
            UiMessageKey.FormatUnsupported ->
                stringResource(R.string.msg_format_unsupported, *args.toTypedArray())
            UiMessageKey.InvalidFreeRegion -> stringResource(R.string.msg_invalid_free_region)
            UiMessageKey.RawRangeInvalid -> stringResource(R.string.msg_raw_range_invalid)
            UiMessageKey.NoIsoForLink -> stringResource(R.string.msg_no_iso_for_link)
            UiMessageKey.NoDeviceForLink -> stringResource(R.string.msg_no_device_for_link)
            UiMessageKey.LinkMismatch -> stringResource(R.string.msg_link_mismatch)
            UiMessageKey.LinkOverwriteNeeded -> stringResource(R.string.msg_link_overwrite_needed)
            UiMessageKey.EfiPartitionMissing ->
                stringResource(R.string.msg_efi_partition_missing, *args.toTypedArray())
            UiMessageKey.EfiGeometryUnknown -> stringResource(R.string.msg_efi_geometry_unknown)
            UiMessageKey.StagingFile ->
                stringResource(R.string.msg_staging_file, *args.toTypedArray())
            UiMessageKey.CannotChangeStaged -> stringResource(R.string.msg_cannot_change_staged)
            UiMessageKey.StagedImageReady ->
                stringResource(R.string.msg_staged_image_ready, *args.toTypedArray())
            UiMessageKey.CannotClearStaged -> stringResource(R.string.msg_cannot_clear_staged)
            UiMessageKey.StagedCleared -> stringResource(R.string.msg_staged_cleared)
            UiMessageKey.OperationInProgress -> stringResource(R.string.msg_operation_in_progress)
            UiMessageKey.SelfTestStarting -> stringResource(R.string.msg_self_test_starting)
            UiMessageKey.InvalidFolderName -> stringResource(R.string.msg_invalid_folder_name)
            UiMessageKey.SelectEntryToDelete -> stringResource(R.string.msg_select_entry_to_delete)
            UiMessageKey.SelectFileToDownload -> stringResource(R.string.msg_select_file_to_download)
            UiMessageKey.SelectPartitionFirst -> stringResource(R.string.msg_select_partition_first)
            UiMessageKey.ReadOnlyPartition ->
                stringResource(R.string.msg_read_only_partition, *args.toTypedArray())
            UiMessageKey.SelectedFileMissing -> stringResource(R.string.msg_selected_file_missing)
            UiMessageKey.FilesPickActive -> stringResource(R.string.msg_files_pick_active)
            UiMessageKey.FilesPickTargetInvalid ->
                stringResource(R.string.msg_files_pick_target_invalid)
            UiMessageKey.VentoyDataPartitionMissing ->
                stringResource(R.string.msg_ventoy_data_partition_missing)
            UiMessageKey.ImportInspectionBusy ->
                stringResource(R.string.msg_import_inspection_busy)
            UiMessageKey.ImportPickerBusy ->
                stringResource(R.string.msg_import_picker_busy)
            UiMessageKey.ImportInspectionCancelling ->
                stringResource(R.string.msg_import_inspection_cancelling)
            UiMessageKey.ImportPickerTimedOut ->
                stringResource(R.string.msg_import_picker_timed_out)
            UiMessageKey.ImportPickerExhausted ->
                stringResource(R.string.msg_import_picker_exhausted)
            UiMessageKey.ImportPickerUnavailable ->
                stringResource(R.string.msg_import_picker_unavailable)
            UiMessageKey.ImageCleared -> stringResource(R.string.msg_image_cleared)
            UiMessageKey.ImageReady ->
                stringResource(R.string.msg_image_ready, *args.toTypedArray())
            UiMessageKey.WritingXml -> stringResource(R.string.msg_writing_xml, *args.toTypedArray())
            UiMessageKey.BackupCleared -> stringResource(R.string.msg_backup_cleared)
            UiMessageKey.BackupReady -> stringResource(R.string.msg_backup_ready, *args.toTypedArray())
            UiMessageKey.RescueImageCleared -> stringResource(R.string.msg_rescue_image_cleared)
            UiMessageKey.RescueImageReady ->
                stringResource(R.string.msg_rescue_image_ready, *args.toTypedArray())
            UiMessageKey.RescueMapCleared -> stringResource(R.string.msg_rescue_map_cleared)
            UiMessageKey.RescueMapReady ->
                stringResource(R.string.msg_rescue_map_ready, *args.toTypedArray())
            UiMessageKey.ChooseGptBackup -> stringResource(R.string.msg_choose_gpt_backup)
            UiMessageKey.ChooseRescueFiles -> stringResource(R.string.msg_choose_rescue_files)
            UiMessageKey.ChooseBackupFolder -> stringResource(R.string.msg_choose_backup_folder)
            UiMessageKey.BackupFolderReady ->
                stringResource(R.string.msg_backup_folder_ready, *args.toTypedArray())
            UiMessageKey.BackupFolderPermissionLost ->
                stringResource(R.string.msg_backup_folder_permission_lost)
            UiMessageKey.BackupFolderTargetChanged ->
                stringResource(R.string.msg_backup_folder_target_changed)
            UiMessageKey.BackupFolderActionLost ->
                stringResource(R.string.msg_backup_folder_action_lost)
            UiMessageKey.BackupFolderTargetUnverifiable ->
                stringResource(R.string.msg_backup_folder_target_unverifiable)
            UiMessageKey.CheckingArchive -> stringResource(R.string.msg_checking_archive)
            UiMessageKey.ArchiveEmpty -> stringResource(R.string.msg_archive_empty)
            UiMessageKey.ArchiveMissingImage -> stringResource(R.string.msg_archive_missing_image)
            UiMessageKey.ArchiveMissingMap -> stringResource(R.string.msg_archive_missing_map)
            UiMessageKey.ArchiveMultipleImages ->
                stringResource(R.string.msg_archive_multiple_images, *args.toTypedArray())
            UiMessageKey.ArchiveUnexpectedFiles ->
                stringResource(R.string.msg_archive_unexpected_files, *args.toTypedArray())
            UiMessageKey.ArchiveUnreadable ->
                stringResource(R.string.msg_archive_unreadable, *args.toTypedArray())
            UiMessageKey.SourceInspectionRejected ->
                stringResource(R.string.msg_source_inspection_rejected, *args.toTypedArray())
            UiMessageKey.RescueMapSectorSizeMismatch ->
                stringResource(R.string.msg_rescue_map_sector_size_mismatch, *args.toTypedArray())
            UiMessageKey.RescueMapDiskTooSmall ->
                stringResource(R.string.msg_rescue_map_disk_too_small, *args.toTypedArray())
            UiMessageKey.RescueMapImageTooShort ->
                stringResource(
                    R.string.msg_rescue_map_image_too_short,
                    *args.toTypedArray(),
                )
            UiMessageKey.RescueMapImageGeometryOverflow ->
                stringResource(R.string.msg_rescue_map_image_geometry_overflow)
            UiMessageKey.RescueMapPartialCopy ->
                stringResource(R.string.msg_rescue_map_partial_copy, *args.toTypedArray())
            UiMessageKey.GptBackupSectorSizeMismatch ->
                stringResource(R.string.msg_gpt_backup_sector_size_mismatch, *args.toTypedArray())
            UiMessageKey.GptBackupDiskMismatch ->
                stringResource(R.string.msg_gpt_backup_disk_mismatch, *args.toTypedArray())
            UiMessageKey.SelectImageFirst -> stringResource(R.string.msg_select_image_first)
            UiMessageKey.CorruptTableCreate -> stringResource(R.string.msg_corrupt_table_create)
            UiMessageKey.VentoyStoreUnavailable ->
                stringResource(R.string.msg_ventoy_store_unavailable)
            UiMessageKey.Probing -> stringResource(R.string.msg_probing)
            UiMessageKey.NoPartitionsFound -> stringResource(R.string.msg_no_partitions_found)
            UiMessageKey.ProbeCancelled -> stringResource(R.string.msg_probe_cancelled)
            UiMessageKey.ProbeFailed -> stringResource(R.string.msg_probe_failed, *args.toTypedArray())
            UiMessageKey.SavedFile -> stringResource(R.string.msg_saved_file, *args.toTypedArray())
            UiMessageKey.UploadCleanupFailed ->
                stringResource(R.string.msg_upload_cleanup_failed, *args.toTypedArray())
            UiMessageKey.StagingLeftovers ->
                stringResource(R.string.msg_staging_leftovers, *args.toTypedArray())
            UiMessageKey.StaleStagedImage -> stringResource(R.string.msg_stale_staged_image)
            UiMessageKey.CannotDeleteStaging ->
                stringResource(R.string.msg_cannot_delete_staging, *args.toTypedArray())
            UiMessageKey.CannotStage -> stringResource(R.string.msg_cannot_stage)
            UiMessageKey.CannotReadUsb ->
                stringResource(R.string.msg_cannot_read_usb, *args.toTypedArray())
            UiMessageKey.CannotReadPartitionTable ->
                stringResource(R.string.msg_cannot_read_partition_table, *args.toTypedArray())
            UiMessageKey.FolderListChanged -> stringResource(R.string.msg_folder_list_changed)
            UiMessageKey.CreatedFolder ->
                stringResource(R.string.msg_created_folder, *args.toTypedArray())
            UiMessageKey.DeletedPath -> stringResource(R.string.msg_deleted_path, *args.toTypedArray())
            UiMessageKey.UploadedFile -> stringResource(R.string.msg_uploaded_file, *args.toTypedArray())
            UiMessageKey.CannotOpenUsb ->
                stringResource(R.string.msg_cannot_open_usb, *args.toTypedArray())
            UiMessageKey.VolumeSyncUnconfirmed ->
                stringResource(R.string.msg_volume_sync_unconfirmed, *args.toTypedArray())
            UiMessageKey.DeleteStagingFailed ->
                stringResource(R.string.msg_delete_staging_failed, *args.toTypedArray())
            UiMessageKey.InvalidEntryName -> stringResource(R.string.msg_invalid_entry_name)
            UiMessageKey.RenameSameName -> stringResource(R.string.msg_rename_same_name)
            UiMessageKey.RenamedPath ->
                stringResource(R.string.msg_renamed_path, *args.toTypedArray())
            UiMessageKey.TargetChanged -> stringResource(R.string.msg_target_changed)
            UiMessageKey.TableDamaged -> stringResource(R.string.msg_table_damaged)
            UiMessageKey.PartitionGeometryUnknown ->
                stringResource(R.string.msg_partition_geometry_unknown)
            UiMessageKey.GptPrimaryDamaged -> stringResource(R.string.msg_gpt_primary_damaged)
            UiMessageKey.PartitionTableAmbiguous ->
                stringResource(R.string.msg_partition_table_ambiguous)
            UiMessageKey.PartitionNameUnsupported ->
                stringResource(R.string.msg_partition_name_unsupported)
            UiMessageKey.InvalidPartitionName ->
                stringResource(R.string.msg_invalid_partition_name, *args.toTypedArray())
            UiMessageKey.InvalidVolumeLabel ->
                stringResource(R.string.msg_invalid_volume_label, *args.toTypedArray())
            UiMessageKey.VolumeLabelUnsupported ->
                stringResource(R.string.msg_volume_label_unsupported, *args.toTypedArray())
            UiMessageKey.FilesystemUnknown -> stringResource(R.string.msg_filesystem_unknown)
            UiMessageKey.SchemeAlreadyCurrent ->
                stringResource(R.string.msg_scheme_already_current)
            UiMessageKey.SchemeUnknown -> stringResource(R.string.msg_scheme_unknown)
            UiMessageKey.GptRepairRequiresGpt ->
                stringResource(R.string.msg_gpt_repair_requires_gpt)
            UiMessageKey.SchemeBackupMetadataUnknown ->
                stringResource(R.string.msg_scheme_backup_metadata_unknown)
            UiMessageKey.SchemeBackupUnverified ->
                stringResource(R.string.msg_scheme_backup_unverified)
            UiMessageKey.SchemeMbrTooLarge -> stringResource(R.string.msg_scheme_mbr_too_large)
            UiMessageKey.Success -> stringResource(R.string.msg_success)
            UiMessageKey.CancelledStatus -> stringResource(R.string.msg_cancelled)
            UiMessageKey.FailedStatus -> stringResource(R.string.msg_failed)
            UiMessageKey.PartialStatus -> stringResource(R.string.msg_partial)
            UiMessageKey.RunningStatus -> stringResource(R.string.msg_running)
            UiMessageKey.QueuedStatus -> stringResource(R.string.msg_queued)
            UiMessageKey.XmlOverwriteRequired ->
                stringResource(R.string.msg_xml_overwrite_required)
            UiMessageKey.ConfirmXmlOverwrite ->
                stringResource(R.string.msg_confirm_xml_overwrite)
            UiMessageKey.LogCopied -> stringResource(R.string.msg_log_copied)
            UiMessageKey.SettingsSourceInvalid ->
                stringResource(R.string.msg_settings_source_invalid)
            UiMessageKey.SettingsSourceStale ->
                stringResource(R.string.msg_settings_source_stale)
            UiMessageKey.SettingsSourceUnavailable ->
                stringResource(R.string.msg_settings_source_unavailable)
        }
    }

/** Localized target-layout problem; the single mapping for dialogs and status messages. */
@Composable
internal fun autounattendProblemText(problem: AutounattendProblem): String =
    stringResource(
        when (problem) {
            AutounattendProblem.BadLanguageTag -> R.string.unattend_problem_language
            AutounattendProblem.BadComputerName -> R.string.unattend_problem_computer
            AutounattendProblem.BadAccountName -> R.string.unattend_problem_account
            AutounattendProblem.BadPartitionLabel -> R.string.unattend_problem_partition_label
            AutounattendProblem.LayoutOverlaps -> R.string.unattend_problem_layout_overlap
            AutounattendProblem.LayoutTooLarge -> R.string.unattend_problem_layout_size
            AutounattendProblem.LayoutSizeOverflow -> R.string.unattend_problem_layout_overflow
            AutounattendProblem.LayoutExceedsDiskSize -> R.string.unattend_problem_layout_exceeds
            AutounattendProblem.LayoutDiskSizeMissing ->
                R.string.unattend_problem_layout_disk_size_missing
            AutounattendProblem.LayoutIncomplete -> R.string.unattend_problem_layout_incomplete
            AutounattendProblem.LayoutImageInstallNotSupported ->
                R.string.unattend_problem_image_install
        },
    )

/** Formats structured scan numbers with current Compose locale resources. */
@Composable
fun readScanSummary(outcome: ReadScanOutcome): String {
    val speed = outcome.averageBytesPerSecond?.let { formatBinaryBytesPerSecond(it) }
        ?: stringResource(R.string.diagnostics_read_speed_unavailable)
    return stringResource(
        R.string.msg_read_scan_result,
        outcome.sectorsRead.toString(),
        outcome.totalSectors.toString(),
        outcome.unreadableSectors.toString(),
        outcome.failedRangeCount,
        speed,
    )
}


@Composable
fun DestructiveAction.label(): String =
    when (this) {
        DestructiveAction.WIPE -> stringResource(R.string.action_wipe)
        DestructiveAction.VENTOY_INSTALL -> stringResource(R.string.action_ventoy_install)
        DestructiveAction.VENTOY_UPDATE -> stringResource(R.string.action_ventoy_update)
        DestructiveAction.RAW_WRITE -> stringResource(R.string.action_raw_write)
        DestructiveAction.FORMAT_PARTITION -> stringResource(R.string.action_format_partition)
        DestructiveAction.DELETE_PARTITION -> stringResource(R.string.action_delete_partition)
        DestructiveAction.CREATE_PARTITION -> stringResource(R.string.action_create_partition)
        DestructiveAction.GPT_REPAIR -> stringResource(R.string.action_gpt_repair)
        DestructiveAction.GPT_RESTORE -> stringResource(R.string.action_gpt_restore)
        DestructiveAction.RESCUE_RESTORE -> stringResource(R.string.action_rescue_restore)
        DestructiveAction.VENTOY_LINK_XML -> stringResource(R.string.action_ventoy_link_xml)
        DestructiveAction.SET_PARTITION_ROLE -> stringResource(R.string.action_set_partition_role)
        DestructiveAction.SET_PARTITION_BOOTABLE ->
            stringResource(R.string.action_set_partition_bootable)
        DestructiveAction.RENAME_PARTITION -> stringResource(R.string.action_rename_partition)
        DestructiveAction.RENAME_VOLUME_LABEL ->
            stringResource(R.string.action_rename_volume_label)
        DestructiveAction.INITIALIZE_PARTITION_TABLE ->
            stringResource(R.string.action_initialize_table)
        DestructiveAction.VENTOY_UNATTEND_XML_WRITE ->
            stringResource(R.string.action_ventoy_unattend_write)
        DestructiveAction.VENTOY_UNATTEND_DISK_LAYOUT ->
            stringResource(R.string.action_ventoy_unattend_layout)
    }

/**
 * Localized title for a user-facing operation. Technical log lines keep
 * [OperationProgress.label] / [OperationTitle.logLabel] unchanged.
 */
@Composable
fun operationLabel(progress: OperationProgress): String = progress.title.localized()

@Composable
fun OperationTitle.localized(): String =
    when (this) {
        is OperationTitle.Destructive -> action.label()
        OperationTitle.ProbeDevice -> stringResource(R.string.op_probe)
        OperationTitle.ReadScan -> stringResource(R.string.op_read_scan)
        OperationTitle.RescueBackup -> stringResource(R.string.op_rescue_backup)
        OperationTitle.GptCriticalBackup -> stringResource(R.string.op_gpt_backup)
        OperationTitle.RefreshPartitions -> stringResource(R.string.op_refresh)
        OperationTitle.SelfTest -> stringResource(R.string.op_self_test)
        OperationTitle.SelfTestRunning -> stringResource(R.string.op_self_test_running)
        OperationTitle.Upload -> stringResource(R.string.op_upload)
        OperationTitle.AutounattendXmlLink -> stringResource(R.string.op_link_xml)
        OperationTitle.CheckRawRange -> stringResource(R.string.op_check_raw_range)
        OperationTitle.ListRawSources -> stringResource(R.string.op_list_raw_sources)
        OperationTitle.ListDataPartitionFiles ->
            stringResource(R.string.op_list_data_partition_files)
        OperationTitle.ReadXmlBindings -> stringResource(R.string.op_read_xml_bindings)
        OperationTitle.RemoveXmlBindings -> stringResource(R.string.op_remove_xml_bindings)
        OperationTitle.SaveGlobalWindowsControls ->
            stringResource(R.string.op_save_global_windows_controls)
        OperationTitle.ReadUnattendXml -> stringResource(R.string.op_read_unattend_xml)
        OperationTitle.ListVentoyImages -> stringResource(R.string.op_list_ventoy_images)
        OperationTitle.GenerateUnattendXml -> stringResource(R.string.op_generate_unattend_xml)
        OperationTitle.ReadVentoyGlobalSettings ->
            stringResource(R.string.op_read_ventoy_global_settings)
        OperationTitle.ListVentoyFolders -> stringResource(R.string.op_list_ventoy_folders)
        OperationTitle.SetVentoyGlobalSetting ->
            stringResource(R.string.op_set_ventoy_global_setting)
        OperationTitle.BindImagesToUnattendXml ->
            stringResource(R.string.op_bind_images_unattend)
        OperationTitle.UnbindImageFromUnattendXml ->
            stringResource(R.string.op_unbind_image_unattend)
        is OperationTitle.ListPath -> stringResource(R.string.op_list, path)
        is OperationTitle.NewFolder -> stringResource(R.string.op_new_folder, name)
        is OperationTitle.DeleteEntry -> stringResource(R.string.op_delete, name)
        is OperationTitle.RenameEntry -> stringResource(R.string.op_rename, from)
        is OperationTitle.DownloadEntry -> stringResource(R.string.op_download, name)
        is OperationTitle.StageForRawWrite -> stringResource(R.string.op_stage, name)
        is OperationTitle.FormatTarget -> stringResource(R.string.op_format, target)
        is OperationTitle.ImportTarget -> stringResource(R.string.op_import, targetName)
    }

@Composable
fun operationStateLabel(state: OperationState): String =
    when (state) {
        OperationState.Queued -> stringResource(R.string.op_queued)
        OperationState.Running -> stringResource(R.string.op_running)
        OperationState.Completed -> stringResource(R.string.op_completed)
        OperationState.Cancelled -> stringResource(R.string.op_cancelled)
        OperationState.Failed -> stringResource(R.string.op_failed)
        OperationState.Partial -> stringResource(R.string.op_partial)
    }

/**
 * Text lines for the destructive confirmation. All authored text comes from resources;
 * system/engine details are not part of this dialog.
 */
@Composable
fun confirmationLines(confirmation: DestructiveConfirmation): List<String> {
    val actionLabel =
        if (confirmation.action == DestructiveAction.FORMAT_PARTITION &&
            confirmation.formatId != null
        ) {
            stringResource(
                R.string.confirm_action_format_as,
                confirmation.targetPartitionName ?: confirmation.action.label(),
                FormatCatalog.label(confirmation.formatId),
            )
        } else {
            confirmation.action.label()
        }
    val lines = mutableListOf(
        stringResource(
            R.string.confirm_base,
            actionLabel,
            confirmation.identity.identity,
            confirmation.identity.capacityLabel,
        ),
    )
    // Backup-GPT repair preserves every partition; saying "cannot be undone" would be false.
    if (confirmation.action != DestructiveAction.GPT_REPAIR) {
        lines += stringResource(R.string.confirm_irreversible)
        if (confirmation.action == DestructiveAction.FORMAT_PARTITION) {
            lines += stringResource(R.string.confirm_format_not_cancelable)
        }
    } else {
        lines += stringResource(R.string.confirm_repair_keeps)
        if (confirmation.initialPartitionNames.size == confirmation.initialPartitions.size) {
            confirmation.initialPartitionNames.forEach { name ->
                lines += stringResource(R.string.confirm_repair_kept_partition, name)
            }
        }
    }
    confirmation.ventoyRelease?.let { release ->
        lines += stringResource(R.string.confirm_ventoy_release, release.release)
        if (release is usb.ventoy.VentoyReleaseSource.Downloaded) {
            lines += if (release.provenance.independentlyPinned) {
                stringResource(
                    R.string.confirm_ventoy_provenance,
                    release.provenance.archiveSha256,
                    release.provenance.sourceUrl,
                )
            } else {
                stringResource(
                    R.string.confirm_ventoy_unpinned,
                    release.provenance.sourceUrl,
                )
            }
        }
    }
    confirmation.targetPartitionName?.let {
        lines += stringResource(R.string.confirm_target_partition, it)
    }
    val createCount = confirmation.createSectorCount
    confirmation.createStartLba?.let { start ->
        if (createCount == null || createCount == 0UL) return@let
        val count = createCount
        lines += stringResource(
            R.string.confirm_create_range,
            start.toLong(),
            (start + count - 1UL).toLong(),
            count.toLong(),
        )
    }
    if (confirmation.action == DestructiveAction.CREATE_PARTITION) {
        confirmation.createName?.takeIf { it.isNotBlank() }?.let { name ->
            lines += stringResource(R.string.confirm_create_name, name)
        }
        confirmation.createFormatId?.let { id ->
            lines += stringResource(R.string.confirm_create_format, FormatCatalog.label(id))
        }
        confirmation.volumeLabel.takeIf { it.isNotBlank() }?.let { label ->
            lines += stringResource(R.string.confirm_create_volume_label, label)
        }
    }
    if (confirmation.action == DestructiveAction.RENAME_PARTITION) {
        confirmation.partitionName?.takeIf { it.isNotBlank() }?.let { name ->
            lines += stringResource(R.string.confirm_partition_name, name)
        }
    }
    if (confirmation.action == DestructiveAction.RENAME_VOLUME_LABEL) {
        confirmation.volumeLabel.takeIf { it.isNotBlank() }?.let { label ->
            lines += stringResource(R.string.confirm_volume_label, label)
        }
    }
    confirmation.partitionRole?.let { role ->
        lines += stringResource(
            R.string.confirm_partition_role,
            stringResource(
                if (role == PartitionRole.Efi) {
                    R.string.partition_role_efi
                } else {
                    R.string.partition_role_data
                },
            ),
        )
    }
    confirmation.partitionBootable?.let { bootable ->
        lines += stringResource(
            R.string.confirm_partition_bootable,
            stringResource(
                if (bootable) R.string.answer_yes else R.string.answer_no,
            ),
        )
    }
    if (confirmation.action == DestructiveAction.INITIALIZE_PARTITION_TABLE) {
        confirmation.targetScheme?.let { scheme ->
            lines += stringResource(
                R.string.confirm_initialize_scheme,
                scheme.toTableScheme().name,
            )
        }
        if (confirmation.initialPartitions.isNotEmpty()) {
            if (confirmation.initialPartitionNames.size == confirmation.initialPartitions.size) {
                confirmation.initialPartitionNames.forEach { name ->
                    lines += stringResource(R.string.confirm_erase_partition, name)
                }
            } else {
                lines += stringResource(
                    R.string.confirm_initialize_partitions,
                    confirmation.initialPartitions.size,
                )
            }
        }
    }
    if (confirmation.action == DestructiveAction.RAW_WRITE) {
        val start = confirmation.rawStartLba
        val count = confirmation.rawSectorCount
        if (start != null && count != null && count > 0UL) {
            lines += stringResource(
                R.string.confirm_raw_range,
                start.toLong(),
                (start + count - 1UL).toLong(),
                count.toLong(),
            )
        }
        val rawLabel =
            confirmation.rawSourceName?.takeIf { it.isNotBlank() }
        rawLabel?.let { name ->
            lines += stringResource(R.string.confirm_raw_source, name)
        }
    }
    if (confirmation.action == DestructiveAction.GPT_RESTORE) {
        DestructiveConfirmation.displayLabel(
            confirmation.gptBackupName ?: confirmation.gptBackupUri,
        )?.let { name ->
            lines += stringResource(R.string.confirm_gpt_backup_source, name)
        }
        lines += gptBackupInspectionLines(confirmation.gptBackupInspection)
    }
    if (confirmation.action == DestructiveAction.RESCUE_RESTORE) {
        lines += stringResource(R.string.confirm_rescue_restore, confirmation.identity.identity)
        DestructiveConfirmation.displayLabel(
            confirmation.rescueImageName ?: confirmation.rescueImageUri,
        )?.let { name ->
            lines += stringResource(R.string.confirm_rescue_image_source, name)
        }
        DestructiveConfirmation.displayLabel(
            confirmation.rescueMapName ?: confirmation.rescueMapUri,
        )?.let { name ->
            lines += stringResource(R.string.confirm_rescue_map_source, name)
        }
        confirmation.rescueMapInspection?.summary?.identity?.let { source ->
            if (source != confirmation.identity.identity) {
                lines += stringResource(
                    R.string.confirm_rescue_source_identity,
                    source,
                    confirmation.identity.identity,
                )
            }
        }
        lines += rescueImageInspectionLines(confirmation.rescueImageInspection)
        lines += rescueMapInspectionLines(confirmation.rescueMapInspection)
    }
    if (confirmation.action == DestructiveAction.VENTOY_UNATTEND_XML_WRITE) {
        confirmation.xmlTargetName?.takeIf { it.isNotBlank() }?.let { name ->
            lines += stringResource(R.string.confirm_xml_overwrite, name)
        }
    }
    if (confirmation.action == DestructiveAction.VENTOY_LINK_XML) {
        val iso = confirmation.isoName?.substringAfterLast('/') ?: "ISO"
        lines += stringResource(
            if (confirmation.linkOverwrite) {
                R.string.confirm_link_overwrite
            } else {
                R.string.confirm_link_new
            },
            iso,
        )
    }
    if (confirmation.ventoyControls.isNotEmpty()) {
        val removal = stringResource(R.string.settings_remove)
        lines += stringResource(
            R.string.confirm_ventoy_settings,
            confirmation.ventoyControls.joinToString(", ") {
                "${it.key}=${it.value ?: removal}"
            },
        )
    }
    return lines
}

private const val INSPECTION_RANGE_PREVIEW = 3

@Composable
fun rescueImageInspectionLines(inspection: RescueImageInspection?): List<String> {
    if (inspection == null) {
        return listOf(stringResource(R.string.diagnostics_inspection_unavailable))
    }
    return listOf(inspectionSizeLine(inspection.sizeBytes))
}

@Composable
fun rescueMapInspectionLines(inspection: RescueMapInspection?): List<String> {
    val unavailable = stringResource(R.string.diagnostics_inspection_unavailable)
    if (inspection == null) return listOf(unavailable)
    inspection.error?.let {
        return listOf(stringResource(R.string.diagnostics_inspection_failed, it))
    }
    val summary = inspection.summary ?: return listOf(unavailable)
    val lines = mutableListOf(
        inspectionSizeLine(inspection.sizeBytes),
        stringResource(
            R.string.diagnostics_map_kind,
            stringResource(
                if (summary.kind == usb.diagnostics.MapKind.COPY) {
                    R.string.diagnostics_map_kind_copy
                } else {
                    R.string.diagnostics_map_kind_restore
                },
            ),
        ),
        stringResource(
            R.string.diagnostics_map_totals,
            summary.sectorCount.toString(),
            summary.sectorSize?.toString() ?: stringResource(R.string.diagnostics_map_sector_size_unknown),
        ),
        mapStateLine(R.string.diagnostics_map_copied, summary.copiedRanges),
        mapStateLine(R.string.diagnostics_map_failed, summary.failedRanges),
        mapStateLine(R.string.diagnostics_map_non_tried, summary.nonTriedRanges),
    )
    return lines
}

@Composable
fun gptBackupInspectionLines(inspection: GptBackupInspection?): List<String> {
    val unavailable = stringResource(R.string.diagnostics_inspection_unavailable)
    if (inspection == null) return listOf(unavailable)
    inspection.error?.let {
        return listOf(stringResource(R.string.diagnostics_inspection_failed, it))
    }
    val summary = inspection.summary ?: return listOf(unavailable)
    val lines = mutableListOf(
        inspectionSizeLine(inspection.sizeBytes),
        stringResource(
            R.string.diagnostics_gpt_blob,
            summary.blobBytes.toString(),
            summary.primarySectors.toString(),
            summary.backupSectors.toString(),
            summary.sectorSize.toString(),
        ),
        stringResource(
            R.string.diagnostics_gpt_geometry,
            summary.originalSectorCount.toString(),
            summary.firstUsable.toString(),
            summary.lastUsable.toString(),
        ),
        stringResource(R.string.diagnostics_gpt_partitions, summary.partitions.toString()),
        stringResource(R.string.diagnostics_gpt_valid),
    )
    return lines
}

@Composable
private fun inspectionSizeLine(sizeBytes: Long?): String =
    if (sizeBytes == null || sizeBytes <= 0L) {
        stringResource(R.string.diagnostics_file_size_unknown)
    } else {
        stringResource(
            R.string.diagnostics_file_size,
            DeviceIdentity.formatCapacity(sizeBytes.toULong()),
        )
    }

@Composable
private fun mapStateLine(label: Int, ranges: List<ULongRange>): String {
    val sectors = ranges.sumOf { it.last - it.first + 1UL }
    val shown = ranges.take(INSPECTION_RANGE_PREVIEW).map {
        stringResource(
            R.string.diagnostics_map_range,
            it.first.toString(),
            it.last.toString(),
        )
    }.joinToString(", ")
    val rangesText = when {
        ranges.isEmpty() -> stringResource(R.string.diagnostics_map_no_ranges)
        ranges.size > INSPECTION_RANGE_PREVIEW ->
            shown + " " + stringResource(
                R.string.diagnostics_map_ranges_more,
                (ranges.size - INSPECTION_RANGE_PREVIEW).toString(),
            )
        else -> shown
    }
    return stringResource(
        R.string.diagnostics_map_state,
        stringResource(label),
        sectors.toString(),
        ranges.size.toString(),
        rangesText,
    )
}
