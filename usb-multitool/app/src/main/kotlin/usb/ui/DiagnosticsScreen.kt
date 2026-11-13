package usb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    onPickBackupFolder: () -> Unit = {},
    onStartRescueBackup: () -> Unit = {},
    onCreateGptCopy: () -> Unit = {},
    onPickRestoreFolder: () -> Unit = {},
) {
    var dangerExpanded by remember { mutableStateOf(false) }
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
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                    enabled = !state.isRunning,
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
        }

        SectionCard(
            title = stringResource(R.string.diagnostics_backups_title),
            trailing = {
                // The full restore policy is one tap away; the blocks stay compact.
                InfoHelpButton(
                    infoTitle = stringResource(R.string.diagnostics_rescue_title),
                    infoText = stringResource(R.string.diagnostics_rescue_note),
                )
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diagnostics_backup_folder),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = state.backupFolderName
                            ?: state.backupFolderUri?.substringAfterLast('/')
                            ?: stringResource(R.string.diagnostics_backup_folder_none),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.backupFolderUri != null && !state.backupFolderUsable) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onPickBackupFolder,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.backupFolderUri == null) {
                                R.string.diagnostics_pick_folder
                            } else {
                                R.string.diagnostics_change_folder
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            SubsectionHeader(
                title = stringResource(R.string.diagnostics_rescue_backup),
                infoTitle = stringResource(R.string.diagnostics_rescue_title),
                infoText = stringResource(R.string.diagnostics_rescue_note),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStartRescueBackup,
                    enabled = state.actionsEnabled,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GlyphIcon(glyph = Glyph.Copy, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_create_image),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onPickRestoreFolder,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GlyphIcon(glyph = Glyph.Copy, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_restore_image),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.rescueImageUri != null) {
                Text(
                    text = stringResource(
                        R.string.diagnostics_image_named,
                        state.rescueImageInspection?.displayName
                            ?: state.rescueImageUri?.substringAfterLast('/').orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InspectionText(
                    rescueImageInspectionLines(
                        state.rescueImageInspection?.takeIf { it.uri == state.rescueImageUri },
                    ),
                )
            }
            if (state.rescueMapUri != null) {
                Text(
                    text = stringResource(
                        R.string.diagnostics_map_named,
                        state.rescueMapInspection?.displayName
                            ?: state.rescueMapUri?.substringAfterLast('/').orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InspectionText(
                    rescueMapInspectionLines(
                        state.rescueMapInspection?.takeIf { it.uri == state.rescueMapUri },
                    ),
                )
            }

            SubsectionHeader(
                title = stringResource(R.string.diagnostics_gpt_copy),
                infoTitle = stringResource(R.string.diagnostics_gpt_restore_info_title),
                infoText = stringResource(R.string.diagnostics_gpt_restore_info_text),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onCreateGptCopy,
                    enabled = state.actionsEnabled,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GlyphIcon(glyph = Glyph.Gpt, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_create_gpt_copy),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onPickBackup,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GlyphIcon(glyph = Glyph.Gpt, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_restore_gpt),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.backupUri != null) {
                Text(
                    text = stringResource(
                        R.string.diagnostics_backup_file_named,
                        state.backupInspection?.displayName
                            ?: state.backupUri?.substringAfterLast('/').orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InspectionText(
                    gptBackupInspectionLines(
                        state.backupInspection?.takeIf { it.uri == state.backupUri },
                    ),
                )
            }
            if (state.partitionTableDamaged) {
                Text(
                    text = stringResource(R.string.diagnostics_gpt_restore_allowed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PerformanceSettingsSection()

        if (!state.actionsEnabled) {
            EmptyHint(UiReducer.presenceMessage(state.presence).localized())
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    GlyphIcon(
                        glyph = if (dangerExpanded) Glyph.ChevronUp else Glyph.ChevronDown,
                        tint = MaterialTheme.colorScheme.error,
                        size = 22.dp,
                    )
                }
                Text(
                    text = stringResource(R.string.diagnostics_danger_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Sub-block heading with its policy help next to it, not as body text. */
@Composable
private fun SubsectionHeader(
    title: String,
    infoTitle: String,
    infoText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        InfoHelpButton(infoTitle = infoTitle, infoText = infoText)
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
    val container = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 1f else 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.35f else 0.18f)
    }
    val content = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // A disabled tile must not look tappable: the container is muted and the glyph and both
    // labels are faded together, matching the board's .dis rule.
    val contentAlpha = if (enabled) 1f else 0.45f
    Surface(
        shape = RoundedCornerShape(16.dp),
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
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
