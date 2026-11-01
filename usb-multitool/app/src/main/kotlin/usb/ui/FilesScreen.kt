package usb.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp

/**
 * Files tab over the real FAT volume of the selected partition.
 *
 * Every action leaves through [onIntent]; this composable never mutates the file list
 * or the displayed path. [AppUiState.currentPath] is the path whose entries are shown
 * and the path the view model actually listed.
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

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Partitions")
        if (state.partitions.isEmpty()) {
            Text(UiReducer.presenceMessage(state.presence))
            return
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.partitions.forEach { partition ->
                FilterChip(
                    selected = partition.id == state.selectedPartitionId,
                    onClick = { onIntent(UiIntent.SelectPartition(partition.id)) },
                    label = {
                        Text(
                            if (partition.isVtoyEfi) {
                                "${partition.name} (EFI)"
                            } else {
                                partition.name
                            },
                        )
                    },
                )
            }
        }

        val selected = state.selectedPartition()
        if (selected?.isVtoyEfi == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow VTOYEFI writes")
                Switch(
                    checked = state.vtoyEfiWriteEnabled,
                    onCheckedChange = { onIntent(UiIntent.SetVtoyEfiWritable(it)) },
                    enabled = state.actionsEnabled,
                )
            }
            if (!state.vtoyEfiWriteEnabled) {
                Text("VTOYEFI is read-only until write is enabled.")
            }
        }

        val canAct = state.actionsEnabled && selected != null
        // A selection only counts while the entry is still present in the listed directory.
        val selection = state.files.firstOrNull { it.name == selectedFile?.name }

        // Top Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { uploadLauncher.launch("*/*") },
                enabled = canAct,
            ) {
                Text("Upload file")
            }

            OutlinedButton(
                onClick = { showNewFolderDialog = true },
                enabled = canAct,
            ) {
                Text("New folder")
            }

            OutlinedButton(
                onClick = {
                    if (selection == null) {
                        onIntent(UiIntent.Status("Select a file to delete"))
                    } else {
                        showDeleteConfirmDialog = true
                    }
                },
                enabled = canAct && selection != null,
            ) {
                Text("Delete")
            }

            OutlinedButton(
                onClick = {
                    when {
                        selection == null -> onIntent(UiIntent.Status("Select a file to download"))
                        selection.isDirectory -> onIntent(UiIntent.Status("Select a file, not a folder"))
                        else -> onRequestDownload(selection.name)
                    }
                },
                enabled = canAct && selection?.isDirectory == false,
            ) {
                Text("Download")
            }
        }

        // Current folder path with back navigation ("..")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onIntent(UiIntent.NavigateUp) },
                enabled = canAct && state.currentPath != "/",
            ) {
                Text("..")
            }
            Text(
                text = state.currentPath,
                modifier = Modifier.weight(1f),
            )
        }

        if (!state.actionsEnabled) {
            Text(UiReducer.presenceMessage(state.presence))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actionsEnabled) {
                if (entry.isDirectory) {
                    onNavigate()
                } else {
                    onSelect()
                }
            }
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                else Modifier
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Text(
            if (entry.isDirectory) {
                "${entry.name}/"
            } else {
                "${entry.name} · ${DeviceIdentity.formatCapacity(entry.sizeBytes)}"
            },
        )
        if (entry.isIso) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        onIntent(UiIntent.IsoActionRequested(entry.name, IsoAction.LinkXml))
                    },
                    enabled = actionsEnabled,
                ) {
                    Text("Link XML")
                }
                OutlinedButton(
                    onClick = {
                        onIntent(
                            UiIntent.IsoActionRequested(entry.name, IsoAction.SendToRawWriter),
                        )
                    },
                    enabled = actionsEnabled,
                ) {
                    Text("Send to raw writer")
                }
            }
        }
    }
}
