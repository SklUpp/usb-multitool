package usb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.formatEtaDuration
import usb.fs.FilesystemSpace
import usb.multitool.R
import usb.remainingSeconds

/**
 * Files tab over real volume of selected partition.
 *
 * Compact partition choice row carries measured filesystem space; folder block owns path,
 * Refresh/CreateFolder and one shared lazy area for every file/folder. Selection
 * Download/Rename/Delete/Clear live in the selection row and the per-row bottom sheet.
 * Operation errors and progress stay in shell's shared status panel. Write badge shows
 * only measured states: EFI policy or driver mount facts; unknown renders no badge.
 */
@Composable
fun FilesScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onRequestDownload: (FilesDownloadBinding) -> Unit = {},
    onRequestBatchDownload: (FilesBatchSelection) -> Unit = {},
    onRequestBatchUpload: () -> Unit = {},
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
    var selectedNames by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        mutableStateOf(emptySet<String>())
    }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    // Capture the exact batch pin at confirm-open time so refresh/navigation cannot retarget.
    var pendingDeleteSelection by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        mutableStateOf<FilesBatchSelection?>(null)
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
    var actionEntry by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf<FileEntry?>(null) }


    val selected = state.selectedPartition()
    val fileListState = rememberLazyListState()
    var isScrollingDown by remember { mutableStateOf(false) }
    LaunchedEffect(fileListState) {
        var previousPosition = 0
        snapshotFlow {
            fileListState.firstVisibleItemIndex * 1_000_000 +
                fileListState.firstVisibleItemScrollOffset to fileListState.isScrollInProgress
        }.collect { (position, scrolling) ->
            isScrollingDown = scrolling && position > previousPosition
            previousPosition = position
        }
    }
    val canAct = state.actionsEnabled && selected != null
    // Only an EFI-protected selected partition loses write actions; a missing selection
    // must not be reported as EFI protection.
    val efiBlocked = selected != null && !state.isEfiWriteAllowed(selected)
    val canWrite = canAct && !efiBlocked
    val selection = resolveListingSelection(state.files, selectedNames)
    val selectedEntries = selection.entries
    val singleSelection = selection.single
    val writeAccess = selected?.let(state::partitionWriteAccess) ?: PartitionWriteAccess.Unknown
    val uploadDescription = stringResource(R.string.files_copy_from_phone)
    val space = selected?.let(state::filesystemSpace)

    // Blocked release marks an unconfirmed volume close/sync after a failed probe
    // release: partitions are cleared and presence stays Busy with no live operation.
    // The close error alone does not prove Android claimed the device, and an
    // Android-side unmount/eject does not reset this latch.
    val blockedRelease = (state.statusMessage as? UiMessage.Key)
        ?.takeIf { it.key == UiMessageKey.VolumeSyncUnconfirmed }
    val systemBlocked = state.presence == DevicePresence.Busy &&
        (state.operation == null || state.operation.state.isTerminal()) && blockedRelease != null
    if (state.partitions.isEmpty()) {
        if (!systemBlocked) {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EmptyHint(stringResource(R.string.files_no_partitions_hint))
                OutlinedButton(
                    onClick = { onIntent(UiIntent.SelectTab(ShellTab.Partitions)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.files_no_partitions_cta))
                }
            }
            return
        }
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = blockedRelease!!.localized(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // A large font or short window must not squeeze file list out of existence.
        // The compact partition row scrolls inside its cap; folder block keeps rest.
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
                PartitionChoiceRow(
                    options = state.partitions.map { it.id to partitionOptionLabel(state, it) },
                    partitions = state.partitions,
                    selectedId = selected?.id ?: state.partitions.first().id,
                    writeAccess = writeAccess,
                    sizeLabel = selected?.let { partitionSizeLabel(state, it) },
                    space = space,
                    enabled = state.actionsEnabled,
                    onSelect = { id -> onIntent(UiIntent.SelectPartition(id)) },
                )
                // Matches the empty-partitions branch below: the same known blocked
                // release state renders the localized card here too, since partitions
                // can be retained while the volume refuses to remount.
                if (systemBlocked) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = blockedRelease!!.localized(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            FolderCard(
                state = state,
                canAct = canAct,
                canWrite = canWrite,
                selection = selection,
                selectedNames = selectedNames,
                fileListState = fileListState,
                onToggleSelect = { entry ->
                    selectedNames = toggleSelectedName(selectedNames, entry.name)
                },
                onAction = { actionEntry = it },
                onNavigate = { entry ->
                    onIntent(UiIntent.OpenDirectory(fatChildPath(state.currentPath, entry.name)))
                },
                onClearSelection = { selectedNames = emptySet() },
                onRefresh = { onIntent(UiIntent.RefreshFiles) },
                onNavigateUp = { onIntent(UiIntent.NavigateUp) },
                onNewFolder = { showNewFolderDialog = true },
                onSaveToPhone = {
                    val device = state.device
                    val partition = state.selectedPartition()
                    if (device == null || partition == null || selectedEntries.isEmpty()) return@FolderCard
                    if (!filesBatchDownloadEnabled(canAct, selectedEntries.map { it.toBatchItem() })) {
                        return@FolderCard
                    }
                    val batch = FilesBatchSelection(
                        directory = state.currentPath,
                        items = selectedEntries.map { it.toBatchItem() },
                        identity = device.identity,
                        attachGeneration = device.attachGeneration,
                        partitionId = partition.id,
                        startLba = partition.startLba,
                        sectorCount = partition.sectorCount,
                    )
                    if (batch.requiresTreeDestination()) {
                        onRequestBatchDownload(batch)
                    } else {
                        val entry = selectedEntries.single()
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
                },
                onRename = {
                    val device = state.device
                    val partition = state.selectedPartition()
                    val target = singleSelection
                    if (device != null &&
                        partition != null &&
                        target != null &&
                        filesBatchRenameEnabled(canWrite, selectedEntries.map { it.toBatchItem() })
                    ) {
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
                onDelete = {
                    val items = selectedEntries.map { it.toBatchItem() }
                    if (!filesBatchDeleteEnabled(canWrite, items)) return@FolderCard
                    val pinned = filesBatchSelectionOrNull(state, selectedEntries)
                    if (pinned == null) {
                        onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SelectedFileMissing)))
                        return@FolderCard
                    }
                    pendingDeleteSelection = pinned
                },
                isLoading = state.filesListingState == FilesListingState.Loading,
                modifier = Modifier.weight(1f),
            )
        }
        ExtendedFloatingActionButton(
            expanded = !isScrollingDown,
            onClick = { if (canWrite) onRequestBatchUpload() },
            icon = { GlyphIcon(Glyph.Plus, size = 24.dp) },
            text = { Text(uploadDescription) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .alpha(if (canWrite) 1f else 0.38f)
                .semantics {
                    contentDescription = uploadDescription
                    if (!canWrite) disabled()
                },
        )
    }
    actionEntry?.let { entry ->
        FileActionSheet(
            entry = entry,
            canWrite = canWrite,
            canDownload = filesBatchDownloadEnabled(canAct, listOf(entry.toBatchItem())),
            onDismiss = { actionEntry = null },
            onDownload = {
                actionEntry = null
                val device = state.device
                val partition = state.selectedPartition()
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
            },
            onRename = {
                actionEntry = null
                renameTarget = entry
                renameName = entry.name
                val device = state.device
                val partition = state.selectedPartition()
                renameBinding = if (device != null && partition != null) {
                    PartitionBinding(device, partition.id, partition.startLba, partition.sectorCount)
                } else null
            },
            onDelete = {
                actionEntry = null
                filesBatchSelectionOrNull(state, listOf(entry))?.let { pendingDeleteSelection = it }
            },
        )
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

    pendingDeleteSelection?.let { pinned ->
        val hasDirectory = pinned.items.any { it.isDirectory }
        val count = pinned.items.size
        val singleName = pinned.items.singleOrNull()?.name.orEmpty()
        val confirmText = when {
            hasDirectory && count == 1 ->
                stringResource(R.string.files_delete_folder_confirm, singleName)
            hasDirectory ->
                stringResource(R.string.files_delete_recursive_confirm, count)
            count == 1 ->
                stringResource(R.string.files_delete_confirm, singleName)
            else ->
                stringResource(R.string.files_delete_confirm, "$count items")
        }
        AlertDialog(
            onDismissRequest = { pendingDeleteSelection = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(confirmText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pinned.matches(state)) {
                            onIntent(UiIntent.DeleteBatch(pinned))
                        } else {
                            onIntent(
                                UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                            )
                        }
                        selectedNames = emptySet()
                        pendingDeleteSelection = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteSelection = null },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }


    state.pendingFilesBatchConflict?.let { conflict ->
        val replaceEnabled = !conflict.isDirectory
        AlertDialog(
            onDismissRequest = {
                onIntent(UiIntent.ResolveFilesBatchConflict(FilesConflictDecision.Cancel))
            },
            title = { Text(stringResource(R.string.import_overwrite_title)) },
            text = {
                Text(
                    buildString {
                        append(stringResource(R.string.import_overwrite_body))
                        append('\n')
                        // pathLabel is already the full volume path including the leaf name.
                        append(filesBatchConflictPathDisplay(conflict))
                    },
                )
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            onIntent(
                                UiIntent.ResolveFilesBatchConflict(FilesConflictDecision.Skip),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.action_skip))
                    }
                    if (replaceEnabled) {
                        TextButton(
                            onClick = {
                                onIntent(
                                    UiIntent.ResolveFilesBatchConflict(
                                        FilesConflictDecision.ReplaceFile,
                                    ),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.import_overwrite_action))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onIntent(
                            UiIntent.ResolveFilesBatchConflict(FilesConflictDecision.Cancel),
                        )
                    },
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
    val listingUsable = state.filesListingState == FilesListingState.Loaded
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
            PartitionChoiceRow(
                options = state.partitions.map { it.id to partitionOptionLabel(state, it) },
                partitions = state.partitions,
                selectedId = mode.partitionId,
                writeAccess = partition?.let(state::partitionWriteAccess) ?: PartitionWriteAccess.Unknown,
                sizeLabel = partition?.let { partitionSizeLabel(state, it) },
                space = partition?.let(state::filesystemSpace),
                enabled = canAct,
                onSelect = { onIntent(UiIntent.SelectFilesPickPartition(it)) },
            )
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
                            enabled = canAct && listingUsable,
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
                                    mode.partitionId == "1" &&
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
                        if (copying.state == OperationState.Running) {
                            remainingSeconds(copying.bytesDone, copying.bytesTotal, copying.bytesPerSecond)?.let { eta ->
                                Text(
                                    text = stringResource(R.string.op_eta, formatEtaDuration(eta)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
            state.filesListingState == FilesListingState.Failed -> item(key = "pick-error") {
                EmptyHint(state.filesError ?: stringResource(R.string.import_folder_read_failed))
            }
            state.filesListingState == FilesListingState.Loading -> item(key = "pick-loading") {
                EmptyHint(stringResource(R.string.files_listing))
            }
            state.filesListingState == FilesListingState.Stale -> item(key = "pick-not-loaded") {
                EmptyHint(stringResource(R.string.import_folder_not_loaded))
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
                        multiSelectActive = false,
                        actionsEnabled = canAct,
                        onToggleSelect = {
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
                        showActions = false,
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
    selection: ListingSelection,
    selectedNames: Set<String>,
    fileListState: LazyListState,
    onToggleSelect: (FileEntry) -> Unit,
    onNavigate: (FileEntry) -> Unit,
    onClearSelection: () -> Unit,
    onRefresh: () -> Unit,
    onNavigateUp: () -> Unit,
    onNewFolder: () -> Unit,
    onSaveToPhone: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAction: (FileEntry) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val loadingDescription = stringResource(R.string.files_listing)
    Column(modifier = modifier.fillMaxWidth()) {
        if (selection.entries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderActionButton(
                    glyph = Glyph.Download,
                    description = stringResource(R.string.files_save_to_phone),
                    enabled = filesBatchDownloadEnabled(canAct, selection.entries.map { it.toBatchItem() }),
                    onClick = onSaveToPhone,
                )
                FolderActionButton(
                    glyph = Glyph.Pencil,
                    description = stringResource(R.string.files_rename),
                    enabled = filesBatchRenameEnabled(canWrite, selection.entries.map { it.toBatchItem() }),
                    onClick = onRename,
                )
                FolderActionButton(
                    glyph = Glyph.Trash,
                    description = stringResource(R.string.action_delete),
                    enabled = filesBatchDeleteEnabled(canWrite, selection.entries.map { it.toBatchItem() }),
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error,
                )
                FolderActionButton(
                    glyph = Glyph.Close,
                    description = stringResource(R.string.files_clear_selection),
                    enabled = canAct,
                    onClick = onClearSelection,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
        // One lazy area for the whole volume: the path/action sub-block is its first item, so
        // a short window or a large font scrolls it away instead of squeezing the list to zero.
        LazyColumn(
            state = fileListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "folder-subblock") {
                FolderSubblock(
                    pathLabel = folderPathLabel(state.currentPath),
                    canNavigateUp = canAct && state.currentPath != "/",
                    canAct = canAct,
                    canWrite = canWrite,
                    onNavigateUp = onNavigateUp,
                    onRefresh = onRefresh,
                    onNewFolder = onNewFolder,
                    isLoading = isLoading,
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
                state.filesListingState == FilesListingState.Loading -> item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                            .semantics { contentDescription = loadingDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
                state.filesListingState == FilesListingState.Failed -> item(key = "error") {
                    EmptyHint(
                        state.filesError ?: stringResource(R.string.import_folder_read_failed),
                        modifier = Modifier.padding(16.dp),
                    )
                }
                state.filesListingState == FilesListingState.Stale -> item(key = "not-loaded") {
                    EmptyHint(
                        stringResource(R.string.import_folder_not_loaded),
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
                        Text(
                            text = stringResource(R.string.files_empty_folder),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                else -> items(state.files, key = { it.name }) { entry ->
                    FileRow(
                        modifier = Modifier.animateItem(),
                        entry = entry,
                        isSelected = entry.name in selectedNames,
                        multiSelectActive = selectedNames.isNotEmpty(),
                        actionsEnabled = canAct,
                        onToggleSelect = { onToggleSelect(entry) },
                        onNavigate = { onNavigate(entry) },
                        onAction = { onAction(entry) },
                    )
                }
            }
        }
    }
}
}

/** Path row with Refresh/CreateFolder only. Selection Download/Rename/Delete/Clear live in the
 * selection row above and the per-row bottom sheet, never in this toolbar. */
@Composable
private fun FolderSubblock(
    pathLabel: String,
    canNavigateUp: Boolean,
    canAct: Boolean,
    canWrite: Boolean,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val upDescription = stringResource(R.string.files_up)
    val refreshDescription = stringResource(R.string.unattend_refresh)
    val newFolderDescription = stringResource(R.string.files_create_folder)
    val pathA11y = stringResource(R.string.files_current_path, pathLabel)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onNavigateUp,
                    enabled = canNavigateUp,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = upDescription }
                ) {
                    GlyphIcon(glyph = Glyph.ArrowUp, size = 22.dp)
                }
                Text(
                    text = pathLabel,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .padding(start = 4.dp)
                        .clearAndSetSemantics { contentDescription = pathA11y }
                        .clickable(enabled = canNavigateUp, onClick = onNavigateUp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    softWrap = false,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLoading) {
                    IconButton(
                        onClick = onRefresh,
                        enabled = canAct,
                        modifier = Modifier.semantics { contentDescription = refreshDescription },
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    FolderActionButton(
                        glyph = Glyph.Refresh,
                        description = refreshDescription,
                        enabled = canAct,
                        onClick = onRefresh,
                    )
                }
                FolderActionButton(
                    glyph = Glyph.FolderPlus,
                    description = newFolderDescription,
                    enabled = canWrite,
                    onClick = onNewFolder,
                )
            }
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
    partitions: List<PartitionTab>,
    selectedId: String,
    writeAccess: PartitionWriteAccess,
    sizeLabel: String?,
    space: FilesystemSpace?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = partitions.firstOrNull { it.id == selectedId }
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = selected?.let(::partitionDisplayName) ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = selected?.filesystemLabel?.ifEmpty { "—" } ?: "—",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = listOfNotNull(sizeLabel, freeSpaceText(space)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
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
 * Factual write badge matching Partitions semantics: neutral container shared by every
 * known state, error tone only for guaranteed EFI policy. Driver-mounted states use
 * measured mount facts; unknown renders no badge at all — never a red RO.
 */
@Composable
private fun WriteAccessBadge(access: PartitionWriteAccess) {
    val label = when (access) {
        PartitionWriteAccess.ReadOnly -> stringResource(R.string.files_write_policy_label)
        PartitionWriteAccess.DriverReadOnly -> stringResource(R.string.files_write_driver_ro_label)
        PartitionWriteAccess.ReadWrite -> stringResource(R.string.files_write_driver_rw_label)
        PartitionWriteAccess.Unknown -> return
    }
    val description = when (access) {
        PartitionWriteAccess.ReadOnly -> stringResource(R.string.files_write_policy_ro)
        PartitionWriteAccess.DriverReadOnly -> stringResource(R.string.files_write_driver_ro)
        PartitionWriteAccess.ReadWrite -> stringResource(R.string.files_write_driver_rw)
        PartitionWriteAccess.Unknown -> return
    }
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    isSelected: Boolean,
    multiSelectActive: Boolean,
    actionsEnabled: Boolean,
    onToggleSelect: () -> Unit,
    onNavigate: () -> Unit,
    onAction: () -> Unit = {},
    showActions: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val moreActionsDescription = stringResource(R.string.files_more_actions)
    val clickLabel = when {
        multiSelectActive || !entry.isDirectory -> stringResource(R.string.files_select_file)
        else -> stringResource(R.string.files_open_folder)
    }
    val longClickLabel = stringResource(R.string.files_select_file)
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
            .combinedClickable(
                enabled = actionsEnabled,
                onClickLabel = clickLabel,
                onLongClickLabel = longClickLabel,
                role = Role.Button,
                onClick = {
                    when {
                        multiSelectActive -> onToggleSelect()
                        entry.isDirectory -> onNavigate()
                        else -> onToggleSelect()
                    }
                },
                onLongClick = onToggleSelect,
            ),
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
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            if (showActions) {
                IconButton(
                    onClick = onAction,
                    enabled = actionsEnabled,
                    modifier = Modifier.semantics {
                        contentDescription = moreActionsDescription
                    },
                ) {
                    GlyphIcon(Glyph.More, size = 24.dp)
                }
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

internal fun partitionDisplayName(partition: PartitionTab): String =
    partition.name.ifBlank { partition.id } +
        partition.volumeLabel.orEmpty().trim().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()

private fun partitionOptionLabel(state: AppUiState, partition: PartitionTab): String =
    buildString {
        append(partitionDisplayName(partition))
        partition.filesystemLabel.takeIf { it.isNotEmpty() }?.let {
            append(" · ")
            append(it)
        }
        partitionSizeLabel(state, partition)?.let {
            append(" · ")
            append(it)
        }
    }

/** Live listing entries still present for the remembered selected names. */
internal data class ListingSelection(
    val entries: List<FileEntry>,
) {
    val single: FileEntry? = entries.singleOrNull()
}

internal fun resolveListingSelection(
    files: List<FileEntry>,
    selectedNames: Set<String>,
): ListingSelection {
    if (selectedNames.isEmpty()) return ListingSelection(emptyList())
    return ListingSelection(files.filter { it.name in selectedNames })
}

internal fun toggleSelectedName(selectedNames: Set<String>, name: String): Set<String> =
    if (name in selectedNames) selectedNames - name else selectedNames + name

internal fun FileEntry.toBatchItem(): FilesBatchItem =
    FilesBatchItem(name = name, isDirectory = isDirectory, sizeBytes = sizeBytes)

/** Snapshot selected listing entries against the live attachment/partition geometry. */
internal fun filesBatchSelectionOrNull(
    state: AppUiState,
    entries: List<FileEntry>,
): FilesBatchSelection? {
    val device = state.device ?: return null
    val partition = state.selectedPartition() ?: return null
    if (entries.isEmpty()) return null
    return FilesBatchSelection(
        directory = state.currentPath,
        items = entries.map { it.toBatchItem() },
        identity = device.identity,
        attachGeneration = device.attachGeneration,
        partitionId = partition.id,
        startLba = partition.startLba,
        sectorCount = partition.sectorCount,
    )
}

/** Prefer full [FilesConflict.pathLabel]; avoid appending name again when already included. */
internal fun filesBatchConflictPathDisplay(conflict: FilesConflict): String {
    val path = conflict.pathLabel
    if (path.isNotEmpty()) return path
    return conflict.name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionSheet(
    entry: FileEntry,
    canWrite: Boolean,
    canDownload: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ListItem(
            headlineContent = { Text(entry.name) },
            supportingContent = {
                Text(
                    DeviceIdentity.formatCapacity(entry.sizeBytes),
                    fontFamily = FontFamily.Monospace,
                )
            },
        )
        if (!entry.isDirectory) {
            sheetActionRow(
                label = stringResource(R.string.files_save_to_phone),
                glyph = Glyph.Download,
                enabled = canDownload,
                onClick = onDownload,
            )
        }
        sheetActionRow(
            label = stringResource(R.string.files_rename),
            glyph = Glyph.Pencil,
            enabled = canWrite,
            onClick = onRename,
        )
        sheetActionRow(
            label = stringResource(R.string.action_delete),
            glyph = Glyph.Trash,
            enabled = canWrite,
            onClick = onDelete,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_cancel)) },
            leadingContent = { GlyphIcon(Glyph.Close, size = 22.dp) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onDismiss),
        )
    }
}

@Composable
private fun sheetActionRow(
    label: String,
    glyph: Glyph,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { GlyphIcon(glyph, size = 22.dp) },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics { if (!enabled) disabled() },
    )
}
