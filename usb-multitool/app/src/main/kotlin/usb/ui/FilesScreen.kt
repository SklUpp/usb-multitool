package usb.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R

/**
 * Files tab over the real FAT volume of the selected partition.
 *
 * Grouped top-down as: partition choice → current folder → selected entry → contents.
 * The list owns the viewport; upload is a single FAB overlaying the list bottom.
 * EFI write protection is not handled here: it lives on the Ventoy tab as an
 * explicit policy override, never as a mount-mode switch.
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
    var renameTarget by remember(state.selectedPartitionId, state.currentPath) {
        mutableStateOf<FileEntry?>(null)
    }
    var renameBinding by remember(state.selectedPartitionId, state.currentPath) {
        mutableStateOf<PartitionBinding?>(null)
    }
    var renameName by remember { mutableStateOf("") }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            onIntent(UiIntent.UploadFile(uri.toString()))
        }
    }

    val selected = state.selectedPartition()
    val canAct = state.actionsEnabled && selected != null
    // Only an EFI-protected selected partition gets the EFI hint; a missing
    // selection must not be reported as EFI protection.
    val efiBlocked = selected != null && !state.isEfiWriteAllowed(selected)
    val canWrite = canAct && !efiBlocked
    val selection = state.files.firstOrNull { it.name == selectedFile?.name }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionCard(
                title = stringResource(R.string.files_section_partition),
                subtitle = selected?.let {
                    "${it.name} · ${it.filesystemLabel.ifEmpty { "—" }} · " +
                        (partitionSizeLabel(state, it) ?: "—")
                } ?: stringResource(R.string.partitions_none),
            ) {
                if (state.partitions.isEmpty()) {
                    EmptyHint(stringResource(R.string.files_no_partitions_hint))
                } else {
                    CompactChoice(
                        options = state.partitions.map {
                            it.id to partitionOptionLabel(state, it)
                        },
                        selected = selected?.id ?: state.partitions.first().id,
                        onSelect = { id -> onIntent(UiIntent.SelectPartition(id)) },
                        label = stringResource(R.string.files_current_partition),
                        enabled = state.actionsEnabled,
                    )
                }
            }

            val upDescription = stringResource(R.string.files_up)
            val createFolderLabel = stringResource(R.string.files_create_folder)
            SectionCard(
                title = stringResource(R.string.files_section_folder),
                subtitle = breadcrumbLabel(state.currentPath),
            ) {
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
                            modifier = Modifier.semantics { contentDescription = upDescription },
                        ) {
                            GlyphIcon(Glyph.ChevronLeft, size = 20.dp)
                        }
                    }
                    Text(
                        text = if (state.partitions.isEmpty()) {
                            stringResource(R.string.partitions_none)
                        } else {
                            pluralStringResource(
                                R.plurals.elements_count,
                                state.files.size,
                                state.files.size,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { showNewFolderDialog = true },
                        enabled = canWrite,
                        modifier = Modifier.semantics { contentDescription = createFolderLabel },
                    ) {
                        GlyphIcon(Glyph.Plus, size = 20.dp)
                    }
                }
                if (selected == null && state.partitions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.msg_select_partition_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (efiBlocked) {
                    Text(
                        text = stringResource(R.string.files_efi_blocked, selected?.name.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            AnimatedVisibility(
                visible = selection != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                selection?.let { selection ->
                SectionCard(
                    title = stringResource(R.string.files_section_selection),
                    subtitle = selection.name,
                    trailing = {
                        TextButton(
                            onClick = { selectedFile = null },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stringResource(R.string.files_clear_selection),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                ) {
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
                                Text(
                                    stringResource(R.string.files_save),
                                    style = MaterialTheme.typography.labelMedium,
                                )
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
                                Text(
                                    stringResource(R.string.files_to_raw),
                                    style = MaterialTheme.typography.labelMedium,
                                )
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
                            onClick = {
                                val device = state.device
                                val partition = state.selectedPartition()
                                if (device != null && partition != null) {
                                    renameTarget = selection
                                    renameName = selection.name
                                    renameBinding = PartitionBinding(
                                        device = device,
                                        partitionId = partition.id,
                                        startLba = partition.startLba,
                                        sectorCount = partition.sectorCount,
                                    )
                                }
                            },
                            enabled = canWrite,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stringResource(R.string.files_rename),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        TextButton(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = canWrite,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stringResource(R.string.action_delete),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                }
            }

            when {
                !state.actionsEnabled -> EmptyHint(UiReducer.presenceMessage(state.presence).localized())
                state.partitions.isEmpty() ->
                    EmptyHint(stringResource(R.string.files_content_unavailable))
                state.files.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val readError = state.filesError
                        Text(
                            text = readError ?: stringResource(R.string.files_empty_folder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (readError != null) {
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

        if (canWrite) {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.files_upload)) },
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
            title = { Text(stringResource(R.string.files_create_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.files_folder_name)) },
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
                    Text(stringResource(R.string.action_create))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewFolderDialog = false
                        newFolderName = ""
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    renameTarget?.let { target ->
        val binding = renameBinding
        AlertDialog(
            onDismissRequest = {
                renameTarget = null
                renameBinding = null
            },
            title = { Text(stringResource(R.string.files_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text(stringResource(R.string.files_new_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (binding != null) {
                            onIntent(
                                UiIntent.RenameEntry(target.name, renameName.trim(), binding),
                            )
                        }
                        renameTarget = null
                        renameBinding = null
                    },
                    enabled = binding != null && renameName.trim().isNotEmpty(),
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null; renameBinding = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDeleteConfirmDialog && selectedFile != null) {
        val entry = state.files.firstOrNull { it.name == selectedFile?.name }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.action_delete)) },
            text = {
                Text(
                    if (entry?.isDirectory == true) {
                        stringResource(R.string.files_delete_folder_confirm, entry.name)
                    } else {
                        stringResource(R.string.files_delete_confirm, entry?.name.orEmpty())
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (entry != null) {
                            onIntent(UiIntent.DeleteEntry(entry.name))
                        } else {
                            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SelectedFileMissing)))
                        }
                        selectedFile = null
                        showDeleteConfirmDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                ) {
                    Text(stringResource(R.string.action_cancel))
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
                onClickLabel = if (entry.isDirectory) {
                    stringResource(R.string.files_open_folder)
                } else {
                    stringResource(R.string.files_select_file)
                },
                role = Role.Button,
            ) {
                if (entry.isDirectory) onNavigate() else onSelect()
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        entry.isDirectory -> stringResource(R.string.type_folder)
                        entry.isIso -> stringResource(R.string.type_iso)
                        else -> stringResource(R.string.type_file)
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

private fun partitionSizeLabel(state: AppUiState, partition: PartitionTab): String? =
    partition.sectorCount?.let {
        DeviceIdentity.formatCapacity(it * state.diskSectorSize.toULong())
    }

private fun partitionOptionLabel(state: AppUiState, partition: PartitionTab): String =
    buildString {
        append(partition.name)
        if (partition.isEfiType) append(" (EFI)")
        partition.filesystemLabel.takeIf { it.isNotEmpty() }?.let {
            append(" · ")
            append(it)
        }
        partitionSizeLabel(state, partition)?.let {
            append(" · ")
            append(it)
        }
    }

@Composable
private fun breadcrumbLabel(path: String): String {
    val trimmed = path.trim('/')
    return if (trimmed.isEmpty()) {
        stringResource(R.string.files_root)
    } else {
        stringResource(R.string.files_root_prefix, trimmed.replace("/", " / "))
    }
}
