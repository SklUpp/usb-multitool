package usb.ui

import android.net.Uri
import android.provider.DocumentsContract
import java.net.URLDecoder
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.yield
import usb.multitool.R

/**
 * SAF document/tree URIs become one canonical display path: percent-decoded document id,
 * forward slashes, no `.`/`..` or duplicate separators. The raw URI is not shown to the user.
 */
internal fun normalizedRestorePath(uri: String): String {
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return uri
    val documentId =
        runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
    val source = documentId ?: parsed.path ?: return uri
    val raw = if (documentId != null) source.substringAfter(':', source) else source
    return normalizeDocumentPath(raw)
}

/** Pure segment normalizer shared by restore display and tests. */
internal fun normalizeDocumentPath(raw: String): String {
    var source = runCatching {
        // Keep literal '+' intact; SAF document ids use percent escapes only.
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(raw).replace('\\', '/')
    val providerMarker = listOf("/document/", "document/", "/tree/", "tree/")
        .firstOrNull { source.contains(it) }
    if (providerMarker != null) {
        source = source.substringAfter(providerMarker)
    }
    // Drop a SAF volume prefix such as primary:, home: or raw:.
    val firstSlash = source.indexOf('/')
    val colon = source.indexOf(':')
    if (colon >= 0 && (firstSlash < 0 || colon < firstSlash)) {
        source = source.substring(colon + 1)
    }
    val segments = source.split('/')
    val normalized = ArrayList<String>(segments.size)
    for (segment in segments) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
            else -> normalized += segment
        }
    }
    return if (normalized.isEmpty()) "/" else "/" + normalized.joinToString("/")
}

