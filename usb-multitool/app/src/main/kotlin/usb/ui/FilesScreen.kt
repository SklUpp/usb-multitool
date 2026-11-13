package usb.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.fs.FilesystemSpace
import usb.multitool.R

/**
 * Files tab over the real volume of the selected partition.
 *
 * Two blocks only: the Partitions block carries the partition choice row and the measured
 * filesystem free space; the folder block owns the path, the icon actions, the expandable
 * statuses and one shared lazy area for every file/folder of the mounted volume. Write state
 * shows RO only for guaranteed EFI protection and unknown otherwise: a verified RW needs a
 * live mount fact. EFI policy still lives on the Ventoy tab, and the reducer keeps enforcing
 * it for every mutation.
 */
@Composable
fun FilesScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onRequestDownload: (FilesDownloadBinding) -> Unit = {},
    onRequestDeviceImage: (String, String) -> Unit = { _, _ -> },
) {
    val pickMode = state.filesPickMode
    if (pickMode != null) {
        FilesPickScreen(
            state = state,
            mode = pickMode,
            onIntent = onIntent,
            onRequestDeviceImage = onRequestDeviceImage,
            modifier = modifier,
        )
        return
    }
    // Attach generation/identity are keys too: the same partition id and path on another
    // stick must not keep a highlighted entry or a captured rename target.
    var selectedFile by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        mutableStateOf<FileEntry?>(null)
    }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        mutableStateOf(false)
    }
    var renameTarget by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        mutableStateOf<FileEntry?>(null)
    }
    var renameBinding by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
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
    // Only an EFI-protected selected partition loses write actions; a missing selection
    // must not be reported as EFI protection.
    val efiBlocked = selected != null && !state.isEfiWriteAllowed(selected)
    val canWrite = canAct && !efiBlocked
    val selection = state.files.firstOrNull { it == selectedFile }
    val writeAccess = selected?.let(state::partitionWriteAccess) ?: PartitionWriteAccess.Unknown
    val space = selected?.let(state::filesystemSpace)

    // Statuses of the current folder are expandable; the expanded choice belongs to this
    // exact attachment + path, not to another stick or folder.
    var statusesExpanded by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        mutableStateOf(false)
    }

    if (state.partitions.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCard(
                title = stringResource(R.string.files_section_partition),
                titleColor = MaterialTheme.colorScheme.onSurface,
            ) {
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // A large font or a short window must not squeeze the file list out of existence:
        // the header is capped and scrolls inside its cap, the folder block keeps the rest.
        val headerMaxHeight = maxHeight * 0.45f
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = headerMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionCard(
                    title = stringResource(R.string.files_section_partition),
                    titleColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    PartitionChoiceRow(
                        options = state.partitions.map { it.id to partitionOptionLabel(state, it) },
                        selectedId = selected?.id ?: state.partitions.first().id,
                        writeAccess = writeAccess,
                        enabled = state.actionsEnabled,
                        onSelect = { id -> onIntent(UiIntent.SelectPartition(id)) },
                    )
                    Text(
                        text = freeSpaceText(space),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            FolderCard(
                state = state,
                canAct = canAct,
                canWrite = canWrite,
                selection = selection,
                selectedFile = selectedFile,
                statusMessage = state.statusMessage,
                statusesExpanded = statusesExpanded,
                onToggleStatuses = { statusesExpanded = !statusesExpanded },
                onSelect = { entry -> selectedFile = if (selectedFile == entry) null else entry },
                onNavigate = { entry ->
                    onIntent(UiIntent.OpenDirectory(fatChildPath(state.currentPath, entry.name)))
                },
                onClearSelection = { selectedFile = null },
                onRefresh = { onIntent(UiIntent.RefreshFiles) },
                onNavigateUp = { onIntent(UiIntent.NavigateUp) },
                onNewFolder = { showNewFolderDialog = true },
                onCopyFromPhone = { uploadLauncher.launch("*/*") },
                onSaveToPhone = {
                    val device = state.device
                    val partition = state.selectedPartition()
                    selection?.takeIf { it.canDownload() }?.let { entry ->
                        if (device != null && partition != null) {
                            onRequestDownload(
                                FilesDownloadBinding(
                                    directory = state.currentPath,
                                    name = entry.name,
                                    sizeBytes = entry.sizeBytes,
                                    identity = device.identity,
                                    attachGeneration = device.attachGeneration,
                                    partitionId = partition.id,
                                    startLba = partition.startLba,
                                    sectorCount = partition.sectorCount,
                                ),
                            )
                        }
                    }
                },
                onRename = {
                    val device = state.device
                    val partition = state.selectedPartition()
                    val target = selection
                    if (device != null && partition != null && target != null) {
                        renameTarget = target
                        renameName = target.name
                        renameBinding = PartitionBinding(
                            device = device,
                            partitionId = partition.id,
                            startLba = partition.startLba,
                            sectorCount = partition.sectorCount,
                        )
                    }
                },
                onDelete = { showDeleteConfirmDialog = true },
                modifier = Modifier.weight(1f),
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

/**
 * Simplified Files screen: only navigation, one explicit choice, and (for the install
 * image) the phone copy action. Every destructive or unrelated file action is absent;
 * the reducer refuses those intents too, so this is not only a visual restriction.
 *
 * Deliberately a separate branch from the ordinary browser: the Files visual redesign
 * (headers, contrast, path row, icons) can change the normal composable without having
 * to rebuild this session's pin/copy safety.
 */
@Composable
private fun FilesPickScreen(
    state: AppUiState,
    mode: FilesPickMode,
    onIntent: (UiIntent) -> Unit,
    onRequestDeviceImage: (String, String) -> Unit,
    modifier: Modifier,
) {
    val partition = state.partitions.firstOrNull { it.id == mode.partitionId }
    val canAct = state.actionsEnabled && partition != null
    val copying = state.operation?.takeIf { it.title is OperationTitle.ImportTarget }
    val upDescription = stringResource(R.string.files_up)
    val hint = stringResource(
        if (mode.target == FilesPickTarget.Folder) {
            R.string.import_folder_use
        } else {
            R.string.files_pick_hint
        },
    )
    val partitionLine =
        partition?.let {
            buildString {
                append(it.name)
                if (it.isEfiType) append(" (EFI)")
                it.filesystemLabel.takeIf { label -> label.isNotEmpty() }?.let { label ->
                    append(" · ")
                    append(label)
                }
                partitionSizeLabel(state, it)?.let { size ->
                    append(" · ")
                    append(size)
                }
            }
        } ?: stringResource(R.string.partitions_none_selected)

    // A vanished import folder resets the listing to root in the reducer; consume the
    // picker demand here instead of leaving an unanswered prompt.
    LaunchedEffect(state.pendingImportFolderPick) {
        if (state.pendingImportFolderPick == WindowsImportKind.Image) {
            onIntent(UiIntent.NavigateUp)
            onIntent(UiIntent.ImportFolderPickOpened)
        }
    }

    val entries =
        if (mode.target == FilesPickTarget.Folder) {
            state.files.filter { it.isDirectory }
        } else {
            state.files
        }
    // Two-step file choice: a tap only marks the row; the explicit button confirms it.
    // Path or attachment changes make the old row meaningless, so they reset the mark.
    val selectsFile = mode.target == FilesPickTarget.File
    val listingUsable = state.filesError == null && !state.filesPending
    // Folder mode never owns a selected file row; only file picks allocate this state.
    // Geometry is a key too: a pin that no longer names this partition cannot keep a row.
    val selectedPickState =
        if (selectsFile) {
            remember(
                mode.sessionId,
                mode.partitionId,
                mode.startLba,
                mode.sectorCount,
                state.currentPath,
                state.device?.identity,
                state.device?.attachGeneration,
            ) {
                mutableStateOf<FileEntry?>(null)
            }
        } else {
            null
        }
    // Equality, not name: a replacement entry with the same name but new size is stale.
    val selectedPickEntry = selectedPickState?.value?.takeIf { selected -> selected in entries }
    LaunchedEffect(entries, selectedPickState?.value, listingUsable) {
        val pickerState = selectedPickState
        if (pickerState != null &&
            (pickerState.value != null && selectedPickEntry == null || !listingUsable)
        ) {
            pickerState.value = null
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "pick-partition") {
            SectionCard(
                title = stringResource(R.string.files_section_partition),
                titleColor = MaterialTheme.colorScheme.onSurface,
            ) {
                InfoRow(
                    stringResource(R.string.files_current_partition),
                    partitionLine,
                )
            }
        }

        item(key = "pick-folder") {
            SectionCard(
                title = stringResource(R.string.files_section_folder),
                subtitle = state.currentPath,
                titleColor = MaterialTheme.colorScheme.onSurface,
                trailing = {
                    IconButton(
                        onClick = { onIntent(UiIntent.NavigateUp) },
                        enabled = canAct && state.currentPath != "/",
                        modifier = Modifier.semantics { contentDescription = upDescription },
                    ) {
                        GlyphIcon(Glyph.ArrowUp, size = 22.dp)
                    }
                },
            ) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (mode.target == FilesPickTarget.Folder) {
                        OutlinedButton(
                            onClick = { onIntent(UiIntent.ConfirmFilesPickFolder) },
                            enabled = canAct && !state.filesPending,
                        ) {
                            Text(stringResource(R.string.import_folder_use))
                        }
                    }
                    TextButton(
                        onClick = { onIntent(UiIntent.CancelFilesPick) },
                        enabled = canAct,
                    ) {
                        Text(stringResource(R.string.files_pick_back))
                    }
                    if (mode.purpose == FilesPickPurpose.WindowsImage &&
                        mode.target == FilesPickTarget.File
                    ) {
                        OutlinedButton(
                            onClick = {
                                onRequestDeviceImage(state.currentPath, mode.sessionId)
                            },
                            enabled =
                                canAct &&
                                    state.isoImportSafInFlight == null &&
                                    state.isoImportUsbInFlight == null &&
                                    state.pendingIsoImport == null,
                        ) {
                            Text(stringResource(R.string.files_pick_device))
                        }
                    }
                }
            }
        }

        if (copying != null) {
            item(key = "pick-copy") {
                SectionCard(
                    title = stringResource(R.string.import_title),
                    titleColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    val failed =
                        copying.state == OperationState.Failed ||
                            copying.state == OperationState.Partial
                    Text(
                        text =
                            "${operationLabel(copying)} · " +
                                operationStateLabel(copying.state),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            if (failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                    if (copying.bytesTotal > 0L) {
                        LinearProgressIndicator(
                            progress = { copying.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 4.dp),
                        )
                        Text(
                            text =
                                "${DeviceIdentity.formatCapacity(copying.bytesDone.coerceAtLeast(0L).toULong())}" +
                                    " / ${DeviceIdentity.formatCapacity(copying.bytesTotal.toULong())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    (copying.errorMessage ?: copying.resultMessage)?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                    if (copying.state == OperationState.Running && copying.cancelable) {
                        TextButton(onClick = { onIntent(UiIntent.CancelOperation(copying.token)) }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        }

        if (state.isoImportSafInFlight != null) {
            item(key = "pick-inspecting") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.import_inspecting),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onIntent(UiIntent.CancelWindowsIsoImport) }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
        state.isoImportError?.let { problem ->
            item(key = "pick-import-error") {
                Text(
                    text = stringResource(unreadableText(problem)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        when {
            state.filesError != null -> item(key = "pick-error") {
                EmptyHint(state.filesError.orEmpty())
            }
            state.filesPending -> item(key = "pick-loading") {
                EmptyHint(stringResource(R.string.files_listing))
            }
            entries.isEmpty() -> item(key = "pick-empty") {
                EmptyHint(stringResource(R.string.files_empty_folder))
            }
            else -> items(entries, key = { it.name }) { entry ->
                Column(modifier = Modifier.animateItem()) {
                    // Keep the confirmation next to the chosen row, so it cannot be
                    // scrolled off-screen together with the header.
                    if (selectedPickEntry == entry) {
                        OutlinedButton(
                            onClick = {
                                // The reducer revalidates the name against live files.
                                onIntent(UiIntent.ConfirmFilesPickFile(entry.name))
                            },
                            enabled = canAct && listingUsable,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            Text(stringResource(R.string.files_pick_use_file))
                        }
                    }
                    FileRow(
                        entry = entry,
                        isSelected = selectedPickEntry == entry,
                        actionsEnabled = canAct,
                        onSelect = {
                            selectedPickState?.value =
                                if (selectedPickState?.value == entry) null else entry
                        },
                        onNavigate = {
                            selectedPickState?.value = null
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

    state.pendingIsoImport?.let { proposal ->
        ImportOverwriteDialog(
            proposal = proposal,
            actionsEnabled = state.actionsEnabled,
            onReplace = { onIntent(UiIntent.ConfirmWindowsIsoImport(true)) },
            onCancel = { onIntent(UiIntent.CancelWindowsIsoImport) },
        )
    }
}

/** One block around the whole mounted volume: path/actions sub-block plus the lazy list. */
@Composable
private fun FolderCard(
    state: AppUiState,
    canAct: Boolean,
    canWrite: Boolean,
    selection: FileEntry?,
    selectedFile: FileEntry?,
    statusMessage: UiMessage?,
    statusesExpanded: Boolean,
    onToggleStatuses: () -> Unit,
    onSelect: (FileEntry) -> Unit,
    onNavigate: (FileEntry) -> Unit,
    onClearSelection: () -> Unit,
    onRefresh: () -> Unit,
    onNavigateUp: () -> Unit,
    onNewFolder: () -> Unit,
    onCopyFromPhone: () -> Unit,
    onSaveToPhone: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        // One lazy area for the whole volume: the path/action sub-block is its first item, so
        // a short window or a large font scrolls it away instead of squeezing the list to zero.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "folder-subblock") {
                FolderSubblock(
                    pathLabel = folderPathLabel(state.currentPath),
                    canNavigateUp = canAct && state.currentPath != "/",
                    canAct = canAct,
                    canWrite = canWrite,
                    selection = selection,
                    statusMessage = statusMessage,
                    statusesExpanded = statusesExpanded,
                    filesPending = state.filesPending,
                    filesError = state.filesError,
                    onToggleStatuses = onToggleStatuses,
                    onNavigateUp = onNavigateUp,
                    onRefresh = onRefresh,
                    onNewFolder = onNewFolder,
                    onCopyFromPhone = onCopyFromPhone,
                    onSaveToPhone = onSaveToPhone,
                    onRename = onRename,
                    onDelete = onDelete,
                    onClearSelection = onClearSelection,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            when {
                !state.actionsEnabled -> item(key = "presence") {
                    EmptyHint(
                        UiReducer.presenceMessage(state.presence).localized(),
                        modifier = Modifier.padding(16.dp),
                    )
                }
                state.filesPending -> item(key = "loading") {
                    EmptyHint(
                        stringResource(R.string.files_listing),
                        modifier = Modifier.padding(16.dp),
                    )
                }
                state.files.isEmpty() -> item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val readError = state.filesError
                        Text(
                            text = readError ?: stringResource(R.string.files_empty_folder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (readError != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                else -> items(state.files, key = { it.name }) { entry ->
                    FileRow(
                        modifier = Modifier.animateItem(),
                        entry = entry,
                        isSelected = entry == selectedFile,
                        actionsEnabled = canAct,
                        onSelect = { onSelect(entry) },
                        onNavigate = { onNavigate(entry) },
                    )
                }
            }
        }
    }
}

/**
 * Path and icon actions at the top of the folder block. Its bottom is the expandable status
 * area: the general status and the real fields of the selected file/folder, nothing synthesized.
 */
@Composable
private fun FolderSubblock(
    pathLabel: String,
    canNavigateUp: Boolean,
    canAct: Boolean,
    canWrite: Boolean,
    selection: FileEntry?,
    statusMessage: UiMessage?,
    statusesExpanded: Boolean,
    filesPending: Boolean,
    filesError: String?,
    onToggleStatuses: () -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onCopyFromPhone: () -> Unit,
    onSaveToPhone: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val upDescription = stringResource(R.string.files_up)
    val refreshDescription = stringResource(R.string.unattend_refresh)
    val newFolderDescription = stringResource(R.string.files_create_folder)
    val copyDescription = stringResource(R.string.files_copy_from_phone)
    val saveDescription = stringResource(R.string.files_save_to_phone)
    val renameDescription = stringResource(R.string.files_rename)
    val deleteDescription = stringResource(R.string.action_delete)
    val clearDescription = stringResource(R.string.files_clear_selection)
    // The current (last) segment is what the user needs to see: keep the scroll at the end
    // whenever the path label or its measured width changes.
    val pathScroll = remember(pathLabel) { ScrollState(0) }
    val pathA11y = stringResource(R.string.files_current_path, pathLabel)
    LaunchedEffect(pathScroll.maxValue) {
        pathScroll.scrollTo(pathScroll.maxValue)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The block title is the up button itself; the path is relative to the root
                // and is scrolled to its end, so the current folder segment stays visible.
                IconButton(
                    onClick = onNavigateUp,
                    enabled = canNavigateUp,
                    modifier = Modifier.semantics { contentDescription = upDescription },
                ) {
                    GlyphIcon(glyph = Glyph.ArrowUp, size = 22.dp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                        .horizontalScroll(pathScroll),
                ) {
                    Text(
                        text = pathLabel,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = pathA11y
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderActionButton(
                    glyph = Glyph.Refresh,
                    description = refreshDescription,
                    enabled = canAct,
                    onClick = onRefresh,
                )
                FolderActionButton(
                    glyph = Glyph.FolderPlus,
                    description = newFolderDescription,
                    enabled = canWrite,
                    onClick = onNewFolder,
                )
                FolderActionButton(
                    glyph = Glyph.Download,
                    description = copyDescription,
                    enabled = canWrite,
                    onClick = onCopyFromPhone,
                )
                FolderActionButton(
                    glyph = Glyph.Upload,
                    description = saveDescription,
                    enabled = downloadActionEnabled(canAct, selection),
                    onClick = onSaveToPhone,
                )
                FolderActionButton(
                    glyph = Glyph.Pencil,
                    description = renameDescription,
                    enabled = canWrite && selection != null,
                    onClick = onRename,
                )
                FolderActionButton(
                    glyph = Glyph.Trash,
                    description = deleteDescription,
                    enabled = canWrite && selection != null,
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error,
                )
                if (selection != null) {
                    FolderActionButton(
                        glyph = Glyph.Close,
                        description = clearDescription,
                        enabled = canAct,
                        onClick = onClearSelection,
                    )
                }
            }
            // Expandable status space at the bottom of the sub-block: general status plus the
            // real fields of the selected file/folder. Collapsed by default, never synthesized.
            FolderStatusArea(
                expanded = statusesExpanded,
                onToggle = onToggleStatuses,
                statusMessage = statusMessage,
                filesPending = filesPending,
                filesError = filesError,
                selection = selection,
            )
        }
    }
}

/**
 * Expandable statuses: a general status from the live state plus the real fields of the
 * selected file/folder. Collapsed by default; nothing is synthesized when a value is absent.
 */
@Composable
private fun FolderStatusArea(
    expanded: Boolean,
    onToggle: () -> Unit,
    statusMessage: UiMessage?,
    filesPending: Boolean,
    filesError: String?,
    selection: FileEntry?,
) {
    val title = stringResource(R.string.files_statuses)
    val expansion = stringResource(
        if (expanded) R.string.files_statuses_expanded else R.string.files_statuses_collapsed,
    )
    val general = statusMessage?.localized()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = title,
                    role = Role.Button,
                ) { onToggle() }
                .semantics { stateDescription = expansion }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            GlyphIcon(
                glyph = if (expanded) Glyph.ChevronUp else Glyph.ChevronDown,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!expanded) {
            general?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        } else {
            general?.let {
                StatusLine(stringResource(R.string.files_status_general), it)
            }
            if (filesPending) {
                StatusLine(
                    stringResource(R.string.type_folder),
                    stringResource(R.string.files_listing),
                )
            }
            filesError?.let {
                StatusLine(stringResource(R.string.type_folder), it, error = true)
            }
            selection?.let { SelectionStatusRow(it) }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, error: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(0.6f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Real fields of the selected entry; a directory never claims a file format or size. */
@Composable
private fun SelectionStatusRow(entry: FileEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.isDirectory) {
            Text(
                text = stringResource(R.string.type_folder),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        } else {
            fileFormatLabel(entry.name, entry.isDirectory)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Text(
                text = DeviceIdentity.formatCapacity(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** Icon-only control; the description is the only visible-to-a11y label. */
@Composable
private fun FolderActionButton(
    glyph: Glyph,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        // tint is captured outside IconButton's disabled color provider, so dim it here.
        GlyphIcon(
            glyph = glyph,
            size = 22.dp,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
        )
    }
}

/** Rectangular partition choice: label, real name/format/size and the truthful RO/RW badge. */
@Composable
private fun PartitionChoiceRow(
    options: List<Pair<String, String>>,
    selectedId: String,
    writeAccess: PartitionWriteAccess,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.files_current_partition)
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = enabled,
                        onClickLabel = label,
                        role = Role.Button,
                    ) { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = options.firstOrNull { it.first == selectedId }?.second ?: "—",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WriteAccessBadge(writeAccess)
                GlyphIcon(
                    glyph = Glyph.ChevronDown,
                    size = 20.dp,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    trailingIcon = if (value == selectedId) {
                        {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * Uppercase write marker. RO is red and only claims guaranteed EFI protection; everything
 * else is an em dash announced as "write state not verified".
 */
@Composable
private fun WriteAccessBadge(access: PartitionWriteAccess) {
    val color = when (access) {
        PartitionWriteAccess.ReadOnly -> MaterialTheme.colorScheme.error
        PartitionWriteAccess.Unknown -> MaterialTheme.colorScheme.onSurface
    }
    val label = when (access) {
        PartitionWriteAccess.ReadOnly -> "RO"
        PartitionWriteAccess.Unknown -> "—"
    }
    val description = when (access) {
        PartitionWriteAccess.ReadOnly -> stringResource(R.string.files_write_ro)
        PartitionWriteAccess.Unknown -> stringResource(R.string.files_write_unknown)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
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
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            Color.Transparent
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { selected = isSelected }
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
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            val shownName = entry.name
            val parts = fileNameParts(entry.name, entry.isDirectory)
            // The suffix is its own node so a long file name ellipsizes the base and never the
            // extension; folders show the icon and their real name, without a trailing slash.
            // The row announces the real full name once, through semantics.
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun freeSpaceText(space: FilesystemSpace?): String =
    if (space != null) {
        stringResource(
            R.string.filesystem_usage_known,
            DeviceIdentity.formatCapacity(space.freeBytes),
            DeviceIdentity.formatCapacity(space.totalBytes),
        )
    } else {
        stringResource(R.string.filesystem_usage_unavailable)
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
