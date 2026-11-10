package usb.ui

import usb.fs.FilesystemId
import usb.fs.FormatCatalog
import usb.fs.VolumeLabel
import usb.partition.PartitionName
import usb.partition.TableScheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import usb.image.RangeProbeAttachment
import usb.image.RangeProbeFinding
import usb.partition.RawRangeCheck
import usb.partition.RawRangePlan
import usb.partition.RawRangeProblem
import usb.partition.RawRangeWarning
import usb.partition.RawWriteRange
import usb.multitool.R
import kotlin.math.roundToInt

/**
 * Partitions tab: read first (layout, selected partition), then create inside free
 * space, then destructive operations (format, raw write, GPT repair), each confirmed.
 *
 * EFI write protection is not toggled here; the honest state is shown as a policy row
 * and the override lives on the Ventoy tab.
 */
@Composable
fun PartitionsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
) {
    var createRegion by remember(state.device, state.freeRegions) { mutableStateOf<FreeRegionUi?>(null) }
    var rawPickerOpen by remember { mutableStateOf(false) }
    var sectorCount by remember { mutableStateOf("") }
    var startLbaInput by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    var createLabel by remember { mutableStateOf("") }
    var createFormatId by remember { mutableStateOf<FilesystemId?>(state.selectedFormatId) }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var labelTargetId by remember { mutableStateOf<String?>(null) }
    var labelText by remember { mutableStateOf("") }
    val namesSupported = state.partitionScheme?.let { PartitionName.supported(it) } == true
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
                    // Outside the scrolling block on purpose: dragging a thumb must not
                    // scroll the dialog, and the handles stay above the fold.
                    if (sliderVisible && alignSectors != null && firstAligned != null) {
                        val mibSectors = alignSectors
                        val regionBase = firstAligned
                        val maxMibFloat = availableMib.toFloat()
                        val safeStart = sliderStart.toFloat()
                        val safeEnd = sliderEnd.toFloat()
                        val rangeA11y = stringResource(R.string.partitions_range_a11y)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.partitions_range_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    .semantics { contentDescription = rangeA11y },
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

    val renameTarget = renameTargetId?.let { id -> state.partitions.firstOrNull { it.id == id } }
    if (renameTarget != null) {
        val newName = renameText.trim()
        val nameOk = newName.isNotEmpty() && PartitionName.problem(newName) == null
        AlertDialog(
            onDismissRequest = { renameTargetId = null },
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
                        onIntent(UiIntent.RequestRenamePartition(newName))
                        renameTargetId = null
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val labelTarget = labelTargetId?.let { id -> state.partitions.firstOrNull { it.id == id } }
    // Renaming a label needs a known filesystem; the row that opens this dialog is already
    // restricted to filesystems whose volume label can be renamed, so a missing id means the
    // partition changed under us and the dialog simply does not reopen.
    val labelFs = labelTarget?.filesystemId
    if (labelTarget != null && labelFs != null) {
        val newLabel = labelText.trim()
        val labelOk = newLabel.isNotEmpty() && newLabel != labelTarget.volumeLabel &&
            VolumeLabel.problem(newLabel, labelFs) == null
        AlertDialog(
            onDismissRequest = { labelTargetId = null },
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
                        onIntent(UiIntent.RequestRenameVolumeLabel(newLabel))
                        labelTargetId = null
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { labelTargetId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val selected = state.selectedPartition()
    val blockedHint = writeBlockHint(state)
    val align = mibAlignSectors(state.diskSectorSize)
    val creatableRegions = state.freeRegions.mapNotNull { region ->
        val skip = align?.let { (it - region.startLba % it) % it } ?: 0UL
        if (skip < region.sectorCount) {
            Triple(region, region.startLba + skip, region.sectorCount - skip)
        } else {
            null
        }
    }
    var freeRegionChoice by remember(state.device, state.freeRegions) {
        mutableStateOf<FreeRegionUi?>(null)
    }
    val chosenRegion =
        creatableRegions.firstOrNull { it.first == freeRegionChoice } ?: creatableRegions.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionCard(
            title = stringResource(R.string.partitions_layout_title),
            subtitle = when {
                !state.tableValid -> stringResource(R.string.partitions_corrupt)
                state.partitionTableDamaged ->
                    stringResource(R.string.partitions_gpt_primary_damaged_title)
                state.partitions.isEmpty() -> stringResource(R.string.partitions_none)
                else -> diskUsageCaption(state)
            },
            trailing = {
                Badge(
                    text = pluralStringResource(
                        R.plurals.partitions_count,
                        state.partitions.size,
                        state.partitions.size,
                    ),
                )
            },
        ) {
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
                DiskMapBar(state)
                state.partitions.forEachIndexed { index, partition ->
                    val isSelected = partition.id == state.selectedPartitionId
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
                        }
                    }
                }
            }
        }

        if (selected != null) {
            SectionCard(
                title = stringResource(R.string.partitions_selected_title),
                subtitle = listOfNotNull(
                    selected.name,
                    selected.filesystemLabel.ifEmpty { "—" },
                    partitionSizeLabel(state, selected),
                ).joinToString(" · "),
            ) {
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
                if (space != null) {
                    Text(
                        text = stringResource(R.string.filesystem_usage_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state.partitionScheme) {
                    TableScheme.GPT -> InfoRow(
                        stringResource(R.string.partitions_name_label),
                        selected.name,
                    )
                    TableScheme.MBR -> EmptyHint(stringResource(R.string.partitions_mbr_no_name))
                    else -> Unit
                }
                // Read from the filesystem when the probe could do it; null means the
                // label is unknown, which is never shown as an empty label.
                selected.volumeLabel?.let { label ->
                    InfoRow(
                        stringResource(R.string.partitions_volume_label_label),
                        label.ifBlank { "—" },
                    )
                }
                blockedHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (namesSupported) {
                    val geometryKnown = selected.startLba != null && selected.sectorCount != null
                    OutlinedButton(
                        onClick = {
                            renameTargetId = selected.id
                            renameText = selected.name
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
                            labelTargetId = selected.id
                            labelText = selected.volumeLabel.orEmpty()
                        },
                        enabled = state.actionsEnabled && state.tableValid &&
                            !state.partitionTableDamaged && !state.partitionTableAmbiguous &&
                            blockedHint == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.partitions_rename_label_action))
                    }
                }
                OutlinedButton(
                    onClick = {
                        onIntent(UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
                    },
                    enabled = state.actionsEnabled && state.selectedPartitionId != null &&
                        blockedHint == null && !state.partitionTableDamaged,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                ) {
                    Text(stringResource(R.string.partitions_delete_action))
                }
            }

            SectionCard(
                title = stringResource(R.string.boot_section_title),
                subtitle = stringResource(R.string.boot_section_subtitle),
            ) {
                InfoRow(
                    stringResource(R.string.boot_role_label),
                    stringResource(
                        if (selected.isEfiType) {
                            R.string.partition_role_efi
                        } else {
                            R.string.partition_role_data
                        },
                    ),
                )
                InfoRow(
                    stringResource(R.string.bootable_label),
                    stringResource(
                        if (selected.bootable) R.string.answer_yes else R.string.answer_no,
                    ),
                )
                val geometryKnown = selected.startLba != null && selected.sectorCount != null
                OutlinedButton(
                    onClick = {
                        onIntent(
                            UiIntent.RequestSetPartitionRole(
                                if (selected.isEfiType) {
                                    PartitionRole.Data
                                } else {
                                    PartitionRole.Efi
                                },
                            ),
                        )
                    },
                    enabled = state.actionsEnabled && state.tableValid &&
                        !state.partitionTableDamaged && geometryKnown &&
                        state.isEfiWriteAllowed(selected),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (selected.isEfiType) {
                                R.string.make_data_action
                            } else {
                                R.string.make_efi_action
                            },
                        ),
                    )
                }
                OutlinedButton(
                    onClick = {
                        onIntent(UiIntent.RequestSetPartitionBootable(!selected.bootable))
                    },
                    enabled = state.actionsEnabled && state.tableValid &&
                        !state.partitionTableDamaged && geometryKnown &&
                        state.isEfiWriteAllowed(selected),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (selected.bootable) {
                                R.string.clear_bootable_action
                            } else {
                                R.string.set_bootable_action
                            },
                        ),
                    )
                }
                Text(
                    text = stringResource(R.string.boot_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.partitions_scheme_title),
            // With partitions present this is a real loss of access to their volumes,
            // so the section says so instead of hiding the only table-level control.
            subtitle = stringResource(
                if (state.partitions.isEmpty()) {
                    R.string.partitions_scheme_note
                } else {
                    R.string.partitions_scheme_replace_note
                },
            ),
        ) {
            val currentScheme = state.partitionScheme?.toPartitionScheme()
            val blocked = state.partitionTableAmbiguous || state.partitionTableDamaged ||
                !state.tableValid
            InfoRow(
                stringResource(R.string.partitions_scheme_current),
                currentScheme?.toTableScheme()?.name
                    ?: stringResource(R.string.partitions_scheme_unknown),
            )
            var schemeChoice by remember(currentScheme) {
                mutableStateOf(
                    if (currentScheme == PartitionScheme.Gpt) {
                        PartitionScheme.Mbr
                    } else {
                        PartitionScheme.Gpt
                    },
                )
            }
            CompactChoice(
                options = listOf(
                    PartitionScheme.Mbr to "MBR",
                    PartitionScheme.Gpt to "GPT",
                ),
                selected = schemeChoice,
                onSelect = { schemeChoice = it },
                label = stringResource(R.string.partitions_scheme_target),
                enabled = state.actionsEnabled && !blocked,
            )
            when {
                state.partitionTableAmbiguous ->
                    EmptyHint(stringResource(R.string.partitions_scheme_blocked))
                state.partitionTableDamaged ->
                    EmptyHint(stringResource(R.string.partitions_gpt_repair_blocked))
                !state.tableValid ->
                    EmptyHint(stringResource(R.string.partitions_corrupt))
            }
            OutlinedButton(
                onClick = {
                    onIntent(UiIntent.RequestInitializePartitionTable(schemeChoice))
                },
                enabled = state.actionsEnabled && !blocked && schemeChoice != currentScheme,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
            ) {
                Text(
                    stringResource(R.string.partitions_scheme_init_action),
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.partitions.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.partitions_scheme_erase_count,
                        state.partitions.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.partitions_free_space_title),
            subtitle = if (creatableRegions.isEmpty()) {
                stringResource(R.string.partitions_free_none)
            } else {
                stringResource(R.string.partitions_free_count, creatableRegions.size)
            },
        ) {
            if (creatableRegions.isEmpty()) {
                EmptyHint(
                    stringResource(
                        if (state.freeRegions.isEmpty()) {
                            R.string.partitions_free_no_regions
                        } else {
                            R.string.partitions_free_no_aligned
                        },
                    ),
                )
            } else {
                chosenRegion?.let { chosen ->
                    val (region, start, sectors) = chosen
                    val regionFacts = lbaRangeLabel(start, sectors) ?: "—"
                    if (creatableRegions.size > 1) {
                        CompactChoice(
                            options = creatableRegions.map { (candidate, candidateStart, candidateSectors) ->
                                candidate to (lbaRangeLabel(candidateStart, candidateSectors) ?: "—")
                            },
                            selected = region,
                            onSelect = { freeRegionChoice = it },
                            label = stringResource(R.string.partitions_region_label),
                            enabled = state.actionsEnabled,
                        )
                    } else {
                        InfoRow(stringResource(R.string.partitions_free_region_label), regionFacts)
                    }
                    OutlinedButton(
                        onClick = {
                            createRegion = region
                            startLbaInput = start.toString()
                            sectorCount = sectors.toString()
                            createName = ""
                            createLabel = ""
                            createFormatId = state.selectedFormatId
                        },
                        enabled = state.actionsEnabled && state.tableValid &&
                            !state.partitionTableDamaged,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.partitions_create_action))
                    }
                }
            }
        }

        if (selected != null) {
            SectionCard(
                title = stringResource(R.string.partitions_format_title),
                subtitle = selected.name,
            ) {
                CompactChoice(
                    options = FormatCatalog.supportedFormatIds().map { it to FormatCatalog.label(it) },
                    selected = state.selectedFormatId,
                    onSelect = { onIntent(UiIntent.SelectFormatFilesystem(it)) },
                    label = stringResource(R.string.partitions_filesystem_label),
                    enabled = state.actionsEnabled,
                )
                OutlinedButton(
                    onClick = {
                        onIntent(UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
                    },
                    enabled = state.actionsEnabled && state.selectedPartitionId != null &&
                        blockedHint == null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                ) {
                    Text(
                        stringResource(
                            R.string.partitions_format_until,
                            FormatCatalog.label(state.selectedFormatId),
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Raw writing: one tool for the whole medium and for any sub-range of it. The range
        // fields and the disk map live here once the geometry contract lands.
        SectionCard(
            title = stringResource(R.string.partitions_raw_write_title),
            subtitle = stringResource(R.string.partitions_raw_write_subtitle),
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
                        rawPickerOpen = true
                        onIntent(UiIntent.LoadRawSourceCandidates)
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
                        rawPickerOpen = true
                        onIntent(UiIntent.LoadRawSourceCandidates)
                    },
                )
            }

            val geometry = state.rawGeometry
            if (geometry == null) {
                // No geometry means the range cannot be checked; offering a write would be a blind
                // destructive action, so the section stops here.
                EmptyHint(stringResource(R.string.raw_write_geometry_unavailable))
            } else {
                var startText by remember(state.device?.identity, geometry.diskSectorCount) {
                    mutableStateOf("0")
                }
                var sizeText by remember(state.device?.identity, geometry.diskSectorCount) {
                    mutableStateOf(geometry.diskSectorCount.toString())
                }
                val draftStart = startText.trim().toULongOrNull()
                val draftCount = sizeText.trim().toULongOrNull()
                val draft = if (draftStart != null && draftCount != null && draftCount > 0UL) {
                    RawWriteRange(draftStart, draftCount)
                } else {
                    null
                }

                // Dragging a handle writes back into the two fields, so the map and the request
                // never disagree; typing is never snapped or rewritten.
                RawPlanMap(
                    geometry = geometry,
                    range = draft ?: RawWriteRange(0UL, geometry.diskSectorCount),
                    onRangeChange = {
                        startText = it.startLba.toString()
                        sizeText = it.sectorCount.toString()
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text(stringResource(R.string.raw_write_start_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = sizeText,
                        onValueChange = { sizeText = it },
                        label = { Text(stringResource(R.string.raw_write_size_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (draft != null) {
                    Text(
                        text = stringResource(
                            R.string.raw_write_end_and_size,
                            (draft.startLba + draft.sectorCount - 1UL).toLong(),
                            DeviceIdentity.formatCapacity(draft.byteLength(geometry.sectorSize)),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val check = draft?.let { RawRangePlan.check(it.startLba, it.sectorCount, geometry) }
                if (check is RawRangeCheck.Rejected) {
                    check.problems.forEach { problem ->
                        Text(
                            text = stringResource(
                                when (problem) {
                                    RawRangeProblem.EmptyRange -> R.string.raw_write_problem_empty
                                    RawRangeProblem.BeyondEndOfDisk -> R.string.raw_write_problem_beyond_disk
                                    RawRangeProblem.RangeTooLarge -> R.string.raw_write_problem_too_large
                                    RawRangeProblem.SourceLargerThanRange ->
                                        R.string.raw_write_problem_source_larger
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (check is RawRangeCheck.Ok) {
                    // Overlaps are printed under the map; only the remaining warnings show here.
                    check.warnings.forEach { warning ->
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
                val probeFresh = draft != null && attachment != null && probe != null &&
                    probe.matches(draft, geometry.diskSectorCount, geometry.sectorSize, attachment)
                if (draft != null) {
                    TextButton(
                        onClick = {
                            onIntent(UiIntent.RequestRangeProbe(draft.startLba, draft.sectorCount))
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
                        // The intent builds the confirmation from these two numbers; the button
                        // never asks for a destructive action on its own. `enabled` guards the
                        // tap, but the lambda needs its own null check to type-check.
                        draft?.let { range ->
                            onIntent(
                                UiIntent.RequestWriteRawRange(range.startLba, range.sectorCount),
                            )
                        }
                    },
                    enabled = state.actionsEnabled && rawName != null && draft != null &&
                        check is RawRangeCheck.Ok && probeFresh,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.partitions_raw_write_action))
                }
            }

            Text(
                text = stringResource(R.string.partitions_raw_overwrite_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

        if (rawPickerOpen) {
            RawSourceDialog(
                candidates = state.rawSourceCandidates,
                truncated = state.rawSourceCandidatesTruncated,
                enabled = state.actionsEnabled,
                onRefresh = { onIntent(UiIntent.LoadRawSourceCandidates) },
                onPick = { candidate ->
                    onIntent(UiIntent.SelectRawSource(candidate.path, candidate.name))
                    rawPickerOpen = false
                },
                onDismiss = {
                    rawPickerOpen = false
                    onIntent(UiIntent.CancelRawSourceLoad)
                },
            )
        }

        // Table repair is not a write of user data; it keeps its own section so a raw write can
        // never be mistaken for a repair, or the other way round.
        SectionCard(
            title = stringResource(R.string.partitions_repair_title),
            subtitle = stringResource(R.string.partitions_repair_subtitle),
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

/**
 * Map strip of partitions plus free space. Draws nothing when the disk has no partitions:
 * an empty bar was indistinguishable from a clipped or disabled control.
 */
@Composable
private fun DiskMapBar(state: AppUiState) {
    val partitions = state.partitions.filter { (it.sectorCount ?: 0uL) > 0uL }
    if (partitions.isEmpty()) return

    val total = state.device?.let {
        it.capacityBytes.toDouble() / state.diskSectorSize.toDouble()
    } ?: 0.0
    val occupied = partitions.sumOf {
        (it.sectorCount ?: 0uL).toDouble()
    }
    val free = (total - occupied).coerceAtLeast(0.0)
    val mapDescription = stringResource(
        R.string.partitions_disk_map_a11y,
        pluralStringResource(
            R.plurals.partitions_count,
            state.partitions.size,
            state.partitions.size,
        ),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = mapDescription },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        partitions.forEach { partition ->
            val weight = if (total > 0.0) {
                ((partition.sectorCount ?: 0uL).toDouble() / total).toFloat().coerceAtLeast(0.02f)
            } else {
                1f
            }
            Box(
                Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .background(
                        if (partition.id == state.selectedPartitionId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    ),
            )
        }
        if (total > 0.0 && free > 0.0) {
            Box(
                Modifier
                    .weight((free / total).toFloat().coerceAtLeast(0.02f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
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

/** Occupied/free caption; mirrors the strip proportions in numbers. */
@Composable
private fun diskUsageCaption(state: AppUiState): String {
    val sectorSize = state.diskSectorSize.toULong()
    val totalSectors = state.device?.let { it.capacityBytes / sectorSize } ?: 0UL
    if (totalSectors == 0UL) return stringResource(R.string.partitions_capacity_unknown)
    val occupied = state.partitions.sumOf { it.sectorCount ?: 0uL }
    val free = if (totalSectors > occupied) totalSectors - occupied else 0UL
    val percent = (occupied.toDouble() / totalSectors.toDouble() * 100).roundToInt()
    return stringResource(
        R.string.partitions_disk_usage,
        percent,
        DeviceIdentity.formatCapacity(free * sectorSize),
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onOpenStick,
            enabled = state.actionsEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.raw_choose_stick))
        }
        OutlinedButton(
            onClick = onPickImage,
            enabled = state.actionsEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.raw_choose_phone))
        }
    }
}

/**
 * File picker for the raw source, limited to real files on the mounted stick. The list is
 * produced by the host from the partition, never from the phone filesystem, and a truncated
 * answer is shown as truncated instead of looking complete.
 */
@Composable
private fun RawSourceDialog(
    candidates: List<RawSourceCandidate>?,
    truncated: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onPick: (RawSourceCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.raw_stick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (candidates == null) {
                    Text(
                        text = stringResource(R.string.raw_stick_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.raw_stick_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    candidates.take(40).forEach { candidate ->
                        TextButton(
                            onClick = { onPick(candidate) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = candidate.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = DeviceIdentity.formatCapacity(candidate.sizeBytes.toULong()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (truncated) {
                    Text(
                        text = stringResource(R.string.raw_stick_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.unattend_refresh)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
