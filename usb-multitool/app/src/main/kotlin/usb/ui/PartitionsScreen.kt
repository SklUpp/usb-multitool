package usb.ui

import usb.fs.FilesystemId
import usb.fs.FormatCatalog
import usb.fs.VolumeLabel
import usb.partition.PartitionName
import usb.partition.RawSchemeProblem
import usb.partition.TableScheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import usb.image.RangeProbeAttachment
import usb.image.RangeProbeFinding
import usb.partition.RawRangePlan
import usb.partition.RawRangeProblem
import usb.partition.RawRangeWarning
import usb.partition.RawWritePlan
import usb.multitool.R

/**
 * Partitions tab: an abstract, touchable partition strip synced with the list, exact
 * per-partition facts, EFI write protection, and destructive table operations.
 * Formatting lives in the selected-partition card; creation is a list item; scheme
 * replacement and the non-destructive backup-GPT repair share one nested prompt.
 *
 * Raw range writing keeps its exact geometry rules and is not part of the strip.
 */
@Composable
fun PartitionsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
) {
    var createRegion by remember(state.device, state.freeRegions) { mutableStateOf<FreeRegionUi?>(null) }
    var sectorCount by remember { mutableStateOf("") }
    var startLbaInput by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    var createLabel by remember { mutableStateOf("") }
    var createFormatId by remember { mutableStateOf<FilesystemId?>(state.selectedFormatId) }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameBinding by remember { mutableStateOf<PartitionBinding?>(null) }
    var renameText by remember { mutableStateOf("") }
    var labelTargetId by remember { mutableStateOf<String?>(null) }
    var labelBinding by remember { mutableStateOf<PartitionBinding?>(null) }
    var labelText by remember { mutableStateOf("") }
    val partitionGeometryKey = state.partitions.map { it.id to (it.startLba to it.sectorCount) }
    var schemePrompt by remember(state.device, state.partitionScheme, partitionGeometryKey) {
        mutableStateOf<PartitionScheme?>(null)
    }
    var schemeChangePrompt by remember(state.device, state.partitionScheme, partitionGeometryKey) {
        mutableStateOf<PartitionScheme?>(null)
    }
    val namesSupported = state.partitionScheme?.let { PartitionName.supported(it) } == true
    // Whole-MiB-aligned start of every free region; the create dialog picks among them.
    val align = mibAlignSectors(state.diskSectorSize)
    val creatableRegions = state.freeRegions.mapNotNull { region ->
        val skip = align?.let { (it - region.startLba % it) % it } ?: 0UL
        if (skip < region.sectorCount) {
            Triple(region, region.startLba + skip, region.sectorCount - skip)
        } else {
            null
        }
    }
    fun openCreate(region: FreeRegionUi, start: ULong, sectors: ULong) {
        createRegion = region
        startLbaInput = start.toString()
        sectorCount = sectors.toString()
        createName = ""
        createLabel = ""
        createFormatId = state.selectedFormatId
    }
    createRegion?.let { region ->
        // Same precondition as UiReducer.RequestCreatePartition: whole MiB alignment.
        val alignSectors = mibAlignSectors(state.diskSectorSize)
        val regionFits = region.startLba <= ULong.MAX_VALUE - region.sectorCount
        val regionEnd = if (regionFits) region.startLba + region.sectorCount else ULong.MAX_VALUE
        val firstAligned = alignSectors?.let { align ->
            region.startLba + if (region.startLba % align == 0UL) 0UL else align - region.startLba % align
        }
        val parsedStart = startLbaInput.toULongOrNull()
        val parsedCount = sectorCount.toULongOrNull()
        val isAligned = alignSectors != null && parsedStart != null &&
            alignSectors > 0UL && parsedStart % alignSectors == 0UL
        val inRegion = parsedStart != null && firstAligned != null &&
            parsedStart >= firstAligned && parsedStart < regionEnd
        val fits = inRegion && parsedStart != null && parsedCount != null && parsedCount > 0UL &&
            parsedCount <= regionEnd - parsedStart &&
            parsedStart <= ULong.MAX_VALUE - (parsedCount - 1UL)
        val formatId = createFormatId
        val formatSupportsLabel = formatId != null && FormatCatalog.supportsVolumeLabel(formatId)
        val nameProblem = createName.trim().takeIf { it.isNotEmpty() }?.let { PartitionName.problem(it) }
        val labelProblem = createLabel.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            formatId?.let { VolumeLabel.problem(raw, it) }
        }
        val extraValid = (!namesSupported || nameProblem == null) &&
            (createLabel.isBlank() || (formatSupportsLabel && labelProblem == null))
        val valid = regionFits && isAligned && fits && extraValid

        // Slider positions are whole MiB offsets; the authoritative bound stays ULong.
        val availableMib =
            if (alignSectors != null && firstAligned != null && firstAligned < regionEnd) {
                PartitionRangeMath.availableMib(regionEnd - firstAligned, alignSectors)
            } else {
                0UL
            }
        val startOffsetSectors =
            if (alignSectors != null && firstAligned != null && parsedStart != null &&
                parsedStart >= firstAligned && (parsedStart - firstAligned) % alignSectors == 0UL
            ) {
                parsedStart - firstAligned
            } else {
                null
            }
        val endOffsetSectors =
            if (startOffsetSectors != null && parsedStart != null && parsedCount != null &&
                parsedCount > 0UL && parsedStart <= ULong.MAX_VALUE - (parsedCount - 1UL)
            ) {
                startOffsetSectors + parsedCount
            } else {
                null
            }
        val sliderStart =
            if (alignSectors != null && startOffsetSectors != null) {
                PartitionRangeMath.snapToMib(
                    (startOffsetSectors / alignSectors).toFloat(),
                    availableMib,
                )
            } else {
                0UL
            }
        val sliderEnd =
            if (alignSectors != null && endOffsetSectors != null) {
                PartitionRangeMath.snapToMib(
                    (endOffsetSectors.toDouble() / alignSectors.toDouble()).toFloat(),
                    availableMib,
                ).coerceAtLeast(sliderStart)
            } else {
                sliderStart
            }
        // The slider is shown only while the typed geometry is MiB-aligned: a fabricated
        // 0..max range would claim boundaries the fields do not hold.
        val sliderVisible =
            alignSectors != null && firstAligned != null && startOffsetSectors != null &&
            availableMib >= 1UL && sliderStart < availableMib
        val tailSectors =
            if (alignSectors != null && firstAligned != null && availableMib > 0UL &&
                firstAligned + availableMib * alignSectors < regionEnd
            ) {
                regionEnd - (firstAligned + availableMib * alignSectors)
            } else {
                0UL
            }

        AlertDialog(
            onDismissRequest = { createRegion = null },
            title = { Text(stringResource(R.string.partitions_create_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (alignSectors == null || firstAligned == null) {
                            stringResource(
                                R.string.partitions_create_free_sectors,
                                region.sectorCount.toLong(),
                                state.diskSectorSize,
                            )
                        } else if (firstAligned >= regionEnd) {
                            stringResource(R.string.partitions_create_no_aligned_space)
                        } else {
                            stringResource(
                                R.string.partitions_create_free_range,
                                firstAligned.toLong(),
                                (regionEnd - 1UL).toLong(),
                                alignSectors.toLong(),
                                state.diskSectorSize,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (creatableRegions.size > 1) {
                        CompactChoice(
                            options = creatableRegions.map { (candidate, candidateStart, candidateSectors) ->
                                candidate to (lbaRangeLabel(candidateStart, candidateSectors) ?: "—")
                            },
                            selected = region,
                            onSelect = { chosen ->
                                creatableRegions.firstOrNull { it.first == chosen }?.let {
                                    openCreate(it.first, it.second, it.third)
                                }
                            },
                            label = stringResource(R.string.partitions_region_label),
                            enabled = state.actionsEnabled,
                        )
                    }
                    // Outside the scrolling block on purpose: dragging a thumb must not
                    // scroll the dialog, and the handles stay above the fold.
                    if (sliderVisible && alignSectors != null && firstAligned != null) {
                        val mibSectors = alignSectors
                        val regionBase = firstAligned
                        val maxMibFloat = availableMib.toFloat()
                        val safeStart = sliderStart.toFloat()
                        val safeEnd = sliderEnd.toFloat()
                        val startLbaForA11y = regionBase + sliderStart * mibSectors
                        // Inclusive last sector: matches the confirmation, which prints +count-1.
                        // Compare against the region base, not absolute zero: sliderEnd = 0 means
                        // an empty range, where subtracting would name the sector before it.
                        val endLbaForA11y = (regionBase + sliderEnd * mibSectors).let {
                            if (it > regionBase) it - 1UL else it
                        }
                        val rangeA11y = stringResource(R.string.partitions_range_a11y)
                        val rangeState = stringResource(
                            R.string.partitions_range_a11y_state,
                            sliderStart.toLong(),
                            startLbaForA11y.toLong(),
                            sliderEnd.toLong(),
                            endLbaForA11y.toLong(),
                            (sliderEnd - sliderStart).toLong(),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.partitions_range_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Uniform MiB axis of the free region only — not the partition strip's equal segments.
                            MibBoundaryTicks(
                                maxMib = availableMib,
                                startMib = sliderStart,
                                endMib = sliderEnd,
                                regionStartLba = regionBase,
                                // Last sector the right handle can select, not the region tail.
                                rightEdgeLastLba = regionBase + availableMib * mibSectors - 1UL,
                            )
                            RangeSlider(
                                value = safeStart..safeEnd,
                                onValueChange = { range ->
                                    val startMib =
                                        PartitionRangeMath.snapToMib(range.start, availableMib)
                                    val endMib =
                                        PartitionRangeMath.snapToMib(range.endInclusive, availableMib)
                                            .coerceAtLeast(startMib)
                                    val selection = PartitionRangeMath.sectorsFor(
                                        firstAligned = regionBase,
                                        regionEnd = regionEnd,
                                        startMib = startMib,
                                        endMib = endMib,
                                        sectorsPerMib = mibSectors,
                                    )
                                    if (selection != null) {
                                        startLbaInput = selection.first.toString()
                                        sectorCount = selection.second.toString()
                                    } else {
                                        startLbaInput =
                                            (regionBase + startMib * mibSectors).toString()
                                        sectorCount = "0"
                                    }
                                },
                                valueRange = 0f..maxMibFloat,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = rangeA11y
                                        stateDescription = rangeState
                                    },
                            )
                            Text(
                                text = stringResource(
                                    R.string.partitions_range_caption,
                                    sliderStart.toLong(),
                                    sliderEnd.toLong(),
                                    (sliderEnd - sliderStart).toLong(),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InfoHint(
                            text = stringResource(R.string.partitions_range_short),
                            infoTitle = stringResource(R.string.partitions_range_info_title),
                            infoText = stringResource(R.string.partitions_range_info_text),
                        )
                        OutlinedTextField(
                            value = startLbaInput,
                            onValueChange = { input -> startLbaInput = input.filter { it.isDigit() } },
                            label = { Text(stringResource(R.string.partitions_start_lba)) },
                            singleLine = true,
                            isError = startLbaInput.isNotBlank() && !(isAligned && inRegion),
                            supportingText = {
                                Text(
                                    if (alignSectors != null) {
                                        stringResource(
                                            R.string.partitions_start_support,
                                            alignSectors.toLong(),
                                        )
                                    } else {
                                        stringResource(R.string.partitions_align_unavailable)
                                    },
                                )
                            },
                            trailingIcon = {
                                TextButton(
                                    onClick = { firstAligned?.let { startLbaInput = it.toString() } },
                                    enabled = firstAligned != null,
                                ) {
                                    Text(
                                        stringResource(R.string.partitions_align),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                        )
                        OutlinedTextField(
                            value = sectorCount,
                            onValueChange = { input -> sectorCount = input.filter { it.isDigit() } },
                            label = { Text(stringResource(R.string.partitions_size_sectors)) },
                            singleLine = true,
                            isError = sectorCount.isNotBlank() && (parsedCount == null || parsedCount == 0UL || !fits),
                        )
                        if (fits && parsedStart != null && parsedCount != null) {
                            Text(
                                text = stringResource(
                                    R.string.partitions_lba_range,
                                    parsedStart.toLong(),
                                    (parsedStart + parsedCount - 1UL).toLong(),
                                    parsedCount.toLong(),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (tailSectors > 0UL) {
                            Text(
                                text = stringResource(
                                    R.string.partitions_free_tail,
                                    tailSectors.toLong(),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            namesSupported -> OutlinedTextField(
                                value = createName,
                                onValueChange = { createName = it.take(PartitionName.MAX_UTF16_UNITS * 2) },
                                label = { Text(stringResource(R.string.partitions_name_label)) },
                                singleLine = true,
                                isError = nameProblem != null,
                                supportingText = {
                                    Text(stringResource(R.string.partitions_name_support))
                                },
                            )
                            state.partitionScheme == TableScheme.MBR ->
                                EmptyHint(stringResource(R.string.partitions_mbr_no_name))
                        }
                        CompactChoice<FilesystemId?>(
                            options = listOf<Pair<FilesystemId?, String>>(
                                null to stringResource(R.string.partitions_create_no_format),
                            ) + FormatCatalog.supportedFormatIds().map {
                                it to FormatCatalog.label(it)
                            },
                            selected = createFormatId,
                            onSelect = {
                                if (it == null) createLabel = ""
                                createFormatId = it
                            },
                            label = stringResource(R.string.partitions_create_format_label),
                            enabled = state.actionsEnabled,
                        )
                        if (formatSupportsLabel) {
                            OutlinedTextField(
                                value = createLabel,
                                onValueChange = {
                                    // The engine limit only shapes the field; the reducer
                                    // re-validates with VolumeLabel.problem before writing.
                                    createLabel = it.take(
                                        formatId?.let { id -> VolumeLabel.maxChars(id) }
                                            ?: VolumeLabel.MAX_CHARS,
                                    )
                                },
                                label = { Text(stringResource(R.string.partitions_volume_label_label)) },
                                singleLine = true,
                                isError = labelProblem != null,
                                supportingText = {
                                    Text(stringResource(R.string.partitions_volume_label_support))
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        onIntent(
                            UiIntent.RequestCreatePartition(
                                startLba = parsedStart!!,
                                sectorCount = parsedCount!!,
                                name = createName.trim().takeIf { namesSupported && it.isNotEmpty() },
                                formatId = createFormatId,
                                volumeLabel = if (formatSupportsLabel) createLabel.trim() else "",
                            ),
                        )
                        createRegion = null
                    },
                ) { Text(stringResource(R.string.partitions_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { createRegion = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val renameTarget = renameBinding?.takeIf { it.matches(state) }
        ?.let { binding -> state.partitions.firstOrNull { it.id == binding.partitionId } }
    LaunchedEffect(renameBinding, renameTarget) {
        if (renameBinding != null && renameTarget == null) {
            renameTargetId = null
            renameBinding = null
        }
    }
    if (renameTarget != null) {
        val newName = renameText.trim()
        val nameOk = newName.isNotEmpty() && PartitionName.problem(newName) == null
        AlertDialog(
            onDismissRequest = { renameTargetId = null; renameBinding = null },
            title = { Text(stringResource(R.string.partitions_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(PartitionName.MAX_UTF16_UNITS * 2) },
                    label = { Text(stringResource(R.string.partitions_new_name)) },
                    singleLine = true,
                    isError = renameText.isNotBlank() && !nameOk,
                    supportingText = {
                        Text(stringResource(R.string.partitions_name_support))
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = state.actionsEnabled && nameOk && newName != renameTarget.name,
                    onClick = {
                        onIntent(
                            UiIntent.RequestRenamePartition(
                                name = newName,
                                target = renameBinding,
                            ),
                        )
                        renameTargetId = null
                        renameBinding = null
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetId = null; renameBinding = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val labelTarget = labelBinding?.takeIf { it.matches(state) }
        ?.let { binding -> state.partitions.firstOrNull { it.id == binding.partitionId } }
    val labelFs = labelTarget?.filesystemId
    LaunchedEffect(labelBinding, labelTarget) {
        if (labelBinding != null && labelTarget == null) {
            labelTargetId = null
            labelBinding = null
        }
    }
    if (labelTarget != null && labelFs != null) {
        val newLabel = labelText.trim()
        val labelOk = newLabel.isNotEmpty() && newLabel != labelTarget.volumeLabel &&
            VolumeLabel.problem(newLabel, labelFs) == null
        AlertDialog(
            onDismissRequest = { labelTargetId = null; labelBinding = null },
            title = { Text(stringResource(R.string.partitions_rename_label_title)) },
            text = {
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it.take(VolumeLabel.maxChars(labelFs)) },
                    label = { Text(stringResource(R.string.partitions_new_label)) },
                    singleLine = true,
                    isError = labelText.isNotBlank() && !labelOk,
                    supportingText = {
                        Text(stringResource(R.string.partitions_volume_label_support))
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = state.actionsEnabled && labelOk,
                    onClick = {
                        onIntent(
                            UiIntent.RequestRenameVolumeLabel(
                                label = newLabel,
                                target = labelBinding,
                            ),
                        )
                        labelTargetId = null
                        labelBinding = null
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { labelTargetId = null; labelBinding = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val selected = state.selectedPartition()
    val orderedPartitions = state.partitions.sortedWith(
        compareBy<PartitionTab> { it.startLba ?: ULong.MAX_VALUE }.thenBy { it.id },
    )
    val currentScheme = state.partitionScheme?.toPartitionScheme()
    val schemeBlocked = state.partitionTableAmbiguous || state.partitionTableDamaged || !state.tableValid
    val blockedHint = writeBlockHint(state)
    val screenScroll = rememberScrollState()
    var viewportTopPx by remember { mutableStateOf(0f) }
    var selectedRowBottomPx by remember { mutableStateOf(Float.POSITIVE_INFINITY) }
    val stickyVisible = selected != null && selectedRowBottomPx < viewportTopPx
    val revealRequester = remember { BringIntoViewRequester() }
    var revealToken by remember { mutableStateOf(0) }
    LaunchedEffect(state.selectedPartitionId) {
        selectedRowBottomPx = Float.POSITIVE_INFINITY
    }
    // Refresh and external intents must not move user's scroll position.
    LaunchedEffect(revealToken) {
        if (revealToken > 0 && state.selectedPartitionId != null) {
            withFrameNanos { }
            revealRequester.bringIntoView()
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        selected?.takeIf { stickyVisible }?.let { stickyPartition ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = stickyPartition.name.ifBlank { stickyPartition.id } +
                        " · " + (stickyPartition.filesystemLabel.ifEmpty { "—" }),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(0f)
                .onGloballyPositioned { viewportTopPx = it.positionInRoot().y }
                .verticalScroll(screenScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        SectionCard(
            title = stringResource(R.string.partitions_layout_title),
            titleColor = MaterialTheme.colorScheme.onSurface,
            // One honest line for bytes, one for sectors; both derived from the live table.
            subtitle = when {
                !state.tableValid -> null
                state.partitionTableDamaged ->
                    stringResource(R.string.partitions_gpt_primary_damaged_title)
                state.partitionTableAmbiguous -> null
                state.partitionScheme == null -> null
                else -> diskUsageCaption(state)
            },
            trailing = {
                Row(
                    modifier = Modifier.fillMaxWidth(fraction = 0.70f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SchemeIcons(
                        current = currentScheme,
                        enabled = state.actionsEnabled && !schemeBlocked && currentScheme != null,
                        onPick = { target ->
                            if (currentScheme == PartitionScheme.Gpt) schemePrompt = target
                            else schemeChangePrompt = target
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        ) {
            if (state.partitions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Badge(
                        text = pluralStringResource(
                            R.plurals.partitions_count,
                            state.partitions.size,
                            state.partitions.size,
                        ),
                    )
                }
            }
            if (!state.tableValid) {
                Text(
                    text = state.statusMessage?.localized()
                        ?: stringResource(R.string.partitions_corrupt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.partitionTableDamaged) {
                Text(
                    text = stringResource(R.string.partitions_gpt_primary_damaged_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.partitions_gpt_restore_allowed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.partitions.isEmpty()) {
                EmptyHint(
                    if (!state.tableValid) {
                        stringResource(R.string.partitions_create_blocked_corrupt)
                    } else {
                        stringResource(R.string.partitions_create_first)
                    },
                )
            } else {
                PartitionSelectStrip(
                    state = state,
                    onIntent = { intent ->
                        onIntent(intent)
                        if (intent is UiIntent.SelectPartition) revealToken++
                    },
                )
                orderedPartitions.forEachIndexed { index, partition ->
                    val isSelected = partition.id == state.selectedPartitionId
                    val access = state.partitionWriteAccess(partition)
                    val accessLabel = when (access) {
                        PartitionWriteAccess.ReadOnly -> "RO"
                        PartitionWriteAccess.Unknown -> "—"
                    }
                    val accessDescription = stringResource(
                        when (access) {
                            PartitionWriteAccess.ReadOnly -> R.string.partitions_write_ro
                            PartitionWriteAccess.Unknown -> R.string.partitions_write_unknown
                        },
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        },
                        border = if (isSelected) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .bringIntoViewRequester(revealRequester)
                                        .onGloballyPositioned {
                                            selectedRowBottomPx = it.positionInRoot().y + it.size.height
                                        }
                                } else {
                                    Modifier
                                },
                            )
                            .selectable(
                                selected = isSelected,
                                enabled = state.actionsEnabled,
                                role = Role.RadioButton,
                                onClick = { onIntent(UiIntent.SelectPartition(partition.id)) },
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioDot(selected = isSelected)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${index + 1} · ${partition.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val rowFacts = listOfNotNull(
                                    partition.filesystemLabel.ifEmpty { "—" },
                                    partitionSizeLabel(state, partition),
                                    lbaRangeLabel(partition.startLba, partition.sectorCount),
                                )
                                Text(
                                    text = rowFacts.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (partition.isEfiType) {
                                Badge(text = "EFI")
                            }
                            Text(
                                text = accessLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (access) {
                                    PartitionWriteAccess.ReadOnly ->
                                        MaterialTheme.colorScheme.error
                                    PartitionWriteAccess.Unknown ->
                                        MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = accessDescription
                                },
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    creatableRegions.firstOrNull()?.let {
                        openCreate(it.first, it.second, it.third)
                    }
                },
                enabled = state.actionsEnabled && state.tableValid &&
                    !state.partitionTableDamaged && creatableRegions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.partitions_create_action))
            }
        }


        if (selected != null) {
            SectionCard(
                title = stringResource(R.string.partitions_selected_title),
                titleColor = MaterialTheme.colorScheme.onSurface,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InfoHelpButton(
                            infoTitle = stringResource(R.string.partitions_info_help_title),
                            infoText = stringResource(R.string.partitions_info_help_text),
                        )
                        androidx.compose.material3.IconButton(
                            onClick = {
                                onIntent(UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
                            },
                            enabled = state.actionsEnabled && blockedHint == null &&
                                !state.partitionTableDamaged,
                            modifier = Modifier.semantics {
                                contentDescription = stringResource(R.string.partitions_delete_action)
                            },
                        ) {
                            GlyphIcon(Glyph.Trash, size = 20.dp, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            ) {
                val access = state.partitionWriteAccess(selected)
                InfoRow(
                    stringResource(R.string.partitions_info_name_label),
                    selected.name.ifBlank { "—" },
                )
                InfoRow(
                    stringResource(R.string.partitions_volume_label_label),
                    selected.volumeLabel?.ifBlank { "—" } ?: "—",
                )
                InfoRow(
                    stringResource(R.string.partitions_efi_label),
                    stringResource(
                        if (selected.isEfiType) R.string.answer_yes else R.string.answer_no,
                    ),
                )
                InfoRow(
                    stringResource(R.string.partitions_write_state_label),
                    when (access) {
                        PartitionWriteAccess.ReadOnly -> "RO"
                        PartitionWriteAccess.Unknown -> "—"
                    },
                )
                InfoRow(
                    stringResource(R.string.partitions_filesystem_label),
                    selected.filesystemLabel.ifEmpty { "—" },
                )
                InfoRow(
                    stringResource(R.string.partitions_size_label),
                    partitionSizeLabel(state, selected) ?: "—",
                )
                InfoRow(
                    stringResource(R.string.partitions_lba_label),
                    lbaRangeLabel(selected.startLba, selected.sectorCount) ?: "—",
                )
                // Filesystem free space, never the table's free region. Unknown stays unknown.
                val space = state.filesystemSpace(selected)
                InfoRow(
                    stringResource(R.string.filesystem_usage_label),
                    if (space != null) {
                        stringResource(
                            R.string.filesystem_usage_known,
                            DeviceIdentity.formatCapacity(space.freeBytes),
                            DeviceIdentity.formatCapacity(space.totalBytes),
                        )
                    } else {
                        stringResource(R.string.filesystem_usage_unavailable)
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactChoice(
                        options = FormatCatalog.supportedFormatIds().map {
                            it to FormatCatalog.label(it)
                        },
                        selected = state.selectedFormatId,
                        onSelect = { onIntent(UiIntent.SelectFormatFilesystem(it)) },
                        label = stringResource(R.string.partitions_format_target),
                        enabled = state.actionsEnabled,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
                        },
                        enabled = state.actionsEnabled && state.selectedPartitionId != null &&
                            blockedHint == null,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                    ) {
                        Text(stringResource(R.string.partitions_format_action))
                    }
                }
                if (namesSupported) {
                    val geometryKnown = selected.startLba != null && selected.sectorCount != null
                    OutlinedButton(
                        onClick = {
                            state.device?.let { device ->
                                renameTargetId = selected.id
                                renameBinding = PartitionBinding(
                                    device = device,
                                    partitionId = selected.id,
                                    startLba = selected.startLba,
                                    sectorCount = selected.sectorCount,
                                )
                                renameText = selected.name
                            }
                        },
                        enabled = state.actionsEnabled && state.tableValid &&
                            !state.partitionTableDamaged && geometryKnown &&
                            // Renaming VTOYEFI/ESP drops the name-based write protection.
                            !selected.readOnly && !state.partitionTableAmbiguous,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.partitions_rename_action))
                    }
                }
                val labelFs = selected.filesystemId
                if (labelFs != null && FormatCatalog.supportsVolumeLabelRename(labelFs)) {
                    OutlinedButton(
                        onClick = {
                            state.device?.let { device ->
                                labelTargetId = selected.id
                                labelBinding = PartitionBinding(
                                    device = device,
                                    partitionId = selected.id,
                                    startLba = selected.startLba,
                                    sectorCount = selected.sectorCount,
                                )
                                labelText = selected.volumeLabel.orEmpty()
                            }
                        },
                        enabled = state.actionsEnabled && state.tableValid &&
                            !state.partitionTableDamaged && !state.partitionTableAmbiguous &&
                            blockedHint == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.partitions_rename_label_action))
                    }
                }
                val geometryKnown = selected.startLba != null && selected.sectorCount != null
                val editEnabled = state.actionsEnabled && state.tableValid &&
                    !state.partitionTableDamaged && geometryKnown &&
                    state.isEfiWriteAllowed(selected)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.boot_role_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            state.device?.let { device ->
                                onIntent(
                                    UiIntent.RequestSetPartitionRole(
                                        role = if (selected.isEfiType) PartitionRole.Data else PartitionRole.Efi,
                                        target = PartitionBinding(device, selected.id, selected.startLba, selected.sectorCount),
                                    ),
                                )
                            }
                        },
                        enabled = editEnabled,
                    ) {
                        Text(
                            stringResource(
                                if (selected.isEfiType) R.string.partition_role_efi
                                else R.string.partition_role_data,
                            ),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.bootable_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            state.device?.let { device ->
                                onIntent(
                                    UiIntent.RequestSetPartitionBootable(
                                        bootable = !selected.bootable,
                                        target = PartitionBinding(device, selected.id, selected.startLba, selected.sectorCount),
                                    ),
                                )
                            }
                        },
                        enabled = editEnabled,
                    ) {
                        Text(stringResource(if (selected.bootable) R.string.answer_yes else R.string.answer_no))
                    }
                }
            }

        }

        EfiWriteCard(state = state, onIntent = onIntent)


        val promptTarget = schemePrompt
        if (promptTarget != null &&
            promptTarget != state.partitionScheme?.toPartitionScheme()
        ) {
            val currentScheme = state.partitionScheme?.toPartitionScheme()
            val repairAvailable = currentScheme == PartitionScheme.Gpt &&
                state.tableValid && !state.partitionTableDamaged &&
                !state.partitionTableAmbiguous
            val mbrTooLarge = promptTarget == PartitionScheme.Mbr &&
                (state.device?.capacityBytes ?: 0UL) /
                state.diskSectorSize.coerceAtLeast(1).toULong() >
                UiReducer.mbrAddressLimitSectors()
            val backupUnknown = currentScheme == PartitionScheme.Gpt &&
                (state.rawGeometry == null ||
                    state.rawGeometry?.metadataProblem ==
                    RawSchemeProblem.GptBackupMetadataUnknown)
            val changeBlocked = mbrTooLarge || backupUnknown
            AlertDialog(
                onDismissRequest = { schemePrompt = null },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.partitions_scheme_choice_title,
                                promptTarget.toTableScheme().name,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        InfoHelpButton(
                            infoTitle = stringResource(R.string.partitions_scheme_help_title),
                            infoText = stringResource(R.string.partitions_scheme_help_text),
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.partitions_scheme_choice_note))
                        if (repairAvailable) {
                            Text(stringResource(R.string.partitions_scheme_repair_note))
                        } else {
                            Text(stringResource(R.string.partitions_gpt_repair_blocked))
                        }
                        if (changeBlocked) {
                            Text(
                                text = stringResource(
                                    when {
                                        mbrTooLarge -> R.string.msg_scheme_mbr_too_large
                                        state.rawGeometry == null ->
                                            R.string.partitions_scheme_backup_unverified
                                        else -> R.string.partitions_scheme_backup_unknown
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (repairAvailable) {
                            TextButton(
                                onClick = {
                                    schemePrompt = null
                                    onIntent(
                                        UiIntent.RequestDestructive(DestructiveAction.GPT_REPAIR),
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.partitions_gpt_repair))
                            }
                        }
                        TextButton(
                            onClick = {
                                schemePrompt = null
                                schemeChangePrompt = promptTarget
                            },
                            enabled = !changeBlocked,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.partitions_scheme_choice_change,
                                    promptTarget.toTableScheme().name,
                                ),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { schemePrompt = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        val changeTarget = schemeChangePrompt
        if (changeTarget != null &&
            changeTarget != state.partitionScheme?.toPartitionScheme()
        ) {
            val mbrTooLarge = changeTarget == PartitionScheme.Mbr &&
                (state.device?.capacityBytes ?: 0UL) /
                state.diskSectorSize.coerceAtLeast(1).toULong() >
                UiReducer.mbrAddressLimitSectors()
            val currentScheme = state.partitionScheme?.toPartitionScheme()
            val backupUnknown = currentScheme == PartitionScheme.Gpt &&
                (state.rawGeometry == null ||
                    state.rawGeometry?.metadataProblem ==
                    RawSchemeProblem.GptBackupMetadataUnknown)
            val changeBlocked = mbrTooLarge || backupUnknown
            AlertDialog(
                onDismissRequest = { schemeChangePrompt = null },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.partitions_scheme_prompt_title,
                                changeTarget.toTableScheme().name,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        InfoHelpButton(
                            infoTitle = stringResource(R.string.partitions_scheme_help_title),
                            infoText = stringResource(R.string.partitions_scheme_help_text),
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.partitions_scheme_prompt_note))
                        if (changeBlocked) {
                            Text(
                                text = stringResource(
                                    when {
                                        mbrTooLarge -> R.string.msg_scheme_mbr_too_large
                                        state.rawGeometry == null ->
                                            R.string.partitions_scheme_backup_unverified
                                        else -> R.string.partitions_scheme_backup_unknown
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.partitions.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.partitions_scheme_erase_title),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            state.partitions.forEach { partition ->
                                Text(
                                    text = stringResource(
                                        R.string.partitions_scheme_erase_partition,
                                        partition.name.ifBlank { partition.id },
                                        partitionSizeLabel(state, partition) ?: "—",
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            schemeChangePrompt = null
                            onIntent(UiIntent.RequestInitializePartitionTable(changeTarget))
                        },
                        enabled = !changeBlocked,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.partitions_scheme_change_action,
                                changeTarget.toTableScheme().name,
                            ),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { schemeChangePrompt = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        // Raw writing: one tool for the whole medium and for any sub-range of it. Only the
        // start is chosen; the end is derived from the decoded image size.
        SectionCard(
            title = stringResource(R.string.partitions_raw_write_title),
            subtitle = null,
            trailing = {
                InfoHelpButton(
                    infoTitle = stringResource(R.string.partitions_raw_info_title),
                    infoText = listOf(
                        stringResource(R.string.partitions_raw_write_subtitle),
                        stringResource(R.string.partitions_raw_overwrite_note),
                        stringResource(R.string.raw_map_snap_hint),
                    ).joinToString("\n\n"),
                )
            },
        ) {
            // One resolved source for the next write; the host never writes on pick.
            val rawName = state.stagedRawImageLabel ?: rawSourceName(state.imageUri)
            val rawStaged = state.stagedRawImagePath != null

            if (rawName == null) {
                EmptyHint(stringResource(R.string.partitions_no_image))
                // Without a source the range editor is pointless, but choosing one must stay
                // possible; the same two pickers are the only entry points.
                PickerButtons(
                    state = state,
                    onPickImage = onPickImage,
                    onOpenStick = {
                        onIntent(
                            UiIntent.BeginFilesPick(
                                purpose = FilesPickPurpose.RawSource,
                                returnTab = ShellTab.Partitions,
                                target = FilesPickTarget.File,
                                partitionId = state.selectedPartitionId,
                            ),
                        )
                    },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.partitions_raw_source_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Badge(
                        text = stringResource(R.string.partitions_raw_selected),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = rawName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (rawStaged) {
                            R.string.partitions_raw_source_staged
                        } else {
                            R.string.partitions_raw_source_saf
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PickerButtons(
                    state = state,
                    onPickImage = onPickImage,
                    onOpenStick = {
                        onIntent(
                            UiIntent.BeginFilesPick(
                                purpose = FilesPickPurpose.RawSource,
                                returnTab = ShellTab.Partitions,
                                target = FilesPickTarget.File,
                                partitionId = state.selectedPartitionId,
                            ),
                        )
                    },
                )
            }

            val geometry = state.rawGeometry
            if (geometry == null) {
                // No geometry means the range cannot be checked; offering a write would be a blind
                // destructive action, so the section stops here.
                EmptyHint(stringResource(R.string.raw_write_geometry_unavailable))
            } else {
                val sourceBytes = state.rawWriteDecodedBytes()
                var startText by remember(
                    state.device?.identity,
                    geometry.diskSectorCount,
                    sourceBytes,
                ) {
                    mutableStateOf("0")
                }
                val draftStart = startText.trim().toULongOrNull()
                // The end is never typed: it is exactly what the decoded image needs.
                val plan = draftStart?.let {
                    RawRangePlan.planFromSourceSize(it, sourceBytes, geometry)
                }
                val planRange =
                    if (state.imageUri != null &&
                        state.stagedRawImagePath == null &&
                        state.rawDecodedSha256 == null
                    ) {
                        null
                    } else {
                        (plan as? RawWritePlan.Ready)?.range
                    }
                val planProblems =
                    ((plan as? RawWritePlan.Rejected)?.problems ?: emptySet())
                        .filterNot { sourceBytes == null && it == RawRangeProblem.SourceSizeUnknown }
                        .toSet()

                if (state.rawSizeMeasuring) {
                    Text(
                        text = stringResource(R.string.raw_write_size_measuring),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onIntent(UiIntent.CancelRawSourceMeasurement) },
                    ) {
                        Text(stringResource(R.string.raw_write_cancel_measure))
                    }
                } else if (state.rawMeasurementWaitingForPrevious) {
                    Text(
                        text = stringResource(R.string.raw_write_size_waiting_previous),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onIntent(UiIntent.CancelRawSourceMeasurement) },
                    ) {
                        Text(stringResource(R.string.raw_write_cancel_measure))
                    }
                } else if (state.rawMeasurementPending) {
                    Text(
                        text = stringResource(R.string.raw_write_size_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.rawDecodedSizeError != null || state.rawDecodedErrorKind != null) {
                    val text = when (state.rawDecodedErrorKind) {
                        RawMeasureError.Busy -> stringResource(R.string.raw_write_error_busy)
                        RawMeasureError.CloseCapacity ->
                            stringResource(R.string.raw_write_error_close_capacity)
                        RawMeasureError.Failed, null -> stringResource(
                            R.string.raw_write_size_measure_failed,
                            state.rawDecodedSizeError ?: "",
                        )
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (sourceBytes == null) {
                    Text(
                        text = stringResource(R.string.raw_write_problem_source_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // One draggable start; the end follows the image size and is never a handle.
                RawPlanMap(
                    geometry = geometry,
                    startLba = (draftStart ?: 0UL).coerceAtMost(geometry.diskSectorCount),
                    sectorCount = planRange?.sectorCount,
                    onStartChange = { startText = it.toString() },
                )

                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text(stringResource(R.string.raw_write_start_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                planRange?.let { range ->
                    Text(
                        text = stringResource(
                            R.string.raw_write_end_and_size,
                            (range.startLba + range.sectorCount - 1UL).toLong(),
                            DeviceIdentity.formatCapacity(range.byteLength(geometry.sectorSize)),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                planProblems.forEach { problem ->
                    Text(
                        text = stringResource(
                            when (problem) {
                                RawRangeProblem.EmptyRange -> R.string.raw_write_problem_empty
                                RawRangeProblem.BeyondEndOfDisk -> R.string.raw_write_problem_beyond_disk
                                RawRangeProblem.RangeTooLarge -> R.string.raw_write_problem_too_large
                                RawRangeProblem.SourceLargerThanRange ->
                                    R.string.raw_write_problem_source_larger
                                RawRangeProblem.SourceSizeUnknown ->
                                    R.string.raw_write_problem_source_unknown
                                RawRangeProblem.SourceEmpty ->
                                    R.string.raw_write_problem_source_empty
                                RawRangeProblem.SourceSizeOverflow ->
                                    R.string.raw_write_problem_source_overflow
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (plan is RawWritePlan.Ready) {
                    // Overlaps are printed under the map; only the remaining warnings show here.
                    plan.warnings.forEach { warning ->
                        when (warning) {
                            is RawRangeWarning.UnalignedStart -> Text(
                                text = stringResource(
                                    R.string.raw_write_warning_unaligned,
                                    warning.startLba.toLong(),
                                    warning.alignmentSectors.toLong(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is RawRangeWarning.SchemeNotTrusted -> Text(
                                text = stringResource(R.string.raw_write_warning_scheme),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Printed once under the map, driven by metadataComplete.
                            is RawRangeWarning.MetadataCoverageUnknown -> Unit
                            is RawRangeWarning.OverlapsObjects -> Unit
                        }
                    }
                }

                // The presence probe is a hint, never a veto: every finding stays confirmable.
                val attachment = state.device?.let {
                    RangeProbeAttachment(
                        identity = it.identity,
                        attachGeneration = it.attachGeneration,
                    )
                }
                val probe = state.rangeProbe
                val probeFresh = planRange != null && attachment != null && probe != null &&
                    probe.matches(
                        planRange,
                        geometry.diskSectorCount,
                        geometry.sectorSize,
                        attachment,
                    )
                if (planRange != null) {
                    TextButton(
                        onClick = {
                            onIntent(
                                UiIntent.RequestRangeProbe(
                                    planRange.startLba,
                                    planRange.sectorCount,
                                ),
                            )
                        },
                        enabled = state.actionsEnabled,
                    ) {
                        Text(stringResource(R.string.raw_probe_action))
                    }
                }
                if (probeFresh && probe != null) {
                    Text(
                        text = stringResource(
                            when (probe.finding) {
                                RangeProbeFinding.DataPresent -> R.string.raw_probe_data_present
                                RangeProbeFinding.NoDataInSamples -> R.string.raw_probe_no_data_sampled
                                RangeProbeFinding.Unknown -> R.string.raw_probe_unknown
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = {
                        // The reducer re-derives the same range from the decoded size; the
                        // button never sends a hand-typed end LBA.
                        draftStart?.let { start ->
                            onIntent(UiIntent.RequestRawWrite(start))
                        }
                    },
                    enabled = state.actionsEnabled && rawName != null && planRange != null &&
                        probeFresh,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.partitions_raw_write_action))
                }
            }

            if (rawStaged) {
                val stagingBusy = state.operation?.state == OperationState.Running ||
                    state.operation?.state == OperationState.Queued
                TextButton(
                    onClick = { onIntent(UiIntent.ClearStagedRawImage(deleteFile = true)) },
                    enabled = !stagingBusy,
                ) {
                    Text(
                        stringResource(R.string.partitions_delete_staged),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

        }

        // Table repair is not a write of user data; it keeps its own section so a raw write can
        // never be mistaken for a repair, or the other way round.
        SectionCard(
            title = stringResource(R.string.partitions_repair_title),
            subtitle = stringResource(R.string.partitions_repair_subtitle),
            trailing = {
                InfoHelpButton(
                    infoTitle = stringResource(R.string.partitions_repair_info_title),
                    infoText = stringResource(R.string.partitions_repair_info_text),
                )
            },
        ) {
            OutlinedButton(
                onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_REPAIR)) },
                enabled = state.actionsEnabled && !state.partitionTableDamaged,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.partitions_gpt_repair))
            }
            if (state.partitionTableDamaged) {
                Text(
                    text = stringResource(R.string.partitions_gpt_repair_blocked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        }
    }
}

/**
 * The strip is proportional to LBA span, with a 48dp minimum hit cell and horizontal overflow.
 * Exact LBA geometry remains in list rows and raw range map. Tapping a segment sends the same
 * [UiIntent.SelectPartition] as the list, and selection made in list scrolls matching segment.
 */
private data class StripSegment(val partition: PartitionTab?, val free: FreeRegionUi?)
private fun segmentStart(segment: StripSegment): ULong =
    segment.partition?.startLba ?: segment.free?.startLba ?: 0UL

private fun segmentSpan(segment: StripSegment): ULong =
    segment.partition?.sectorCount ?: segment.free?.sectorCount ?: 0UL

private fun segmentEnd(segment: StripSegment): ULong =
    segmentStart(segment).let { start ->
        val span = segmentSpan(segment)
        if (start > ULong.MAX_VALUE - span) ULong.MAX_VALUE else start + span
    }

@Composable
private fun PartitionSelectStrip(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    val partitions = state.partitions.filter { (it.sectorCount ?: 0uL) > 0uL }
    val segments = buildList {
        partitions.forEach { add(StripSegment(it, null)) }
        state.freeRegions.filter { it.sectorCount > 0UL }.forEach { add(StripSegment(null, it)) }
    }.sortedWith(compareBy { it.partition?.startLba ?: it.free?.startLba ?: ULong.MAX_VALUE })
    if (segments.isEmpty()) return
    val gap = 2.dp
    // 48 dp meets Material touch guidance; horizontal scrolling preserves it on narrow screens.
    val minTouch = 48.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val diskSectors = state.device?.capacityBytes
            ?.div(state.diskSectorSize.coerceAtLeast(1).toULong())
            ?.takeIf { it > 0UL }
            ?: segments.maxOf(::segmentEnd)
        val totalSectors = diskSectors.coerceAtLeast(1UL)
        val availablePx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val minPx = with(density) { minTouch.toPx() }
        val widthsPx = segments.map { segment ->
            maxOf(
                (segmentSpan(segment).toDouble() / totalSectors.toDouble() * availablePx).toFloat(),
                minPx,
            )
        }
        val widths = widthsPx.map { with(density) { it.toDp() } }
        val selectedIndex = segments.indexOfFirst { it.partition?.id == state.selectedPartitionId }
        LaunchedEffect(state.selectedPartitionId, widthsPx, scroll.maxValue) {
            if (selectedIndex >= 0) {
                scroll.animateScrollTo(
                    (widthsPx.take(selectedIndex).sum() + gapPx * selectedIndex).roundToInt(),
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                segments.forEachIndexed { index, segment ->
                    val partition = segment.partition
                    val isSelected = partition?.id == state.selectedPartitionId
                    val description = if (partition != null) {
                        stringResource(
                            R.string.partitions_strip_item_a11y,
                            index + 1,
                            partition.name.ifBlank { partition.id },
                            partitionSizeLabel(state, partition) ?: "—",
                        )
                    } else {
                        stringResource(R.string.partitions_strip_free)
                    }
                    Column(
                        modifier = Modifier
                            .width(widths[index])
                            .fillMaxHeight()
                            .then(
                                if (partition != null) {
                                    Modifier.selectable(
                                        selected = isSelected,
                                        enabled = state.actionsEnabled,
                                        role = Role.RadioButton,
                                        onClick = { onIntent(UiIntent.SelectPartition(partition.id)) },
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .semantics { contentDescription = description },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (partition == null) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(4.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.partitions_strip_scale_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
// PAR/RAW matrix: layout title, sorted LBA rows, 48dp abstract selectable/free segments,
// sticky selection, GPT/MBR warning actions, create-in-list, EFI policy, bound editors,
// derived raw end, overlap list, probe and dual source pickers are wired above.

/**
 * Two text glyphs ("GPT"/"MBR") inside oval pills. The current scheme gets the selection
 * outline and a neutral filter wash; the other pill opens the nested repair/change prompt.
 * The whole construct is framed in the error colour because changing the scheme erases
 * partitions. No vector asset is required: the text itself is the icon and carries the
 * accessibility description.
 */
@Composable
private fun SchemeIcons(
    current: PartitionScheme?,
    enabled: Boolean,
    onPick: (PartitionScheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                RoundedCornerShape(12.dp),
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SchemeIcon(
            scheme = PartitionScheme.Gpt,
            current = current,
            enabled = enabled,
            onPick = onPick,
        )
        SchemeArrow(pointsRight = current != PartitionScheme.Mbr)
        SchemeIcon(
            scheme = PartitionScheme.Mbr,
            current = current,
            enabled = enabled,
            onPick = onPick,
        )
    }
}

@Composable
private fun SchemeIcon(
    scheme: PartitionScheme,
    current: PartitionScheme?,
    enabled: Boolean,
    onPick: (PartitionScheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = scheme.toTableScheme().name
    val isCurrent = current == scheme
    val actionAvailable = enabled && !isCurrent
    val description = when {
        isCurrent -> stringResource(R.string.partitions_scheme_current_a11y, label)
        // Disabled or unknown-scheme pills must not promise an action they cannot open.
        !actionAvailable -> stringResource(R.string.partitions_scheme_unavailable_a11y, label)
        // GPT current opens the neutral chooser (repair or change); only an MBR current
        // pill leads straight to the destructive change warning.
        current == PartitionScheme.Gpt ->
            stringResource(R.string.partitions_scheme_actions_a11y, label)
        current == PartitionScheme.Mbr ->
            stringResource(R.string.partitions_scheme_change_a11y, label)
        else -> stringResource(R.string.partitions_scheme_unavailable_a11y, label)
    }
    Surface(
        // Fully rounded pill: text wrapped by the oval background, not a rectangular tile.
        shape = RoundedCornerShape(percent = 50),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (isCurrent) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
        } else {
            null
        },
        modifier = modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .selectable(
                selected = isCurrent,
                enabled = enabled && !isCurrent,
                role = Role.RadioButton,
                onClick = { onPick(scheme) },
            )
            .semantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent) {
                // Selection filter: a neutral wash, never colour alone.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SchemeArrow(pointsRight: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(width = 28.dp, height = 24.dp)) {
        val y = size.height / 2f
        val inset = 4.dp.toPx()
        val head = 6.dp.toPx()
        val startX = if (pointsRight) inset else size.width - inset
        val endX = if (pointsRight) size.width - inset else inset
        drawLine(
            color = color,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = 2.dp.toPx(),
        )
        val direction = if (pointsRight) -1f else 1f
        drawLine(
            color = color,
            start = Offset(endX, y),
            end = Offset(endX + direction * head, y - head),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = color,
            start = Offset(endX, y),
            end = Offset(endX + direction * head, y + head),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/**
 * EFI write protection moved here from the Ventoy tab. The switch is bound to the same
 * [UiIntent.SetVtoyEfiWritable] and [AppUiState.isEfiWriteAllowed] exact identity +
 * startLba + sectorCount check, so a re-plug or geometry change can never inherit an
 * override for a different partition. Damaged, ambiguous or unreadable tables keep the
 * control disabled and the override is dropped by the reducer on such a refresh.
 */
@Composable
private fun EfiWriteCard(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    SectionCard(
        title = stringResource(R.string.efi_section_title),
        titleColor = MaterialTheme.colorScheme.onSurface,
        trailing = {
            InfoHelpButton(
                infoTitle = stringResource(R.string.efi_policy_info_title),
                infoText = stringResource(R.string.efi_policy_info_text),
            )
        },
    ) {
        val tableBlocked = state.partitionTableAmbiguous ||
            state.partitionTableDamaged ||
            !state.tableValid
        if (state.efiPartitions.isEmpty()) {
            EmptyHint(stringResource(R.string.efi_missing))
        } else {
            state.efiPartitions.forEach { partition ->
                val policyAllows = state.isEfiWriteAllowed(partition)
                SwitchRow(
                    label = partition.name.ifBlank { partition.id },
                    checked = policyAllows,
                    onCheckedChange = {
                        onIntent(UiIntent.SetVtoyEfiWritable(partition.id, it))
                    },
                    enabled = state.actionsEnabled && !tableBlocked,
                    hint = stringResource(
                        if (policyAllows) {
                            R.string.efi_write_policy_allowed
                        } else {
                            R.string.efi_write_policy_protected
                        },
                    ),
                )
            }
        }
        // Disabled reasons stay reachable: the same table faults shown in the scheme card.
        if (tableBlocked) {
            Text(
                text = stringResource(
                    when {
                        state.partitionTableAmbiguous -> R.string.partitions_efi_table_ambiguous
                        state.partitionTableDamaged -> R.string.partitions_efi_table_damaged
                        else -> R.string.partitions_efi_table_invalid
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Default Material3 range-slider thumb width. Verified in the resolved artifact
 * material3 1.4.0: `SliderTokens.HandleWidth = 4.dp` (HandleHeight = 44.dp), and
 * `RangeSliderState.updateMinMaxPx` maps values between `startThumbWidth / 2` and
 * `totalWidth - endThumbWidth / 2`. The tick inset is that same half width, so marks
 * line up with the handles instead of a guessed gap.
 */
private val SliderThumbWidth = 4.dp

/**
 * Visible MiB alignment marks for the create-partition slider, with the two ends of the
 * selectable MiB span labelled exactly: the first usable aligned LBA and the last sector
 * the right handle can select. The axis is uniform MiB of the free region (convenience
 * first), never proportional disk LBA widths; the disk map keeps LBA geometry separately.
 * These ends are not necessarily neighbouring block edges — an unaligned region start skips
 * a few sectors and an unaligned tail stays outside the MiB span. Marks are thinned on large
 * regions, so the labels name the selectable ends instead of promising a full 1 MiB grid.
 */
@Composable
private fun MibBoundaryTicks(
    maxMib: ULong,
    startMib: ULong,
    endMib: ULong,
    regionStartLba: ULong,
    rightEdgeLastLba: ULong,
) {
    if (maxMib == 0UL) return
    val marks = remember(maxMib) { PartitionRangeTicks.marks(maxMib) }
    val tickColor = MaterialTheme.colorScheme.outline
    val activeColor = MaterialTheme.colorScheme.primary
    val a11y = stringResource(R.string.partitions_range_ticks_a11y)
    val trackInset = SliderThumbWidth / 2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11y },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp),
        ) {
            // Slider thumbs inset the usable track; ticks use the same span so marks line up.
            val inset = trackInset.toPx()
            val trackWidth = (size.width - 2f * inset).coerceAtLeast(1f)
            val span = maxMib.toDouble()
            marks.forEach { mark ->
                val x = inset + (mark.toDouble() / span).toFloat() * trackWidth
                val active = mark in startMib..endMib
                // The two ends are the selectable span limits, not MiB steps: draw them heavier.
                val spanEnd = mark == 0UL || mark == maxMib
                drawLine(
                    color = if (active || spanEnd) activeColor else tickColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = when {
                        spanEnd -> 3.dp.toPx()
                        active -> 2.dp.toPx()
                        else -> 1.dp.toPx()
                    },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = trackInset),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(
                    R.string.partitions_range_tick_edge_label,
                    0L,
                    regionStartLba.toLong(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.partitions_range_tick_edge_label,
                    maxMib.toLong(),
                    rightEdgeLastLba.toLong(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Canvas(Modifier.size(20.dp)) {
        drawCircle(
            color = color,
            radius = size.minDimension / 2f - 1.dp.toPx(),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        if (selected) {
            drawCircle(color = color, radius = size.minDimension / 2f - 5.dp.toPx())
        }
    }
}

private fun partitionSizeLabel(state: AppUiState, partition: PartitionTab): String? =
    partition.sectorCount?.let {
        DeviceIdentity.formatCapacity(it * state.diskSectorSize.toULong())
    }

/**
 * "LBA start–end · count sectors" for one exact range, or null when the geometry is
 * unknown or the end LBA would overflow. The end is start + count - 1, never a guess.
 */
@Composable
private fun lbaRangeLabel(start: ULong?, count: ULong?): String? {
    if (start == null || count == null || count == 0UL) return null
    if (start > ULong.MAX_VALUE - (count - 1UL)) return null
    return stringResource(
        R.string.partitions_lba_range,
        start.toLong(),
        (start + count - 1UL).toLong(),
        count.toLong(),
    )
}

/** Sectors per whole MiB, or null when the logical sector size cannot express it. */
private fun mibAlignSectors(sectorSize: Int): ULong? =
    if (sectorSize > 0 && (1024 * 1024) % sectorSize == 0) {
        ((1024 * 1024) / sectorSize).toULong()
    } else {
        null
    }

/** Occupied/free caption; null when the table cannot support an honest number. */
@Composable
private fun diskUsageCaption(state: AppUiState): String? {
    // Unknown/ambiguous tables turn "0 used" into false precision; a trusted empty
    // table is different: zero occupied sectors is then a real, reportable fact.
    if (!state.tableValid || state.partitionTableDamaged || state.partitionTableAmbiguous) {
        return null
    }
    if (state.partitionScheme == null) return null
    if (state.partitions.any { it.startLba == null || it.sectorCount == null }) return null
    val sectorSize = state.diskSectorSize.toULong()
    val totalBytes = state.device?.capacityBytes ?: 0UL
    if (sectorSize == 0UL || totalBytes == 0UL) {
        return stringResource(R.string.partitions_capacity_unknown)
    }
    val totalSectors = totalBytes / sectorSize
    if (totalSectors == 0UL) return stringResource(R.string.partitions_capacity_unknown)
    val occupied = state.partitions.sumOf { it.sectorCount ?: 0uL }
    // Contradictory geometry (partitions beyond capacity) is reported as unknown, not clipped.
    if (occupied > totalSectors) return null
    val usedBytes = occupied * sectorSize
    val percent = usedBytes.toDouble() / totalBytes.toDouble() * 100.0
    return stringResource(
        R.string.partitions_disk_usage,
        DeviceIdentity.formatCapacity(usedBytes),
        DeviceIdentity.formatCapacity(totalBytes),
        percent,
    ) + "\n" + stringResource(
        R.string.partitions_disk_sectors,
        occupied.toLong(),
        totalSectors.toLong(),
    )
}

/** Explanation for a blocked destructive action, or null when allowed. */
@Composable
private fun writeBlockHint(state: AppUiState): String? {
    val message = UiReducer.writeBlockMessage(state) ?: return null
    val partition = state.selectedPartition()
    return if (partition != null &&
        partition.readOnly &&
        !state.isEfiWriteAllowed(partition)
    ) {
        stringResource(R.string.partitions_efi_blocked, partition.name)
    } else {
        message.localized()
    }
}


/** Two real sources: a file already on the stick, or a phone document. Picking only stages. */
@Composable
private fun PickerButtons(
    state: AppUiState,
    onPickImage: () -> Unit,
    onOpenStick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onOpenStick,
            enabled = state.actionsEnabled,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Text(stringResource(R.string.raw_choose_stick))
        }
        OutlinedButton(
            onClick = onPickImage,
            enabled = state.actionsEnabled,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Text(stringResource(R.string.raw_choose_phone))
        }
    }
}