@Composable
private fun RestorePathLine(label: String, path: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = path ?: stringResource(R.string.wave4_file_not_selected),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One line: canonical path and filename, with no provider/volume prefix. */
private fun restorePathText(uri: String?, fallback: String?): String {
    val path = uri?.let(::normalizedRestorePath)
    return if (path != null && path != "/") path else fallback.orEmpty()
}

@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickBackupFolder: () -> Unit = {},
    onPickRescueFile: () -> Unit = {},
    onPickGptFile: () -> Unit = {},
    onOpenBackupSettings: () -> Unit = {},
    onStartRescueBackup: () -> Unit = {},
    onCreateGptCopy: () -> Unit = {},
    /** Live persisted-grant check from the shell; required so a stale snapshot cannot ship. */
    backupFolderGrantValid: () -> Boolean,
) {
    var dangerExpanded by remember { mutableStateOf(false) }
    // The persisted SAF grant can vanish after startup while [AppUiState.backupFolderUsable]
    // still holds the old snapshot. Create clicks re-check the live grant: a lost grant
    // publishes that fact and opens Settings, so no SAF chooser ever starts from here.
    val requestCreate: (() -> Unit) -> Unit = { create ->
        if (backupFolderGrantValid()) {
            create()
        } else {
            onIntent(
                UiIntent.SetBackupFolder(
                    uri = state.backupFolderUri,
                    name = state.backupFolderName,
                    usable = false,
                ),
            )
            onOpenBackupSettings()
        }
    }
    val pickRestoreFileLabel = stringResource(R.string.diagnostics_pick_restore_file)
    val pickGptFileLabel = stringResource(R.string.diagnostics_pick_gpt_file)
    val usbPresent = state.device != null &&
        (state.presence == DevicePresence.Ready || state.presence == DevicePresence.Busy)
    val dangerActionLabel = stringResource(
        if (dangerExpanded) R.string.diagnostics_collapse else R.string.diagnostics_expand,
    )
    val dangerStateLabel = stringResource(
        if (dangerExpanded) R.string.diagnostics_expanded else R.string.diagnostics_collapsed,
    )
    // Scroll state is hoisted so expanding the danger block can bring its action into view.
    val scrollState = rememberScrollState()
    val wipeRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(dangerExpanded) {
        if (dangerExpanded) {
            // The expanded action is laid out in this frame; yield so the requester is attached.
            yield()
            wipeRequester.bringIntoView()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (usbPresent) {
            SectionCard(
                title = stringResource(R.string.diagnostics_checks_title),
                subtitle = stringResource(R.string.diagnostics_checks_subtitle),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DiagTile(
                        glyph = Glyph.Shield,
                        title = stringResource(R.string.diagnostics_self_test),
                        hint = stringResource(R.string.diagnostics_self_test_hint),
                        enabled = state.actionsEnabled,
                        highlighted = true,
                        onClick = { onIntent(UiIntent.RunSelfTest) },
                        modifier = Modifier.weight(1f),
                    )
                    DiagTile(
                        glyph = Glyph.Scan,
                        title = stringResource(R.string.diagnostics_read_scan),
                        hint = stringResource(R.string.diagnostics_read_scan_hint),
                        enabled = state.actionsEnabled,
                        onClick = { onIntent(UiIntent.StartReadScan) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DiagTile(
                        glyph = Glyph.Pulse,
                        title = stringResource(R.string.diagnostics_smart),
                        hint = stringResource(R.string.diagnostics_smart_hint),
                        enabled = state.actionsEnabled,
                        onClick = { onIntent(UiIntent.StartSmartRead) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.weight(1f))
                }
                val checkOp = state.operation
                val checkLines = when {
                    checkOp?.title == OperationTitle.SmartRead ->
                        (checkOp.smartResult ?: state.smartResult)
                            ?.let { smartInspectionLines(it) }
                            .orEmpty()
                    checkOp?.title == OperationTitle.SelfTest ||
                        checkOp?.title == OperationTitle.ReadScan ->
                        OperationPresentation.lines(checkOp)
                    state.smartResult != null ->
                        smartInspectionLines(state.smartResult)
                    else -> emptyList()
                }
                InspectionText(checkLines)
                if (checkOp?.title == OperationTitle.SelfTest ||
                    checkOp?.title == OperationTitle.ReadScan ||
                    checkOp?.title == OperationTitle.SmartRead
                ) {
                    CancelOperationButton(state = state, onIntent = onIntent, ownerTab = ShellTab.Diagnostics)
                }
            }
        }

        SectionCard(
            title = stringResource(R.string.diagnostics_backups_title),
            trailing = {
                // One help per card: rescue image + both GPT Service paths share this sheet.
                InfoHelpButton(
                    infoTitle = stringResource(R.string.diagnostics_rescue_title),
                    infoText = stringResource(R.string.diagnostics_rescue_note) +
                        "\n\n" + stringResource(R.string.diagnostics_gpt_restore_info_text),
                )
            },
        ) {
            val backupFolderReady = state.backupFolderUsable
            val resetSelectionLabel = stringResource(R.string.diagnostics_reset_selection)
            val chooseFolderLabel =
                stringResource(R.string.diagnostics_backup_choose_folder)
            val folderStatusText = when {
                state.backupFolderUri == null ->
                    stringResource(R.string.diagnostics_backup_folder_none)
                else -> state.backupFolderUri?.let(::normalizedRestorePath)
                    ?: stringResource(R.string.diagnostics_backup_folder_none)
            }
            val folderStatusColor = when {
                state.backupFolderUri != null && !state.backupFolderUsable ->
                    MaterialTheme.colorScheme.error
                state.backupFolderUri == null ->
                    MaterialTheme.colorScheme.onSurfaceVariant
                else ->
                    MaterialTheme.colorScheme.onSurface
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_backup_folder),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = folderStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = folderStatusColor,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(
                    onClick = onPickBackupFolder,
                    modifier = Modifier.semantics {
                        contentDescription = chooseFolderLabel
                    },
                ) {
                    GlyphIcon(glyph = Glyph.Folder, size = 18.dp)
                }
            }
            if (!backupFolderReady) {
                    // Folder choice lives in Settings; create actions stay blocked until a
                    // usable tree is available. Restore/wipe keep their own paths.
                    val noticeText = stringResource(
                        if (state.backupFolderUri == null) {
                            R.string.msg_choose_backup_folder
                        } else {
                            R.string.msg_backup_folder_permission_lost
                        },
                    )
                    val openSettingsLabel =
                        stringResource(R.string.diagnostics_backup_open_settings)
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = noticeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onOpenBackupSettings,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(
                                    text = openSettingsLabel,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

            SubsectionHeader(
                title = stringResource(R.string.diagnostics_rescue_backup),
                hint = stringResource(R.string.diagnostics_rescue_backup_hint),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = onPickRescueFile,
                            enabled = state.actionsEnabled,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = pickRestoreFileLabel
                                },
                        ) {
                            GlyphIcon(glyph = Glyph.File, size = 18.dp)
                        }
                        IconButton(
                            onClick = { onIntent(UiIntent.ResetRescueSelection) },
                            enabled = state.rescueImageUri != null || state.rescueMapUri != null,
                            modifier = Modifier.semantics {
                                contentDescription = resetSelectionLabel
                            },
                        ) {
                            GlyphIcon(glyph = Glyph.Close, size = 18.dp)
                        }
                    }
                },
            )
            RestorePathLine(
                label = stringResource(R.string.diagnostics_rescue_image_file),
                path = restorePathText(
                    state.rescueImageUri,
                    state.rescueImageInspection?.displayName,
                ),
            )
            RestorePathLine(
                label = stringResource(R.string.diagnostics_rescue_map_file),
                path = restorePathText(
                    state.rescueMapUri,
                    state.rescueMapInspection?.displayName,
                ),
            )
            val rescueImage = state.rescueImageInspection
                ?.takeIf { it.uri == state.rescueImageUri }
            val rescueMap = state.rescueMapInspection
                ?.takeIf { it.uri == state.rescueMapUri }
            val rescueReady = state.rescueImageUri != null &&
                state.rescueMapUri != null &&
                rescueImage != null &&
                rescueMap != null &&
                rescueMap.error == null &&
                rescueMap.summary?.sectorSize != null
            ActionList(
                items = listOf(
                    {
                        FilledTonalButton(
                            onClick = { requestCreate(onStartRescueBackup) },
                            enabled = state.actionsEnabled && backupFolderReady,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            GlyphIcon(glyph = Glyph.Copy, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.diagnostics_create_image),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    {
                        ApplyCancelButton(
                            state = state,
                            onIntent = onIntent,
                            ownerTab = ShellTab.Diagnostics,
                            label = stringResource(R.string.diagnostics_restore_image),
                            enabled = state.actionsEnabled && rescueReady,
                            onClick = {
                                onIntent(UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE))
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            matchesTitle = { title ->
                                title == OperationTitle.RescueBackup ||
                                    title == OperationTitle.Destructive(DestructiveAction.RESCUE_RESTORE)
                            },
                        )
                    },
                ),
                layout = ActionListLayout.Column,
            )
            if (state.rescueImageUri != null || state.rescueMapUri != null) {
                val restoreLines = buildList {
                    if (state.rescueImageUri != null) {
                        addAll(
                            rescueImageInspectionLines(
                                state.rescueImageInspection?.takeIf {
                                    it.uri == state.rescueImageUri
                                },
                            ),
                        )
                    }
                    if (state.rescueImageUri != null && state.rescueMapUri == null) {
                        add(stringResource(R.string.diagnostics_restore_map_missing))
                    }
                    if (state.rescueMapUri != null) {
                        addAll(
                            rescueMapInspectionLines(
                                state.rescueMapInspection?.takeIf {
                                    it.uri == state.rescueMapUri
                                },
                            ),
                        )
                    }
                }
                InspectionText(restoreLines)
            }

            SubsectionHeader(
                title = stringResource(R.string.diagnostics_gpt_copy),
                hint = stringResource(R.string.diagnostics_gpt_copy_hint),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = onPickGptFile,
                            enabled = state.actionsEnabled,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = pickGptFileLabel
                                },
                        ) {
                            GlyphIcon(glyph = Glyph.File, size = 18.dp)
                        }
                        IconButton(
                            onClick = { onIntent(UiIntent.ResetGptSelection) },
                            enabled = state.backupUri != null,
                            modifier = Modifier.semantics {
                                contentDescription = resetSelectionLabel
                            },
                        ) {
                            GlyphIcon(glyph = Glyph.Close, size = 18.dp)
                        }
                    }
                },
            )
            RestorePathLine(
                label = stringResource(R.string.diagnostics_gpt_backup_file),
                path = restorePathText(
                    state.backupUri,
                    state.backupInspection?.displayName,
                ),
            )
            val gptRestoreReady = state.backupUri != null &&
                state.backupInspection?.takeIf { it.uri == state.backupUri }?.let {
                    it.error == null && it.summary != null
                } == true
            ActionList(
                items = listOf(
                    {
                        FilledTonalButton(
                            onClick = { requestCreate(onCreateGptCopy) },
                            enabled = state.actionsEnabled &&
                                state.partitionScheme == usb.partition.TableScheme.GPT &&
                                backupFolderReady,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            GlyphIcon(glyph = Glyph.Gpt, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.diagnostics_create_gpt_copy),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    {
                        Button(
                            onClick = {
                                onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
                            },
                            enabled = state.actionsEnabled && gptRestoreReady,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            GlyphIcon(glyph = Glyph.Gpt, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.diagnostics_restore_gpt),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                ),
                layout = ActionListLayout.Column,
            )
            if (state.backupUri != null) {
                InspectionText(
                    gptBackupInspectionLines(
                        state.backupInspection?.takeIf { it.uri == state.backupUri },
                    ),
                )
            }
            // Live backup-GPT repair: RequestDestructive(GPT_REPAIR), never GPT_RESTORE.
            // Same UI guards as the former Partitions repair card: actionsEnabled and a
            // readable primary (not partitionTableDamaged). No backup-folder grant needed.
            val repairEnabled = state.actionsEnabled &&
                state.partitionScheme == usb.partition.TableScheme.GPT &&
                !state.partitionTableDamaged
            val repairLabel = stringResource(R.string.diagnostics_gpt_repair)
            val repairA11y = stringResource(R.string.diagnostics_gpt_repair_a11y)
            FilledTonalButton(
                onClick = {
                    onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_REPAIR))
                },
                enabled = repairEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = repairA11y },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
                GlyphIcon(glyph = Glyph.Gpt, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = repairLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.diagnostics_gpt_repair_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.partitionTableDamaged) {
                Text(
                    text = stringResource(R.string.diagnostics_gpt_repair_blocked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.diagnostics_gpt_restore_allowed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!usbPresent) {
            EmptyHint(UiReducer.presenceMessage(state.presence).localized())
        }

        if (usbPresent) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = dangerActionLabel,
                        ) { dangerExpanded = !dangerExpanded }
                        .semantics { stateDescription = dangerStateLabel },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_danger_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    GlyphIcon(
                        glyph = if (dangerExpanded) Glyph.ChevronUp else Glyph.ChevronDown,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        size = 22.dp,
                    )
                }
                Text(
                    text = stringResource(R.string.diagnostics_danger_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (dangerExpanded) {
                    Button(
                        onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.WIPE)) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(wipeRequester),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.diagnostics_wipe))
                    }
                }
            }
        }
        }
    }
}

/** Sub-block heading; policy help lives on the card, not on every row. */
@Composable
private fun SubsectionHeader(
    title: String,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun DiagTile(
    glyph: Glyph,
    title: String,
    hint: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    // Disabled tiles follow the M3 disabled tokens: a neutral onSurface container at 0.12
    // and content at 0.38, so a tile never looks tappable and never reads as an error.
    val container = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // A disabled tile must not look tappable: the container is muted and the glyph and both
    // labels are faded together, matching the board's .dis rule.
    val contentAlpha = if (enabled) 1f else 0.38f
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        modifier = modifier
            .heightIn(min = 84.dp)
            .clickable(
                enabled = enabled,
                onClickLabel = title,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .alpha(contentAlpha),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                GlyphIcon(
                    glyph = glyph,
                    size = 24.dp,
                    tint = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Decorative "start now" hint; the whole tile stays the only click target.
            GlyphIcon(
                glyph = Glyph.Play,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .alpha(contentAlpha),
            )
        }
    }
}

/** Compact bounded-inspection lines; never invents a value when the provider omitted one. */
@Composable
private fun InspectionText(lines: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().height(200.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_info_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

