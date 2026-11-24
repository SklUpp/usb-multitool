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

/** Maps read-only operation outcome to UI progress without touching operation ownership. */
fun readOnlyOperationProgress(
    token: OperationToken,
    outcome: ServiceOperationState,
    title: OperationTitle,
    bytesDone: Long,
    bytesTotal: Long,
    resultMessage: String?,
    failureCause: Throwable?,
    cancelable: Boolean,
    bytesPerSecond: Long = 0L,
    readScanResult: ReadScanOutcome? = null,
    smartResult: SmartOutcome? = null,
): OperationProgress = when (outcome) {
    is ServiceOperationState.Failed -> OperationProgress(
        state = OperationState.Failed,
        title = title,
        token = token,
        errorMessage = outcome.cause.message ?: outcome.cause.javaClass.simpleName,
        cancelable = cancelable,
    )
    is ServiceOperationState.Cancelled -> OperationProgress(
        state = OperationState.Cancelled,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        readScanResult = readScanResult,
        smartResult = smartResult,
        cancelable = cancelable,
    )
    is ServiceOperationState.Partial -> OperationProgress(
        state = OperationState.Partial,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        errorMessage = failureCause?.message ?: failureCause?.javaClass?.simpleName,
        readScanResult = readScanResult,
        smartResult = smartResult,
        cancelable = cancelable,
    )
    is ServiceOperationState.Queued -> OperationProgress(
        state = OperationState.Queued,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        cancelable = cancelable,
    )
    is ServiceOperationState.Running -> OperationProgress(
        state = OperationState.Running,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        bytesPerSecond = bytesPerSecond,
        cancelable = cancelable,
    )
    is ServiceOperationState.Completed -> OperationProgress(
        state = OperationState.Completed,
        title = title,
        token = token,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        resultMessage = resultMessage,
        readScanResult = readScanResult,
        smartResult = smartResult,
        cancelable = cancelable,
    )
}

/** Maps every service state to its UI state without coordinator-specific branching. */
fun serviceOperationUiState(state: ServiceOperationState): OperationState = when (state) {
    is ServiceOperationState.Queued -> OperationState.Queued
    is ServiceOperationState.Running -> OperationState.Running
    is ServiceOperationState.Completed -> OperationState.Completed
    is ServiceOperationState.Cancelled -> OperationState.Cancelled
    is ServiceOperationState.Partial -> OperationState.Partial
    is ServiceOperationState.Failed -> OperationState.Failed
}

/** Prefer terminal failure message, then operation.failureCause, then simple class name. */
fun serviceOperationErrorMessage(
    state: ServiceOperationState,
    operationFailure: Throwable? = null,
): String? =
    (state as? ServiceOperationState.Failed)?.cause?.message
        ?: operationFailure?.message
        ?: (state as? ServiceOperationState.Failed)?.cause?.javaClass?.simpleName
        ?: operationFailure?.javaClass?.simpleName


/** Single source of presentation logic for operations across UI surfaces. */
object OperationPresentation {
    @Composable
    fun label(progress: OperationProgress): String = progress.title.localized()

    @Composable
    fun label(title: OperationTitle): String = title.localized()

    fun tab(title: OperationTitle): ShellTab? = title.originTab()

    fun isCancelable(progress: OperationProgress?): Boolean =
        progress != null && progress.cancelable &&
            (progress.state == OperationState.Running || progress.state == OperationState.Queued)

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

    @Composable
    fun lines(progress: OperationProgress): List<String> = operationProgressLines(progress)
}

/** Maps an operation progress to human-readable inspection or error lines. */
@Composable
fun operationProgressLines(progress: OperationProgress): List<String> = buildList {
    when {
        progress.title == OperationTitle.SelfTest -> {
            progress.errorMessage?.lines()?.filter { it.isNotBlank() }?.let(::addAll)
                ?: progress.resultMessage?.lines()?.filter { it.isNotBlank() }?.let(::addAll)
        }
        progress.title == OperationTitle.ReadScan -> {
            progress.readScanResult?.let { add(readScanSummary(it)) }
        }
        progress.title == OperationTitle.SmartRead -> {
            progress.smartResult?.let { addAll(smartInspectionLines(it)) }
        }
        else -> {
            (progress.errorMessage ?: progress.resultMessage)?.let {
                add(normalizeProviderText(it))
            }
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
    onCancel: (() -> Unit)? = null,
) {
    val failed = operation.state == OperationState.Failed || operation.state == OperationState.Partial
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${OperationPresentation.label(operation)}: ${OperationPresentation.stateLabel(operation.state, operation.phase)}",
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
        if (onCancel != null && OperationPresentation.isCancelable(operation)) {
            TextButton(
                onClick = onCancel,
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
