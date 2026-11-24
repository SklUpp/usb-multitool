package usb.ui

import usb.fs.FilesystemId
import usb.fs.FormatCatalog
import usb.fs.VolumeLabel
import usb.partition.PartitionName
import usb.partition.TableScheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import usb.image.RangeProbeAttachment
import usb.image.RangeProbeFinding
import usb.partition.RawObjectKind
import usb.partition.RawRangePlan
import usb.partition.RawRangeProblem
import usb.partition.RawRangeWarning
import usb.partition.RawWritePlan
import usb.multitool.R

/** Small pull toward MiB/partition boundaries; the slider still steps whole MiB. */
private const val MAGNET_THRESHOLD_MIB = 0.8f

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
    var startMibInput by remember { mutableStateOf("") }
    var endMibInput by remember { mutableStateOf("") }
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
    // Raw image writing is whole-device semantics; the chart and confirmation still
    // make the overwritten range explicit before anything is submitted.
    var initializeScheme by remember(state.device, state.partitionScheme, partitionGeometryKey) {
        mutableStateOf(PartitionScheme.Gpt)
    }
    val namesSupported = state.partitionScheme?.let { PartitionName.supported(it) } == true
    // Whole-MiB-aligned start of every free region; the create dialog picks among them.
    val creatableRegions = remember(state.freeRegions, state.diskSectorSize) {
        val align = mibAlignSectors(state.diskSectorSize) ?: return@remember emptyList()
        state.freeRegions.mapNotNull { region ->
            val skip = (align - region.startLba % align) % align
            val remaining = region.sectorCount - skip
            if (remaining >= align) {
                Triple(region, region.startLba + skip, remaining)
            } else {
                null
            }
        }
    }
    fun openCreate(region: FreeRegionUi, start: ULong, sectors: ULong) {
        // The dialog works in whole MiB; a sub-MiB tail stays free.
        val align = mibAlignSectors(state.diskSectorSize) ?: 1UL
        createRegion = region
        startLbaInput = start.toString()
        val alignedCount = (sectors / align) * align
        sectorCount = alignedCount.toString()
        createName = ""
        createLabel = ""
        createFormatId = state.selectedFormatId
        val firstAligned = region.startLba + if (region.startLba % align == 0UL) 0UL else align - region.startLba % align
        val startMib = if (start >= firstAligned) (start - firstAligned) / align else 0UL
        val endMib = startMib + (alignedCount / align)
        startMibInput = startMib.toString()
        endMibInput = endMib.toString()
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
                startOffsetSectors / alignSectors
            } else {
                0UL
            }
        val sliderEnd =
            if (alignSectors != null && endOffsetSectors != null) {
                (endOffsetSectors / alignSectors).coerceAtLeast(sliderStart)
            } else {
                sliderStart
            }
        // The slider is shown only while the typed geometry is MiB-aligned: a fabricated
        // 0..max range would claim boundaries the fields do not hold.
        val sliderVisible =
            alignSectors != null && firstAligned != null && startOffsetSectors != null &&
            availableMib >= 1UL && sliderStart <= availableMib && sliderEnd <= availableMib

        val parsedStartMib = startMibInput.toULongOrNull()
        val parsedEndMib = endMibInput.toULongOrNull()
        val startMibProblem = when {
            startMibInput.isNotBlank() && parsedStartMib == null ->
                stringResource(R.string.partitions_create_error_out_of_bounds, availableMib.toLong())
            parsedStartMib != null && parsedStartMib > availableMib ->
                stringResource(R.string.partitions_create_error_out_of_bounds, availableMib.toLong())
            parsedStartMib != null && parsedEndMib != null && parsedStartMib > parsedEndMib ->
                stringResource(R.string.partitions_create_start_error_greater_than_end)
            else -> null
        }
        val endMibProblem = when {
            endMibInput.isNotBlank() && parsedEndMib == null ->
                stringResource(R.string.partitions_create_error_out_of_bounds, availableMib.toLong())
            parsedEndMib != null && parsedEndMib > availableMib ->
                stringResource(R.string.partitions_create_error_out_of_bounds, availableMib.toLong())
            parsedStartMib != null && parsedEndMib != null && parsedEndMib <= parsedStartMib ->
                stringResource(R.string.partitions_create_end_error_less_than_start)
            else -> null
        }
        // The confirm action submits LBA/count, so the visible fields must describe exactly
        // that geometry; a half-applied text edit must never create a different range.
        val mibInputsValid = startMibProblem == null && endMibProblem == null &&
            parsedStartMib == sliderStart && parsedEndMib == sliderEnd &&
            sliderEnd > sliderStart
        val valid = regionFits && isAligned && fits && extraValid && mibInputsValid

        AlertDialog(
            onDismissRequest = { createRegion = null },
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.partitions_create_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                    if (alignSectors == null || firstAligned == null || firstAligned >= regionEnd) {
                        Text(
                            text = if (alignSectors == null || firstAligned == null) {
                                stringResource(
                                    R.string.partitions_create_free_sectors,
                                    region.sectorCount.toLong(),
                                    state.diskSectorSize,
                                )
                            } else {
                                stringResource(R.string.partitions_create_no_aligned_space)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (sliderVisible && alignSectors != null && firstAligned != null) {
                        val mibSectors = alignSectors
                        val regionBase = firstAligned
                        val maxMibFloat = availableMib.toFloat()
                        val safeStart = sliderStart.toFloat()
                        val safeEnd = sliderEnd.toFloat()
                        val partitionSizesMib = state.partitions.mapNotNull { part ->
                            part.sectorCount?.div(mibSectors)?.takeIf { it > 0UL }
                        }
                        val baseMagnetPoints = remember(availableMib, mibSectors, regionBase) {
                            PartitionRangeMath.snapPoints(
                                availableMib = availableMib,
                                firstAligned = regionBase,
                                sectorsPerMib = mibSectors,
                            )
                        }
                        // Size magnets are relative to the opposite thumb: moving the
                        // start keeps the end fixed, moving the end keeps the start fixed.
                        val startMagnetPoints = baseMagnetPoints +
                            PartitionRangeMath.sizePointsBelow(sliderEnd, partitionSizesMib)
                        val endMagnetPoints = baseMagnetPoints +
                            PartitionRangeMath.sizePointsAbove(
                                sliderStart,
                                availableMib,
                                partitionSizesMib,
                            )
                        val magnetHaptic = LocalHapticFeedback.current
                        var lastStartMagnet by remember { mutableStateOf<ULong?>(null) }
                        var lastEndMagnet by remember { mutableStateOf<ULong?>(null) }
                        val startLba = regionBase + sliderStart * mibSectors
                        val endLba = (regionBase + sliderEnd * mibSectors).let {
                            if (it > regionBase) it - 1UL else it
                        }
                        val rangeA11y = stringResource(R.string.partitions_range_a11y)
                        val rangeState = stringResource(
                            R.string.partitions_range_a11y_state,
                            sliderStart.toLong(),
                            startLba.toLong(),
                            sliderEnd.toLong(),
                            endLba.toLong(),
                            (sliderEnd - sliderStart).toLong(),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            RangeSlider(
                                value = safeStart..safeEnd,
                                onValueChange = { range ->
                                    // Whole MiB first; a small magnet only for live region
                                    // boundaries and existing partition sizes, with haptics
                                    // as the signal that the thumb was pulled.
                                    val startMagnet = PartitionRangeMath.nearestMagnet(
                                        position = range.start,
                                        maxMib = availableMib,
                                        snapPoints = startMagnetPoints,
                                        thresholdMib = MAGNET_THRESHOLD_MIB,
                                    )
                                    val endMagnet = PartitionRangeMath.nearestMagnet(
                                        position = range.endInclusive,
                                        maxMib = availableMib,
                                        snapPoints = endMagnetPoints,
                                        thresholdMib = MAGNET_THRESHOLD_MIB,
                                    )
                                    if (startMagnet != null && startMagnet != lastStartMagnet) {
                                        magnetHaptic.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                    }
                                    if (endMagnet != null && endMagnet != lastEndMagnet) {
                                        magnetHaptic.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                    }
                                    lastStartMagnet = startMagnet
                                    lastEndMagnet = endMagnet
                                    val snapStart =
                                        (startMagnet ?: PartitionRangeMath.snapToMib(
                                            range.start,
                                            availableMib,
                                        )).coerceAtMost(
                                            if (availableMib > 1UL) availableMib - 1UL else 0UL,
                                        )
                                    val snapEnd =
                                        (endMagnet ?: PartitionRangeMath.snapToMib(
                                            range.endInclusive,
                                            availableMib,
                                        )).coerceAtLeast(snapStart + 1UL)
                                            .coerceAtMost(availableMib)
                                    val selection = PartitionRangeMath.sectorsFor(
                                        firstAligned = regionBase,
                                        regionEnd = regionEnd,
                                        startMib = snapStart,
                                        endMib = snapEnd,
                                        sectorsPerMib = mibSectors,
                                    )
                                    if (selection != null) {
                                        startLbaInput = selection.first.toString()
                                        sectorCount = selection.second.toString()
                                    } else {
                                        startLbaInput =
                                            (regionBase + snapStart * mibSectors).toString()
                                        sectorCount = "0"
                                    }
                                    startMibInput = snapStart.toString()
                                    endMibInput = snapEnd.toString()
                                },
                                valueRange = 0f..maxMibFloat,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = rangeA11y
                                        stateDescription = rangeState
                                    },
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = startMibInput,
                                    onValueChange = { input ->
                                        startMibInput = input
                                        val s = input.toULongOrNull()
                                        val e = parsedEndMib
                                        if (s != null && e != null && s < e && e <= availableMib) {
                                            val selection = PartitionRangeMath.sectorsFor(
                                                firstAligned = regionBase,
                                                regionEnd = regionEnd,
                                                startMib = s,
                                                endMib = e,
                                                sectorsPerMib = mibSectors,
                                            )
                                            if (selection != null) {
                                                startLbaInput = selection.first.toString()
                                                sectorCount = selection.second.toString()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.partitions_create_start_mib)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = startMibProblem != null,
                                    supportingText = startMibProblem?.let { msg -> { Text(msg) } },
                                )
                                OutlinedTextField(
                                    value = endMibInput,
                                    onValueChange = { input ->
                                        endMibInput = input
                                        val e = input.toULongOrNull()
                                        val s = parsedStartMib
                                        if (e != null && s != null && e > s && e <= availableMib) {
                                            val selection = PartitionRangeMath.sectorsFor(
                                                firstAligned = regionBase,
                                                regionEnd = regionEnd,
                                                startMib = s,
                                                endMib = e,
                                                sectorsPerMib = mibSectors,
                                            )
                                            if (selection != null) {
                                                startLbaInput = selection.first.toString()
                                                sectorCount = selection.second.toString()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.partitions_create_end_mib)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = endMibProblem != null,
                                    supportingText = endMibProblem?.let { msg -> { Text(msg) } },
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.partitions_create_size_mib,
                                    (sliderEnd - sliderStart).toLong(),
                                ),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (namesSupported) {
                            OutlinedTextField(
                                value = createName,
                                onValueChange = { createName = it.take(PartitionName.MAX_UTF16_UNITS * 2) },
                                label = { Text(stringResource(R.string.partitions_name_label)) },
                                singleLine = true,
                                isError = nameProblem != null,
                                supportingText = {
                                    Text(stringResource(R.string.partitions_name_support))
                                },
                            )
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
                    enabled = valid && state.actionsEnabled && sliderVisible,
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.extraLarge,
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
    LaunchedEffect(labelBinding, labelTarget, labelFs) {
        if (labelBinding != null && (labelTarget == null || labelFs == null)) {
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.extraLarge,
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
    val orderedPartitions = remember(state.partitions) {
        state.partitions.sortedWith(
            compareBy<PartitionTab> { it.startLba ?: ULong.MAX_VALUE }.thenBy { it.id },
        )
    }
    val currentScheme = state.partitionScheme?.toPartitionScheme()
    val schemeBlocked = state.partitionTableAmbiguous || state.partitionTableDamaged || !state.tableValid
    val blockedHint = writeBlockHint(state)
    val screenScroll = rememberScrollState()
    val latestState by rememberUpdatedState(state)
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var layoutBlockBottomWindow by remember(state.selectedPartitionId) {
        mutableFloatStateOf(Float.POSITIVE_INFINITY)
    }
    // The pinned copy appears only after the whole Layout block has scrolled above
    // the viewport; the block itself still owns the selector while visible.
    val pinnedVisible =
        selected != null && layoutBlockBottomWindow <= viewportTop

    LaunchedEffect(
        state.device,
        state.selectedPartitionId,
        selected?.startLba,
        selected?.sectorCount,
    ) {
        filesystemMenuTarget = null
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { viewportTop = it.positionInWindow().y },
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(screenScroll)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = stringResource(R.string.partitions_layout_title),
                subtitle = diskGeometry(state)?.let { geometry ->
                    val partitionCount = pluralStringResource(
                        R.plurals.partitions_count_label,
                        state.partitions.size,
                        state.partitions.size,
                    )
                    val specialCount = state.partitions.count { it.special }
                    val countLabel = stringResource(
                        R.string.partitions_layout_count_with_special,
                        partitionCount,
                        pluralStringResource(
                            R.plurals.partitions_layout_special_count,
                            specialCount,
                            specialCount,
                        ),
                    )
                    stringResource(
                        R.string.partitions_layout_subtitle,
                        stringResource(
                            R.string.partitions_layout_mib,
                            geometry.exactMib,
                        ),
                        formatGroupedULong(0UL),
                        formatGroupedULong(geometry.lastLbaInclusive),
                        countLabel,
                    )
                },
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    layoutBlockBottomWindow =
                        coordinates.positionInWindow().y + coordinates.size.height
                },
                titleColor = MaterialTheme.colorScheme.onSurface,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
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
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .wrapContentWidth()
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = capsuleA11y }
                                    .clickable(
                                        enabled = state.actionsEnabled && !schemeBlocked,
                                        role = Role.Button,
                                        onClick = {
                                            onIntent(
                                                UiIntent.RequestInitializePartitionTable(capsuleTarget),
                                            )
                                        },
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
                                        shape = MaterialTheme.shapes.extraSmall,
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
            ) {
                if (!state.partitionTableRecognized &&
                    state.partitionTableRecognition != usb.partition.TableRecognition.SUPERFLOPPY &&
                    !state.partitionTableAmbiguous
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
                    Button(
                        onClick = {
                            onIntent(UiIntent.RequestInitializePartitionTable(initializeScheme))
                        },
                        enabled = state.actionsEnabled && !state.partitionTableDamaged,
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
                        onSelectPartition = { id ->
                            onIntent(UiIntent.SelectPartition(id))
                        },
                        onSelectFree = { region ->
                            if (state.tableValid && !state.partitionTableDamaged) {
                                creatableRegions.firstOrNull { it.first == region }
                                    ?.let { openCreate(it.first, it.second, it.third) }
                            }
                        },
                    )
                    val overlaps = remember(state.partitions) {
                        overlappingPartitions(state.partitions)
                    }
                    if (overlaps.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.partitions_overlap_heading),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        overlaps.forEach { (first, second, sectors) ->
                            Text(
                                text = stringResource(
                                    R.string.partitions_overlap_line,
                                    first.name.ifBlank { first.id },
                                    second.name.ifBlank { second.id },
                                    sectors.toLong(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                Box {
                    PartitionDropdown(
                        partitions = orderedPartitions,
                        selectedId = (selected ?: orderedPartitions.firstOrNull())?.id,
                        writeAccessOf = state::partitionWriteAccess,
                        usageOf = state::partitionUsageSnapshot,
                        phaseOf = state::partitionUsagePhase,
                        sectorSize = state.diskSectorSize,
                        enabled = state.actionsEnabled,
                        onSelect = { onIntent(UiIntent.SelectPartition(it)) },
                        showLba = true,
                    )
                }
                Button(
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



            selected?.let { live ->
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

            state.pendingEfiWriteBinding?.let { binding ->
                val target = state.partitions.firstOrNull { it.id == binding.partitionId }
                val valid = target != null && binding.matches(state) && target.readOnly &&
                    state.actionsEnabled && state.tableValid && !state.partitionTableAmbiguous &&
                    !state.partitionTableDamaged
                AlertDialog(
                    onDismissRequest = { onIntent(UiIntent.DismissEfiWriteConfirmation) },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    tonalElevation = 6.dp,
                    shape = MaterialTheme.shapes.extraLarge,
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

            RawWriteSection(
                state = state,
                onIntent = onIntent,
                onPickImage = onPickImage,
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = pinnedVisible,
            enter = fadeIn(tween(140)) + expandVertically(tween(220), expandFrom = Alignment.Top) +
                slideInVertically(tween(220)) { -it / 3 },
            exit = fadeOut(tween(120)) +
                shrinkVertically(tween(160), shrinkTowards = Alignment.Top) +
                slideOutVertically(tween(160)) { -it / 3 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(2f),
        ) {
            selected?.let { live ->
                PartitionDropdown(
                    partitions = orderedPartitions,
                    selectedId = live.id,
                    writeAccessOf = state::partitionWriteAccess,
                    usageOf = state::partitionUsageSnapshot,
                    phaseOf = state::partitionUsagePhase,
                    sectorSize = state.diskSectorSize,
                    enabled = state.actionsEnabled,
                    onSelect = { onIntent(UiIntent.SelectPartition(it)) },
                    showLba = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        }
    }
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
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.partitions_selected_title),
        titleColor = MaterialTheme.colorScheme.onSurface,
        trailing = {
            InfoHelpButton(
                infoTitle = stringResource(R.string.partitions_info_help_title),
                infoText = listOf(
                    stringResource(R.string.partitions_info_help_text),
                    stringResource(R.string.partitions_vtoyefi_role_derived),
                ).joinToString("\n\n"),
            )
        },
        modifier = modifier,
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
        FactRow(
            label = stringResource(R.string.partitions_info_name_label),
            value = selected.name.ifBlank { "—" },
            enabled = renameEnabled,
            interactive = namesSupported,
            onClick = if (namesSupported) { { onRename(selected) } } else null,
        )
        val labelFs = selected.filesystemId
        val labelSupported = labelFs != null && FormatCatalog.supportsVolumeLabelRename(labelFs)
        val labelEnabled = labelSupported && state.actionsEnabled && state.tableValid &&
            !state.partitionTableDamaged && !state.partitionTableAmbiguous && blockedHint == null
        FactRow(
            label = stringResource(R.string.partitions_volume_label_label),
            value = selected.volumeLabel?.ifBlank { "—" } ?: "—",
            enabled = labelEnabled,
            interactive = labelSupported,
            onClick = if (labelSupported) { { onLabel(selected) } } else null,
        )
        if (labelFs == FilesystemId.EXT) {
            Text(
                text = stringResource(R.string.partitions_volume_label_ext_format_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FactRow(
            label = stringResource(R.string.partitions_filesystem_label),
            value = selected.filesystemLabel.ifEmpty { "—" },
            enabled = state.actionsEnabled && blockedHint == null,
            interactive = true,
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
        if (selected.displayEfi && selected.readOnly) {
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
        }
        val bootableToggleEnabled = editRoleEnabled && !vtoyRoleDerived
        val bootableToggleState =
            stringResource(if (selected.displayBootable) R.string.answer_yes else R.string.answer_no)
        FactRow(
            label = stringResource(R.string.bootable_label),
            value = bootableToggleState,
            enabled = bootableToggleEnabled,
            interactive = !vtoyRoleDerived,
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
        val formatTarget = filesystemMenuTarget?.takeIf { it.matches(latestState) }
        if (filesystemMenuTarget != null && formatTarget == null) {
            LaunchedEffect(filesystemMenuTarget) { onFilesystemMenu(null) }
        }
        if (formatTarget != null) {
            var formatChoice by remember(formatTarget) {
                mutableStateOf(
                    selected.filesystemId
                        ?.takeIf { it in FormatCatalog.supportedFormatIds() }
                        ?: state.selectedFormatId,
                )
            }
            ModalBottomSheet(
                onDismissRequest = { onFilesystemMenu(null) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.partitions_format_sheet_title_named,
                            selected.name.ifBlank { selected.id },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Column(modifier = Modifier.selectableGroup()) {
                        FormatCatalog.supportedFormatIds().forEach { formatId ->
                            val formatSelected = formatChoice == formatId
                            PopupChoiceBlock(
                                selected = formatSelected,
                                enabled = state.actionsEnabled,
                                role = Role.RadioButton,
                                onClick = {
                                    onFilesystemMenu(null)
                                    if (formatTarget.matches(latestState)) {
                                        formatChoice = formatId
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
                            ) {
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
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
            },
            enabled = state.actionsEnabled && blockedHint == null &&
                !state.partitionTableDamaged,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
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
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
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
        },
        trailingContent = if (interactive && onClick != null) {
            {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (!enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RawWriteSection(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onPickImage: () -> Unit,
) {
    var sourceSheetOpen by remember(
        state.device?.identity,
        state.device?.attachGeneration,
        state.selectedPartitionId,
    ) { mutableStateOf(false) }
    LaunchedEffect(state.actionsEnabled, sourceSheetOpen) {
        if (!state.actionsEnabled) sourceSheetOpen = false
    }
    val rawName = state.stagedRawImageLabel
        ?: state.imageName?.takeIf { it.isNotBlank() }
        ?: rawSourceName(state.imageUri)
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
                    stringResource(R.string.raw_write_not_partition_note),
                    stringResource(R.string.raw_map_snap_hint),
                    stringResource(R.string.raw_write_help_examples),
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
                Text(
                    text = stringResource(R.string.wave4_file_not_selected),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
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
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.extraLarge,
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
                            PopupChoiceBlock(
                                onClick = {
                                    sourceSheetOpen = false
                                    onPickImage()
                                },
                            ) {
                                ListItem(
                                    headlineContent = {
                                        Text(stringResource(R.string.partitions_raw_source_phone))
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                            PopupChoiceBlock(
                                onClick = {
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
                            ) {
                                ListItem(
                                    headlineContent = {
                                        Text(stringResource(R.string.partitions_raw_source_stick))
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
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
                                text = normalizedRestorePath(path),
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
                                    append(", ")
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

                val geometry = state.rawGeometry
                var customStart by remember(
                    state.device?.identity,
                    state.device?.attachGeneration,
                    state.selectedPartitionId,
                    state.imageUri,
                    state.stagedRawImagePath,
                ) { mutableStateOf("0") }
                var customStartMib by remember(
                    state.device?.identity,
                    state.device?.attachGeneration,
                    state.selectedPartitionId,
                    state.imageUri,
                    state.stagedRawImagePath,
                ) { mutableStateOf("0") }
                val rawAlignSectors = mibAlignSectors(state.diskSectorSize) ?: 1UL
                val typedCustomStart = customStart.trim().toULongOrNull()
                val alignedCustomStart = typedCustomStart?.let { value ->
                    PartitionRangeMath.alignUp(value, rawAlignSectors)
                }
                val rawSectorSize = (state.rawGeometry?.sectorSize ?: state.diskSectorSize)
                    .takeIf { it > 0 }
                val lbaValue = customStart
                val mibValue = customStartMib
                if (alignedCustomStart == 0UL) {
                    Text(
                        text = stringResource(R.string.raw_write_whole_disk_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val typedMib = mibValue.replace(',', '.').toBigDecimalOrNull()
                val mibError = mibValue.isNotBlank() &&
                    (typedMib == null ||
                        rawSectorSize == null ||
                        typedMib < java.math.BigDecimal.ZERO ||
                        rawStartLbaFromMib(mibValue, rawSectorSize) == null)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = lbaValue,
                        onValueChange = { text ->
                            val digits = text.filter { ch -> ch.isDigit() }
                            customStart = digits
                            customStartMib = digits.toULongOrNull()?.let { typed ->
                                val aligned = PartitionRangeMath.alignUp(
                                    typed,
                                    rawAlignSectors,
                                )
                                rawSectorSize?.let { formatMibInput(aligned, it) }.orEmpty()
                            }.orEmpty()
                        },
                        label = { Text(stringResource(R.string.raw_write_start_label)) },
                        singleLine = true,
                        isError = lbaValue.isNotBlank() && lbaValue.toULongOrNull() == null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                alignedCustomStart?.let {
                                    customStart = it.toString()
                                    rawSectorSize?.let { size ->
                                        customStartMib = formatMibInput(it, size)
                                    }
                                }
                            },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = mibValue,
                        onValueChange = { text ->
                            val normalized = text
                                .filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                                .replace(',', '.')
                            customStartMib = normalized
                            rawSectorSize
                                ?.let { rawStartLbaFromMib(normalized, it) }
                                ?.let { customStart = it.toString() }
                        },
                        label = { Text(stringResource(R.string.raw_write_start_mib_label)) },
                        singleLine = true,
                        isError = mibError,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                    alignedCustomStart
                        ?.takeIf { it != typedCustomStart }
                        ?.let { aligned ->
                            Text(
                                text = stringResource(
                                    R.string.raw_write_start_aligned,
                                    aligned.toLong(),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                geometry?.let { liveGeometry ->
                    RawPlanMap(
                        geometry = liveGeometry,
                        startLba = alignedCustomStart ?: 0UL,
                        sectorCount = sourceSectors,
                        onStartChange = { started ->
                            customStart = started.toString()
                            rawSectorSize?.let { size ->
                                customStartMib = formatMibInput(started, size)
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.raw_verify_readback_label),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    val verifyLabel = stringResource(R.string.raw_verify_readback_label)
                    Switch(
                        checked = state.rawVerifyReadback,
                        onCheckedChange = { onIntent(UiIntent.SetRawVerifyReadback(it)) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.semantics { contentDescription = verifyLabel },
                    )
                }
                if (geometry == null) {
                    EmptyHint(stringResource(R.string.raw_write_geometry_unavailable))
                } else {
                    val draftStart: ULong? = alignedCustomStart
                    val plan = draftStart?.let {
                        RawRangePlan.planFromSourceSize(it, sourceBytes, geometry)
                    }
                    val planRange = (plan as? RawWritePlan.Ready)?.range
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
                            RawMeasureError.Failed, null ->
                                stringResource(R.string.raw_write_size_measure_failed)
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
                    if (sourceBytes == null &&
                        !state.rawSizeMeasuring &&
                        !state.rawMeasurementPending &&
                        !state.rawMeasurementWaitingForPrevious
                    ) {
                        TextButton(
                            onClick = { onIntent(UiIntent.RetryRawSourceMeasurement) },
                            enabled = state.actionsEnabled,
                        ) {
                            Text(stringResource(R.string.settings_retry))
                        }
                    }

                    val rangeAssessment = planRange?.let { range ->
                        assessRawRange(
                            state = state,
                            geometry = geometry,
                            rangeStart = range.startLba,
                            rangeCount = range.sectorCount,
                            selected = selected,
                            wholeDisk = range.startLba == 0UL,
                        )
                    }

                    planRange?.let { range ->
                        val end = range.startLba + range.sectorCount - 1UL
                        val sectorSize = geometry.sectorSize
                        val startMibText = formatMibDecimal(range.startLba, sectorSize)
                        val endMibText = formatMibDecimal(end + 1UL, sectorSize)
                        val startLbaText = formatGroupedULong(range.startLba)
                        val endLbaText = formatGroupedULong(end)
                        val countText = formatGroupedULong(range.sectorCount)
                        val monoFacts = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                        )
                        Text(
                            text = stringResource(
                                R.string.partitions_raw_binding_lba,
                                startLbaText,
                                endLbaText,
                                countText,
                            ),
                            style = monoFacts,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                R.string.partitions_raw_binding_mib,
                                startMibText,
                                endMibText,
                            ),
                            style = monoFacts,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        rangeAssessment?.message?.let { warning ->
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (rangeAssessment.ok) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
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
                                is RawRangeWarning.MetadataCoverageUnknown -> Text(
                                    text = stringResource(R.string.raw_map_metadata_incomplete),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                is RawRangeWarning.OverlapsObjects -> warning.overlaps.forEach { overlap ->
                                    val objectLabel = when (overlap.target.kind) {
                                        RawObjectKind.Partition ->
                                            overlap.target.name
                                                ?: stringResource(R.string.raw_map_legend_partition)
                                        RawObjectKind.BootArea ->
                                            stringResource(R.string.raw_map_object_boot)
                                        RawObjectKind.TableArea ->
                                            stringResource(R.string.raw_map_object_table)
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.raw_map_overlap_line,
                                            objectLabel,
                                            overlap.target.startLba.toLong(),
                                            (overlap.target.endLbaExclusive - 1UL).toLong(),
                                            overlap.overlapSectors.toLong(),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                            text = when (probe.finding) {
                                RangeProbeFinding.DataPresent -> stringResource(
                                    R.string.raw_probe_data_present,
                                    probe.dataSectors,
                                    probe.sampledSectors.size,
                                )
                                RangeProbeFinding.NoDataInSamples -> stringResource(
                                    R.string.raw_probe_no_data_sampled,
                                    probe.sampledSectors.size,
                                )
                                RangeProbeFinding.Unknown ->
                                    stringResource(R.string.raw_probe_unknown)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OperationAction(
                        state = state,
                        onIntent = onIntent,
                        ownerTab = ShellTab.Partitions,
                        applyButton = OperationApply(
                            label = stringResource(R.string.partitions_raw_write_sectors),
                            enabled = state.actionsEnabled && planRange != null &&
                                planProblems.isEmpty() &&
                                rangeAssessment?.ok == true,
                            onClick = {
                                draftStart?.let { start ->
                                    onIntent(UiIntent.RequestRawWrite(start))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
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
    wholeDisk: Boolean,
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
    // Whole-disk raw write necessarily replaces the table and boot sectors; the
    // destructive confirmation is the gate, not a range veto that made the chip inert.
    val refinedOk = wholeDisk || insideFree || onlySelected

    var roEfiBlocked = false
    if (!wholeDisk) {
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
    }

    val message = when {
        wholeDisk -> stringResource(R.string.partitions_raw_range_whole_disk)
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
 * Exact LBA overview: proportional widths follow diskSectorCount with
 * leading/internal/trailing gaps, fill the row, and carry no labels. Selection stays in
 * the partition list and dropdown, so tiny segments need no fabricated touch target.
 * Invalid geometry shows a localized reason instead of a map.
 */
private data class StripSegment(
    val partition: PartitionTab? = null,
    val free: FreeRegionUi? = null,
    val gapStart: ULong? = null,
    val gapSectorCount: ULong? = null,
)

private data class StripGeometry(
    val valid: Boolean,
    val segments: List<StripSegment>,
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

/** Exact pairwise intersections of live partitions, using their exclusive end LBAs. */
private fun overlappingPartitions(
    partitions: List<PartitionTab>,
): List<Triple<PartitionTab, PartitionTab, ULong>> {
    data class Range(val partition: PartitionTab, val start: ULong, val end: ULong)
    val ranges = partitions.mapNotNull { partition ->
        val start = partition.startLba ?: return@mapNotNull null
        val count = partition.sectorCount?.takeIf { it > 0UL } ?: return@mapNotNull null
        if (start > ULong.MAX_VALUE - count) return@mapNotNull null
        Range(partition, start, start + count)
    }.sortedBy { it.start }
    val result = mutableListOf<Triple<PartitionTab, PartitionTab, ULong>>()
    for (i in ranges.indices) {
        for (j in i + 1 until ranges.size) {
            val first = ranges[i]
            val second = ranges[j]
            if (second.start >= first.end) break
            val overlapStart = maxOf(first.start, second.start)
            val overlapEnd = minOf(first.end, second.end)
            if (overlapEnd > overlapStart) {
                result += Triple(
                    first.partition,
                    second.partition,
                    overlapEnd - overlapStart,
                )
            }
        }
    }
    return result
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartitionSelectStrip(
    state: AppUiState,
    onSelectPartition: (String) -> Unit,
    onSelectFree: (FreeRegionUi) -> Unit,
) {
    val diskSectorCount = state.device?.capacityBytes
        ?.div(state.diskSectorSize.coerceAtLeast(1).toULong())
        ?.takeIf { it > 0UL }
        ?: return
    val geometry = remember(state.partitions, state.freeRegions, diskSectorCount) {
        val partitions = state.partitions
            .filter { (it.sectorCount ?: 0uL) > 0uL }
            .sortedWith(compareBy<PartitionTab> { it.startLba ?: ULong.MAX_VALUE }.thenBy { it.id })
        val sourceSegments = buildList {
            partitions.forEach { add(StripSegment(partition = it)) }
            state.freeRegions.filter { it.sectorCount > 0UL }.forEach { add(StripSegment(free = it)) }
        }.sortedWith(compareBy { segmentStart(it) })
        val valid = sourceSegments.zipWithNext().all { (a, b) ->
            segmentEnd(a) >= segmentStart(a) && segmentEnd(a) <= segmentStart(b)
        } && sourceSegments.all {
            segmentStart(it) <= diskSectorCount && segmentEnd(it) >= segmentStart(it) &&
                segmentEnd(it) <= diskSectorCount
        }
        val segments = if (valid) {
            buildList {
                var cursor = 0UL
                sourceSegments.forEach { segment ->
                    val start = segmentStart(segment)
                    if (start > cursor) {
                        add(StripSegment(gapStart = cursor, gapSectorCount = start - cursor))
                    }
                    add(segment)
                    cursor = segmentEnd(segment)
                }
                if (diskSectorCount > cursor) {
                    add(StripSegment(gapStart = cursor, gapSectorCount = diskSectorCount - cursor))
                }
            }
        } else emptyList()
        StripGeometry(valid, segments)
    }
    val segments = geometry.segments
    if (!geometry.valid) {
        Text(
            text = stringResource(R.string.partitions_strip_geometry_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    if (segments.isEmpty()) return
    val selectedFill = MaterialTheme.colorScheme.primary
    val selectedContent = MaterialTheme.colorScheme.onPrimary
    // Saturated hue tints stay visible in both themes without turning the strip into
    // a row of near-black blocks. Selected remains the sole full-primary fill.
    val partitionFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    val partitionInk = MaterialTheme.colorScheme.onSurface
    val specialFill = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val specialInk = MaterialTheme.colorScheme.onSurface
    val reservedFill = MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
    val reservedInk = MaterialTheme.colorScheme.onSurface
    val freeFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val gapFill = MaterialTheme.colorScheme.outlineVariant
    val outline = MaterialTheme.colorScheme.outline
    val hasSpecial = segments.any { it.partition?.special == true }
    val hasReserved = segments.any { it.partition?.reserved == true }
    val hasPartition = segments.any { seg ->
        val p = seg.partition
        p != null && !p.special && !p.reserved
    }
    val hasFree = segments.any { it.free != null }
    val hasGap = segments.any { it.gapSectorCount != null }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        // Keep tiny partitions selectable without hiding larger proportions. When the
        // minimum widths no longer fit, the strip scrolls instead of collapsing to pixels.
        val minimumSegmentWidth = 12.dp
        val rawWidths = segments.map { segment ->
            (maxWidth * (segmentSpan(segment).toDouble() / diskSectorCount.toDouble()).toFloat())
                .coerceAtLeast(minimumSegmentWidth)
        }
        val rawTotal = rawWidths.fold(0.dp) { total, width -> total + width }
        val widths = if (rawTotal < maxWidth && rawTotal > 0.dp) {
            val scale = maxWidth.value / rawTotal.value
            rawWidths.map { it * scale }
        } else {
            rawWidths
        }
        val contentWidth = widths.fold(0.dp) { total, width -> total + width }
            .coerceAtLeast(maxWidth)
        val stripScroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(
            state.selectedPartitionId,
            widths,
            contentWidth,
            maxWidth,
            stripScroll.maxValue,
        ) {
            val index = segments.indexOfFirst {
                it.partition?.id == state.selectedPartitionId
            }
            if (index >= 0 && contentWidth > maxWidth) {
                val before = widths.take(index).fold(0.dp) { total, width -> total + width }
                val center = before + widths[index] / 2
                val target = with(density) {
                    (center - maxWidth / 2).toPx().toInt()
                }
                stripScroll.animateScrollTo(target.coerceIn(0, stripScroll.maxValue))
            }
        }
        Row(
            modifier = Modifier
                .then(
                    if (contentWidth > maxWidth) {
                        Modifier.horizontalScroll(stripScroll, overscrollEffect = null)
                    } else {
                        Modifier
                    },
                )
                .width(contentWidth)
                .height(48.dp),
        ) {
            segments.forEachIndexed { index, segment ->
                val partition = segment.partition
                val free = segment.free
                val isSelected = partition?.id == state.selectedPartitionId
                val fill = when {
                    partition != null && isSelected -> selectedFill
                    partition != null && partition.special -> specialFill
                    partition != null && partition.reserved -> reservedFill
                    partition != null -> partitionFill
                    free != null -> freeFill
                    else -> gapFill
                }
                val ink = when {
                    partition != null && isSelected -> selectedContent
                    partition != null && partition.special -> specialInk
                    partition != null && partition.reserved -> reservedInk
                    partition != null -> partitionInk
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val description = when {
                    partition != null -> stringResource(
                        R.string.partitions_strip_item_a11y_name,
                        partition.name.ifBlank { partition.id },
                    )
                    free != null -> stringResource(R.string.partitions_strip_free)
                    else -> stringResource(R.string.partitions_strip_unallocated)
                }
                Box(
                    modifier = Modifier
                        .width(widths[index])
                        .fillMaxHeight()
                        .background(fill)
                        .then(
                            when {
                                partition != null -> Modifier.selectable(
                                    selected = isSelected,
                                    enabled = state.actionsEnabled,
                                    role = Role.RadioButton,
                                    onClick = { onSelectPartition(partition.id) },
                                )
                                free != null -> Modifier.clickable(
                                    enabled = state.actionsEnabled,
                                    role = Role.Button,
                                    onClick = { onSelectFree(free) },
                                )
                                else -> Modifier.clearAndSetSemantics { }
                            },
                        )
                        .semantics { contentDescription = description },
                ) {
                    val fraction =
                        segmentSpan(segment).toDouble() / diskSectorCount.toDouble()
                    if (partition != null && fraction >= 0.10 && widths[index] >= 64.dp) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = partition.name.ifBlank { partition.id },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (partition.filesystemLabel.isNotEmpty()) {
                                Text(
                                    text = partition.filesystemLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StripLegend(
            color = selectedFill,
            borderColor = MaterialTheme.colorScheme.primary,
            label = stringResource(R.string.partitions_strip_legend_selected),
        )
        if (hasSpecial) {
            StripLegend(
                color = specialFill,
                borderColor = outline,
                label = stringResource(R.string.partitions_strip_legend_special),
            )
        }
        if (hasReserved) {
            StripLegend(
                color = reservedFill,
                borderColor = outline,
                label = stringResource(R.string.partitions_strip_legend_reserved),
            )
        }
        if (hasPartition) {
            StripLegend(
                color = partitionFill,
                borderColor = outline,
                label = stringResource(R.string.partitions_strip_legend_partition),
            )
        }
        if (hasFree) {
            StripLegend(
                color = freeFill,
                borderColor = outline,
                label = stringResource(R.string.partitions_strip_free),
            )
        }
        if (hasGap) {
            StripLegend(
                color = gapFill,
                borderColor = outline,
                label = stringResource(R.string.partitions_strip_unallocated),
            )
        }
    }
}

@Composable
private fun StripLegend(
    color: Color,
    borderColor: Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(3.dp))
                .border(1.dp, borderColor, RoundedCornerShape(3.dp)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
// PAR/RAW matrix: layout title, sorted LBA rows, proportional unlabeled map segments,
// pinned selection, GPT/MBR warning actions, create-in-list, EFI policy, bound editors,
// derived raw end, overlap list, probe and dual source pickers are wired above.






/** Size in bytes from the live table, or null when the geometry cannot express it. */
private fun partitionSizeBytes(state: AppUiState, partition: PartitionTab): ULong? =
    partitionSizeBytes(partition, state.diskSectorSize)

/** Free/size line shared with the fixed partition picker. */
@Composable
private fun partitionUsageLabel(state: AppUiState, partition: PartitionTab): String =
    filesystemUsageText(
        snapshot = state.partitionUsageSnapshot(partition),
        phase = state.partitionUsagePhase(partition),
        totalBytes = partitionSizeBytes(state, partition),
    ) ?: "—"

/**
 * "LBA start–end · count sectors" (or another [templateId] shape) for one exact range, or
 * null when the geometry is unknown or the end LBA would overflow. The end is
 * start + count - 1, never a guess. [templateId] is a string resource id.
 */
@Composable
internal fun lbaRangeLabel(
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
    val exactMib: String,
    val totalSectors: ULong,
    val lastLbaInclusive: ULong,
)

/** Size and block/LBA totals only: the layout card no longer claims used vs free space. */
private fun diskGeometry(state: AppUiState): DiskGeometry? {
    if (state.diskSectorSize <= 0) return null
    val sectorSize = state.diskSectorSize.toULong()
    val totalBytes = state.device?.capacityBytes ?: 0UL
    if (sectorSize == 0UL || totalBytes == 0UL) return null
    val totalSectors = totalBytes / sectorSize
    if (totalSectors == 0UL) return null
    val exactMib = java.math.BigDecimal(totalBytes.toString())
        .divide(java.math.BigDecimal.valueOf(1024L * 1024L))
        .stripTrailingZeros()
        .toPlainString()
    return DiskGeometry(
        sizeLabel = DeviceIdentity.formatCapacity(totalBytes),
        exactMib = exactMib,
        totalSectors = totalSectors,
        lastLbaInclusive = totalSectors - 1UL,
    )
}

/** Locale-aware grouping for LBA / MiB counts shown in layout and raw binding. */
private fun formatGroupedULong(value: ULong): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault())
        .format(java.math.BigInteger(value.toString()))

private fun formatMibDecimal(lba: ULong, sectorSize: Int): String {
    val bytes = java.math.BigDecimal(lba.toString())
        .multiply(java.math.BigDecimal.valueOf(sectorSize.toLong()))
    return bytes
        .divide(java.math.BigDecimal.valueOf(1024L * 1024L), 2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

/** Editable MiB text keeps sector-level resolution, unlike the two-decimal fact pills. */
private fun formatMibInput(lba: ULong, sectorSize: Int): String {
    val bytes = java.math.BigDecimal(lba.toString())
        .multiply(java.math.BigDecimal.valueOf(sectorSize.toLong()))
    return bytes
        .divide(java.math.BigDecimal.valueOf(1024L * 1024L), 6, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

/**
 * Whole-MiB start for a typed MiB value. Fractional input rounds up so the marker never
 * lands before the number the user typed; null means zero sector size or overflow.
 */
internal fun rawStartLbaFromMib(text: String, sectorSize: Int): ULong? {
    val mib = text.replace(',', '.').toBigDecimalOrNull() ?: return null
    if (mib < java.math.BigDecimal.ZERO) return null
    val perMib = mibAlignSectors(sectorSize) ?: return null
    val whole = mib
        .setScale(0, java.math.RoundingMode.CEILING)
        .toBigInteger()
    if (whole.bitLength() >= 64) return null
    val value = whole.toLong().toULong()
    return if (value <= ULong.MAX_VALUE / perMib) value * perMib else null
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
        FilesystemId.ISO -> R.string.partitions_format_iso_desc
    },
)
