package usb.ui

import usb.fs.FilesystemId
import usb.fs.FilesystemSpace
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsagePhase
import usb.fs.FormatCatalog
import usb.fs.VolumeLabel
import usb.partition.PartitionName
import usb.partition.RawSchemeProblem
import usb.partition.TableScheme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.input.KeyboardType
import usb.image.RangeProbeAttachment
import usb.image.RangeProbeFinding
import usb.partition.RawObjectKind
import usb.partition.RawRangePlan
import usb.partition.RawRangeProblem
import usb.partition.RawRangeWarning
import usb.partition.RawWritePlan
import usb.multitool.R

/**
 * Partitions tab: layout strip + list, sticky selector, selected facts, compact
 * GPT/MBR capsule beside the Layout title, and two-phase sector write. Backup GPT
 * repair/restore stays on Diagnostics (Backup GPT copy), not on this tab.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var filesystemMenuTarget by remember { mutableStateOf<PartitionBinding?>(null) }
    val partitionGeometryKey = state.partitions.map { it.id to (it.startLba to it.sectorCount) }
    var schemeChangePrompt by remember(state.device, state.partitionScheme, partitionGeometryKey) {
        mutableStateOf<PartitionScheme?>(null)
    }
    var partitionPickerOpen by remember { mutableStateOf(false) }
    // LBA 0 is always vetoed by the range guard, so the whole-disk chip can never
    // write; default to the selected partition instead of a permanently blocked mode.
    var rawTargetMode by remember(state.device?.identity) { mutableStateOf(RawTargetMode.Partition) }
    var initializeScheme by remember(state.device, state.partitionScheme, partitionGeometryKey) {
        mutableStateOf(PartitionScheme.Gpt)
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.partitions_create_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                        ),
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
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontFeatureSettings = "tnum",
                                ),
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
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (tailSectors > 0UL) {
                            Text(
                                text = stringResource(
                                    R.string.partitions_free_tail,
                                    tailSectors.toLong(),
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontFeatureSettings = "tnum",
                                ),
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(28.dp),
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(28.dp),
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
    var layoutCardTopPx by remember { mutableStateOf(Float.POSITIVE_INFINITY) }
    val stickyVisible = false
    val revealRequester = remember { BringIntoViewRequester() }
    var revealToken by remember { mutableStateOf(0) }
    LaunchedEffect(revealToken) {
        if (revealToken > 0 && state.selectedPartitionId != null) {
            withFrameNanos { }
            revealRequester.bringIntoView()
        }
    }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(
        state.device,
        state.selectedPartitionId,
        selected?.startLba,
        selected?.sectorCount,
    ) {
        filesystemMenuTarget = null
    }

    if (partitionPickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { partitionPickerOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.partitions_select_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                orderedPartitions.forEachIndexed { index, partition ->
                    val isActive = partition.id == state.selectedPartitionId
                    val access = state.partitionWriteAccess(partition)
                    val readOnly = access == PartitionWriteAccess.ReadOnly ||
                        access == PartitionWriteAccess.DriverReadOnly
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(
                                    R.string.partitions_sticky_title,
                                    index + 1,
                                    partition.name.ifBlank { partition.id },
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(
                                    R.string.partitions_sticky_meta,
                                    partitionSizeLabel(state, partition) ?: "—",
                                    partition.filesystemLabel.ifEmpty { "—" },
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontFeatureSettings = "tnum",
                                ),
                            )
                        },
                        leadingContent = if (isActive) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else null,
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (partition.displayEfi) Badge(text = stringResource(R.string.partitions_badge_efi))
                                if (readOnly) {
                                    Badge(
                                        text = stringResource(R.string.partitions_access_ro),
                                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        content = writeAccessTone(readWrite = false),
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isActive) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                onIntent(UiIntent.SelectPartition(partition.id))
                                partitionPickerOpen = false
                            },
                    )
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = stickyVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f),
        ) {
            val stickyPartition = selected
            if (stickyPartition != null) {
                val stickyIndex = orderedPartitions.indexOfFirst { it.id == stickyPartition.id }
                    .takeIf { it >= 0 }?.plus(1) ?: 1
                val stickyAccess = state.partitionWriteAccess(stickyPartition)
                val stickyRo = stickyAccess == PartitionWriteAccess.ReadOnly ||
                    stickyAccess == PartitionWriteAccess.DriverReadOnly
                val stickySpace = state.filesystemSpace(stickyPartition)
                val stickyName = stickyPartition.name.ifBlank { stickyPartition.id }
                val stickyLabel = stickyPartition.volumeLabel?.trim()?.takeIf { it.isNotEmpty() }
                val stickyTitle = stickyLabel?.let { "$stickyName ($it)" } ?: stickyName
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClick = { partitionPickerOpen = true },
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.partitions_sticky_title,
                                        stickyIndex,
                                        stickyTitle,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (stickyPartition.filesystemLabel.isNotEmpty()) {
                                    Badge(text = stickyPartition.filesystemLabel)
                                }
                                if (stickyPartition.displayEfi) {
                                    Badge(text = stringResource(R.string.partitions_badge_efi))
                                }
                                if (stickyRo) {
                                    Badge(
                                        text = stringResource(R.string.partitions_access_ro),
                                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        content = writeAccessTone(readWrite = false),
                                    )
                                }
                            }
                            Text(
                                text = partitionUsageLabel(state, stickyPartition, stickySpace),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.UnfoldMore,
                            contentDescription = stringResource(
                                R.string.partitions_sticky_expand_a11y,
                            ),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            PartitionChoiceRow(
                partitions = state.partitions,
                selectedId = state.selectedPartitionId,
                writeAccess = selected?.let(state::partitionWriteAccess)
                    ?: PartitionWriteAccess.Unknown,
                usageOf = state::partitionUsageSnapshot,
                phaseOf = state::partitionUsagePhase,
                enabled = state.actionsEnabled,
                sizeOf = { partitionSizeLabel(state, it) },
                onSelect = { onIntent(UiIntent.SelectPartition(it)) },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .zIndex(0f)
                .onGloballyPositioned { viewportTopPx = it.positionInRoot().y }
                .verticalScroll(screenScroll)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = stringResource(R.string.partitions_layout_title),
                titleColor = MaterialTheme.colorScheme.onSurface,
                trailing = {
                    FlowRow(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val countLabel = pluralStringResource(
                            R.plurals.partitions_count_label,
                            state.partitions.size,
                            state.partitions.size,
                        )
                        Box(
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = countLabel
                            },
                        ) {
                            Badge(text = countLabel)
                        }
                        val needsInitialize = !state.partitionTableRecognized &&
                            state.partitionTableRecognition !=
                            usb.partition.TableRecognition.SUPERFLOPPY &&
                            state.tableValid && !state.partitionTableAmbiguous
                        val capsuleTarget = currentScheme?.let {
                            if (it == PartitionScheme.Gpt) PartitionScheme.Mbr
                            else PartitionScheme.Gpt
                        }
                        val capsuleScheme = currentScheme?.toTableScheme()?.name
                        if (!needsInitialize && capsuleScheme != null && capsuleTarget != null) {
                            val targetName = capsuleTarget.toTableScheme().name
                            val capsuleA11y = stringResource(
                                R.string.partitions_scheme_capsule_a11y,
                                capsuleScheme,
                                targetName,
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                                ),
                                modifier = Modifier
                                    .wrapContentWidth()
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = capsuleA11y }
                                    .clickable(
                                        enabled = state.actionsEnabled && !schemeBlocked,
                                        role = Role.Button,
                                        onClick = { schemeChangePrompt = capsuleTarget },
                                    )
                                    .alpha(if (state.actionsEnabled && !schemeBlocked) 1f else 0.38f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = capsuleScheme,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = stringResource(R.string.partitions_scheme_arrow),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.errorContainer,
                                    ) {
                                        Text(
                                            text = targetName,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            maxLines = 1,
                                            modifier = Modifier.padding(
                                                horizontal = 8.dp,
                                                vertical = 4.dp,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        InfoHelpButton(
                            infoTitle = stringResource(R.string.partitions_scheme_help_title_next),
                            infoText = stringResource(R.string.partitions_scheme_help_text_next),
                        )
                    }
                },
                modifier = Modifier.onGloballyPositioned {
                    layoutCardTopPx = it.positionInRoot().y
                },
            ) {
                val geometry = diskGeometry(state)
                if (geometry != null) {
                    Text(
                        text = stringResource(
                            R.string.partitions_geometry_signature,
                            geometry.sizeLabel,
                            geometry.totalSectors.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!state.partitionTableRecognized &&
                    state.partitionTableRecognition != usb.partition.TableRecognition.SUPERFLOPPY &&
                    state.tableValid && !state.partitionTableAmbiguous
                ) {
                    val segmentHaptic = LocalHapticFeedback.current
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(PartitionScheme.Gpt, PartitionScheme.Mbr).forEachIndexed { index, scheme ->
                            SegmentedButton(
                                selected = initializeScheme == scheme,
                                onClick = {
                                    if (initializeScheme != scheme) {
                                        initializeScheme = scheme
                                        segmentHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                enabled = state.actionsEnabled,
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                            ) {
                                Text(scheme.toTableScheme().name)
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            onIntent(UiIntent.RequestInitializePartitionTable(initializeScheme))
                        },
                        enabled = state.actionsEnabled && !schemeBlocked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.partitions_scheme_initialize_action))
                    }
                } else {
                    // GPT/MBR converter lives in the section trailing row (wrap-content).
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
                        text = stringResource(R.string.partitions_gpt_recovery_diagnostics_hint),
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
                }
                if (state.partitions.isNotEmpty() || state.freeRegions.any { it.sectorCount > 0UL }) {
                    PartitionSelectStrip(
                        state = state,
                        onIntent = { intent ->
                            onIntent(intent)
                            if (intent is UiIntent.SelectPartition) revealToken++
                        },
                        onSelectFree = { region ->
                            if (state.tableValid && !state.partitionTableDamaged) {
                                creatableRegions.firstOrNull { it.first == region }
                                    ?.let { openCreate(it.first, it.second, it.third) }
                            }
                        },
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    orderedPartitions.forEachIndexed { index, partition ->
                        val isSelected = partition.id == state.selectedPartitionId
                        val access = state.partitionWriteAccess(partition)
                        val readOnly = access == PartitionWriteAccess.ReadOnly ||
                            access == PartitionWriteAccess.DriverReadOnly
                        val accessDescription = when (access) {
                            PartitionWriteAccess.ReadOnly,
                            PartitionWriteAccess.DriverReadOnly,
                            -> stringResource(R.string.partitions_write_state_read_only)
                            PartitionWriteAccess.ReadWrite ->
                                stringResource(R.string.partitions_write_state_read_write)
                            PartitionWriteAccess.Unknown -> null
                        }
                        val displayName = partition.name.ifBlank { partition.id }
                        val displayLabel = partition.volumeLabel?.trim()?.takeIf { it.isNotEmpty() }
                        val rowTitle = stringResource(
                            R.string.partitions_ordinal_title,
                            index + 1,
                            displayLabel?.let { "$displayName ($it)" } ?: displayName,
                        )
                        val rowSpace = state.filesystemSpace(partition)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                            shadowElevation = 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isSelected) {
                                        Modifier.bringIntoViewRequester(revealRequester)
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = rowTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (partition.filesystemLabel.isNotEmpty()) {
                                        Badge(text = partition.filesystemLabel)
                                    }
                                    if (partition.displayEfi) {
                                        Badge(text = stringResource(R.string.partitions_badge_efi))
                                    }
                                    if (accessDescription != null && readOnly) {
                                        Box(
                                            modifier = Modifier.clearAndSetSemantics {
                                                contentDescription = accessDescription
                                            },
                                        ) {
                                            Badge(
                                                text = stringResource(R.string.partitions_access_ro),
                                                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                content = writeAccessTone(readWrite = false),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = partitionUsageLabel(state, partition, rowSpace),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val rowStart = partition.startLba
                                val rowCount = partition.sectorCount
                                val rowEnd = if (rowStart != null && rowCount != null &&
                                    rowCount > 0UL &&
                                    rowStart <= ULong.MAX_VALUE - (rowCount - 1UL)
                                ) {
                                    rowStart + rowCount - 1UL
                                } else {
                                    null
                                }
                                val rowRange = if (rowStart != null && rowEnd != null) {
                                    stringResource(
                                        R.string.partitions_info_lba_range_value,
                                        rowStart.toLong(),
                                        rowEnd.toLong(),
                                    )
                                } else {
                                    null
                                }
                                Text(
                                    text = listOfNotNull(
                                        partitionSizeLabel(state, partition),
                                        rowRange,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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



            AnimatedContent(
                targetState = selected?.id,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "selectedPartitionInfo",
            ) { selectedId ->
                val live = orderedPartitions.firstOrNull { it.id == selectedId }
                if (live != null) {
                    SelectedPartitionCard(
                        state = state,
                        selected = live,
                        blockedHint = blockedHint,
                        showBlockedHint = !(
                            live.readOnly && live.displayEfi &&
                                !state.partitionTableAmbiguous &&
                                !state.partitionTableDamaged &&
                                state.tableValid
                        ),
                        namesSupported = namesSupported,
                        onIntent = onIntent,
                        onRename = { part ->
                            state.device?.let { device ->
                                renameTargetId = part.id
                                renameBinding = PartitionBinding(
                                    device = device,
                                    partitionId = part.id,
                                    startLba = part.startLba,
                                    sectorCount = part.sectorCount,
                                )
                                renameText = part.name
                            }
                        },
                        onLabel = { part ->
                            state.device?.let { device ->
                                labelTargetId = part.id
                                labelBinding = PartitionBinding(
                                    device = device,
                                    partitionId = part.id,
                                    startLba = part.startLba,
                                    sectorCount = part.sectorCount,
                                )
                                labelText = part.volumeLabel.orEmpty()
                            }
                        },
                        filesystemMenuTarget = filesystemMenuTarget,
                        onFilesystemMenu = { filesystemMenuTarget = it },
                        latestState = latestState,
                    )
                }
            }

            state.pendingEfiWriteBinding?.let { binding ->
                val target = state.partitions.firstOrNull { it.id == binding.partitionId }
                val valid = target != null && binding.matches(state) && target.readOnly &&
                    state.actionsEnabled && state.tableValid && !state.partitionTableAmbiguous &&
                    !state.partitionTableDamaged
                AlertDialog(
                    onDismissRequest = { onIntent(UiIntent.DismissEfiWriteConfirmation) },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    tonalElevation = 6.dp,
                    shape = RoundedCornerShape(28.dp),
                    title = { Text(stringResource(R.string.efi_confirm_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.efi_confirm_body,
                                target?.name?.ifBlank { target.id } ?: binding.partitionId,
                                binding.device.identity,
                                binding.startLba?.toString() ?: "?",
                                binding.sectorCount?.toString() ?: "?",
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (valid) {
                                    onIntent(UiIntent.ConfirmSetVtoyEfiWritable(binding))
                                }
                            },
                            enabled = valid,
                        ) { Text(stringResource(R.string.action_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { onIntent(UiIntent.DismissEfiWriteConfirmation) }) {
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
                val backupUnknown = currentScheme == PartitionScheme.Gpt &&
                    (state.rawGeometry == null ||
                        state.rawGeometry?.metadataProblem ==
                        RawSchemeProblem.GptBackupMetadataUnknown)
                val geometryUnknown = state.partitions.any {
                    it.startLba == null || it.sectorCount == null
                }
                val schemeUnknown =
                    state.partitionTableRecognition == usb.partition.TableRecognition.SUPERFLOPPY ||
                    (currentScheme == null && state.partitionTableRecognized)
                val metadataUnverified = state.metadataFingerprint == null ||
                    (if (changeTarget == PartitionScheme.Gpt) {
                        state.initializationMetadataFingerprintGpt
                    } else {
                        state.initializationMetadataFingerprintMbr
                    }) == null
                val changeBlocked = mbrTooLarge || backupUnknown ||
                    state.partitionTableAmbiguous || state.partitionTableDamaged ||
                    !state.tableValid || geometryUnknown || schemeUnknown || metadataUnverified
                val blockedReason = when {
                    mbrTooLarge -> R.string.msg_scheme_mbr_too_large
                    state.rawGeometry == null -> R.string.partitions_scheme_backup_unverified
                    backupUnknown -> R.string.partitions_scheme_backup_unknown
                    geometryUnknown -> R.string.msg_partition_geometry_unknown
                    schemeUnknown -> R.string.msg_scheme_unknown
                    metadataUnverified -> R.string.msg_scheme_metadata_unverified
                    else -> R.string.partitions_scheme_blocked
                }
                AlertDialog(
                    onDismissRequest = { schemeChangePrompt = null },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    tonalElevation = 6.dp,
                    shape = RoundedCornerShape(28.dp),
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
                                infoTitle = stringResource(R.string.partitions_scheme_help_title_next),
                                infoText = stringResource(R.string.partitions_scheme_help_text_next),
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
                                    text = stringResource(blockedReason),
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
                                color = if (changeBlocked) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
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

            RawWriteSection(
                state = state,
                onIntent = onIntent,
                onPickImage = onPickImage,
                rawTargetMode = rawTargetMode,
                onRawTargetMode = { rawTargetMode = it },
                orderedPartitions = orderedPartitions,
            )
        }
    }
}

private enum class RawTargetMode {
    WholeDisk,
    Partition,
    Custom,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectedPartitionCard(
    state: AppUiState,
    selected: PartitionTab,
    blockedHint: String?,
    showBlockedHint: Boolean = true,
    namesSupported: Boolean,
    onIntent: (UiIntent) -> Unit,
    onRename: (PartitionTab) -> Unit,
    onLabel: (PartitionTab) -> Unit,
    filesystemMenuTarget: PartitionBinding?,
    onFilesystemMenu: (PartitionBinding?) -> Unit,
    latestState: AppUiState,
) {
    SectionCard(
        title = stringResource(R.string.partitions_selected_title),
        titleColor = MaterialTheme.colorScheme.onSurface,
        trailing = {
            InfoHelpButton(
                infoTitle = stringResource(R.string.partitions_info_help_title),
                infoText = stringResource(R.string.partitions_info_help_text),
            )
        },
    ) {
        if (showBlockedHint) {
            blockedHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val geometryKnown = selected.startLba != null && selected.sectorCount != null
        val renameEnabled = state.actionsEnabled && state.tableValid &&
            !state.partitionTableDamaged && geometryKnown &&
            !selected.readOnly && !state.partitionTableAmbiguous && namesSupported
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f)
        FactRow(
            label = stringResource(R.string.partitions_info_name_label),
            value = selected.name.ifBlank { "—" },
            enabled = renameEnabled,
            interactive = namesSupported,
            onClick = if (namesSupported) {{ onRename(selected) }} else null,
        )
        HorizontalDivider(color = dividerColor)
        val labelFs = selected.filesystemId
        val labelSupported = labelFs != null && FormatCatalog.supportsVolumeLabelRename(labelFs)
        val labelEnabled = labelSupported && state.actionsEnabled && state.tableValid &&
            !state.partitionTableDamaged && !state.partitionTableAmbiguous && blockedHint == null
        FactRow(
            label = stringResource(R.string.partitions_volume_label_label),
            value = selected.volumeLabel?.ifBlank { "—" } ?: "—",
            enabled = labelEnabled,
            interactive = labelSupported,
            onClick = if (labelSupported) {{ onLabel(selected) }} else null,
        )
        HorizontalDivider(color = dividerColor)
        FactRow(
            label = stringResource(R.string.partitions_filesystem_label),
            value = selected.filesystemLabel.ifEmpty { "—" },
            enabled = state.actionsEnabled && blockedHint == null,
            interactive = true,
            editPrimary = false,
            onClick = {
                state.device?.let { device ->
                    onFilesystemMenu(
                        PartitionBinding(
                            device = device,
                            partitionId = selected.id,
                            startLba = selected.startLba,
                            sectorCount = selected.sectorCount,
                        ),
                    )
                }
            },
        )
        HorizontalDivider(color = dividerColor)
        val editRoleEnabled = state.actionsEnabled && state.tableValid &&
            !state.partitionTableDamaged && geometryKnown &&
            state.isEfiWriteAllowed(selected)
        val vtoyRoleDerived = selected.isVtoyEfiRole
        val typeLabel = stringResource(
            if (selected.displayEfi) R.string.partitions_info_type_efi
            else R.string.partitions_info_type_data,
        )
        FactRow(
            label = stringResource(R.string.partitions_info_type_label),
            value = typeLabel,
            enabled = editRoleEnabled && !vtoyRoleDerived,
            interactive = !vtoyRoleDerived,
            editPrimary = false,
            onClick = if (!vtoyRoleDerived) {
                {
                    state.device?.let { device ->
                        onIntent(
                            UiIntent.RequestSetPartitionRole(
                                role = if (selected.isEfiType) {
                                    PartitionRole.Data
                                } else {
                                    PartitionRole.Efi
                                },
                                target = PartitionBinding(
                                    device,
                                    selected.id,
                                    selected.startLba,
                                    selected.sectorCount,
                                ),
                            ),
                        )
                    }
                }
            } else null,
        )
        if (vtoyRoleDerived) {
            Text(
                text = stringResource(R.string.partitions_vtoyefi_role_derived),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = dividerColor)
        val bootableToggleEnabled = editRoleEnabled && !vtoyRoleDerived
        val bootableToggleState =
            stringResource(if (selected.displayBootable) R.string.answer_yes else R.string.answer_no)
        FactRow(
            label = stringResource(R.string.bootable_label),
            value = bootableToggleState,
            enabled = bootableToggleEnabled,
            interactive = !vtoyRoleDerived,
            editPrimary = false,
            onClick = if (!vtoyRoleDerived) {
                {
                    state.device?.let { device ->
                        onIntent(
                            UiIntent.RequestSetPartitionBootable(
                                bootable = !selected.bootable,
                                target = PartitionBinding(
                                    device,
                                    selected.id,
                                    selected.startLba,
                                    selected.sectorCount,
                                ),
                            ),
                        )
                    }
                }
            } else null,
        )
        HorizontalDivider(color = dividerColor)
        val usage = state.partitionUsageSnapshot(selected)?.usage
        val freeSpaceText = when (usage) {
            is FilesystemUsage.Known -> DeviceIdentity.formatCapacity(usage.space.freeBytes)
            else -> when (state.partitionUsagePhase(selected)) {
                FilesystemUsagePhase.Queued -> stringResource(R.string.usage_queued)
                FilesystemUsagePhase.Waiting -> stringResource(R.string.usage_waiting)
                FilesystemUsagePhase.Calculating -> stringResource(R.string.usage_calculating)
                else -> "—"
            }
        }
        FactRow(
            label = stringResource(R.string.partitions_strip_free),
            value = freeSpaceText,
            enabled = false,
        )
        HorizontalDivider(color = dividerColor)
        val access = state.partitionWriteAccess(selected)
        val accessText = when (access) {
            PartitionWriteAccess.ReadOnly,
            PartitionWriteAccess.DriverReadOnly,
            -> stringResource(R.string.partitions_write_state_read_only)
            PartitionWriteAccess.ReadWrite ->
                stringResource(R.string.partitions_write_state_read_write)
            PartitionWriteAccess.Unknown ->
                stringResource(R.string.partitions_write_state_unknown)
        }
        FactRow(
            label = stringResource(R.string.partitions_info_access_label),
            value = accessText,
            enabled = false,
        )
        if (selected.readOnly) {
            HorizontalDivider(color = dividerColor)
            val efiPolicyEnabled = state.isEfiWriteAllowed(selected)
            val efiBinding = state.device?.let { device ->
                PartitionBinding(device, selected.id, selected.startLba, selected.sectorCount)
            }
            val efiGeometryKnown = selected.startLba != null && selected.sectorCount != null
            SwitchRow(
                label = stringResource(R.string.efi_allow_writes),
                checked = efiPolicyEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && efiBinding != null) {
                        onIntent(UiIntent.RequestSetVtoyEfiWritable(efiBinding))
                    } else if (!enabled) {
                        onIntent(UiIntent.SetVtoyEfiWritable(selected.id, false))
                    }
                },
                enabled = state.actionsEnabled && !state.partitionTableAmbiguous &&
                    !state.partitionTableDamaged && state.tableValid && efiBinding != null &&
                    efiGeometryKnown,
                hint = null,
            )
            Text(
                text = stringResource(R.string.partitions_efi_ro_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val formatTarget = filesystemMenuTarget
        if (formatTarget != null) {
            ModalBottomSheet(
                onDismissRequest = { onFilesystemMenu(null) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.partitions_format_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Text(
                        text = listOfNotNull(
                            selected.name.ifBlank { selected.id },
                            partitionSizeLabel(state, selected),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    Column(modifier = Modifier.selectableGroup()) {
                        FormatCatalog.supportedFormatIds().forEach { formatId ->
                            val formatSelected = state.selectedFormatId == formatId
                            ListItem(
                                headlineContent = {
                                    Text(FormatCatalog.label(formatId))
                                },
                                supportingContent = {
                                    Text(formatDescription(formatId))
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = formatSelected,
                                        onClick = null,
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = formatSelected,
                                        role = Role.RadioButton,
                                        onClick = {
                                            onFilesystemMenu(null)
                                            if (formatTarget.matches(latestState)) {
                                                onIntent(
                                                    UiIntent.SelectFormatFilesystem(formatId),
                                                )
                                                onIntent(
                                                    UiIntent.RequestDestructive(
                                                        DestructiveAction.FORMAT_PARTITION,
                                                    ),
                                                )
                                            }
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
            },
            enabled = state.actionsEnabled && blockedHint == null &&
                !state.partitionTableDamaged,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text(stringResource(R.string.partitions_delete_action))
        }
    }
}

@Composable
private fun FactRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    interactive: Boolean = false,
    editPrimary: Boolean = true,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (onClick != null) 48.dp else 44.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = if (mono) {
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (interactive && onClick != null) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (!enabled) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                } else if (editPrimary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawWriteSection(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onPickImage: () -> Unit,
    rawTargetMode: RawTargetMode,
    onRawTargetMode: (RawTargetMode) -> Unit,
    orderedPartitions: List<PartitionTab>,
) {
    var sourceSheetOpen by remember { mutableStateOf(false) }
    val rawName = state.stagedRawImageLabel ?: rawSourceName(state.imageUri)
    val rawStaged = state.stagedRawImagePath != null
    val hasImage = rawName != null
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasImage) {
                FilledTonalButton(
                    onClick = { sourceSheetOpen = true },
                    enabled = state.actionsEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.partitions_raw_pick_image))
                }
                if (sourceSheetOpen) {
                    ModalBottomSheet(
                        onDismissRequest = { sourceSheetOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 3.dp,
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.partitions_raw_pick_image),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.partitions_raw_source_phone))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        sourceSheetOpen = false
                                        onPickImage()
                                    },
                            )
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.partitions_raw_source_stick))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        sourceSheetOpen = false
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
                    }
                }
            } else {
                val sourceBytes = state.rawWriteDecodedBytes()
                val sizeLabel = when {
                    sourceBytes != null && sourceBytes >= 0L ->
                        DeviceIdentity.formatCapacity(sourceBytes.toULong())
                    state.stagedRawImageSizeBytes != null ->
                        DeviceIdentity.formatCapacity(state.stagedRawImageSizeBytes!!.toULong())
                    else -> "—"
                }
                val sectorSize = state.diskSectorSize.coerceAtLeast(1)
                val sourceSectors = sourceBytes?.takeIf { it > 0L }?.let {
                    ((it + sectorSize - 1L) / sectorSize)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rawName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        (state.stagedRawImagePath ?: state.imageUri)?.let { path ->
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = buildString {
                                append(sizeLabel)
                                if (sourceSectors != null) {
                                    append(" · ")
                                    append(
                                        stringResource(
                                            R.string.partitions_raw_source_sectors,
                                            sourceSectors,
                                        ),
                                    )
                                }
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val clearA11y = stringResource(R.string.partitions_raw_clear_image_a11y)
                    IconButton(
                        onClick = {
                            if (rawStaged) {
                                onIntent(UiIntent.ClearStagedRawImage(deleteFile = true))
                            } else {
                                onIntent(UiIntent.SetImage(uri = null))
                            }
                        },
                        enabled = state.operation?.state != OperationState.Running &&
                            state.operation?.state != OperationState.Queued,
                        modifier = Modifier.semantics { contentDescription = clearA11y },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                        )
                    }
                }

                val selected = state.selectedPartition()
                val selectedIndex = orderedPartitions.indexOfFirst { it.id == selected?.id }
                    .takeIf { it >= 0 }?.plus(1)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = rawTargetMode == RawTargetMode.WholeDisk,
                        onClick = { onRawTargetMode(RawTargetMode.WholeDisk) },
                        label = { Text(stringResource(R.string.partitions_raw_target_whole)) },
                    )
                    FilterChip(
                        selected = rawTargetMode == RawTargetMode.Partition,
                        onClick = { onRawTargetMode(RawTargetMode.Partition) },
                        enabled = selected?.startLba != null,
                        label = {
                            Text(
                                stringResource(
                                    R.string.partitions_raw_target_partition,
                                    selectedIndex ?: 0,
                                ),
                            )
                        },
                    )
                    FilterChip(
                        selected = rawTargetMode == RawTargetMode.Custom,
                        onClick = { onRawTargetMode(RawTargetMode.Custom) },
                        label = { Text(stringResource(R.string.partitions_raw_target_custom)) },
                    )
                }

                val geometry = state.rawGeometry
                var customStart by remember(
                    state.device?.identity,
                    state.imageUri,
                    state.stagedRawImagePath,
                ) { mutableStateOf("0") }
                if (rawTargetMode == RawTargetMode.Custom) {
                    OutlinedTextField(
                        value = customStart,
                        onValueChange = { customStart = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.raw_write_start_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (geometry == null) {
                    EmptyHint(stringResource(R.string.raw_write_geometry_unavailable))
                } else {
                    val draftStart: ULong? = when (rawTargetMode) {
                        RawTargetMode.WholeDisk -> 0UL
                        RawTargetMode.Partition -> selected?.startLba
                        RawTargetMode.Custom -> customStart.trim().toULongOrNull()
                    }
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
                        TextButton(onClick = { onIntent(UiIntent.CancelRawSourceMeasurement) }) {
                            Text(stringResource(R.string.raw_write_cancel_measure))
                        }
                    } else if (state.rawMeasurementWaitingForPrevious) {
                        Text(
                            text = stringResource(R.string.raw_write_size_waiting_previous),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onIntent(UiIntent.CancelRawSourceMeasurement) }) {
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

                    val rangeAssessment = planRange?.let { range ->
                        assessRawRange(
                            state = state,
                            geometry = geometry,
                            rangeStart = range.startLba,
                            rangeCount = range.sectorCount,
                            selected = selected,
                        )
                    }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        val diskSectors = geometry.diskSectorCount.coerceAtLeast(1UL)
                        val rangeStart = planRange?.startLba ?: 0UL
                        val rangeCount = planRange?.sectorCount ?: 0UL
                        val rangeEnd = if (rangeStart <= ULong.MAX_VALUE - rangeCount) {
                            rangeStart + rangeCount
                        } else {
                            ULong.MAX_VALUE
                        }
                        val startFraction = (rangeStart.toDouble() / diskSectors.toDouble())
                            .coerceIn(0.0, 1.0)
                        val endFraction = (rangeEnd.toDouble() / diskSectors.toDouble())
                            .coerceIn(startFraction, 1.0)
                        val spanFraction = (endFraction - startFraction).coerceAtLeast(0.0)
                        if (planRange != null && rangeAssessment != null && spanFraction > 0.0) {
                            Box(
                                modifier = Modifier
                                    .offset(x = maxWidth * startFraction.toFloat())
                                    .width(maxWidth * spanFraction.toFloat())
                                    .fillMaxHeight()
                                    .background(
                                        if (rangeAssessment.ok) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    ),
                            )
                        }
                    }

                    planRange?.let { range ->
                        val end = range.startLba + range.sectorCount - 1UL
                        val verdictCore = stringResource(
                            R.string.partitions_raw_verdict,
                            range.startLba.toLong(),
                            end.toLong(),
                        )
                        val warning = rangeAssessment?.message
                        Text(
                            text = if (warning != null) "$verdictCore · $warning" else verdictCore,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontFeatureSettings = "tnum",
                            ),
                            color = if (rangeAssessment?.ok == true) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
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
                                is RawRangeWarning.MetadataCoverageUnknown -> Unit
                                is RawRangeWarning.OverlapsObjects -> Unit
                            }
                        }
                    }

                    val attachment = state.device?.let {
                        RangeProbeAttachment(
                            identity = it.identity,
                            attachGeneration = it.attachGeneration,
                        )
                    }
                    val probe = state.rangeProbe
                    val probeFresh = planRange?.let { range ->
                        attachment != null && probe != null && probe.matches(
                            range,
                            geometry.diskSectorCount,
                            geometry.sectorSize,
                            attachment,
                        )
                    } == true
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

                    FilledTonalButton(
                        onClick = {
                            draftStart?.let { start ->
                                onIntent(UiIntent.RequestRawWrite(start))
                            }
                        },
                        enabled = state.actionsEnabled && planRange != null &&
                            probeFresh && planProblems.isEmpty() &&
                            rangeAssessment?.ok == true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(stringResource(R.string.partitions_raw_write_sectors))
                    }
                }
            }
        }
    }
}

private data class RawRangeAssessment(
    val ok: Boolean,
    val outOfDisk: Boolean,
    val roEfiBlocked: Boolean,
    val message: String?,
)

@Composable
private fun assessRawRange(
    state: AppUiState,
    geometry: usb.partition.RawRangeGeometry,
    rangeStart: ULong,
    rangeCount: ULong,
    selected: PartitionTab?,
): RawRangeAssessment {
    if (rangeCount == 0UL || rangeStart > ULong.MAX_VALUE - rangeCount) {
        return RawRangeAssessment(
            ok = false,
            outOfDisk = true,
            roEfiBlocked = false,
            message = stringResource(R.string.partitions_raw_range_out),
        )
    }
    val rangeEnd = rangeStart + rangeCount
    if (rangeEnd > geometry.diskSectorCount) {
        return RawRangeAssessment(
            ok = false,
            outOfDisk = true,
            roEfiBlocked = false,
            message = stringResource(R.string.partitions_raw_range_out),
        )
    }
    val overlaps = geometry.overlaps(rangeStart, rangeCount)
    val touchesTableOrBoot = overlaps.any {
        it.target.kind == RawObjectKind.TableArea || it.target.kind == RawObjectKind.BootArea
    }
    val touchesLba0 = rangeStart == 0UL
    val partitionHits = overlaps.filter { it.target.kind == RawObjectKind.Partition }
    val selectedStart = selected?.startLba
    val selectedCount = selected?.sectorCount
    val selectedEnd =
        if (selectedStart != null && selectedCount != null &&
            selectedStart <= ULong.MAX_VALUE - selectedCount
        ) {
            selectedStart + selectedCount
        } else {
            null
        }
    val onlySelected = selectedStart != null && selectedEnd != null &&
        rangeStart >= selectedStart && rangeEnd <= selectedEnd &&
        partitionHits.isNotEmpty() &&
        partitionHits.all {
            it.target.startLba == selectedStart &&
                it.target.endLbaExclusive == selectedEnd
        } &&
        !touchesTableOrBoot
    val insideFree = partitionHits.isEmpty() && !touchesTableOrBoot && !touchesLba0
    val refinedOk = insideFree || onlySelected

    var roEfiBlocked = false
    for (hit in partitionHits) {
        val part = state.partitions.firstOrNull { p ->
            p.startLba == hit.target.startLba && p.sectorCount == hit.target.sectorCount
        } ?: continue
        val access = state.partitionWriteAccess(part)
        val readOnly = access == PartitionWriteAccess.ReadOnly ||
            access == PartitionWriteAccess.DriverReadOnly || part.readOnly
        if (readOnly && !state.isEfiWriteAllowed(part)) {
            roEfiBlocked = true
            break
        }
    }

    val message = when {
        !refinedOk -> stringResource(R.string.partitions_raw_range_touches_protected)
        roEfiBlocked -> stringResource(R.string.partitions_raw_range_ro_efi)
        else -> stringResource(R.string.partitions_raw_range_ok)
    }
    return RawRangeAssessment(
        ok = refinedOk && !roEfiBlocked,
        outOfDisk = false,
        roEfiBlocked = roEfiBlocked,
        message = message,
    )
}

/**
 * Exact LBA overview: widths follow diskSectorCount with leading/internal/trailing gaps.
 * Segments under 48dp stay noninteractive overview; partition list and Create button remain
 * the 48dp selection/create fallback. Invalid geometry shows a localized reason instead of a map.
 */
