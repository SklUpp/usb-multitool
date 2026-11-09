package usb.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Files tab over the real FAT volume of the selected partition.
 *
 * The list owns the viewport; partition chips, breadcrumb and selection actions are
 * compact rows above it. Upload is a single FAB overlaying the list bottom.
 */
@Composable
fun FilesScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onRequestDownload: (String) -> Unit = {},
) {
    var selectedFile by remember(state.selectedPartitionId, state.currentPath) {
        mutableStateOf<FileEntry?>(null)
    }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            onIntent(UiIntent.UploadFile(uri.toString()))
        }
    }

    val selected = state.selectedPartition()
    val canAct = state.actionsEnabled && selected != null
    val selection = state.files.firstOrNull { it.name == selectedFile?.name }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.partitions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.partitions.forEach { partition ->
                        FilterChip(
                            selected = partition.id == state.selectedPartitionId,
                            onClick = { onIntent(UiIntent.SelectPartition(partition.id)) },
                            label = {
                                val fsTag = partition.filesystemLabel.let { if (it.isNotEmpty()) " · $it" else "" }
                                Text(
                                    if (partition.isVtoyEfi) "${partition.name} (EFI)$fsTag"
                                    else "${partition.name}$fsTag",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }

                    if (selected?.isVtoyEfi == true) {
                        Row(
                            modifier = Modifier.toggleable(
                                value = state.vtoyEfiWriteEnabled,
                                enabled = state.actionsEnabled,
                                role = Role.Switch,
                                onValueChange = { onIntent(UiIntent.SetVtoyEfiWritable(it)) },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Запись EFI",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.vtoyEfiWriteEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Switch(
                                checked = state.vtoyEfiWriteEnabled,
                                onCheckedChange = null,
                                enabled = state.actionsEnabled,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp),
                ) {
                    IconButton(
                        onClick = { onIntent(UiIntent.NavigateUp) },
                        enabled = canAct && state.currentPath != "/",
                        modifier = Modifier.semantics { contentDescription = "На уровень выше" },
                    ) {
                        GlyphIcon(Glyph.ChevronLeft, size = 20.dp)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = breadcrumbLabel(state.currentPath),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { showNewFolderDialog = true },
                    enabled = canAct,
                    modifier = Modifier.semantics { contentDescription = "Создать папку" },
                ) {
                    GlyphIcon(Glyph.Plus, size = 20.dp)
                }
            }

            if (selection != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = selection.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = { selectedFile = null },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text("Отмена", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!selection.isDirectory) {
                                TextButton(
                                    onClick = { onRequestDownload(selection.name) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("Сохранить", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(
                                    onClick = {
                                        onIntent(
                                            UiIntent.IsoActionRequested(
                                                selection.name,
                                                IsoAction.SendToRawWriter,
                                            ),
                                        )
                                    },
                                    enabled = state.actionsEnabled,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("В raw", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (selection.isIso) {
                                TextButton(
                                    onClick = {
                                        onIntent(
                                            UiIntent.IsoActionRequested(
                                                selection.name,
                                                IsoAction.LinkXml,
                                            ),
                                        )
                                    },
                                    enabled = state.actionsEnabled,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("XML", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            TextButton(
                                onClick = { showDeleteConfirmDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "Удалить",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = if (state.partitions.isEmpty()) {
                    "Разделы не найдены"
                } else {
                    "${state.files.size} ${elementsLabel(state.files.size)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            when {
                !state.actionsEnabled -> EmptyHint(UiReducer.presenceMessage(state.presence))
                state.files.isEmpty() -> Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val status = state.statusMessage
                        val isError = state.operation?.state == OperationState.Failed ||
                            (status != null && (status.contains("error", ignoreCase = true) ||
                                status.contains("cannot", ignoreCase = true) ||
                                status.contains("failed", ignoreCase = true)))
                        Text(
                            text = if (isError) (status ?: "Не удалось прочитать папку") else "Папка пуста",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 76.dp),
                ) {
                    items(state.files, key = { it.name }) { entry ->
                        FileRow(
                            entry = entry,
                            isSelected = entry == selectedFile,
                            actionsEnabled = canAct,
                            onSelect = {
                                selectedFile = if (selectedFile == entry) null else entry
                            },
                            onNavigate = {
                                onIntent(
                                    UiIntent.OpenDirectory(
                                        fatChildPath(state.currentPath, entry.name),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (canAct) {
            ExtendedFloatingActionButton(
                text = { Text("Загрузить") },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                onClick = { uploadLauncher.launch("*/*") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 8.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewFolderDialog = false
                newFolderName = ""
            },
            title = { Text("Новая папка") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Имя папки") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newFolderName.trim()
                        if (name.isNotEmpty()) {
                            onIntent(UiIntent.CreateFolder(name))
                        }
                        showNewFolderDialog = false
                        newFolderName = ""
                    },
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewFolderDialog = false
                        newFolderName = ""
                    },
                ) {
                    Text("Отмена")
                }
            },
        )
    }

    if (showDeleteConfirmDialog && selectedFile != null) {
        val entry = state.files.firstOrNull { it.name == selectedFile?.name }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Удалить") },
            text = {
                Text(
                    if (entry?.isDirectory == true) {
                        "Удалить папку ${entry.name}? Она должна быть пустой — непустые папки отклоняются."
                    } else {
                        "Удалить ${entry?.name}?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (entry != null) {
                            onIntent(UiIntent.DeleteEntry(entry.name))
                        } else {
                            onIntent(UiIntent.Status("Выбранный файл не найден"))
                        }
                        selectedFile = null
                        showDeleteConfirmDialog = false
                    },
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                ) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    isSelected: Boolean,
    actionsEnabled: Boolean,
    onSelect: () -> Unit,
    onNavigate: () -> Unit,
) {
    val glyph = when {
        entry.isDirectory -> Glyph.Folder
        entry.isIso -> Glyph.Iso
        else -> Glyph.File
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
        border = if (isSelected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = actionsEnabled,
                onClickLabel = if (entry.isDirectory) "Открыть папку" else "Выбрать файл",
                role = Role.Button,
            ) {
                if (entry.isDirectory) onNavigate() else onSelect()
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphIcon(glyph, size = 22.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.isDirectory || isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        entry.isDirectory -> "Папка"
                        entry.isIso -> "ISO-образ"
                        else -> "Файл"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!entry.isDirectory) {
                Text(
                    text = DeviceIdentity.formatCapacity(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun breadcrumbLabel(path: String): String {
    val trimmed = path.trim('/')
    return if (trimmed.isEmpty()) "Корень" else "Корень / " + trimmed.replace("/", " / ")
}

private fun elementsLabel(count: Int): String =
    when (count % 10) {
        1 -> if (count % 100 == 11) "элементов" else "элемент"
        2, 3, 4 -> if (count % 100 in 12..14) "элементов" else "элемента"
        else -> "элементов"
    }
