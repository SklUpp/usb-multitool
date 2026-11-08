package usb.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Files tab over the real FAT volume of the selected partition.
 *
 * Prioritized for file browsing: maximum viewport height dedicated to the file list.
 * Partitions and toolbars take minimal, compact rows at the top.
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

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // P1: Compact partition selector row
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
                            Text(
                                if (partition.isVtoyEfi) "${partition.name} (EFI)" else partition.name,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }

                if (selected?.isVtoyEfi == true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "EFI Write",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.vtoyEfiWriteEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Switch(
                            checked = state.vtoyEfiWriteEnabled,
                            onCheckedChange = { onIntent(UiIntent.SetVtoyEfiWritable(it)) },
                            enabled = state.actionsEnabled,
                        )
                    }
                }
            }
        }

        // P1: Navigation path and primary actions row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { onIntent(UiIntent.NavigateUp) },
                enabled = canAct && state.currentPath != "/",
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("Up ..", style = MaterialTheme.typography.labelMedium)
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = state.currentPath,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Button(
                onClick = { uploadLauncher.launch("*/*") },
                enabled = canAct,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("Upload", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = { showNewFolderDialog = true },
                enabled = canAct,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("+Dir", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Contextual action bar if item selected
        if (selection != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selection.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!selection.isDirectory) {
                            TextButton(
                                onClick = { onRequestDownload(selection.name) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Save", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        TextButton(
                            onClick = { showDeleteConfirmDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "Delete",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        // P1 Main Work Area: File List fills available screen height
        if (!state.actionsEnabled) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = UiReducer.presenceMessage(state.presence),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.files.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
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
                        text = if (isError) (status ?: "Failed to list folder") else "Folder is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                            onIntent(UiIntent.OpenDirectory(fatChildPath(state.currentPath, entry.name)))
                        },
                        onIntent = onIntent,
                    )
                }
            }
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewFolderDialog = false
                newFolderName = ""
            },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
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
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewFolderDialog = false
                        newFolderName = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteConfirmDialog && selectedFile != null) {
        val entry = state.files.firstOrNull { it.name == selectedFile?.name }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete") },
            text = { Text("Delete ${entry?.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (entry != null) {
                            onIntent(UiIntent.DeleteEntry(entry.name))
                        } else {
                            onIntent(UiIntent.Status("Selected file not found"))
                        }
                        selectedFile = null
                        showDeleteConfirmDialog = false
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                ) {
                    Text("Cancel")
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
    onIntent: (UiIntent) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actionsEnabled) {
                if (entry.isDirectory) {
                    onNavigate()
                } else {
                    onSelect()
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (entry.isDirectory) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = "DIR",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (entry.isIso) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "ISO",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }

                    Text(
                        text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (entry.isDirectory || isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!entry.isDirectory) {
                    Text(
                        text = DeviceIdentity.formatCapacity(entry.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (entry.isIso) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onIntent(UiIntent.IsoActionRequested(entry.name, IsoAction.LinkXml))
                        },
                        enabled = actionsEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Link XML", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            onIntent(
                                UiIntent.IsoActionRequested(entry.name, IsoAction.SendToRawWriter),
                            )
                        },
                        enabled = actionsEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("To Raw Writer", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