private data class StripSegment(
    val partition: PartitionTab? = null,
    val free: FreeRegionUi? = null,
    val gapStart: ULong? = null,
    val gapSectorCount: ULong? = null,
)
private fun segmentStart(segment: StripSegment): ULong =
    segment.partition?.startLba ?: segment.free?.startLba ?: segment.gapStart ?: 0UL

private fun segmentSpan(segment: StripSegment): ULong =
    segment.partition?.sectorCount ?: segment.free?.sectorCount ?: segment.gapSectorCount ?: 0UL
private fun segmentEnd(segment: StripSegment): ULong =
    segmentStart(segment).let { start ->
        val span = segmentSpan(segment)
        if (start > ULong.MAX_VALUE - span) ULong.MAX_VALUE else start + span
    }

@Composable
private fun PartitionSelectStrip(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onSelectFree: (FreeRegionUi) -> Unit,
) {
    val partitions = state.partitions
        .filter { (it.sectorCount ?: 0uL) > 0uL }
        .sortedWith(compareBy<PartitionTab> { it.startLba ?: ULong.MAX_VALUE }.thenBy { it.id })
    val creatableStarts = mibAlignSectors(state.diskSectorSize)?.let { align ->
        state.freeRegions.mapNotNull { region ->
            val skip = (align - region.startLba % align) % align
            region.startLba.takeIf { skip < region.sectorCount }
        }.toSet()
    }.orEmpty()
    val diskSectorCount = state.device?.capacityBytes
        ?.div(state.diskSectorSize.coerceAtLeast(1).toULong())
        ?.takeIf { it > 0UL }
        ?: return
    val sourceSegments = buildList {
        partitions.forEach { add(StripSegment(partition = it)) }
        state.freeRegions.filter { it.sectorCount > 0UL }.forEach { add(StripSegment(free = it)) }
    }.sortedWith(compareBy { segmentStart(it) })
    val geometryValid = sourceSegments.zipWithNext().all { (a, b) ->
        segmentEnd(a) >= segmentStart(a) && segmentEnd(a) <= segmentStart(b)
    } && sourceSegments.all {
        segmentStart(it) <= diskSectorCount && segmentEnd(it) >= segmentStart(it) && segmentEnd(it) <= diskSectorCount
    }
    val segments = if (geometryValid) {
        buildList {
            var cursor = 0UL
            sourceSegments.forEach { segment ->
                val start = segmentStart(segment)
                if (start > cursor) add(StripSegment(gapStart = cursor, gapSectorCount = start - cursor))
                add(segment)
                cursor = segmentEnd(segment)
            }
            if (diskSectorCount > cursor) {
                add(StripSegment(gapStart = cursor, gapSectorCount = diskSectorCount - cursor))
            }
        }
    } else emptyList()
    if (!geometryValid) {
        Text(
            text = stringResource(R.string.partitions_strip_geometry_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    if (segments.isEmpty()) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalSectors = diskSectorCount
        val availablePx = with(density) { maxWidth.toPx() }
        val minimumPx = with(density) { 48.dp.toPx() }
        // Keep the real LBA proportions for usable segments, but never shrink a segment
        // below 48 dp. The resulting track may be wider than the viewport and scrolls
        // horizontally; every partition/free segment keeps a real touch target.
        val widthsPx = segments.map { segment ->
            (segmentSpan(segment).toDouble() / totalSectors.toDouble() * availablePx)
                .toFloat().coerceAtLeast(minimumPx)
        }
        val widths = widthsPx.map { with(density) { it.toDp() } }
        val totalWidth = with(density) { widthsPx.sum().toDp() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
        Row(
            modifier = Modifier
                .width(totalWidth)
                .height(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEachIndexed { index, segment ->
                val partition = segment.partition
                val isSelected = partition?.id == state.selectedPartitionId
                val partitionNumber = partition?.let { part -> partitions.indexOfFirst { it.id == part.id } + 1 }
                val description = if (partition != null) {
                    stringResource(R.string.partitions_strip_item_a11y, partitionNumber ?: 0,
                        partition.name.ifBlank { partition.id }, partitionSizeLabel(state, partition) ?: "—")
                } else if (segment.free != null) {
                    listOfNotNull(
                        stringResource(R.string.partitions_strip_free),
                        lbaRangeLabel(segment.free.startLba, segment.free.sectorCount),
                    ).joinToString(" · ")
                } else {
                    stringResource(R.string.partitions_strip_unallocated)
                }
                val freeSelectable = segment.free?.let { free ->
                    state.tableValid && !state.partitionTableDamaged && free.startLba in creatableStarts
                } == true
                val fill = when {
                    partition != null && isSelected -> MaterialTheme.colorScheme.primary
                    partition != null -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
                val interactive = widths[index] >= 48.dp && (
                    partition != null || (segment.free != null && freeSelectable)
                )
                Box(
                    modifier = Modifier
                        .width(widths[index])
                        .fillMaxHeight()
                        .background(fill)
                        .then(
                            when {
                                isSelected -> Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface)
                                segment.free != null -> Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                )
                                else -> Modifier
                            },
                        )
                        .then(
                            when {
                                interactive && partition != null -> Modifier.selectable(
                                    selected = isSelected,
                                    enabled = state.actionsEnabled,
                                    role = Role.RadioButton,
                                    onClick = { onIntent(UiIntent.SelectPartition(partition.id)) },
                                )
                                interactive && segment.free != null -> Modifier.clickable(
                                    enabled = state.actionsEnabled,
                                    role = Role.Button,
                                    onClick = { onSelectFree(segment.free) },
                                )
                                else -> Modifier.clearAndSetSemantics { }
                            },
                        )
                        .then(
                            if (interactive) {
                                Modifier.semantics { contentDescription = description }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (partitionNumber != null && partitionNumber > 0) {
                        Text(partitionNumber.toString(), style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        }
    }
}
// PAR/RAW matrix: layout title, sorted LBA rows, 48dp abstract selectable/free segments,
// sticky selection, GPT/MBR warning actions, create-in-list, EFI policy, bound editors,
// derived raw end, overlap list, probe and dual source pickers are wired above.






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
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.partitions_range_tick_edge_label,
                    maxMib.toLong(),
                    rightEdgeLastLba.toLong(),
                ),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Size in bytes from the live table, or null when the geometry cannot express it. */
private fun partitionSizeBytes(state: AppUiState, partition: PartitionTab): ULong? {
    val sectors = partition.sectorCount ?: return null
    val sectorSize = state.diskSectorSize
    if (sectorSize <= 0 || sectors > ULong.MAX_VALUE / sectorSize.toULong()) return null
    return sectors * sectorSize.toULong()
}

private fun partitionSizeLabel(state: AppUiState, partition: PartitionTab): String? =
    partitionSizeBytes(state, partition)?.let { DeviceIdentity.formatCapacity(it) }

/** Display measured usage or the honest phase of the current per-partition probe. */
@Composable
private fun partitionUsageLabel(
    state: AppUiState,
    partition: PartitionTab,
    space: FilesystemSpace?,
): String {
    val measured = state.partitionUsageSnapshot(partition)?.usage as? FilesystemUsage.Known
    if (measured != null) {
        return DeviceIdentity.formatCapacityPair(measured.space.usedBytes, measured.space.totalBytes)
    }
    return when (state.partitionUsagePhase(partition)) {
        FilesystemUsagePhase.Queued -> stringResource(R.string.usage_queued)
        FilesystemUsagePhase.Waiting -> stringResource(R.string.usage_waiting)
        FilesystemUsagePhase.Calculating -> stringResource(R.string.usage_calculating)
        null -> {
            val max = space?.totalBytes ?: partitionSizeBytes(state, partition)
            if (max == null) "—" else "— / ${DeviceIdentity.formatCapacity(max)}"
        }
    }
}

/**
 * "LBA start–end · count sectors" (or another [templateId] shape) for one exact range, or
 * null when the geometry is unknown or the end LBA would overflow. The end is
 * start + count - 1, never a guess. [templateId] is a string resource id.
 */
@Composable
private fun lbaRangeLabel(
    start: ULong?,
    count: ULong?,
    templateId: Int = R.string.partitions_lba_range,
): String? {
    if (start == null || count == null || count == 0UL) return null
    if (start > ULong.MAX_VALUE - (count - 1UL)) return null
    return stringResource(
        templateId,
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


/** Disk size and total LBA count for the Layout card; null when geometry is unknown. */
private data class DiskGeometry(
    val sizeLabel: String,
    val totalSectors: ULong,
)

/** Size and block/LBA totals only: the layout card no longer claims used vs free space. */
private fun diskGeometry(state: AppUiState): DiskGeometry? {
    if (state.diskSectorSize <= 0) return null
    val sectorSize = state.diskSectorSize.toULong()
    val totalBytes = state.device?.capacityBytes ?: 0UL
    if (sectorSize == 0UL || totalBytes == 0UL) return null
    val totalSectors = totalBytes / sectorSize
    if (totalSectors == 0UL) return null
    return DiskGeometry(
        sizeLabel = DeviceIdentity.formatCapacity(totalBytes),
        totalSectors = totalSectors,
    )
}


/** Explanation for a blocked destructive action, or null when allowed. */
@Composable
private fun writeBlockHint(state: AppUiState): String? =
    // Keep the reducer guard for format/delete/label. Callers hide only the
    // duplicate EFI RO caption via showBlockedHint; other causes stay visible.
    UiReducer.writeBlockMessage(state)?.localized()


/** One-line engine summary shown under the filesystem label in the format sheet. */
@Composable
private fun formatDescription(id: FilesystemId): String = stringResource(
    when (id) {
        FilesystemId.FAT -> R.string.partitions_format_fat_desc
        FilesystemId.EXFAT -> R.string.partitions_format_exfat_desc
        FilesystemId.EXT -> R.string.partitions_format_ext_desc
        FilesystemId.NTFS -> R.string.partitions_format_ntfs_desc
    },
)
