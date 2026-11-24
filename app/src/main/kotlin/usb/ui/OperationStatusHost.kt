package usb.ui

import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.res.Resources
import usb.formatBinaryBytesPerSecond
import usb.formatEtaDuration
import usb.remainingSeconds
import usb.image.ReadbackProblem
import usb.image.ReadbackVerdict
import usb.multitool.R

/**
 * One numeric progress line: percent, throughput and ETA only when measured. Each fact
 * stays separate so a missing speed never hides a known ETA and vice versa.
 */
internal fun operationProgressFacts(metric: String?, speed: String?, eta: String?): String =
    listOfNotNull(metric, speed, eta).filter { it.isNotEmpty() }.joinToString(FACT_SEPARATOR)

/** Field separator shared by every status surface: strip, dialog and journal prefix. */
internal const val FACT_SEPARATOR = ", "

/** Colour family of one status fact; `error` is reserved for real failures. */
internal enum class StatusTone { Neutral, Success, Warning, Error }

@Composable
internal fun StatusTone.contentColor(): Color =
    when (this) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.Success -> MaterialTheme.colorScheme.primary
        // Partial / unverified: tertiary (amber), not error — error is reserved for failures.
        StatusTone.Warning -> MaterialTheme.colorScheme.tertiary
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }

/**
 * One fact set for every surface, in one fixed order: name, state, progress
 * ([operationProgressFacts]), readback verdict, result. Missing values stay null and are
 * omitted by the renderer instead of printing an empty or placeholder line.
 */
private data class OperationStatusFacts(
    val name: String,
    val state: String,
    val progress: String?,
    val readback: String?,
    val readbackTone: StatusTone,
    val result: String?,
    val resultTone: StatusTone,
    val tone: StatusTone,
) {
    /** One-line strip text; the progress dialog owns the percentage. */
    val summary: String get() = listOfNotNull(name, state).joinToString(" — ")
}

internal fun newerStandaloneStatusMessage(
    operation: OperationProgress?,
    statusMessage: UiMessage?,
    statusSequence: Long,
    operationStatusSequence: Long,
): UiMessage? =
    statusMessage?.takeIf {
        operation?.state?.isTerminal() == true &&
            statusSequence > operationStatusSequence
    }

internal fun newerStandaloneStatusMessage(state: AppUiState): UiMessage? =
    newerStandaloneStatusMessage(
        operation = state.operation,
        statusMessage = state.statusMessage,
        statusSequence = state.statusSequence,
        operationStatusSequence = state.operationStatusSequence,
    )

@Composable
private fun rememberOperationStatusFacts(
    operation: OperationProgress?,
    diskSectorSize: Int,
): OperationStatusFacts? {
    if (operation == null) return null
    val name = OperationPresentation.label(operation.title)
    val stateLabel = OperationPresentation.stateLabel(operation.state, operation.phase)
    val resources = LocalResources.current
    // smartSummary / readScanSummary are @Composable; resolve here before remember.
    val failureText = operation.failure
        ?.takeIf { it != OperationFailure.Cancelled }
        ?.let { OperationPresentation.failureText(it) }
    val result = (operation.smartResult?.let { smartSummary(it) }
        ?: operation.readScanResult?.let { readScanSummary(it) }
        ?: if (operation.state == OperationState.Partial) {
            operation.resultMessage ?: failureText
        } else {
            failureText ?: operation.resultMessage
        })
    return remember(operation, diskSectorSize, resources, name, stateLabel, result) {
        computeOperationStatusFacts(
            op = operation,
            diskSectorSize = diskSectorSize,
            resources = resources,
            name = name,
            stateLabel = stateLabel,
            result = result,
        )
    }
}

