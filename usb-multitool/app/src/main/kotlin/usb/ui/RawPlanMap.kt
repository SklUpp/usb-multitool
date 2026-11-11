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
import usb.partition.RawWriteRange
import usb.partition.SchemeTrust
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Map of the whole medium: existing objects, the chosen raw range on top, and the range handles.
 *
 * The drawing is never the only source of information — every overlap is also printed as text
 * below the track, and the canvas carries a spoken summary. Dragging snaps to partition
 * boundaries when the scheme is confirmed; the numeric start/size fields elsewhere stay exact, so
 * nothing typed is ever rewritten here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RawPlanMap(
    geometry: RawRangeGeometry,
    range: RawWriteRange,
    onRangeChange: (RawWriteRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val disk = geometry.diskSectorCount
    if (disk == 0UL || range.sectorCount == 0UL) return
    val confirmed = geometry.trust is SchemeTrust.Confirmed
    val overlaps = geometry.overlaps(range.startLba, range.sectorCount)
    val density = LocalDensity.current
    val snapTolerance = 12.dp

    var widthPx by remember { mutableStateOf(0) }
    var dragged by remember { mutableStateOf<Handle?>(null) }
    val currentRange by rememberUpdatedState(range)
    val currentConfirmed by rememberUpdatedState(confirmed)
    val currentSnapPoints by rememberUpdatedState(geometry.snapPoints)
    val currentOnChange by rememberUpdatedState(onRangeChange)

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

    fun snap(rawLba: ULong): ULong {
        val bounded = rawLba.coerceIn(0UL, disk)
        if (!currentConfirmed || widthPx == 0) return bounded
        val tolerance = with(density) {
            (snapTolerance.toPx() / widthPx * disk.toDouble()).roundToLong()
        }.coerceAtLeast(1L)
        val nearest = currentSnapPoints.minByOrNull { abs(it.toLong() - bounded.toLong()) }
            ?: return bounded
        return if (abs(nearest.toLong() - bounded.toLong()) <= tolerance) nearest else bounded
    }

    val spoken = stringResource(R.string.raw_map_summary, range.startLba.toLong(), range.sectorCount.toLong(), overlaps.size)
    val snapA11y = if (confirmed) stringResource(R.string.raw_map_snap_ticks_a11y) else null
    val spokenWithSnap = listOfNotNull(spoken, snapA11y).joinToString(". ")
    // Screen readers get the same snap edges the pointer uses, in LBA, without dragging.
    val snapState = if (confirmed) {
        stringResource(
            R.string.raw_map_snap_state,
            range.startLba.toLong(),
            (range.startLba + range.sectorCount - 1UL).toLong(),
            geometry.snapPoints.size,
        )
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
                .pointerInput(geometry, widthPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val active = currentRange
                            val startX = fractionOf(active.startLba) * widthPx
                            val endX = fractionOf((active.startLba + active.sectorCount).coerceAtMost(disk)) * widthPx
                            dragged = if (abs(offset.x - startX) <= abs(offset.x - endX)) Handle.Start else Handle.End
                        },
                        onDragEnd = { dragged = null },
                        onDragCancel = { dragged = null },
                        onDrag = { change, _ ->
                            val lba = snap(
                                (change.position.x / widthPx.coerceAtLeast(1) * disk.toDouble())
                                    .roundToLong()
                                    .coerceIn(0L, disk.toLong())
                                    .toULong(),
                            )
                            val active = currentRange
                            val endExclusive = (active.startLba + active.sectorCount).coerceAtMost(disk)
                            when (dragged) {
                                Handle.Start -> if (lba < endExclusive) {
                                    currentOnChange(RawWriteRange(lba, endExclusive - lba))
                                }
                                Handle.End -> if (lba > active.startLba) {
                                    currentOnChange(RawWriteRange(active.startLba, lba - active.startLba))
                                }
                                null -> Unit
                            }
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
            val rx = fractionOf(range.startLba) * size.width
            val rw = (fractionOf(range.sectorCount) * size.width).coerceAtLeast(1f)
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
            val handleWidth = 6.dp.toPx()
            drawRect(rangeColor, Offset(rx - handleWidth / 2f, 0f), Size(handleWidth, size.height))
            drawRect(rangeColor, Offset(rx + rw - handleWidth / 2f, 0f), Size(handleWidth, size.height))
            if (confirmed) {
                // Visible boundary marks that dragging snaps to; numeric fields stay exact.
                geometry.snapPoints.forEach { point ->
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

        if (confirmed) {
            Text(
                text = stringResource(R.string.raw_map_snap_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
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

        Column(modifier = Modifier.padding(top = 6.dp)) {
            overlaps.forEach { overlap ->
                val target = overlap.target
                val label = target.name ?: when (target.kind) {
                    RawObjectKind.Partition ->
                        stringResource(R.string.raw_map_object_partition, partitionLabels.getValue(target))
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

private enum class Handle { Start, End }

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
