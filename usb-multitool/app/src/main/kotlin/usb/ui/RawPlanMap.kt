package usb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import usb.multitool.R
import usb.partition.RawObjectKind
import usb.partition.RawRangeObject
import androidx.compose.ui.unit.dp
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
    val density = LocalDensity.current
    val currentOnChange by rememberUpdatedState(onStartChange)
    val maxStart = if (count == null || count >= disk) 0UL else disk - count
    val overlaps = count?.let { geometry.overlaps(startLba, it) }.orEmpty()
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
                geometry.snapPoints.count { it <= maxStart },
            )
        } else {
            stringResource(R.string.raw_map_snap_state_start, startLba.toLong())
        }
    } else {
        null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        RawUnifiedMap(
            geometry = geometry,
            segments = remember(geometry) { rawMapSegments(geometry.objects, disk) },
            startLba = startLba,
            sectorCount = count,
            spoken = spokenWithSnap,
            snapState = snapState,
            onStartChange = currentOnChange,
            partitionColor = partitionColor,
            bootColor = bootColor,
            tableColor = tableColor,
            trackColor = trackColor,
            rangeColor = rangeColor,
            snapTickColor = snapTickColor,
        )
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

@Composable
private fun RawUnifiedMap(
    geometry: RawRangeGeometry,
    segments: List<RawMapSegment>,
    startLba: ULong,
    sectorCount: ULong?,
    spoken: String,
    snapState: String?,
    onStartChange: (ULong) -> Unit,
    partitionColor: Color,
    bootColor: Color,
    tableColor: Color,
    trackColor: Color,
    rangeColor: Color,
    snapTickColor: Color,
) {
    val density = LocalDensity.current
    val disk = geometry.diskSectorCount
    val confirmed = geometry.trust is SchemeTrust.Confirmed
    val maxStart = if (sectorCount == null || sectorCount >= disk) 0UL else disk - sectorCount
    if (disk == 0UL) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minPx = with(density) { 48.dp.toPx() }
        val availablePx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { 4.dp.toPx() }
        val widthsPx = segments.map { segment ->
            maxOf(
                (segment.sectorCount.toDouble() / disk.toDouble() * availablePx.toDouble()).toFloat(),
                minPx,
            )
        }
        val contentPx = maxOf(availablePx, widthsPx.sum() + gapPx * (segments.size - 1).coerceAtLeast(0))
        val contentWidth = with(density) { contentPx.toDp() }
        fun lbaAtX(rawX: Float): ULong {
            var offset = 0f
            val x = rawX.coerceIn(0f, contentPx)
            segments.forEachIndexed { index, segment ->
                val end = offset + widthsPx[index]
                if (x <= end) {
                    val fraction = ((x - offset) / widthsPx[index]).coerceIn(0f, 1f)
                    return segment.startLba + (segment.sectorCount.toDouble() * fraction).toULong()
                }
                offset = end + gapPx
            }
            return disk
        }
        fun xForLba(lba: ULong): Float {
            var offset = 0f
            segments.forEachIndexed { index, segment ->
                val end = segment.startLba + segment.sectorCount
                if (lba <= end) {
                    val fraction = ((lba - segment.startLba).toDouble() / segment.sectorCount.toDouble()).toFloat()
                    return offset + widthsPx[index] * fraction.coerceIn(0f, 1f)
                }
                offset += widthsPx[index] + gapPx
            }
            return contentPx
        }
        fun chooseStart(candidate: ULong): ULong {
            if (candidate > maxStart) return candidate
            if (!confirmed) return candidate
            val tolerancePx = with(density) { SNAP_TOLERANCE_DP.dp.toPx() }
            val nearest = geometry.snapPoints
                .asSequence()
                .filter { it <= maxStart }
                .minByOrNull { abs(xForLba(it) - xForLba(candidate)) }
            return nearest?.takeIf { abs(xForLba(it) - xForLba(candidate)) <= tolerancePx } ?: candidate
        }
        val mapScroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().horizontalScroll(mapScroll)) {
            Box(Modifier.width(contentWidth).height(104.dp)) {
                Canvas(
                    Modifier.fillMaxWidth().height(56.dp).semantics {
                        contentDescription = spoken
                        if (snapState != null) stateDescription = snapState
                    }.pointerInput(geometry, contentPx, sectorCount) {
                        detectDragGestures { change, _ ->
                            onStartChange(chooseStart(lbaAtX(change.position.x)))
                        }
                    },
                ) {
                    drawRect(trackColor, Offset.Zero, size)
                    segments.forEachIndexed { index, segment ->
                        val x = widthsPx.take(index).sum() + gapPx * index
                        val w = widthsPx[index]
                        val color = when {
                            segment.rawObjects.any { it.kind == RawObjectKind.BootArea } -> bootColor
                            segment.rawObjects.any { it.kind == RawObjectKind.TableArea } -> tableColor
                            segment.rawObjects.any { it.kind == RawObjectKind.Partition } -> partitionColor
                            else -> trackColor
                        }
                        drawRect(color, Offset(x, 0f), Size(w, size.height))
                    }
                    val rx = xForLba(startLba)
                    sectorCount?.let { count ->
                        val end = if (startLba <= disk - count) startLba + count else disk
                        val rw = (xForLba(end) - rx).coerceAtLeast(MIN_SEGMENT_WIDTH_DP.dp.toPx())
                        drawRect(rangeColor.copy(alpha = 0.22f), Offset(rx, 0f), Size(rw, size.height))
                        drawRect(rangeColor, Offset(rx, 0f), Size(rw, size.height), style = Stroke(2.dp.toPx()))
                    }
                    drawRect(rangeColor, Offset(rx - 3.dp.toPx(), 0f), Size(6.dp.toPx(), size.height))
                    geometry.snapPoints.forEach { point ->
                        val x = xForLba(point)
                        drawLine(snapTickColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomStart),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    segments.forEachIndexed { index, segment ->
                        val label = segment.rawObjects.joinToString(" / ") { objectInSegment ->
                            objectInSegment.name ?: when (objectInSegment.kind) {
                                RawObjectKind.Partition -> "Partition"
                                RawObjectKind.BootArea -> "Boot area"
                                RawObjectKind.TableArea -> "Table metadata"
                            }
                        }.ifEmpty { "Free space" }
                        Box(
                            Modifier.width(with(density) { widthsPx[index].toDp() }).height(48.dp)
                                .clickable { onStartChange(segment.startLba) }
                                .semantics {
                                    contentDescription = "$label, LBA ${segment.startLba}, ${segment.sectorCount} sectors"
                                },
                        ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                    }
                }
            }
        }
    }
}

private data class RawMapSegment(
    val startLba: ULong,
    val sectorCount: ULong,
    val rawObjects: List<RawRangeObject>,
)

private fun rawMapSegments(objects: List<RawRangeObject>, disk: ULong): List<RawMapSegment> {
    if (disk == 0UL) return emptyList()
    val clipped = objects.asSequence()
        .filter { it.startLba < disk && it.sectorCount > 0UL }
        .map { objectInDisk -> objectInDisk to minOf(objectInDisk.endLbaExclusive, disk) }
        .filter { (objectInDisk, end) -> end > objectInDisk.startLba }
        .toList()
    val boundaries = sortedSetOf<ULong>().apply {
        add(0UL)
        add(disk)
        clipped.forEach { (objectInDisk, end) ->
            add(objectInDisk.startLba)
            add(end)
        }
    }.toList()
    return boundaries.zipWithNext().mapNotNull { (start, end) ->
        if (end <= start) return@mapNotNull null
        val covered = clipped
            .filter { (objectInDisk, objectEnd) -> objectInDisk.startLba < end && objectEnd > start }
            .map { it.first }
        RawMapSegment(start, end - start, covered)
    }
}

private const val MIN_SEGMENT_WIDTH_DP = 4
private const val SNAP_TOLERANCE_DP = 12
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
