package usb.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import android.provider.OpenableColumns
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FilesScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentPath by remember { mutableStateOf("/") }
    var selectedFile by remember { mutableStateOf<FileEntry?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectedPartitionId) {
        currentPath = "/"
        selectedFile = null
    }

    val context = LocalContext.current
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val norm = currentPath.trim('/')
            val createdName = if (norm.isEmpty()) fileName else "$norm/$fileName"
            val size = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        cursor.getLong(sizeIndex).toULong()
                    } else 0uL
                }
            }.getOrNull() ?: 0uL
            val entry = FileEntry(name = createdName, isDirectory = false, sizeBytes = size)
            onIntent(UiIntent.AddFile(entry))
            onIntent(UiIntent.Status("Uploaded $fileName to $currentPath"))
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
                    onClick = {
                        currentPath = "/"
                        selectedFile = null
                        onIntent(UiIntent.SelectPartition(partition.id))
                    },
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

        // Top Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { uploadLauncher.launch("*/*") },
                enabled = state.actionsEnabled,
            ) {
                Text("Upload file")
            }

            OutlinedButton(
                onClick = { showNewFolderDialog = true },
                enabled = state.actionsEnabled,
            ) {
                Text("New folder")
            }

            OutlinedButton(
                onClick = {
                    if (selectedFile != null) {
                        showDeleteConfirmDialog = true
                    } else {
                        onIntent(UiIntent.Status("Select a file to delete"))
                    }
                },
                enabled = state.actionsEnabled && selectedFile != null,
            ) {
                Text("Delete")
            }
        }

        // Current folder path with back navigation ("..")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val trimmed = currentPath.trimEnd('/')
                    val lastSlash = trimmed.lastIndexOf('/')
                    currentPath = if (lastSlash <= 0) "/" else trimmed.substring(0, lastSlash)
                    selectedFile = null
                },
                enabled = state.actionsEnabled && currentPath != "/",
            ) {
                Text("..")
            }
            Text(
                text = currentPath,
                modifier = Modifier.weight(1f),
            )
        }

        if (!state.actionsEnabled) {
            Text(UiReducer.presenceMessage(state.presence))
        }

        val normPath = currentPath.trim('/')
        val displayedFiles = remember(state.files, currentPath) {
            val matching = if (normPath.isEmpty()) {
                state.files.filter { entry ->
                    val rel = entry.name.trimStart('/')
                    !rel.contains('/')
                }
            } else {
                state.files.filter { entry ->
                    val rel = entry.name.trimStart('/')
                    rel.startsWith("$normPath/") && !rel.removePrefix("$normPath/").contains('/')
                }
            }
            if (matching.isEmpty() && normPath.isNotEmpty()) {
                simulatedFolderContents[currentPath] ?: emptyList()
            } else {
                matching
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(displayedFiles, key = { it.name }) { entry ->
                FileRow(
                    entry = entry,
                    isSelected = entry == selectedFile,
                    actionsEnabled = state.actionsEnabled,
                    onSelect = {
                        selectedFile = if (selectedFile == entry) null else entry
                    },
                    onNavigate = {
                        val dirName = entry.name.substringAfterLast('/')
                        val nextPath = if (currentPath.endsWith("/")) "$currentPath$dirName" else "$currentPath/$dirName"
                        currentPath = nextPath
                        selectedFile = null
                        val norm = nextPath.trim('/')
                        val hasEntries = state.files.any { it.name.trimStart('/').startsWith("$norm/") }
                        if (!hasEntries) {
                            val simulated = simulatedFolderContents[nextPath]
                            if (simulated != null) {
                                val seeded = simulated.map { sim ->
                                    FileEntry(
                                        name = "$norm/${sim.name}",
                                        isDirectory = sim.isDirectory,
                                        sizeBytes = sim.sizeBytes,
                                    )
                                }
                                onIntent(UiIntent.UpdateFiles(state.files + seeded))
                            }
                        }
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
                            val norm = currentPath.trim('/')
                            val createdName = if (norm.isEmpty()) name else "$norm/$name"
                            val entry = FileEntry(name = createdName, isDirectory = true, sizeBytes = 0uL)
                            onIntent(UiIntent.AddFile(entry))
                            val createdPath = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                            onIntent(UiIntent.Status("Created folder $createdPath"))
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
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete") },
            text = { Text("Delete ${selectedFile?.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val entry = selectedFile
                        if (entry != null) {
                            onIntent(UiIntent.DeleteFile(entry))
                            val name = entry.name.substringAfterLast('/')
                            onIntent(UiIntent.Status("Deleted $name"))
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
        val displayName = entry.name.substringAfterLast('/')
        Text(
            if (entry.isDirectory) {
                "$displayName/"
            } else {
                "$displayName · ${DeviceIdentity.formatCapacity(entry.sizeBytes)}"
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

private val simulatedFolderContents: Map<String, List<FileEntry>> = mapOf(
    "/ventoy" to listOf(
        FileEntry("ventoy.json", isDirectory = false, sizeBytes = 512uL),
        FileEntry("log.txt", isDirectory = false, sizeBytes = 1024uL),
    ),
    "/ISO" to listOf(
        FileEntry("ubuntu-24.04-desktop-amd64.iso", isDirectory = false, sizeBytes = 4_500_000_000uL),
    ),
    "/EFI" to listOf(
        FileEntry("BOOT", isDirectory = true, sizeBytes = 0uL),
        FileEntry("bootx64.efi", isDirectory = false, sizeBytes = 1_048_576uL),
    ),
    "/grub" to listOf(
        FileEntry("grub.cfg", isDirectory = false, sizeBytes = 2048uL),
    ),
    "/docs" to listOf(
        FileEntry("manual.pdf", isDirectory = false, sizeBytes = 104_857uL),
    ),
)
