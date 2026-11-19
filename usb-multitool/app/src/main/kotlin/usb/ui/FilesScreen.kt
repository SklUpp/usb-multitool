package usb.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.formatBinaryBytesPerSecond
import usb.formatEtaDuration
import usb.fs.FilesystemSpace
import usb.multitool.R
import usb.remainingSeconds

/** Fade-through only: no horizontal translation, so the list can never overflow its box. */
internal fun AnimatedContentTransitionScope<DirectoryContentTarget>.directoryContentTransform(
    reducedMotion: Boolean,
): ContentTransform {
    if (reducedMotion) return EnterTransition.None togetherWith ExitTransition.None
    return fadeIn(tween(180))
        .togetherWith(fadeOut(tween(120)))
        .using(SizeTransform(clip = true))
}

/** Stable AnimatedContent target: path drives motion, pins make stale content distinct. */
internal data class DirectoryContentTarget(
    val partitionId: String,
    val path: String,
    val identity: String,
    val attachGeneration: String,
    val listGeneration: String,
    val listingState: String,
    val error: String,
    val files: List<FileEntry> = emptyList(),
    val visibleFiles: List<FileEntry> = emptyList(),
)

internal fun directoryContentKey(
    state: AppUiState,
    visibleFiles: List<FileEntry> = state.files,
): DirectoryContentTarget =
    DirectoryContentTarget(
        partitionId = state.selectedPartitionId.orEmpty(),
        path = normalizeFatPath(state.currentPath),
        identity = state.device?.identity.orEmpty(),
        attachGeneration = state.device?.attachGeneration?.toString().orEmpty(),
        listGeneration = state.filesListGeneration.toString(),
        listingState = state.filesListingState.name,
        error = state.filesError.orEmpty(),
        files = state.files,
        visibleFiles = visibleFiles,
    )

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
    val baseContext = LocalContext.current
    val reducedMotion = runCatching {
        android.provider.Settings.Global.getFloat(
            baseContext.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)


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
                modifier = Modifier.fillMaxWidth().animateContentSize(),
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
                        modifier = Modifier.fillMaxWidth().animateContentSize(),
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
                reducedMotion = reducedMotion,
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
            // M3 disabled tokens; ExtendedFAB has no `enabled`, so the colors carry it.
            containerColor =
                if (canWrite) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
            contentColor =
                if (canWrite) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
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
                pluralStringResource(R.plurals.files_delete_files_confirm, count, count)
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

@Composable
private fun filesPickTitle(mode: FilesPickMode): String =
    stringResource(
        when (mode.purpose) {
            FilesPickPurpose.WindowsImage -> R.string.files_pick_title_image
            FilesPickPurpose.RawSource -> R.string.files_pick_title_raw
            FilesPickPurpose.VentoyConfig -> R.string.files_pick_title_ventoy
            FilesPickPurpose.WindowsTemplate -> R.string.files_pick_title_template
        },
    )

/**
 * Fixed picker header: names the task and keeps the exit reachable above the scrolling
 * folder card. Cancellation still goes through the reducer, so a running operation and an
 * open confirmation keep their guards: the header only submits the same intent as Back.
 */
@Composable
private fun FilesPickHeader(mode: FilesPickMode, onCancel: () -> Unit) {
    val title = filesPickTitle(mode)
    val closeDescription = stringResource(R.string.files_pick_close)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = closeDescription },
            ) {
                GlyphIcon(Glyph.Close, size = 22.dp)
            }
        }
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
            R.string.files_pick_folder_hint
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
    val pickContext = LocalContext.current
    val pickReducedMotion = runCatching {
        android.provider.Settings.Global.getFloat(
            pickContext.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
    LaunchedEffect(entries, selectedPickState?.value, listingUsable) {
        val pickerState = selectedPickState
        if (pickerState != null &&
            (pickerState.value != null && selectedPickEntry == null || !listingUsable)
        ) {
            pickerState.value = null
        }
    }

    // Same fade-through as the normal browser: no horizontal translation; reduced motion
    // is flat.
    Column(modifier = modifier.fillMaxSize()) {
    FilesPickHeader(mode = mode, onCancel = { onIntent(UiIntent.CancelFilesPick) })
        AnimatedContent(
            targetState = directoryContentKey(
                state,
                if (mode.target == FilesPickTarget.Folder) state.files.filter { it.isDirectory } else state.files,
            ),
            transitionSpec = {
                directoryContentTransform(pickReducedMotion)
            },
            label = "pick-folder",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { target ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            userScrollEnabled = !transition.isRunning,
        ) {
            item(key = "pick-partition") {
                PartitionChoiceRow(
                    options = state.partitions.map { it.id to partitionOptionLabel(state, it) },
                    partitions = state.partitions,
                    selectedId = mode.partitionId,
                    writeAccess = partition?.let(state::partitionWriteAccess) ?: PartitionWriteAccess.Unknown,
                    space = partition?.let(state::filesystemSpace),
                    enabled = canAct && !transition.isRunning,
                    onSelect = { onIntent(UiIntent.SelectFilesPickPartition(it)) },
                )
            }

            item(key = "pick-folder") {
                SectionCard(
                    title = stringResource(R.string.files_section_folder),
                    titleColor = MaterialTheme.colorScheme.onSurface,
                    trailing = {
                        IconButton(
                            onClick = { onIntent(UiIntent.NavigateUp) },
                            enabled = canAct && target.path != "/" && !transition.isRunning,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = upDescription },
                        ) {
                            GlyphIcon(Glyph.ArrowUp, size = 22.dp)
                        }
                    },
                ) {
                    // One monospace line with a trailing slash, start-ellipsized like the browser
                    // path: a deep FAT path must not wrap and push the rows down.
                    Text(
                        text = folderPathLabel(target.path),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        softWrap = false,
                    )
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
                                enabled = canAct && listingUsable && !transition.isRunning,
                            ) {
                                Text(stringResource(R.string.import_folder_use))
                            }
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
                                    canAct && !transition.isRunning &&
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
                            val copyPercent =
                                if (copying.bytesTotal > 0L) "${(copying.fraction * 100).toInt()}%" else null
                            val copySpeed =
                                copying
                                    .takeIf {
                                        it.state == OperationState.Running && it.bytesPerSecond > 0L
                                    }
                                    ?.let { formatBinaryBytesPerSecond(it.bytesPerSecond) }
                            val copyEta =
                                if (copying.state == OperationState.Running) {
                                    remainingSeconds(
                                        copying.bytesDone,
                                        copying.bytesTotal,
                                        copying.bytesPerSecond,
                                    )?.let { stringResource(R.string.op_eta, formatEtaDuration(it)) }
                                } else {
                                    null
                                }
                            val copyProgress = operationProgressFacts(copyPercent, copySpeed, copyEta)
                            if (copyProgress.isNotEmpty()) {
                                Text(
                                    text = copyProgress,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                            TextButton(
                                onClick = { onIntent(UiIntent.CancelOperation(copying.token)) },
                                enabled = !transition.isRunning,
                            ) {
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
                        TextButton(
                            onClick = { onIntent(UiIntent.CancelWindowsIsoImport) },
                            enabled = !transition.isRunning,
                        ) {
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

            // Snapshot render: leaving content keeps its own listing state and rows, so the
            // exit fade never swaps in the newly loaded directory. Header above stays on
            // live state so the path toolbar always names the current directory.
            val pickRows = target.visibleFiles
            val pickListingState = target.listingState
            val pickError = target.error.ifEmpty { null }
            when {
                pickListingState == FilesListingState.Failed.name -> item(key = "pick-error") {
                    EmptyHint(pickError ?: stringResource(R.string.import_folder_read_failed))
                }
                pickListingState == FilesListingState.Loading.name -> item(key = "pick-loading") {
                    EmptyHint(stringResource(R.string.files_listing))
                }
                pickListingState == FilesListingState.Stale.name -> item(key = "pick-not-loaded") {
                    EmptyHint(stringResource(R.string.import_folder_not_loaded))
                }
                pickRows.isEmpty() -> item(key = "pick-empty") {
                    EmptyHint(stringResource(R.string.files_empty_folder))
                }
                    else -> items(pickRows, key = { it.name }) { entry ->
                    // Snapshot row: leaving content never sees live-list swaps mid-transition.
                    val picked = selectedPickState?.value?.takeIf { it.name == entry.name }
                        ?.takeIf { live -> live in pickRows }
                    Column(modifier = Modifier.animateItem()) {
                        // Keep the confirmation next to the chosen row, so it cannot be
                        // scrolled off-screen together with the header.
                        if (picked != null && selectedPickEntry != null) {
                            OutlinedButton(
                                onClick = {
                                    // The reducer revalidates the name against live files.
                                    onIntent(UiIntent.ConfirmFilesPickFile(entry.name))
                                },
                                enabled = canAct && listingUsable && !transition.isRunning,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                            ) {
                                Text(stringResource(R.string.files_pick_use_file))
                            }
                        }
                        FilesPickRow(
                            entry = entry,
                            isSelected = picked != null && selectedPickEntry != null,
                            actionsEnabled = canAct && !transition.isRunning,
                            onToggleSelect = {
                                val live = entries.firstOrNull { it.name == entry.name } ?: return@FilesPickRow
                                selectedPickState?.value =
                                    if (selectedPickState?.value == live) null else live
                            },
                            onNavigate = {
                                selectedPickState?.value = null
                                onIntent(
                                    UiIntent.OpenDirectory(
                                        fatChildPath(target.path, entry.name),
                                    ),
                                )
                            },
                        )
                    }
                }
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
    reducedMotion: Boolean,
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
    // Whole card (selection bar + list) fades as one snapshot: both read the same
    // target so leaving content never mixes live state under the exit fade.
    AnimatedContent(
        targetState = directoryContentKey(state),
        transitionSpec = {
            directoryContentTransform(reducedMotion)
        },
        label = "folder",
        modifier = modifier.fillMaxWidth(),
    ) { target ->
        // Snapshot render: leaving content keeps the exact files it captured, so
        // navigation never swaps rows under the exit fade.
        val rows = target.files
        val rowsState = target.listingState
        val rowsError = target.error.ifEmpty { null }
        val locked = transition.isRunning
        Column(modifier = Modifier.fillMaxWidth()) {
            if (selection.entries.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FolderActionButton(
                        glyph = Glyph.Download,
                        description = stringResource(R.string.files_save_to_phone),
                        enabled = filesBatchDownloadEnabled(canAct, selection.entries.map { it.toBatchItem() }) && !locked,
                        onClick = onSaveToPhone,
                    )
                    FolderActionButton(
                        glyph = Glyph.Pencil,
                        description = stringResource(R.string.files_rename),
                        enabled = filesBatchRenameEnabled(canWrite, selection.entries.map { it.toBatchItem() }) && !locked,
                        onClick = onRename,
                    )
                    FolderActionButton(
                        glyph = Glyph.Trash,
                        description = stringResource(R.string.action_delete),
                        enabled = filesBatchDeleteEnabled(canWrite, selection.entries.map { it.toBatchItem() }) && !locked,
                        onClick = onDelete,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    FolderActionButton(
                        glyph = Glyph.Close,
                        description = stringResource(R.string.files_clear_selection),
                        enabled = canAct && !locked,
                        onClick = onClearSelection,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            ) {
                // One lazy area for the whole volume: the path/action sub-block is its
                // first item, so a short window or a large font scrolls it away instead
                // of squeezing the list to zero.
                LazyColumn(
                    state = fileListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    userScrollEnabled = !locked,
                ) {
                    item(key = "folder-subblock") {
                        FolderSubblock(
                            pathLabel = folderPathLabel(target.path),
                            canNavigateUp = canAct && target.path != "/" && !locked,
                            canAct = canAct && !locked,
                            canWrite = canWrite && !locked,
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
                        rowsState == FilesListingState.Loading.name -> item(key = "loading") {
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
                        rowsState == FilesListingState.Failed.name -> item(key = "error") {
                            EmptyHint(
                                rowsError ?: stringResource(R.string.import_folder_read_failed),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        rowsState == FilesListingState.Stale.name -> item(key = "not-loaded") {
                            EmptyHint(
                                stringResource(R.string.import_folder_not_loaded),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        rows.isEmpty() -> item(key = "empty") {
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
                        else -> items(rows, key = { it.name }) { entry ->
                            FileRow(
                                entry = entry,
                                isSelected = entry.name in selectedNames,
                                multiSelectActive = selectedNames.isNotEmpty(),
                                actionsEnabled = canAct && !locked,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onNavigateUp,
                enabled = canNavigateUp,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = upDescription },
            ) {
                GlyphIcon(glyph = Glyph.ArrowUp, size = 22.dp)
            }
            Text(
                text = pathLabel,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(
                        enabled = canNavigateUp,
                        onClickLabel = upDescription,
                        role = Role.Button,
                        onClick = onNavigateUp,
                    )
                    .padding(start = 4.dp)
                    .semantics { contentDescription = pathA11y },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                softWrap = false,
            )
            if (isLoading) {
                IconButton(
                    onClick = onRefresh,
                    enabled = canAct,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = refreshDescription },
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
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        // tint is captured outside IconButton's disabled color provider, so dim it here.
        GlyphIcon(
            glyph = glyph,
            size = 22.dp,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
        )
    }
}

/** Rectangular partition choice: label, real name/format/free space and the RO/RW chip. */
@Composable
private fun PartitionChoiceRow(
    options: List<Pair<String, String>>,
    partitions: List<PartitionTab>,
    selectedId: String,
    writeAccess: PartitionWriteAccess,
    space: FilesystemSpace?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = partitions.firstOrNull { it.id == selectedId }
    val label = stringResource(R.string.files_current_partition)
    // Exactly one partition leaves nothing to choose: no chevron, no menu, no tap.
    val selectable = partitions.size > 1
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
                        enabled = enabled && selectable,
                        onClickLabel = label,
                        role = Role.Button,
                    ) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = freeSpaceText(space),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WriteAccessBadge(writeAccess)
                if (selectable) {
                    GlyphIcon(
                        glyph = Glyph.ChevronDown,
                        size = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, text) ->
                val isSelected = value == selectedId
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    modifier =
                        if (isSelected) {
                            Modifier.semantics { this.selected = true }
                        } else {
                            Modifier
                        },
                    trailingIcon = if (isSelected) {
                        {
                            GlyphIcon(Glyph.Check, size = 18.dp)
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
 * Compact write chip: only `RO` or `RW`, both on one neutral container so the tones stay
 * distinct in every palette (the container roles collapse to the same colour here). Text
 * colour carries the meaning: RW primary, RO onSurfaceVariant. Red stays reserved for
 * errors and the danger zone; unknown renders no chip. The long factual description
 * remains the a11y label.
 */
@Composable
private fun WriteAccessBadge(access: PartitionWriteAccess) {
    val label = when (access) {
        PartitionWriteAccess.ReadOnly, PartitionWriteAccess.DriverReadOnly ->
            stringResource(R.string.files_write_short_ro)
        PartitionWriteAccess.ReadWrite -> stringResource(R.string.files_write_short_rw)
        PartitionWriteAccess.Unknown -> return
    }
    val description = when (access) {
        PartitionWriteAccess.ReadOnly -> stringResource(R.string.files_write_policy_ro)
        PartitionWriteAccess.DriverReadOnly -> stringResource(R.string.files_write_driver_ro)
        PartitionWriteAccess.ReadWrite -> stringResource(R.string.files_write_driver_rw)
        PartitionWriteAccess.Unknown -> return
    }
    val content =
        if (access == PartitionWriteAccess.ReadWrite) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
        modifier = Modifier
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
            // The suffix is its own node so a long file name ellipsizes the base and keeps
            // the extension start visible. Without a cap the suffix is measured with the
            // whole row width: an unusually long "extension" then clips mid-glyph and
            // collapses the base to zero.
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 96.dp)
                            .padding(start = 2.dp),
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

/**
 * Picker row only: full name (extension included) wraps in the headline without truncation,
 * size stays in the supporting line with tabular monospace figures. Folders show the type
 * label instead of a size. Selection visuals, click/long-press behavior and [fatChildPath]
 * navigation match [FileRow]; the normal browser keeps [FileRow] untouched.
 */
@Composable
private fun FilesPickRow(
    entry: FileEntry,
    isSelected: Boolean,
    actionsEnabled: Boolean,
    onToggleSelect: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clickLabel = if (entry.isDirectory) {
        stringResource(R.string.files_open_folder)
    } else {
        stringResource(R.string.files_select_file)
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
                onClick = { if (entry.isDirectory) onNavigate() else onToggleSelect() },
                onLongClick = onToggleSelect,
            ),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    softWrap = true,
                )
            },
            supportingContent = {
                if (entry.isDirectory) {
                    Text(
                        text = stringResource(R.string.type_folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = DeviceIdentity.formatCapacity(entry.sizeBytes),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingContent = {
                Icon(
                    painter = painterResource(
                        if (entry.isDirectory) R.drawable.ic_file_folder else R.drawable.ic_file,
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
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
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
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
