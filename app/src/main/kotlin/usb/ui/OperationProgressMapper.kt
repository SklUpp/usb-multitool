package usb.ui

import usb.service.OperationToken
import usb.operation.OperationState as ServiceOperationState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import usb.multitool.R
import usb.formatBinaryBytesPerSecond
import usb.formatEtaDuration
import usb.operation.DiskOperationPhase
import usb.remainingSeconds

/** One service outcome, normalized once: UI state + typed failure. */
data class OperationResult(
    val state: OperationState,
    val failure: OperationFailure?,
) {
    fun toProgress(
        token: OperationToken,
        title: OperationTitle,
        bytesDone: Long = 0L,
        bytesTotal: Long = 0L,
        bytesPerSecond: Long = 0L,
        cancelable: Boolean = true,
        resultMessage: String? = null,
        readScanResult: ReadScanOutcome? = null,
        smartResult: SmartOutcome? = null,
        rawWriteResult: usb.image.RawWriteResult? = null,
    ): OperationProgress =
        when (state) {
            OperationState.Failed -> OperationProgress(
                state = OperationState.Failed,
                title = title,
                token = token,
                failure = failure,
                cancelable = cancelable,
            )
            OperationState.Cancelled -> OperationProgress(
                state = OperationState.Cancelled,
                title = title,
                token = token,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                resultMessage = resultMessage,
                readScanResult = readScanResult,
                smartResult = smartResult,
                failure = failure,
                cancelable = cancelable,
            )
            OperationState.Partial -> OperationProgress(
                state = OperationState.Partial,
                title = title,
                token = token,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                resultMessage = resultMessage,
                failure = failure,
                readScanResult = readScanResult,
                smartResult = smartResult,
                rawWriteResult = rawWriteResult,
                cancelable = cancelable,
            )
            OperationState.Queued -> OperationProgress(
                state = OperationState.Queued,
                title = title,
                token = token,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                cancelable = cancelable,
            )
            OperationState.Running -> OperationProgress(
                state = OperationState.Running,
                title = title,
                token = token,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                bytesPerSecond = bytesPerSecond,
                cancelable = cancelable,
            )
            OperationState.Completed -> OperationProgress(
                state = OperationState.Completed,
                title = title,
                token = token,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                resultMessage = resultMessage,
                failure = failure,
                readScanResult = readScanResult,
                smartResult = smartResult,
                rawWriteResult = rawWriteResult,
                cancelable = cancelable,
            )
        }
}

fun ServiceOperationState.toOperationResult(operationFailure: Throwable? = null): OperationResult {
    val state = when (this) {
        is ServiceOperationState.Queued -> OperationState.Queued
        is ServiceOperationState.Running -> OperationState.Running
        is ServiceOperationState.Completed -> OperationState.Completed
        is ServiceOperationState.Cancelled -> OperationState.Cancelled
        is ServiceOperationState.Partial -> OperationState.Partial
        is ServiceOperationState.Failed -> OperationState.Failed
    }
    val failure =
        (this as? ServiceOperationState.Failed)?.cause?.toOperationFailure()
            ?: operationFailure.toOperationFailure()
    return OperationResult(state = state, failure = failure)
}



/** Single source of presentation logic for operations across UI surfaces. */
object OperationPresentation {
    @Composable
    fun label(title: OperationTitle): String =
        when (title) {
            is OperationTitle.Destructive -> title.action.statusTitle()
            OperationTitle.ProbeDevice -> stringResource(R.string.op_probe)
            OperationTitle.ReadScan -> stringResource(R.string.op_read_scan)
            OperationTitle.SmartRead -> stringResource(R.string.op_smart_read)

            OperationTitle.RescueBackup -> stringResource(R.string.op_rescue_backup)
            OperationTitle.GptCriticalBackup -> stringResource(R.string.op_gpt_backup)
            OperationTitle.RefreshPartitions -> stringResource(R.string.op_refresh)
            OperationTitle.EjectDevice -> stringResource(R.string.op_eject)
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
            OperationTitle.WriteUnattendXml -> stringResource(R.string.op_write_unattend_xml)
            OperationTitle.ReadVentoyGlobalSettings ->
                stringResource(R.string.op_read_ventoy_global_settings)
            OperationTitle.ListVentoyFolders -> stringResource(R.string.op_list_ventoy_folders)
            OperationTitle.SetVentoyGlobalSetting ->
                stringResource(R.string.op_set_ventoy_global_setting)
            OperationTitle.BindImagesToUnattendXml ->
                stringResource(R.string.op_bind_images_unattend)
            OperationTitle.UnbindImageFromUnattendXml ->
                stringResource(R.string.op_unbind_image_unattend)
            is OperationTitle.ListPath -> stringResource(R.string.op_list, normalizeFatPath(title.path))
            is OperationTitle.NewFolder -> stringResource(R.string.op_new_folder, title.name)
            is OperationTitle.DeleteEntry -> stringResource(R.string.op_delete, title.name)
            is OperationTitle.RenameEntry -> stringResource(R.string.op_rename, title.from)
            is OperationTitle.DownloadEntry -> stringResource(R.string.op_download, title.name)
            is OperationTitle.DownloadBatch -> stringResource(R.string.op_download_batch, title.count)
            is OperationTitle.UploadBatch -> stringResource(R.string.op_upload_batch, title.count)
            is OperationTitle.DeleteBatch -> stringResource(R.string.op_delete_batch, title.count)
            is OperationTitle.StageForRawWrite -> stringResource(R.string.op_stage, title.name)
            is OperationTitle.FormatTarget -> stringResource(R.string.op_format, title.target)
            is OperationTitle.ImportTarget -> stringResource(R.string.op_import, title.targetName)
        }

