package usb.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.formatBinaryBytesPerSecond
import usb.formatEtaDuration
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsagePhase
import usb.fs.FilesystemUsageReason
import usb.fs.FilesystemUsageSnapshot
import usb.multitool.R
import usb.remainingSeconds

/** Fade-through only: no horizontal translation, so the list can never overflow its box. */
internal fun AnimatedContentTransitionScope<DirectoryContentTarget>.directoryContentTransform(
    reducedMotion: Boolean,
): ContentTransform {
    return EnterTransition.None togetherWith ExitTransition.None
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
    var showNewFolderDialog by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf(false) }
    var newFolderName by remember(state.currentPath) { mutableStateOf("") }
    var newFolderPath by remember(state.currentPath) { mutableStateOf<String?>(null) }
    var newFolderBinding by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf<PartitionBinding?>(null) }
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
    var renameName by remember(renameTarget, renameBinding) { mutableStateOf("") }
    var actionEntry by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf<FileEntry?>(null) }


    val selected = state.selectedFilesVolume()
    val canAct = state.actionsEnabled && selected != null
    // partitionWriteAccess already folds EFI policy before driver mount facts.
    val writeAccess = selected?.let(state::partitionWriteAccess) ?: PartitionWriteAccess.Unknown
    val volumeWritable = writeAccess != PartitionWriteAccess.ReadOnly &&
        writeAccess != PartitionWriteAccess.DriverReadOnly
    val canWrite = canAct && volumeWritable
    val selection = resolveListingSelection(state.files, selectedNames)
    val selectedEntries = selection.entries
    val singleSelection = selection.single
    val uploadDescription = stringResource(R.string.files_copy_from_phone)

    // Blocked release marks an unconfirmed volume close/sync after a failed probe
    // release: partitions are cleared and presence stays Busy with no live operation.
    // The close error alone does not prove Android claimed the device, and an
    // Android-side unmount/eject does not reset this latch.
    val blockedRelease = (state.statusMessage as? UiMessage.Key)
        ?.takeIf { it.key == UiMessageKey.VolumeSyncUnconfirmed }
    val systemBlocked = state.presence == DevicePresence.Busy &&
        (state.operation == null || state.operation.state.isTerminal()) && blockedRelease != null
    if (state.filesVolumes.isEmpty()) {
        if (!systemBlocked) {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EmptyHint(stringResource(R.string.files_no_partitions_hint))
                Button(
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
                shape = MaterialTheme.shapes.small,
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
                PartitionDropdown(
                    partitions = state.filesVolumes,
                    selectedId = selected?.id,
                    writeAccessOf = state::partitionWriteAccess,
                    usageOf = state::partitionUsageSnapshot,
                    phaseOf = state::partitionUsagePhase,
                    sectorSize = state.diskSectorSize,
                    enabled = state.actionsEnabled,
                    onSelect = { id -> onIntent(UiIntent.SelectPartition(id)) },
                )
                CancelOperationButton(state = state, onIntent = onIntent)
                // Matches the empty-partitions branch below: the same known blocked
                // release state renders the localized card here too, since partitions
                // can be retained while the volume refuses to remount.
                if (systemBlocked) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
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
                onToggleSelect = { entry ->
                    selectedNames = toggleSelectedName(selectedNames, entry.name)
                },
                onAction = { actionEntry = it },
                onNavigate = { entry ->
                    onIntent(UiIntent.OpenDirectory(fatChildPath(state.currentPath, entry.name)))
                },
                onClearSelection = { selectedNames = emptySet() },
                onRefresh = { onIntent(UiIntent.RefreshFiles) },
                onOpenPath = { path -> onIntent(UiIntent.OpenDirectory(path)) },
                onNewFolder = {
                    val device = state.device
                    val partition = state.selectedFilesVolume()
                    if (device != null && partition != null) {
                        newFolderPath = state.currentPath
                        newFolderBinding = PartitionBinding(
                            device = device,
                            partitionId = partition.id,
                            startLba = partition.startLba,
                            sectorCount = partition.sectorCount,
                        )
                        newFolderName = ""
                        showNewFolderDialog = true
                    }
                },
                onSaveToPhone = {
                    val device = state.device
                    val partition = state.selectedFilesVolume()
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
                    val partition = state.selectedFilesVolume()
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
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 12.dp),
            )
        }
        if (canWrite) {
            FloatingActionButton(
                onClick = onRequestBatchUpload,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .semantics { contentDescription = uploadDescription },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                )
            }
        }
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
                val partition = state.selectedFilesVolume()
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
                newFolderPath = null
                newFolderBinding = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            iconContentColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            title = { Text(stringResource(R.string.files_create_folder)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).imePadding(),
                ) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text(stringResource(R.string.files_folder_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newFolderName
                        val targetPath = newFolderPath
                        val binding = newFolderBinding
                        if (name.isNotEmpty() && targetPath != null && binding != null) {
                            onIntent(UiIntent.CreateFolder(name, targetPath, binding))
                        }
                        showNewFolderDialog = false
                        newFolderName = ""
                        newFolderPath = null
                        newFolderBinding = null
                    },
                    enabled = newFolderName.isNotBlank(),
                ) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewFolderDialog = false
                        newFolderName = ""
                        newFolderPath = null
                        newFolderBinding = null
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val liveRenameTarget = renameTarget?.let { target ->
        state.files.firstOrNull { it.name == target.name && it.isDirectory == target.isDirectory }
    }
    LaunchedEffect(renameTarget, liveRenameTarget) {
        if (renameTarget != null && liveRenameTarget == null) {
            renameTarget = null
            renameBinding = null
        }
    }
    liveRenameTarget?.let { target ->
        val binding = renameBinding
        AlertDialog(
            onDismissRequest = { renameTarget = null; renameBinding = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            iconContentColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            title = { Text(stringResource(R.string.files_rename_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).imePadding(),
                ) {
                    OutlinedTextField(
                        value = renameName,
                        onValueChange = { renameName = it },
                        label = { Text(stringResource(R.string.files_new_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (binding != null) onIntent(UiIntent.RenameEntry(target.name, renameName, binding))
                        renameTarget = null
                        renameBinding = null
                    },
                    enabled = binding != null && renameName.isNotEmpty(),
                ) { Text(stringResource(R.string.action_confirm)) }
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
            hasDirectory && count == 1 -> stringResource(R.string.files_delete_folder_confirm, singleName)
            hasDirectory -> stringResource(R.string.files_delete_recursive_confirm, count)
            count == 1 -> stringResource(R.string.files_delete_confirm, singleName)
            else -> pluralStringResource(R.plurals.files_delete_files_confirm, count, count)
        }
        AlertDialog(
            onDismissRequest = { pendingDeleteSelection = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            iconContentColor = MaterialTheme.colorScheme.error,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(confirmText, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    if (pinned.matches(state)) onIntent(UiIntent.DeleteBatch(pinned))
                    else onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
                    selectedNames = emptySet()
                    pendingDeleteSelection = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSelection = null }) {
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            iconContentColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 6.dp,
            title = { Text(stringResource(R.string.import_overwrite_title)) },
            text = {
                Text(
                    buildString {
                        append(stringResource(R.string.import_overwrite_body))
                        append('\n')
                        append(filesBatchConflictPathDisplay(conflict))
                    },
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        onIntent(UiIntent.ResolveFilesBatchConflict(FilesConflictDecision.Skip))
                    }) { Text(stringResource(R.string.action_skip)) }
                    if (replaceEnabled) {
                        TextButton(onClick = {
                            onIntent(UiIntent.ResolveFilesBatchConflict(FilesConflictDecision.ReplaceFile))
                        }) { Text(stringResource(R.string.import_overwrite_action)) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onIntent(UiIntent.ResolveFilesBatchConflict(FilesConflictDecision.Cancel))
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Fixed picker header: clickable path + close. Cancellation still goes through the
 * reducer, so a running operation and an open confirmation keep their guards: the
 * header only submits the same intent as Back. Height is locked so path/font cannot
 * bounce the chrome.
 */
@Composable
private fun FilesPickHeader(
    path: String,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val closeDescription = stringResource(R.string.files_pick_close)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .height(56.dp)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FolderBreadcrumbs(
                path = path,
                enabled = enabled,
                onOpen = onOpen,
                modifier = Modifier.weight(1f),
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
    val partition = state.filesVolumes.firstOrNull { it.id == mode.partitionId }
    val ventoyDataPartitionId = state.ventoyDataPartition()?.id
    val canAct = state.actionsEnabled && partition != null
    val copying = state.operation?.takeIf { it.title is OperationTitle.ImportTarget }
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
    val pathEnabled = canAct && !state.isRunning
    val showDeviceImage =
        mode.purpose == FilesPickPurpose.WindowsImage &&
            mode.target == FilesPickTarget.File &&
            mode.partitionId == ventoyDataPartitionId
    val deviceImageEnabled =
        canAct && !state.isRunning &&
            state.isoImportSafInFlight == null &&
            state.isoImportUsbInFlight == null &&
            state.pendingIsoImport == null
    val confirmEnabled = canAct && listingUsable && !state.isRunning
    val fileConfirmLabel =
        stringResource(
            if (mode.purpose == FilesPickPurpose.WindowsImage) {
                R.string.files_pick_confirm_windows_image
            } else {
                R.string.files_pick_confirm
            },
        )
    val deviceImageDescription = stringResource(R.string.files_pick_device)
    Column(modifier = modifier.fillMaxSize()) {
        FilesPickHeader(
            path = state.currentPath,
            enabled = pathEnabled,
            onOpen = { path -> onIntent(UiIntent.OpenDirectory(path)) },
            onCancel = { onIntent(UiIntent.CancelFilesPick) },
        )
        // The selector is part of the fixed chrome, not the scrolling list: scrolling a deep
        // folder must not hide the partition the pick belongs to.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            PartitionDropdown(
                partitions = state.filesVolumes,
                selectedId = mode.partitionId,
                writeAccessOf = state::partitionWriteAccess,
                usageOf = state::partitionUsageSnapshot,
                phaseOf = state::partitionUsagePhase,
                sectorSize = state.diskSectorSize,
                enabled = canAct && !state.isRunning,
                onSelect = { onIntent(UiIntent.SelectFilesPickPartition(it)) },
            )
        }
        AnimatedContent(
            targetState = directoryContentKey(
                state,
                if (mode.target == FilesPickTarget.Folder) state.files.filter { it.isDirectory } else state.files,
            ),
            transitionSpec = {
                directoryContentTransform(reducedMotion = true)
            },
            label = "pick-folder",
            // One slot per directory/partition/stick pin: refresh keeps scroll.
            contentKey = {
                listOf(it.partitionId, it.path, it.identity, it.attachGeneration)
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { target ->
        val listState = rememberLazyListState()
        LaunchedEffect(
            target.partitionId,
            target.path,
            target.identity,
            target.attachGeneration,
        ) {
            listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Rhythm only; system/nav-bar inset stays in AppShell (pickOpen must mirror journal).
            contentPadding = PaddingValues(bottom = 16.dp),
            userScrollEnabled = true
        ) {
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
                                "${operationLabel(copying)}: " +
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
                                text = normalizeProviderText(message),
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
                            enabled = copying.cancelable,
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
                            enabled = true
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
                    Text(
                        text = pickError?.let(::normalizeProviderText)
                            ?: stringResource(R.string.import_folder_read_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                pickListingState == FilesListingState.Loading.name -> item(key = "pick-loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.files_listing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                pickListingState == FilesListingState.Stale.name -> item(key = "pick-not-loaded") {
                    Text(
                        text = stringResource(R.string.import_folder_not_loaded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                pickRows.isEmpty() -> item(key = "pick-empty") {
                    val emptyText =
                        if (mode.target == FilesPickTarget.Folder &&
                            target.files.any { !it.isDirectory }
                        ) {
                            R.string.files_empty_subfolders
                        } else {
                            R.string.files_empty_folder
                        }
                    EmptyHint(stringResource(emptyText))
                }
                    else -> items(pickRows, key = { it.name }) { entry ->
                    // Snapshot row: leaving content never sees live-list swaps mid-transition.
                    val picked = selectedPickState?.value?.takeIf { it.name == entry.name }
                        ?.takeIf { live -> live in pickRows }
                    Column(modifier = Modifier.animateItem()) {
                        FilesPickRow(
                            entry = entry,
                            isSelected = picked != null && selectedPickEntry != null,
                            actionsEnabled = canAct && !state.deviceBusy,
                            allowLongPress = mode.target != FilesPickTarget.Folder,
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
        Surface(
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selectsFile) {
                        selectedPickEntry?.let { selected ->
                            Text(
                                text = folderPathLabel(state.currentPath) + selected.name,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (showDeviceImage) {
                    IconButton(
                        onClick = { onRequestDeviceImage(state.currentPath, mode.sessionId) },
                        enabled = deviceImageEnabled,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = deviceImageDescription },
                    ) {
                        GlyphIcon(Glyph.File, size = 22.dp)
                    }
                }
                if (selectsFile) {
                    TextButton(
                        onClick = {
                            val selected = selectedPickEntry ?: return@TextButton
                            // The reducer revalidates the name against live files.
                            onIntent(UiIntent.ConfirmFilesPickFile(selected.name))
                        },
                        enabled = confirmEnabled && selectedPickEntry != null,
                    ) {
                        Text(fileConfirmLabel)
                    }
                } else {
                    TextButton(
                        onClick = { onIntent(UiIntent.ConfirmFilesPickFolder) },
                        enabled = confirmEnabled,
                    ) {
                        Text(stringResource(R.string.import_folder_use))
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
    onToggleSelect: (FileEntry) -> Unit,
    onNavigate: (FileEntry) -> Unit,
    onClearSelection: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPath: (String) -> Unit,
    onNewFolder: () -> Unit,
    onSaveToPhone: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAction: (FileEntry) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val loadingDescription = stringResource(R.string.files_listing)
    val listState = rememberLazyListState()
    LaunchedEffect(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        listState.scrollToItem(0)
    }
    val rows = remember(state.files) {
        state.files.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name },
        )
    }
    var fileFilter by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf("") }
    var searchOpen by remember(
        state.selectedPartitionId,
        state.currentPath,
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf(false) }
    val visibleRows = if (fileFilter.isBlank()) {
        rows
    } else {
        rows.filter { it.name.contains(fileFilter.trim(), ignoreCase = true) }
    }
    val rowsState = state.filesListingState.name
    val rowsError = state.filesError
    val locked = state.deviceBusy
    Column(modifier = modifier.fillMaxSize()) {
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
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            // The reducer clears files before a new path is loaded. Rendering the live
            // state directly (rather than an exiting AnimatedContent slot) prevents old
            // rows and the new path header from flashing together.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                userScrollEnabled = true
            ) {
                item(key = "folder-subblock") {
                    FolderSubblock(
                        path = state.currentPath,
                        pathLabel = folderPathLabel(state.currentPath),
                        canAct = canAct && !locked,
                        canWrite = canWrite && !locked,
                        onOpenPath = onOpenPath,
                        onRefresh = onRefresh,
                        onNewFolder = onNewFolder,
                        searchOpen = searchOpen,
                        filter = fileFilter,
                        onFilterChange = { fileFilter = it },
                        onSearchToggle = {
                            if (searchOpen) {
                                fileFilter = ""
                                searchOpen = false
                            } else {
                                searchOpen = true
                            }
                        },
                        isLoading = isLoading,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
                if (state.deviceBusy) {
                    item(key = "busy") {
                        EmptyHint(
                            UiMessage.Key(UiMessageKey.WaitForOperation).localized(),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                when {
                    !state.deviceReady -> item(key = "presence") {
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
                            rowsError?.let(::normalizeProviderText)
                                ?: stringResource(R.string.import_folder_read_failed),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    rowsState == FilesListingState.Stale.name -> item(key = "not-loaded") {
                        EmptyHint(
                            stringResource(R.string.import_folder_not_loaded),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    visibleRows.isEmpty() && rows.isNotEmpty() -> item(key = "filter-empty") {
                        EmptyHint(
                            stringResource(R.string.files_filter_empty),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    visibleRows.isEmpty() -> item(key = "empty") {
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
                    else -> items(visibleRows, key = { it.name }) { entry ->
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

/** Path row with an expanding search slot plus the fixed Refresh/CreateFolder icons. Selection
 * Download/Rename/Delete/Clear live in the selection row above and the per-row bottom sheet. */
@Composable
private fun FolderSubblock(
    path: String,
    pathLabel: String,
    canAct: Boolean,
    canWrite: Boolean,
    onOpenPath: (String) -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    searchOpen: Boolean,
    filter: String,
    onFilterChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val refreshDescription = stringResource(R.string.unattend_refresh)
    val newFolderDescription = stringResource(R.string.files_create_folder)
    val searchDescription = stringResource(R.string.files_filter_hint)
    val clearSearchDescription = stringResource(R.string.files_search_clear)
    val closeSearchDescription = stringResource(R.string.files_search_close)
    val pathA11y = stringResource(R.string.files_current_path, pathLabel)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var wasSearchOpen by remember { mutableStateOf(searchOpen) }
    LaunchedEffect(searchOpen) {
        if (searchOpen && !wasSearchOpen) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else if (!searchOpen) {
            keyboard?.hide()
        }
        wasSearchOpen = searchOpen
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fullWidth = maxWidth
            // The field grows leftwards from the 48 dp search button and shrinks back into
            // a circle; breadcrumb/action chrome waits until the collapse finishes.
            val searchWidth by animateDpAsState(
                targetValue = if (searchOpen) fullWidth else 48.dp,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "files-search-width",
            )
            val searchProgress by animateFloatAsState(
                targetValue = if (searchOpen) 1f else 0f,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "files-search-progress",
            )
            val collapsed = !searchOpen && searchProgress < 0.01f
            val searchShape = RoundedCornerShape(24.dp + (12.dp - 24.dp) * searchProgress)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (collapsed) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        FolderBreadcrumbs(
                            path = path,
                            enabled = canAct,
                            onOpen = onOpenPath,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = pathA11y },
                        )
                    }
                    FolderActionButton(
                        glyph = Glyph.Search,
                        description = searchDescription,
                        // Local filter over the current snapshot: no stick work involved.
                        enabled = true,
                        onClick = onSearchToggle,
                    )
                    if (isLoading) {
                        IconButton(
                            onClick = onRefresh,
                            enabled = canAct,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = refreshDescription },
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
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
                } else {
                    Spacer(Modifier.weight(1f))
                    OutlinedTextField(
                        value = filter,
                        onValueChange = onFilterChange,
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = searchDescription,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (filter.isEmpty()) {
                                        onSearchToggle()
                                    } else {
                                        onFilterChange("")
                                    }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = if (filter.isEmpty()) {
                                        closeSearchDescription
                                    } else {
                                        clearSearchDescription
                                    }
                                },
                            ) { GlyphIcon(Glyph.Close, size = 18.dp) }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = searchShape,
                        modifier = Modifier
                            .width(searchWidth)
                            .height(48.dp)
                            .clip(searchShape)
                            .focusRequester(focusRequester),
                    )
                }
            }
        }
    }
}

/** Clickable path segments in one horizontally scrollable row; replaces the old Up button. */
@Composable
private fun FolderBreadcrumbs(
    path: String,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootLabel = stringResource(R.string.files_breadcrumb_root)
    val parts = path.trim('/').split('/').filter { it.isNotBlank() }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onOpen("/") },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(rootLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        var current = ""
        parts.forEach { part ->
            current = if (current.isEmpty()) "/$part" else "$current/$part"
            // Capture a fresh val per segment; lambdas over the mutable `current` would all
            // open the deepest path after the loop finishes.
            val target = current
            Text(
                text = "/",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { onOpen(target) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(part, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/**
 * One MD3 exposed dropdown for partition selection. The field shows only the current
 * partition; the menu repeats the main-list row format: name, filesystem/EFI badges,
 * free/size, LBA range and RO/RW. No ordinal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PartitionDropdown(
    partitions: List<PartitionTab>,
    selectedId: String?,
    writeAccessOf: (PartitionTab) -> PartitionWriteAccess,
    usageOf: (PartitionTab) -> FilesystemUsageSnapshot?,
    phaseOf: (PartitionTab) -> FilesystemUsagePhase?,
    sectorSize: Int?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showLba: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = selectedId?.let { id -> partitions.firstOrNull { it.id == id } }
        ?: partitions.firstOrNull()
    val selectable = partitions.isNotEmpty()
    val rowEnabled = enabled && selectable
    LaunchedEffect(rowEnabled) {
        if (!rowEnabled) expanded = false
    }
    val placeholder = stringResource(R.string.files_select_partition)
    val label = stringResource(R.string.files_partition_radio_group)
    ExposedDropdownMenuBox(
        expanded = expanded && rowEnabled,
        onExpandedChange = { if (rowEnabled) expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) {
        TextField(
            value = selected?.let(::partitionDisplayName).orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = rowEnabled,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded && rowEnabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            partitions.forEach { partition ->
                val isSelected = partition.id == selectedId
                DropdownMenuItem(
                    text = {
                        PartitionOptionRow(
                            partition = partition,
                            selected = isSelected,
                            access = writeAccessOf(partition),
                            usage = filesystemUsageText(
                                snapshot = usageOf(partition),
                                phase = phaseOf(partition),
                                totalBytes = partitionSizeBytes(
                                    partition,
                                    sectorSize,
                                ),
                            ),
                            showLba = showLba,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(partition.id)
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { this.selected = isSelected },
                )
            }
        }
    }
}

@Composable
private fun PartitionOptionRow(
    partition: PartitionTab,
    selected: Boolean,
    access: PartitionWriteAccess,
    usage: String?,
    showLba: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = partitionDisplayName(partition),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier.padding(start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (partition.filesystemLabel.isNotEmpty()) {
                        Badge(text = partition.filesystemLabel)
                    }
                    if (partition.displayEfi) {
                        Badge(text = stringResource(R.string.partitions_badge_efi))
                    }
                    if (partition.displayBootable) {
                        Badge(text = stringResource(R.string.partitions_badge_boot))
                    }
                    WriteAccessBadge(access)
                }
            }
            usage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                )
            }
            if (showLba) {
                lbaRangeLabel(
                    start = partition.startLba,
                    count = partition.sectorCount,
                    templateId = R.string.partitions_info_lba_range_value,
                )?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Compact write chip: only `RO` or `RW` on one neutral tonal container.
 * Tone from [writeAccessTone]: RW primary, RO theme-aware amber. Error stays reserved for
 * failures and destructive actions. Unknown renders no chip; the long factual description
 * remains the a11y label.
 */
@Composable
internal fun WriteAccessBadge(access: PartitionWriteAccess) {
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
    val content = writeAccessTone(readWrite = access == PartitionWriteAccess.ReadWrite)
    Box(modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Badge(
            text = label,
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = content,
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
    val contentColor = when {
        !actionsEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = when {
        !actionsEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        headlineContent = {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.isDirectory || isSelected) {
                    FontWeight.Medium
                } else {
                    FontWeight.Normal
                },
                color = contentColor,
            )
        },
        supportingContent = if (!entry.isDirectory) {
            {
                Text(
                    text = DeviceIdentity.formatCapacity(entry.sizeBytes),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    color = supportingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
        } else {
            null
        },
        leadingContent = {
            Icon(
                painter = painterResource(
                    if (entry.isDirectory) R.drawable.ic_file_folder else R.drawable.ic_file,
                ),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = if (showActions) {
            {
                IconButton(
                    onClick = onAction,
                    enabled = actionsEnabled,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = moreActionsDescription },
                ) {
                    GlyphIcon(Glyph.More, size = 24.dp)
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            headlineColor = contentColor,
            supportingColor = supportingColor,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
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
            )
            .heightIn(min = 48.dp),
    )
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
    allowLongPress: Boolean = true,
) {
    val clickLabel = if (entry.isDirectory) {
        stringResource(R.string.files_open_folder)
    } else {
        stringResource(R.string.files_select_file)
    }
    val longClickLabel = stringResource(R.string.files_select_file)
    val contentColor = when {
        !actionsEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = when {
        !actionsEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        headlineContent = {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = contentColor,
                softWrap = true,
            )
        },
        supportingContent = {
            if (entry.isDirectory) {
                Text(
                    text = stringResource(R.string.type_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingColor,
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
                    color = supportingColor,
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
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            headlineColor = contentColor,
            supportingColor = supportingColor,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .semantics { selected = isSelected }
            .combinedClickable(
                enabled = actionsEnabled,
                onClickLabel = clickLabel,
                onLongClickLabel = longClickLabel.takeIf { allowLongPress },
                role = Role.Button,
                onClick = { if (entry.isDirectory) onNavigate() else onToggleSelect() },
                onLongClick = onToggleSelect.takeIf { allowLongPress },
            ),
    )
}

/**
 * Explicit localized batch phase. Never a percent: one blocking `space()` call gives no
 * progress, so the UI may state only which phase holds the partition. Null stays null so
 * callers render nothing instead of inventing [R.string.filesystem_usage_unavailable].
 */
@Composable
internal fun filesystemUsagePhaseText(phase: FilesystemUsagePhase?): String? =
    when (phase) {
        FilesystemUsagePhase.Queued -> stringResource(R.string.usage_queued)
        FilesystemUsagePhase.Waiting -> stringResource(R.string.usage_waiting)
        FilesystemUsagePhase.Calculating -> stringResource(R.string.usage_calculating)
        null -> null
    }

/**
 * One usage line for a partition: measured free/total from a snapshot whose key still
 * matches, otherwise the in-flight phase. A measured [FilesystemUsage.Unavailable] is a
 * real answer and stays visible; a missing snapshot with no phase renders nothing.
 */
@Composable
internal fun filesystemUsageText(
    snapshot: FilesystemUsageSnapshot?,
    phase: FilesystemUsagePhase?,
    totalBytes: ULong? = null,
): String? =
    when (val usage = snapshot?.usage) {
        is FilesystemUsage.Known -> stringResource(
            R.string.filesystem_usage_known,
            DeviceIdentity.formatCapacity(usage.space.freeBytes),
            DeviceIdentity.formatCapacity(usage.space.totalBytes),
        )
        is FilesystemUsage.Unavailable -> stringResource(
            when (usage.reason) {
                FilesystemUsageReason.UnsupportedFilesystem ->
                    R.string.filesystem_usage_unavailable_unsupported
                FilesystemUsageReason.EngineMissing ->
                    R.string.filesystem_usage_unavailable_engine
                FilesystemUsageReason.Closed ->
                    R.string.filesystem_usage_unavailable_closed
                FilesystemUsageReason.IoFailure ->
                    R.string.filesystem_usage_unavailable_io
            },
        )
        null -> filesystemUsagePhaseText(phase) ?: totalBytes?.let {
            stringResource(
                R.string.filesystem_usage_known,
                "—",
                DeviceIdentity.formatCapacity(it),
            )
        }
    }

internal fun partitionSizeBytes(partition: PartitionTab, sectorSize: Int?): ULong? {
    val count = partition.sectorCount ?: return null
    if (sectorSize == null || sectorSize <= 0) return null
    if (count > ULong.MAX_VALUE / sectorSize.toULong()) return null
    return count * sectorSize.toULong()
}

internal fun partitionDisplayName(partition: PartitionTab): String =
    partition.name.ifBlank { partition.id } +
        partition.volumeLabel.orEmpty().trim().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()

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
    val partition = state.selectedFilesVolume() ?: return null
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        modifier = Modifier.imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = {
                    Text(entry.name, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        DeviceIdentity.formatCapacity(entry.sizeBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = MaterialTheme.colorScheme.onSurface,
                    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
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
        }
    }
}

@Composable
private fun sheetActionRow(
    label: String,
    glyph: Glyph,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Disabled dims only this row's text and icon; each action is a rounded plate.
    val contentColor =
        if (enabled) {
            LocalContentColor.current
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
    PopupChoiceBlock(enabled = enabled, onClick = onClick) {
        ListItem(
            headlineContent = { Text(label, color = contentColor) },
            leadingContent = { GlyphIcon(glyph, size = 22.dp, tint = contentColor) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.semantics { if (!enabled) disabled() },
        )
    }
}
