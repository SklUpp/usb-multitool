package usb.ui

import usb.fs.FormatCatalog

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
    var sectorCount by remember { mutableStateOf("") }
    var startLbaInput by remember { mutableStateOf("") }
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
        val valid = regionFits && isAligned && fits

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
            } else if (startOffsetSectors != null) {
                sliderStart
            } else {
                availableMib
            }

        AlertDialog(
            onDismissRequest = { createRegion = null },
            title = { Text(stringResource(R.string.partitions_create_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
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
                                regionEnd.toLong(),
                                alignSectors.toLong(),
                                state.diskSectorSize,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                    if (alignSectors != null && availableMib >= 1UL && firstAligned != null) {
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
                                R.string.partitions_end_lba,
                                (parsedStart + parsedCount - 1UL).toLong(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        onIntent(UiIntent.RequestCreatePartition(parsedStart!!, parsedCount!!))
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
                Text(
                    text = diskUsageCaption(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                                Text(
                                    text = buildString {
                                        append(partition.filesystemLabel.ifEmpty { "—" })
                                        partitionSizeLabel(state, partition)?.let {
                                            append(" · ")
                                            append(it)
                                        }
                                        if (partition.startLba != null) {
                                            append(" · LBA ")
                                            append(partition.startLba)
                                        }
                                    },
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

        SectionCard(
            title = stringResource(R.string.partitions_selected_title),
            subtitle = selected?.let {
                "${it.name} · ${it.filesystemLabel.ifEmpty { "—" }} · " +
                    (partitionSizeLabel(state, it) ?: "—")
            } ?: stringResource(R.string.partitions_none_selected),
        ) {
            if (selected == null) {
                EmptyHint(stringResource(R.string.partitions_none_created))
            } else {
                InfoRow(
                    stringResource(R.string.partitions_filesystem_label),
                    selected.filesystemLabel.ifEmpty { "—" },
                )
                InfoRow(
                    stringResource(R.string.partitions_size_label),
                    partitionSizeLabel(state, selected) ?: "—",
                )
                InfoRow(
                    stringResource(R.string.partitions_start_label),
                    selected.startLba?.let { "LBA $it" } ?: "—",
                )
                blockedHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
        }

        SectionCard(
            title = stringResource(R.string.boot_section_title),
            subtitle = stringResource(R.string.boot_section_subtitle),
        ) {
            if (selected == null) {
                EmptyHint(stringResource(R.string.partitions_none_selected))
            } else {
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
                if (selected.readOnly && !state.isEfiWriteAllowed(selected)) {
                    blockedHint?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
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
                    if (creatableRegions.size > 1) {
                        CompactChoice(
                            options = creatableRegions.map { (candidate, candidateStart, candidateSectors) ->
                                candidate to stringResource(
                                    R.string.partitions_region_option,
                                    candidateStart.toLong(),
                                    candidateSectors.toLong(),
                                )
                            },
                            selected = region,
                            onSelect = { freeRegionChoice = it },
                            label = stringResource(R.string.partitions_region_label),
                            enabled = state.actionsEnabled,
                        )
                    }
                    InfoRow(
                        stringResource(R.string.partitions_free_label),
                        stringResource(
                            R.string.partitions_region_option,
                            start.toLong(),
                            sectors.toLong(),
                        ),
                    )
                    OutlinedButton(
                        onClick = {
                            createRegion = region
                            startLbaInput = start.toString()
                            sectorCount = sectors.toString()
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

        SectionCard(title = stringResource(R.string.partitions_format_title)) {
            CompactChoice(
                options = FormatCatalog.supportedFormatIds().map { it to FormatCatalog.label(it) },
                selected = state.selectedFormatId,
                onSelect = { onIntent(UiIntent.SelectFormatFilesystem(it)) },
                label = stringResource(R.string.partitions_filesystem_label),
                enabled = state.actionsEnabled,
            )
            InfoRow(
                stringResource(R.string.files_section_partition),
                selected?.name ?: stringResource(R.string.partitions_none_selected),
            )
            OutlinedButton(
                onClick = {
                    onIntent(UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
                },
                enabled = state.actionsEnabled && state.selectedPartitionId != null && blockedHint == null,
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

        SectionCard(
            title = stringResource(R.string.partitions_raw_title),
            subtitle = stringResource(R.string.partitions_raw_subtitle),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.stagedRawImageLabel
                            ?: state.imageUri?.let { it.substringAfterLast('/') }
                            ?: stringResource(R.string.partitions_no_image),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (state.imageUri != null || state.stagedRawImagePath != null) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedButton(
                        onClick = onPickImage,
                        enabled = state.actionsEnabled,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(R.string.partitions_pick),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (state.imageUri == null && state.stagedRawImagePath == null) {
                        onPickImage()
                    } else {
                        onIntent(UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE))
                    }
                },
                enabled = state.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.stagedRawImagePath != null) {
                        stringResource(R.string.partitions_write_staged)
                    } else {
                        stringResource(R.string.partitions_write_image)
                    },
                )
            }
            if (state.stagedRawImagePath != null) {
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

        SectionCard(
            title = stringResource(R.string.partitions_maintenance_title),
            subtitle = stringResource(R.string.partitions_maintenance_subtitle),
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

@Composable
private fun Badge(
    text: String,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.secondaryContainer,
    content: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
        )
    }
}

private fun partitionSizeLabel(state: AppUiState, partition: PartitionTab): String? =
    partition.sectorCount?.let {
        DeviceIdentity.formatCapacity(it * state.diskSectorSize.toULong())
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
