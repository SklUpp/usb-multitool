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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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

    if (state.partitions.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCard(title = stringResource(R.string.files_section_partition)) {
                EmptyHint(stringResource(R.string.files_no_partitions_hint))
                OutlinedButton(
                    onClick = { onIntent(UiIntent.SelectTab(ShellTab.Partitions)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.files_no_partitions_cta))
                }
            }
        }
        return
    }

    val upDescription = stringResource(R.string.files_up)
    val createFolderLabel = stringResource(R.string.files_create_folder)

    Box(modifier = modifier.fillMaxSize()) {
        // One scroll container: the header cards scroll with the folder contents, so the
        // folder actions and the selected-entry card stay reachable in landscape and on
        // short screens, where three fixed cards alone could fill the viewport.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 76.dp),
        ) {
            item(key = "partition") {
                SectionCard(
                    title = stringResource(R.string.files_section_partition),
                    subtitle = selected?.let {
                        "${it.name} · ${it.filesystemLabel.ifEmpty { "—" }} · " +
                            (partitionSizeLabel(state, it) ?: "—")
                    } ?: stringResource(R.string.partitions_none_selected),
                ) {
                    CompactChoice(
                        options = state.partitions.map {
                            it.id to partitionOptionLabel(state, it)
                        },
                        selected = selected?.id ?: state.partitions.first().id,
                        onSelect = { id -> onIntent(UiIntent.SelectPartition(id)) },
                        label = stringResource(R.string.files_current_partition),
                        enabled = state.actionsEnabled,
                    )
                    val space = selected?.let(state::filesystemSpace)
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
                }
            }

            item(key = "folder") {
                SectionCard(
                    title = stringResource(R.string.files_section_folder),
                    subtitle = breadcrumbLabel(state.currentPath),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.elements_count,
                                state.files.size,
                                state.files.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // Named actions: an up-chevron and a plus alone were ambiguous.
                        TextButton(
                            onClick = { onIntent(UiIntent.NavigateUp) },
                            enabled = canAct && state.currentPath != "/",
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.semantics { contentDescription = upDescription },
                        ) {
                            Text(stringResource(R.string.files_up_action))
                        }
                        TextButton(
                            onClick = { showNewFolderDialog = true },
                            enabled = canWrite,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.semantics { contentDescription = createFolderLabel },
                        ) {
                            Text(stringResource(R.string.files_new_folder_action))
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
            }

            if (selection != null) {
                item(key = "selection") {
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
                !state.actionsEnabled -> item(key = "presence") {
                    EmptyHint(UiReducer.presenceMessage(state.presence).localized())
                }
                state.filesPending -> item(key = "loading") {
                    EmptyHint(stringResource(R.string.files_listing))
                }
                state.files.isEmpty() -> item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
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
                else -> items(state.files, key = { it.name }) { entry ->
                    // Reorders/inserts animate in place instead of jumping.
                    val rowModifier = Modifier.animateItem()
                    FileRow(
                        modifier = rowModifier,
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

        if (canWrite) {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.files_upload)) },
                icon = { GlyphIcon(Glyph.Plus, size = 20.dp) },
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
                    enabled = newFolderName.isNotBlank(),
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
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = modifier
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
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (entry.isDirectory) R.drawable.ic_file_folder else R.drawable.ic_file,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            val shownName = if (entry.isDirectory) "${entry.name}/" else entry.name
            val parts = fileNameParts(entry.name, entry.isDirectory)
            // The suffix is its own node so a long name ellipsizes the base and never the
            // extension; the row announces the real full name once, through semantics.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = shownName },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val weight =
                    if (entry.isDirectory || isSelected) FontWeight.Medium else FontWeight.Normal
                Text(
                    text = parts.base,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (parts.suffix.isNotEmpty()) {
                    Text(
                        text = parts.suffix,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = weight,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
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

private data class FileNameParts(val base: String, val suffix: String)

/**
 * Splits a real entry name into an ellipsizable base and a suffix that is never truncated:
 * directories keep their trailing slash, files keep their extension. Names without an
 * extension and hidden names (leading dot) stay whole, so nothing is invented or cut.
 */
private fun fileNameParts(name: String, isDirectory: Boolean): FileNameParts {
    if (isDirectory) return FileNameParts(name, "/")
    val dot = name.lastIndexOf('.')
    return if (dot in 1 until name.lastIndex) {
        FileNameParts(name.substring(0, dot), name.substring(dot))
    } else {
        FileNameParts(name, "")
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