private fun computeOperationStatusFacts(
    op: OperationProgress,
    diskSectorSize: Int,
    resources: Resources,
    name: String,
    stateLabel: String,
    result: String?,
): OperationStatusFacts {
    val running = op.state == OperationState.Running || op.state == OperationState.Queued
    // The state is its own field, so the metric never falls back to repeating it.
    val metric = when {
        op.bytesTotal > 0L && op.title.reportsCurrentFileBytes() ->
            resources.getString(R.string.op_batch_current_file_pct, (op.fraction * 100).toInt())
        op.bytesTotal > 0L -> "${(op.fraction * 100).toInt()}%"
        op.bytesDone > 0L -> DeviceIdentity.formatCapacity(op.bytesDone.toULong())
        else -> null
    }
    val speed =
        if (running && op.bytesPerSecond > 0L) formatBinaryBytesPerSecond(op.bytesPerSecond) else null
    val etaSeconds =
        if (running) remainingSeconds(op.bytesDone, op.bytesTotal, op.bytesPerSecond) else null
    val eta = etaSeconds?.let { resources.getString(R.string.op_eta, formatEtaDuration(it)) }
    val progress = operationProgressFacts(metric, speed, eta).takeIf { it.isNotEmpty() }
    // Data-integrity result: stays visible after the operation ends, unlike a snackbar.
    val readback = op.rawWriteResult?.let { result ->
        val text = when (val verdict = result.readback) {
            ReadbackVerdict.Verified ->
                if (result.writtenBytes.toULong() == result.range.byteLength(diskSectorSize)) {
                    resources.getString(R.string.raw_readback_verified)
                } else {
                    resources.getString(R.string.raw_readback_verified_partial, result.writtenBytes)
                }
            ReadbackVerdict.Skipped -> resources.getString(R.string.raw_readback_skipped)
            is ReadbackVerdict.Mismatch -> resources.getString(
                R.string.raw_readback_mismatch,
                verdict.mismatchedSectors.toLong(),
                verdict.firstMismatchLba.toLong(),
            )
            is ReadbackVerdict.Unverified -> resources.getString(
                R.string.raw_readback_unverified,
                verdict.range.startLba.toLong(),
                verdict.range.sectorCount.toLong(),
            )
            is ReadbackVerdict.Incomplete -> resources.getString(
                R.string.raw_readback_incomplete,
                resources.getString(
                    when (verdict.reason) {
                        ReadbackProblem.Cancelled -> R.string.raw_readback_reason_cancelled
                        ReadbackProblem.SourceChanged -> R.string.raw_readback_reason_source_changed
                        ReadbackProblem.IoError -> R.string.raw_readback_reason_io_error
                    },
                ),
            )
        }
        val tone = when (result.readback) {
            is ReadbackVerdict.Mismatch, is ReadbackVerdict.Incomplete -> StatusTone.Error
            is ReadbackVerdict.Unverified, ReadbackVerdict.Skipped -> StatusTone.Warning
            ReadbackVerdict.Verified -> StatusTone.Success
        }
        val average = result.averageBytesPerSecond?.let {
            resources.getString(R.string.raw_average_speed, formatBinaryBytesPerSecond(it))
        }
        listOfNotNull(text, average).joinToString(" ") to tone
    }
    val tone = when {
        op.state == OperationState.Failed -> StatusTone.Error
        op.state == OperationState.Cancelled -> StatusTone.Neutral
        op.failure != null && op.failure != OperationFailure.Cancelled -> StatusTone.Error
        op.state == OperationState.Partial -> StatusTone.Warning
        op.state == OperationState.Completed -> StatusTone.Success
        else -> StatusTone.Neutral
    }
    return OperationStatusFacts(
        name = name,
        state = stateLabel,
        progress = progress,
        readback = readback?.first,
        readbackTone = readback?.second ?: StatusTone.Neutral,
        result = result,
        resultTone = when {
            (op.failure != null && op.failure != OperationFailure.Cancelled) ||
                op.state == OperationState.Failed -> StatusTone.Error
            op.state == OperationState.Partial -> StatusTone.Warning
            else -> StatusTone.Neutral
        },
        tone = tone,
    )
}

/**
 * Permanent status block under the header: inset `surfaceContainer*` band (not a card,
 * not an error banner). Whole block is tappable (48 dp). One-line title
 * `Status: &lt;operation&gt; — &lt;state&gt;` (no subtitle, no separate expand control).
 * Idle/standalone use neutral ink. The title announces running operation state and
 * standalone messages, while the progress track remains visible without becoming a live
 * region. Thin rounded progress track only while running. No dividers.
 */
@Composable
internal fun OperationStatusHost(
    operation: OperationProgress?,
    diskSectorSize: Int,
    statusMessage: UiMessage?,
    statusSequence: Long,
    operationStatusSequence: Long,
    modifier: Modifier = Modifier,
) {
    val running = operation?.state == OperationState.Running ||
        operation?.state == OperationState.Queued
    val facts = rememberOperationStatusFacts(operation, diskSectorSize)
    val newerStandaloneText = newerStandaloneStatusMessage(
        operation = operation,
        statusMessage = statusMessage,
        statusSequence = statusSequence,
        operationStatusSequence = operationStatusSequence,
    )?.localized()
    val lineText = newerStandaloneText
        ?: facts?.let { fact ->
            if (running) {
                fact.summary
            } else {
                if (fact.tone == StatusTone.Error) {
                    fact.result ?: fact.readback ?: fact.summary
                } else {
                    fact.readback ?: fact.result ?: fact.summary
                }
            }
        }
        ?: statusMessage?.localized()
    val progressDescription = stringResource(R.string.shell_status_progress)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 4.dp)
            .heightIn(min = 48.dp, max = 76.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.status_dialog_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = lineText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (facts?.tone == StatusTone.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (lineText != null) {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    ),
            )
            if (running) {
                val total = operation?.bytesTotal ?: 0L
                val fraction = (operation?.fraction ?: 0f).coerceIn(0f, 1f)
                val animatedFraction by animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = tween(240),
                    label = "status-progress",
                )
                val percent = "${(animatedFraction * 100f).roundToInt()}%"
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .semantics { contentDescription = progressDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    if (total > 0L) {
                        CircularProgressIndicator(
                            progress = { animatedFraction },
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                    if (total > 0L) {
                        Text(
                            text = percent,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