    fun tab(title: OperationTitle): ShellTab? = title.originTab()

    fun isCancelable(progress: OperationProgress?): Boolean =
        progress != null && progress.cancelable &&
            (progress.state == OperationState.Running || progress.state == OperationState.Queued)

    fun cancelToken(
        progress: OperationProgress?,
        ownerTab: ShellTab? = null,
        matchesTitle: (OperationTitle) -> Boolean = { true },
    ): OperationToken? =
        progress?.takeIf {
            isCancelable(it) &&
                (ownerTab == null || tab(it.title) == ownerTab) &&
                matchesTitle(it.title)
        }?.token

    @Composable
    fun stateLabel(state: OperationState, phase: DiskOperationPhase? = null): String =
        phase?.let {
            stringResource(
                when (it) {
                    DiskOperationPhase.Write -> R.string.op_phase_write
                    DiskOperationPhase.Sync -> R.string.op_phase_sync
                    DiskOperationPhase.Verify -> R.string.op_phase_verify
                },
            )
        } ?: operationStateLabel(state)

    @androidx.annotation.StringRes
    fun failureTextRes(failure: OperationFailure): Int =
        when (failure) {
            OperationFailure.Cancelled -> R.string.msg_failure_cancelled
            OperationFailure.AttachmentChanged -> R.string.msg_failure_attachment_changed
            OperationFailure.SizeMismatch -> R.string.msg_failure_size_mismatch
            OperationFailure.SafIo -> R.string.msg_failure_io
            OperationFailure.ProviderRejected -> R.string.msg_failure_provider_rejected
        }

    @Composable
    fun failureText(failure: OperationFailure): String =
        stringResource(failureTextRes(failure))

    @Composable
    fun lines(progress: OperationProgress): List<String> = operationProgressLines(progress)
}

/** Maps an operation progress to human-readable inspection or error lines. */
@Composable
fun operationProgressLines(progress: OperationProgress): List<String> = buildList {
    when {
        progress.title == OperationTitle.SelfTest -> {
            progress.resultMessage?.lines()?.filter { it.isNotBlank() }?.let(::addAll)
        }
        progress.title == OperationTitle.ReadScan -> {
            progress.readScanResult?.let { add(readScanSummary(it)) }
        }
        progress.title == OperationTitle.SmartRead -> {
            progress.smartResult?.let { addAll(smartInspectionLines(it)) }
        }
        else -> {
            // Structured outcome first; Cancelled failure never hides resultMessage.
            progress.resultMessage?.let { add(normalizeProviderText(it)) }
                ?: progress.failure
                    ?.takeIf { it != OperationFailure.Cancelled }
                    ?.let { add(OperationPresentation.failureText(it)) }
        }
    }
}

/**
 * Shared composable to render operation state, progress bar, progress facts,
 * detail lines, and an optional cancel button.
 */
@Composable
fun OperationInfo(
    operation: OperationProgress,
    showDetails: Boolean = false,
    modifier: Modifier = Modifier,
    onCancel: ((OperationToken) -> Unit)? = null,
) {
    val failed = operation.state == OperationState.Failed || operation.state == OperationState.Partial
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${OperationPresentation.label(operation.title)}: ${OperationPresentation.stateLabel(operation.state, operation.phase)}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (operation.bytesTotal > 0L) {
            LinearProgressIndicator(
                progress = { operation.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 4.dp),
            )
            Text(
                text = "${DeviceIdentity.formatCapacity(operation.bytesDone.coerceAtLeast(0L).toULong())} / ${DeviceIdentity.formatCapacity(operation.bytesTotal.toULong())}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val metric = if (operation.bytesTotal > 0L) "${(operation.fraction * 100).toInt()}%" else null
            val speed = operation.takeIf { it.state == OperationState.Running && it.bytesPerSecond > 0L }
                ?.let { formatBinaryBytesPerSecond(it.bytesPerSecond) }
            val eta = if (operation.state == OperationState.Running) {
                remainingSeconds(operation.bytesDone, operation.bytesTotal, operation.bytesPerSecond)
                    ?.let { stringResource(R.string.op_eta, formatEtaDuration(it)) }
            } else {
                null
            }
            val progressFacts = operationProgressFacts(metric, speed, eta)
            if (progressFacts.isNotEmpty()) {
                Text(
                    text = progressFacts,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val lines = operationProgressLines(operation)
        if (showDetails && lines.isNotEmpty()) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OperationPresentation.cancelToken(operation)?.let { token ->
            if (onCancel != null) {
                TextButton(
                    onClick = { onCancel(token) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
