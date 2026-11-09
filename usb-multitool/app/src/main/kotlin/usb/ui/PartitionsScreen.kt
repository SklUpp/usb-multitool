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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
        val alignSectors = if (state.diskSectorSize > 0 && (1024 * 1024) % state.diskSectorSize == 0) {
            ((1024 * 1024) / state.diskSectorSize).toULong()
        } else {
            null
        }
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
        AlertDialog(
            onDismissRequest = { createRegion = null },
            title = { Text("Создать раздел") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (alignSectors == null || firstAligned == null) {
                            "Свободно ${region.sectorCount} секторов · ${state.diskSectorSize} байт/сектор"
                        } else if (firstAligned >= regionEnd) {
                            "В свободном регионе нет места, выровненного по $alignSectors секторам"
                        } else {
                            "Свободно с LBA $firstAligned до LBA $regionEnd · шаг $alignSectors секторов · ${state.diskSectorSize} байт/сектор"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = startLbaInput,
                        onValueChange = { input -> startLbaInput = input.filter { it.isDigit() } },
                        label = { Text("Начальный LBA") },
                        singleLine = true,
                        isError = startLbaInput.isNotBlank() && !(isAligned && inRegion),
                        supportingText = {
                            Text(
                                if (alignSectors != null) {
                                    "Должен быть кратен $alignSectors секторам и лежать в свободном регионе"
                                } else {
                                    "Выравнивание 1 МиБ недоступно для этого размера сектора"
                                },
                            )
                        },
                        trailingIcon = {
                            TextButton(
                                onClick = { firstAligned?.let { startLbaInput = it.toString() } },
                                enabled = firstAligned != null,
                            ) { Text("Выровнять", style = MaterialTheme.typography.labelSmall) }
                        },
                    )
                    OutlinedTextField(
                        value = sectorCount,
                        onValueChange = { input -> sectorCount = input.filter { it.isDigit() } },
                        label = { Text("Размер в секторах") },
                        singleLine = true,
                        isError = sectorCount.isNotBlank() && (parsedCount == null || parsedCount == 0UL || !fits),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        onIntent(UiIntent.RequestCreatePartition(parsedStart!!, parsedCount!!))
                        createRegion = null
                    },
                ) { Text("Продолжить") }
            },
            dismissButton = {
                TextButton(onClick = { createRegion = null }) { Text("Отмена") }
            },
        )
    }

    val selected = state.selectedPartition()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(
            title = "Раскладка диска",
            subtitle = selected?.let {
                "${it.name} · ${it.filesystemLabel.ifEmpty { "—" }} · ${partitionSizeLabel(state, it) ?: "—"}"
            },
            trailing = {
                Badge(text = partitionsLabel(state.partitions.size))
            },
        ) {
            DiskMapBar(state)

            if (!state.tableValid) {
                Text(
                    text = state.statusMessage ?: "Таблица разделов повреждена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.partitions.isEmpty()) {
                EmptyHint(
                    if (!state.tableValid) {
                        "Создание невозможно: сначала восстановите или сотрите таблицу."
                    } else {
                        UiReducer.presenceMessage(state.presence)
                    },
                )
            } else {
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
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                            if (partition.readOnly) {
                                Badge(text = "RO", container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                }
            }

            state.freeRegions.forEach { region ->
                val align = if (state.diskSectorSize > 0 && (1024 * 1024) % state.diskSectorSize == 0) {
                    ((1024 * 1024) / state.diskSectorSize).toULong()
                } else {
                    null
                }
                val skip = align?.let { (it - region.startLba % it) % it } ?: 0UL
                if (skip < region.sectorCount) {
                    val start = region.startLba + skip
                    val sectors = region.sectorCount - skip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Свободно · LBA $start · $sectors секторов",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(
                            onClick = {
                                createRegion = region
                                startLbaInput = start.toString()
                                sectorCount = sectors.toString()
                            },
                            enabled = state.actionsEnabled && state.tableValid,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            Text("Создать", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    onIntent(UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
                },
                enabled = state.actionsEnabled && state.selectedPartitionId != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
            ) {
                Text("Удалить раздел")
            }
        }

        SectionCard(title = "Форматирование") {
            SegmentedChoice(
                options = FormatCatalog.supportedFormatIds().map { it to FormatCatalog.label(it) },
                selected = state.selectedFormatId,
                onSelect = { onIntent(UiIntent.SelectFormatFilesystem(it)) },
                enabled = state.actionsEnabled,
            )
            InfoRow("Раздел", selected?.name ?: "не выбран")
            OutlinedButton(
                onClick = {
                    onIntent(UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
                },
                enabled = state.actionsEnabled && state.selectedPartitionId != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
            ) {
                Text(
                    "Форматировать в ${FormatCatalog.label(state.selectedFormatId)}…",
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        SectionCard(title = "Обслуживание") {
            OutlinedButton(
                onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_REPAIR)) },
                enabled = state.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Восстановить таблицу GPT")
            }
        }

        SectionCard(
            title = "Прошивка образа",
            subtitle = "Прямая запись секторами; границы образа проверяются.",
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
                            ?: "Образ не выбран",
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
                        Text("Выбрать", style = MaterialTheme.typography.labelMedium)
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
                        "Записать подготовленный образ"
                    } else {
                        "Записать образ на USB"
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
                    Text("Удалить подготовленный файл", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun DiskMapBar(state: AppUiState) {
    val total = state.device?.let {
        it.capacityBytes.toDouble() / state.diskSectorSize.toDouble()
    } ?: 0.0
    val partitions = state.partitions.filter { (it.sectorCount ?: 0uL) > 0uL }
    val occupied = partitions.sumOf {
        (it.sectorCount ?: 0uL).toDouble()
    }
    val free = (total - occupied).coerceAtLeast(0.0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = "Раскладка диска: ${partitionsLabel(state.partitions.size)}"
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (partitions.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        } else {
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
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
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
