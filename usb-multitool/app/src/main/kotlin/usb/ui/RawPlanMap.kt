package usb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import usb.multitool.R
import usb.partition.RawObjectKind
import usb.partition.RawSchemeProblem
import usb.partition.RawRangeGeometry
import usb.partition.SchemeTrust
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Map of the whole medium: existing objects, the chosen start, and the derived range.
 *
 * Only the start is draggable. The end is whatever the decoded image size needs, so the
 * drawing can never disagree with the range that gets confirmed. Dragging snaps the start
 * to confirmed partition edges within [SNAP_TOLERANCE_DP], then clamps it so the image
 * still fits; a snapped start can genuinely begin on a partition edge. Every overlap is
 * also printed as a real list below the track. The numeric start field stays exact.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RawPlanMap(
    geometry: RawRangeGeometry,
    startLba: ULong,
    sectorCount: ULong?,
    onStartChange: (ULong) -> Unit,
    modifier: Modifier = Modifier,
) {
    val disk = geometry.diskSectorCount
    if (disk == 0UL) return
    val confirmed = geometry.trust is SchemeTrust.Confirmed
    val count = sectorCount?.takeIf { it in 1UL..disk }
    val overlaps = count?.let { geometry.overlaps(startLba, it) }.orEmpty()
    val density = LocalDensity.current
    val snapTolerance = SNAP_TOLERANCE_DP.dp

    var widthPx by remember { mutableStateOf(0) }
    val currentConfirmed by rememberUpdatedState(confirmed)
    val currentSnapPoints by rememberUpdatedState(geometry.snapPoints)
    val currentOnChange by rememberUpdatedState(onStartChange)
    val currentCount by rememberUpdatedState(count)

    val rangeColor = MaterialTheme.colorScheme.primary
    // Three tones of one muted hue over the track: secondaryContainer and tertiaryContainer
    // resolve to the track colour itself in both themes, so every existing object vanished.
    val partitionColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val bootColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val tableColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val snapTickColor = MaterialTheme.colorScheme.outline

    val partitionLabels = remember(geometry) {
        val parts = geometry.objects.filter { it.kind == RawObjectKind.Partition }
        parts.withIndex().associate { (index, obj) -> obj to (index + 1) }
    }

    fun fractionOf(lba: ULong): Float = (lba.toDouble() / disk.toDouble()).toFloat()

    fun maxStart(): ULong {
        val needed = currentCount ?: return disk - 1UL
        return if (needed >= disk) 0UL else disk - needed
    }

    fun snapAndClamp(rawLba: ULong): ULong {
        val limit = maxStart()
        val bounded = rawLba.coerceIn(0UL, limit)
        if (!currentConfirmed || widthPx == 0) return bounded
        val tolerance = with(density) {
            (snapTolerance.toPx() / widthPx * disk.toDouble()).roundToLong()
        }.coerceAtLeast(1L)
        val nearest = currentSnapPoints
            .asSequence()
            .filter { it <= limit }
            .minByOrNull { abs(it.toLong() - bounded.toLong()) }
            ?: return bounded
        return if (abs(nearest.toLong() - bounded.toLong()) <= tolerance) nearest else bounded
    }

    val derivedEnd = count?.let { startLba + it - 1UL }
    val spoken = if (derivedEnd != null) {
        stringResource(
            R.string.raw_map_summary,
            startLba.toLong(),
            count?.toLong() ?: 0L,
            overlaps.size,
        )
    } else {
        stringResource(R.string.raw_map_summary_start_only, startLba.toLong())
    }
    val snapA11y = if (confirmed) stringResource(R.string.raw_map_snap_ticks_a11y) else null
    val spokenWithSnap = listOfNotNull(spoken, snapA11y).joinToString(". ")
    // Screen readers get the same start and derived end without dragging.
    val snapState = if (confirmed) {
        if (derivedEnd != null) {
            stringResource(
                R.string.raw_map_snap_state,
                startLba.toLong(),
                derivedEnd.toLong(),
                geometry.snapPoints.count { it <= maxStart() },
            )
        } else {
            stringResource(R.string.raw_map_snap_state_start, startLba.toLong())
        }
    } else {
        null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onSizeChanged { widthPx = it.width }
                .semantics {
                    contentDescription = spokenWithSnap
                    if (snapState != null) stateDescription = snapState
                }
                .pointerInput(geometry, widthPx, count) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            val lba = snapAndClamp(
                                (change.position.x / widthPx.coerceAtLeast(1) * disk.toDouble())
                                    .roundToLong()
                                    .coerceIn(0L, disk.toLong())
                                    .toULong(),
                            )
                            currentOnChange(lba)
                        },
                    )
                },
        ) {
            drawRect(color = trackColor, topLeft = Offset.Zero, size = size)
            geometry.objects.forEach { obj ->
                val x = fractionOf(obj.startLba) * size.width
                val w = (fractionOf(obj.sectorCount) * size.width).coerceAtLeast(1f)
                val color = when (obj.kind) {
                    RawObjectKind.Partition -> partitionColor
                    RawObjectKind.BootArea -> bootColor
                    RawObjectKind.TableArea -> tableColor
                }
                drawRect(color = color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            }
            val rx = fractionOf(startLba) * size.width
            if (count != null) {
                // Display-only minimum so a small image on a huge medium stays visible;
                // the exact sector count is shown as text and is what gets written.
                val exact = fractionOf(count) * size.width
                val rw = exact.coerceAtLeast(MIN_RANGE_WIDTH_DP.dp.toPx())
                    .coerceAtMost((size.width - rx).coerceAtLeast(1f))
                drawRect(
                    color = rangeColor.copy(alpha = 0.22f),
                    topLeft = Offset(rx, 0f),
                    size = Size(rw, size.height),
                )
                drawRect(
                    color = rangeColor,
                    topLeft = Offset(rx, 0f),
                    size = Size(rw, size.height),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            val handleWidth = 6.dp.toPx()
            drawRect(rangeColor, Offset(rx - handleWidth / 2f, 0f), Size(handleWidth, size.height))
            if (confirmed) {
                // Visible boundary marks that dragging snaps to; the numeric field stays exact.
                geometry.snapPoints.filter { it <= maxStart() }.forEach { point ->
                    val x = fractionOf(point) * size.width
                    drawLine(
                        color = snapTickColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LegendItem(rangeColor, stringResource(R.string.raw_map_legend_range))
            LegendItem(partitionColor, stringResource(R.string.raw_map_legend_partition))
            LegendItem(bootColor, stringResource(R.string.raw_map_legend_boot))
            LegendItem(tableColor, stringResource(R.string.raw_map_legend_table))
        }

        if (overlaps.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.raw_map_overlaps_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    overlaps.forEach { overlap ->
                        val target = overlap.target
                        val label = target.name ?: when (target.kind) {
                            RawObjectKind.Partition ->
                                stringResource(
                                    R.string.raw_map_object_partition,
                                    partitionLabels.getValue(target),
                                )
                            RawObjectKind.BootArea -> stringResource(R.string.raw_map_object_boot)
                            RawObjectKind.TableArea -> stringResource(R.string.raw_map_object_table)
                        }
                        Text(
                            text = stringResource(
                                R.string.raw_map_overlap_line,
                                label,
                                target.startLba.toLong(),
                                (target.startLba + target.sectorCount - 1UL).toLong(),
                                overlap.overlapSectors.toLong(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        if (!geometry.metadataComplete) {
            // The state is authoritative; the reason only picks the sentence, so a future
            // RawSchemeProblem value still gets an honest generic line instead of silence.
            val metadataNote = when (geometry.metadataProblem) {
                RawSchemeProblem.HybridAmbiguous -> R.string.raw_map_metadata_hybrid
                RawSchemeProblem.GptBackupMetadataUnknown -> R.string.raw_map_metadata_backup_unknown
                null -> R.string.raw_map_metadata_incomplete
                else -> R.string.raw_map_metadata_backup_unknown
            }
            Text(
                text = stringResource(metadataNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (!confirmed) {
            Text(
                text = stringResource(R.string.raw_map_scheme_untrusted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private const val SNAP_TOLERANCE_DP = 12
private const val MIN_RANGE_WIDTH_DP = 4

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
