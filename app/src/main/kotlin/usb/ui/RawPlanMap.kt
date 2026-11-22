package usb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import usb.multitool.R
import usb.partition.RawObjectKind
import usb.partition.RawRangeGeometry
import usb.partition.RawRangeObject
import usb.partition.RawSchemeProblem
import usb.partition.SchemeTrust
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Map of the whole medium: existing objects, the chosen start, and the derived range.
 *
 * Only the start is draggable. The end is whatever the decoded image size needs, so the
 * drawing can never disagree with the range that gets confirmed. Dragging snaps the start
 * to confirmed partition edges within [SNAP_TOLERANCE_DP] (a [RawRangeGeometry.snapPoints]
 * entry is a section start or a section end), then clamps it so the image still fits; a
 * snapped start can genuinely begin on a partition edge. Every overlap is also printed as
 * a real list below the track. The numeric start field stays exact.
 *
 * Pan model: the wide strip itself never pans by swipe — a swipe on it always moves the
 * start. Overlong content is panned only by the thin slider below the strip, and the pan
 * is a `graphicsLayer` translation clipped to the viewport, so there is no scroll gesture
 * to steal the drag. Pan state is not keyed on content width (that reset every remeasure).
 * Drag uses content-space deltas from the marker so pan and LBA stay in sync; while the
 * start is dragged the view auto-pans just enough to keep the marker inside the viewport.
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
    val currentOnChange by rememberUpdatedState(onStartChange)
    val maxStart = if (count == null || count >= disk) 0UL else disk - count
    val overlaps = count?.let { geometry.overlaps(startLba, it) }.orEmpty()
    val rangeColor = MaterialTheme.colorScheme.primary
    // Distinct container roles, not alpha tints: adjacent object classes must stay
    // distinguishable on both light and dark surfaces and match their legend swatches.
    val partitionColor = MaterialTheme.colorScheme.secondaryContainer
    val bootColor = MaterialTheme.colorScheme.tertiaryContainer
    val tableColor = MaterialTheme.colorScheme.primaryContainer
    val trackColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val freeColor = MaterialTheme.colorScheme.surfaceContainerHighest
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
            freeColor = freeColor,
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
            LegendItem(freeColor, stringResource(R.string.partitions_strip_free))
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
    freeColor: Color,
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
        // When sectorCount is unknown (measuring / Rejected), maxStart is 0 and must not
        // pin the marker; allow any disk LBA. Fit clamp applies only once a count exists.
        val ceiling = if (sectorCount == null) disk else maxStart
        fun chooseStart(candidate: ULong): ULong {
            val clamped = candidate.coerceAtMost(ceiling)
            if (!confirmed) return clamped
            val tolerancePx = with(density) { SNAP_TOLERANCE_DP.dp.toPx() }
            val candidateX = xForLba(clamped)
            val nearest = geometry.snapPoints
                .asSequence()
                .filter { it <= ceiling }
                .minByOrNull { abs(xForLba(it) - candidateX) }
            // Nearest confirmed edge within tolerance wins, including slow drags.
            return nearest?.takeIf { abs(xForLba(it) - candidateX) <= tolerancePx } ?: clamped
        }
        val maxPanPx = (contentPx - availablePx).coerceAtLeast(0f)
        // Pan must survive contentPx remeasures: remember(contentPx) recreated state at 0
        // on every width tweak, so a new drag looked like a jump back to the strip start.
        var panOffset by remember { mutableStateOf(0f) }
        fun currentPan(): Float = panOffset.coerceIn(0f, maxPanPx)
        fun panTo(value: Float) {
            panOffset = value.coerceIn(0f, maxPanPx)
        }
        // Move the view only when the marker would otherwise leave the viewport.
        fun keepMarkerVisible(markerX: Float) {
            if (maxPanPx <= 0f) return
            val marginPx = with(density) { MARKER_VISIBLE_MARGIN_DP.dp.toPx() }
            val pan = currentPan()
            when {
                markerX < pan + marginPx -> panTo(markerX - marginPx)
                markerX > pan + availablePx - marginPx -> panTo(markerX - availablePx + marginPx)
            }
        }
        // Shrink-only coerce when the content gets shorter; never force pan back to 0.
        LaunchedEffect(maxPanPx) {
            if (panOffset > maxPanPx) panOffset = maxPanPx
        }
        // External start edits (typed LBA) still bring the marker on-screen once.
        // Drag path below owns pan during gestures so absolute pointer+pan feedback cannot
        // ratchet pan when xForLba/lbaAtX are not inverses under min segment widths.
        LaunchedEffect(startLba, contentPx, availablePx) {
            keepMarkerVisible(xForLba(startLba))
        }
        val latestStartLba by rememberUpdatedState(startLba)
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    // A Box reports max(child width), and the only child is requiredWidth,
                    // so the node would report contentWidth and clip nothing. Report the
                    // viewport width explicitly instead. Width is bounded here: the Column
                    // is fillMaxWidth and the sheet scrolls vertically, never horizontally.
                    .fillMaxWidth()
                    .height(MAP_HEIGHT_DP.dp)
                    // The clip must be outside the custom layout: it sizes its layer from
                    // its child, so inside it would measure the requiredWidth content.
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        // Measure tightly at the viewport width: the Box must report the
                        // viewport, not the requiredWidth content, or the strip background
                        // bleeds past the sheet padding. Width is bounded: the Column is
                        // fillMaxWidth and the sheet scrolls vertically only.
                        val width = constraints.maxWidth
                        val placeable = measurable.measure(constraints.copy(minWidth = width))
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }
                    .semantics {
                        contentDescription = spoken
                        if (snapState != null) stateDescription = snapState
                    }
                    .pointerInput(geometry, contentPx, ceiling, confirmed) {
                        // Local drag LBA tracks deltas in content space so pan changes and
                        // non-invertible width mapping cannot desync pointer vs marker.
                        var dragLba = latestStartLba
                        detectDragGestures(
                            onDragStart = {
                                dragLba = latestStartLba
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val proposedX = (xForLba(dragLba) + dragAmount.x)
                                    .coerceIn(0f, contentPx)
                                // Keep the unsnapped LBA as the next drag origin. Storing the
                                // snapped value makes every small delta inside the snap radius
                                // return to the same edge, trapping the marker there.
                                val raw = lbaAtX(proposedX)
                                dragLba = raw
                                val next = chooseStart(raw)
                                onStartChange(next)
                                keepMarkerVisible(xForLba(next))
                            },
                        )
                    },
            ) {
                Box(
                    Modifier
                        .requiredWidth(contentWidth)
                        .height(MAP_HEIGHT_DP.dp)
                        .graphicsLayer { translationX = -currentPan() },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(trackColor, Offset.Zero, size)
                        segments.forEachIndexed { index, segment ->
                            val x = widthsPx.take(index).sum() + gapPx * index
                            val w = widthsPx[index]
                            val color = when {
                                segment.rawObjects.any { it.kind == RawObjectKind.BootArea } -> bootColor
                                segment.rawObjects.any { it.kind == RawObjectKind.TableArea } -> tableColor
                                segment.rawObjects.any { it.kind == RawObjectKind.Partition } -> partitionColor
                                else -> freeColor
                            }
                            drawRect(color, Offset(x, 0f), Size(w, size.height))
                            drawRect(
                                snapTickColor.copy(alpha = 0.35f),
                                Offset(x, 0f),
                                Size(w, size.height),
                                style = Stroke(1.dp.toPx()),
                            )
                        }
                        val rx = xForLba(startLba)
                        sectorCount?.let { count ->
                            val end = if (startLba <= disk - count) startLba + count else disk
                            val rw = (xForLba(end) - rx).coerceAtLeast(MIN_SEGMENT_WIDTH_DP.dp.toPx())
                            drawRect(rangeColor.copy(alpha = 0.38f), Offset(rx, 0f), Size(rw, size.height))
                            drawRect(rangeColor, Offset(rx, 0f), Size(rw, size.height), style = Stroke(3.dp.toPx()))
                        }
                        drawRect(rangeColor, Offset(rx - 3.dp.toPx(), 0f), Size(6.dp.toPx(), size.height))
                        geometry.snapPoints.forEach { point ->
                            val x = xForLba(point)
                            drawLine(snapTickColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                        }
                    }
                }
            }
            if (maxPanPx > 0f) {
                // Same width and edges as the strip; no horizontal padding anywhere above.
                PanSlider(
                    maxPanPx = maxPanPx,
                    viewportPx = availablePx,
                    contentPx = contentPx,
                    panPx = currentPan(),
                    onPanChange = ::panTo,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Thin pan slider under the strip. The track shows the visible fraction of the content and
 * the thumb its position; dragging or tapping moves the pan. Only the top 24 dp of the 48 dp
 * band draws the 4 dp track and 8 dp thumb, so the bar keeps its place under the strip while
 * the touch target meets the minimum. Only a horizontal drag is claimed: a vertical gesture
 * that starts on the band still reaches the parent scroll, and the consumed horizontal moves
 * keep the shell tab swipe from firing. It is a separate composable from the map, so a thumb
 * gesture can never be mistaken for a start-marker drag.
 */
@Composable
private fun PanSlider(
    maxPanPx: Float,
    viewportPx: Float,
    contentPx: Float,
    panPx: Float,
    onPanChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val description = stringResource(R.string.raw_map_pan_a11y)
    val fraction = if (maxPanPx > 0f) (panPx / maxPanPx).coerceIn(0f, 1f) else 0f
    val state = stringResource(R.string.raw_map_pan_state, (fraction * 100f).roundToInt())
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val thumbColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.fillMaxWidth().height(PAN_TOUCH_HEIGHT_DP.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val thumbPx = maxOf(
            with(density) { PAN_THUMB_MIN_WIDTH_DP.dp.toPx() },
            widthPx * (viewportPx / contentPx),
        ).coerceAtMost(widthPx)
        val travelPx = (widthPx - thumbPx).coerceAtLeast(0f)
        val thumbX = thumbLeftForPan(travelPx, panPx, maxPanPx)
        val thumbXNow by rememberUpdatedState(thumbX)
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = description
                    stateDescription = state
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        onPanChange(target.coerceIn(0f, 1f) * maxPanPx)
                        true
                    }
                }
                .pointerInput(maxPanPx, travelPx, thumbPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Grab the thumb in place; a tap on the track jumps to that spot.
                        val grab = if (down.position.x in thumbXNow..(thumbXNow + thumbPx)) {
                            down.position.x - thumbXNow
                        } else {
                            onPanChange(
                                panForThumbLeft(travelPx, down.position.x - thumbPx / 2f, maxPanPx),
                            )
                            thumbPx / 2f
                        }
                        // The band yields vertical drags: only a horizontal slop claims the
                        // gesture, so a scroll or tab swipe starting here still works. Once
                        // claimed, consuming the moves is what stops the shell swipe.
                        val claimed = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        horizontalDrag(claimed.id) { change ->
                            onPanChange(panForThumbLeft(travelPx, change.position.x - grab, maxPanPx))
                            change.consume()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackH = PAN_TRACK_HEIGHT_DP.dp.toPx()
                val thumbH = PAN_THUMB_HEIGHT_DP.dp.toPx()
                // Draw inside the 24 dp visual band at the top of the touch band, so the bar
                // keeps its old distance from the strip; the extra height below is touch only.
                val visualBandPx = minOf(size.height, PAN_VISUAL_BAND_DP.dp.toPx())
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, (visualBandPx - trackH) / 2f),
                    size = Size(size.width, trackH),
                    cornerRadius = CornerRadius(trackH / 2f),
                )
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(thumbX, (visualBandPx - thumbH) / 2f),
                    size = Size(thumbPx, thumbH),
                    cornerRadius = CornerRadius(thumbH / 2f),
                )
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

// Touch tolerance for snapping; [RawRangeGeometry.snapPoints] already lists section starts
// and section ends, so both ends of any partition are snap targets without extra lookups.
private const val SNAP_TOLERANCE_DP = 24
private const val MAP_HEIGHT_DP = 56
private const val MARKER_VISIBLE_MARGIN_DP = 8
private const val PAN_TOUCH_HEIGHT_DP = 48
private const val PAN_VISUAL_BAND_DP = 24
private const val PAN_TRACK_HEIGHT_DP = 4
private const val PAN_THUMB_HEIGHT_DP = 8
private const val PAN_THUMB_MIN_WIDTH_DP = 24

/** Pan offset for a thumb whose left edge sits at [thumbLeftPx]; clamped to the track ends. */
internal fun panForThumbLeft(travelPx: Float, thumbLeftPx: Float, maxPanPx: Float): Float =
    if (travelPx <= 0f) 0f else (thumbLeftPx / travelPx).coerceIn(0f, 1f) * maxPanPx

/** Thumb left edge for [panPx]; the inverse of [panForThumbLeft] so both stay in sync. */
internal fun thumbLeftForPan(travelPx: Float, panPx: Float, maxPanPx: Float): Float =
    if (maxPanPx <= 0f || travelPx <= 0f) 0f else (panPx / maxPanPx).coerceIn(0f, 1f) * travelPx

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(2.dp),
                ),
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
