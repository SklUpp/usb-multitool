package usb.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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

@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickBackup: () -> Unit = {},
    onOpenBackupSettings: () -> Unit = {},
    onStartRescueBackup: () -> Unit = {},
    onCreateGptCopy: () -> Unit = {},
    onPickRestoreFolder: () -> Unit = {},
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
                state.smartResult?.let { outcome ->
                    InspectionText(smartInspectionLines(outcome))
                }
                CancelOperationButton(state = state, onIntent = onIntent)
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
            val folderStatusText = when {
                state.backupFolderUri == null ->
                    stringResource(R.string.diagnostics_backup_folder_none)
                else -> state.backupFolderUri?.let { uri ->
                    state.backupFolderName?.takeIf { it.isNotBlank() }?.let { "$it\n$uri" } ?: uri
                } ?: stringResource(R.string.diagnostics_backup_folder_none)
            }
            val folderStatusColor = when {
                state.backupFolderUri != null && !state.backupFolderUsable ->
                    MaterialTheme.colorScheme.error
                state.backupFolderUri == null ->
                    MaterialTheme.colorScheme.onSurfaceVariant
                else ->
                    MaterialTheme.colorScheme.onSurface
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                )
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
            }

            SubsectionHeader(
                title = stringResource(R.string.diagnostics_rescue_backup),
                hint = stringResource(R.string.diagnostics_rescue_backup_hint),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { requestCreate(onStartRescueBackup) },
                    enabled = state.actionsEnabled && backupFolderReady,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GlyphIcon(glyph = Glyph.Copy, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_create_image),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                FilledTonalButton(
                    onClick = onPickRestoreFolder,
                    enabled = state.actionsEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GlyphIcon(glyph = Glyph.Copy, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_pick_image_folder),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (state.rescueImageUri != null) {
                Text(
                    text = stringResource(
                        R.string.diagnostics_image_named,
                        state.rescueImageInspection?.displayName
                            ?: state.rescueImageUri?.substringAfterLast('/').orEmpty(),
                    ) + "\n" + state.rescueImageUri.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                InspectionText(
                    rescueImageInspectionLines(
                        state.rescueImageInspection?.takeIf { it.uri == state.rescueImageUri },
                    ),
                )
            }
            if (state.rescueImageUri != null && state.rescueMapUri == null) {
                Text(
                    text = stringResource(R.string.diagnostics_restore_map_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.rescueMapUri != null) {
                Text(
                    text = stringResource(
                        R.string.diagnostics_map_named,
                        state.rescueMapInspection?.displayName
                            ?: state.rescueMapUri?.substringAfterLast('/').orEmpty(),
                    ) + "\n" + state.rescueMapUri.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                InspectionText(
                    rescueMapInspectionLines(
                        state.rescueMapInspection?.takeIf { it.uri == state.rescueMapUri },
                    ),
                )
            }
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
            Button(
                onClick = {
                    onIntent(UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE))
                },
                enabled = state.actionsEnabled && rescueReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                GlyphIcon(glyph = Glyph.Copy, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.diagnostics_restore_image))
            }

            SubsectionHeader(
                title = stringResource(R.string.diagnostics_gpt_copy),
                hint = stringResource(R.string.diagnostics_gpt_copy_hint),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { requestCreate(onCreateGptCopy) },
                    enabled = state.actionsEnabled && backupFolderReady,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GlyphIcon(glyph = Glyph.Gpt, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_create_gpt_copy),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                FilledTonalButton(
                    onClick = onPickBackup,
                    enabled = state.actionsEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GlyphIcon(glyph = Glyph.Gpt, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_pick_gpt_file),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (state.backupUri != null) {
                Text(
                    text = stringResource(
                        R.string.diagnostics_backup_file_named,
                        state.backupInspection?.displayName
                            ?: state.backupUri?.substringAfterLast('/').orEmpty(),
                    ) + "\n" + state.backupUri.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                InspectionText(
                    gptBackupInspectionLines(
                        state.backupInspection?.takeIf { it.uri == state.backupUri },
                    ),
                )
            }
            val gptRestoreReady = state.backupUri != null &&
                state.backupInspection?.takeIf { it.uri == state.backupUri }?.let {
                    it.error == null && it.summary != null
                } == true
            Button(
                onClick = {
                    onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
                },
                enabled = state.actionsEnabled && gptRestoreReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                GlyphIcon(glyph = Glyph.Gpt, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.diagnostics_restore_gpt))
            }
            // Live backup-GPT repair: RequestDestructive(GPT_REPAIR), never GPT_RESTORE.
            // Same UI guards as the former Partitions repair card: actionsEnabled and a
            // readable primary (not partitionTableDamaged). No backup-folder grant needed.
            val repairEnabled = state.actionsEnabled && !state.partitionTableDamaged
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

