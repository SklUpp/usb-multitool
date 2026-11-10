package usb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R

@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickBackup: () -> Unit = {},
    onPickRescueImage: () -> Unit = {},
    onPickRescueMap: () -> Unit = {},
) {
    var dangerExpanded by remember { mutableStateOf(false) }
    val dangerActionLabel = stringResource(
        if (dangerExpanded) R.string.diagnostics_collapse else R.string.diagnostics_expand,
    )
    val dangerStateLabel = stringResource(
        if (dangerExpanded) R.string.diagnostics_expanded else R.string.diagnostics_collapsed,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            subtitle = stringResource(R.string.diagnostics_backups_subtitle),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagTile(
                    glyph = Glyph.Copy,
                    title = stringResource(R.string.diagnostics_rescue_backup),
                    hint = stringResource(R.string.diagnostics_rescue_backup_hint),
                    enabled = state.actionsEnabled,
                    onClick = { onIntent(UiIntent.StartRescueBackup) },
                    modifier = Modifier.weight(1f),
                )
                DiagTile(
                    glyph = Glyph.Gpt,
                    title = stringResource(R.string.diagnostics_gpt_copy),
                    hint = stringResource(R.string.diagnostics_gpt_copy_hint),
                    enabled = state.actionsEnabled,
                    onClick = { onIntent(UiIntent.CaptureGptBackup) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (!state.actionsEnabled) {
            EmptyHint(UiReducer.presenceMessage(state.presence).localized())
        }

        SectionCard(
            title = stringResource(R.string.diagnostics_rescue_title),
            subtitle = stringResource(R.string.diagnostics_rescue_subtitle),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_rescue_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPickRescueImage,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = state.rescueImageUri?.substringAfterLast('/')?.let {
                            stringResource(R.string.diagnostics_image_named, it)
                        } ?: stringResource(R.string.diagnostics_pick_image),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onPickRescueMap,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = state.rescueMapUri?.substringAfterLast('/')?.let {
                            stringResource(R.string.diagnostics_map_named, it)
                        } ?: stringResource(R.string.diagnostics_pick_map),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE)) },
                enabled = state.actionsEnabled &&
                    state.rescueImageUri != null &&
                    state.rescueMapUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diagnostics_restore_image))
            }
        }

        SectionCard(
            title = stringResource(R.string.diagnostics_gpt_restore_title),
            subtitle = stringResource(R.string.diagnostics_gpt_restore_subtitle),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.backupUri?.let { it.substringAfterLast('/') }
                            ?: stringResource(R.string.diagnostics_no_gpt_file),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (state.backupUri != null) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedButton(
                        onClick = onPickBackup,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(R.string.partitions_pick),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Button(
                onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE)) },
                enabled = state.actionsEnabled && state.backupUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diagnostics_restore_gpt))
            }
            if (state.partitionTableDamaged) {
                Text(
                    text = stringResource(R.string.diagnostics_gpt_restore_allowed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                    Button(
                        onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.WIPE)) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.fillMaxWidth(),
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
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val content = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
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
                .padding(10.dp),
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
                maxLines = 1,
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
