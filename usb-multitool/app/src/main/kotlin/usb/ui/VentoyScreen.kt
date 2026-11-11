package usb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R

@Composable
fun VentoyScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickWindowsFile: (WindowsImportKind, String?) -> Unit = { _, _ -> },
) {
    val pinned = usb.ventoy.VentoyAssets.PINNED_RELEASE
    val latest = state.latestVentoyRelease
    val newerLatest = latest?.takeIf { it != pinned }
    val updateAvailable = newerLatest != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = stringResource(R.string.ventoy_section_title),
            subtitle = buildString {
                append(stringResource(R.string.ventoy_pinned_prefix, pinned))
                newerLatest?.let { append(stringResource(R.string.ventoy_latest_suffix, it)) }
            },
            trailing = {
                if (updateAvailable) {
                    Badge(
                        text = stringResource(R.string.ventoy_badge_update),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            },
        ) {
            if (!state.actionsEnabled) {
                EmptyHint(UiReducer.presenceMessage(state.presence).localized())
            }
            Button(
                onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_UPDATE)) },
                enabled = state.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ventoy_update))
            }
            OutlinedButton(
                onClick = {
                    onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_DOWNLOAD_INSTALL))
                },
                enabled = state.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ventoy_download))
            }
            Text(
                text = stringResource(R.string.ventoy_update_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = stringResource(R.string.efi_section_title),
            trailing = {
                InfoHelpButton(
                    infoTitle = stringResource(R.string.efi_policy_info_title),
                    infoText = stringResource(R.string.efi_policy_info_text),
                )
            },
        ) {
            val efis = state.efiPartitions.filter { it.startLba != null && it.sectorCount != null }
            if (efis.isEmpty()) {
                EmptyHint(stringResource(R.string.efi_missing))
            } else {
                var targetId by remember(
                    state.device?.identity,
                    state.device?.attachGeneration,
                    state.partitions,
                ) {
                    mutableStateOf<String?>(null)
                }
                val target = efis.firstOrNull { it.id == targetId }
                    ?: efis.firstOrNull { it.id == state.vtoyEfiWritePartitionId }
                    ?: efis.firstOrNull { it.id == state.selectedPartitionId }
                    ?: efis.first()
                val unlocked = state.isEfiWriteAllowed(target)
                if (efis.size > 1) {
                    CompactChoice(
                        options = efis.map { it.id to it.name },
                        selected = target.id,
                        onSelect = { targetId = it },
                        label = stringResource(R.string.efi_partition_label),
                        enabled = state.actionsEnabled,
                    )
                } else {
                    InfoRow(stringResource(R.string.efi_partition_label), target.name)
                }
                SwitchRow(
                    label = stringResource(R.string.efi_allow_writes),
                    checked = unlocked,
                    onCheckedChange = { onIntent(UiIntent.SetVtoyEfiWritable(target.id, it)) },
                    enabled = state.actionsEnabled,
                    hint = stringResource(R.string.efi_allow_hint),
                )
            }
        }

        // Phone import lives next to the sections it feeds: an image becomes bindable, and an
        // XML template becomes the answer file. The source document is never moved or deleted.
        ImportFromPhoneCard(
            state = state,
            onIntent = onIntent,
            onPickWindowsFile = onPickWindowsFile,
        )

        // Windows/XML work is one subsection of this tab: the answer file that already exists,
        // the parameters used to regenerate it, which images are bound to it, and the real
        // global `control` values. No new tab, nothing in Files.
        AutounattendSection(state = state, onIntent = onIntent)

        SectionCard(
            title = stringResource(R.string.ventoy_fresh_title),
            subtitle = stringResource(R.string.ventoy_fresh_subtitle),
            danger = true,
        ) {
            Text(
                text = stringResource(R.string.ventoy_fresh_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL)) },
                enabled = state.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
            ) {
                Text(
                    stringResource(R.string.ventoy_fresh_action),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}


/**
 * Phone-to-stick import. The destination is an existing folder of the Ventoy data partition,
 * picked before the system picker opens; a target that already exists needs an explicit
 * overwrite tick, and a template carrying destructive sections needs a second one.
 */
@Composable
private fun ImportFromPhoneCard(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onPickWindowsFile: (WindowsImportKind, String?) -> Unit,
) {
    var folderKind by remember { mutableStateOf<WindowsImportKind?>(null) }

    SectionCard(
        title = stringResource(R.string.import_title),
        subtitle = stringResource(R.string.import_subtitle),
    ) {
        ImportRow(
            label = stringResource(R.string.import_image_label),
            hint = stringResource(R.string.import_image_hint),
            enabled = state.actionsEnabled,
            onClick = { folderKind = WindowsImportKind.Image },
        )
        ImportRow(
            label = stringResource(R.string.import_template_label),
            hint = stringResource(R.string.import_template_hint),
            enabled = state.actionsEnabled,
            onClick = { folderKind = WindowsImportKind.XmlTemplate },
        )
        state.isoImportError?.let { problem ->
            Text(
                text = stringResource(unreadableText(problem)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onIntent(UiIntent.CancelWindowsIsoImport) }) {
                Text(stringResource(R.string.import_error_dismiss))
            }
        }
        Text(
            text = stringResource(R.string.import_kept_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    folderKind?.let { kind ->
        ImportFolderDialog(
            kind = kind,
            folders = state.ventoyFolders,
            folderError = state.ventoyFoldersError,
            onRefresh = { onIntent(UiIntent.LoadVentoyFolders) },
            onPick = { directory ->
                folderKind = null
                onPickWindowsFile(kind, directory)
            },
            onDismiss = { folderKind = null },
        )
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

@Composable
private fun ImportRow(
    label: String,
    hint: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.import_choose))
        }
    }
}

/** Only an existing folder on the data partition may be a destination. */
@Composable
private fun ImportFolderDialog(
    kind: WindowsImportKind,
    folders: List<String>?,
    folderError: String?,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val preferred = if (kind == WindowsImportKind.XmlTemplate) "/ventoy" else "/ISO"
    val options = folders.orEmpty()
    var chosen by remember(kind, options) {
        mutableStateOf(if (preferred in options) preferred else options.firstOrNull())
    }
    val selection = chosen

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_folder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.import_folder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    folders == null -> Text(
                        text = stringResource(R.string.import_folder_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    options.isEmpty() -> Text(
                        text = stringResource(R.string.import_folder_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Column(
                        // Weighted with fill = false: the error line and the Refresh button
                        // below are measured first, so the folder list can only consume the
                        // height that is actually left in the dialog slot.
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        options.take(40).forEach { folder ->
                            val current = folder == selection
                            TextButton(
                                onClick = { chosen = folder },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = folder,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (current) FontWeight.Bold else null,
                                    color = if (current) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                folderError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.unattend_refresh))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selection?.let(onPick) },
                enabled = selection != null,
            ) { Text(stringResource(R.string.import_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Existing target: replacing it is destructive, and a template that brings layout or
 * destructive sections needs a second explicit tick. Nothing here deletes the phone copy.
 */
@Composable
private fun ImportOverwriteDialog(
    proposal: IsoImportProposal,
    actionsEnabled: Boolean,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    var overwriteAck by remember(proposal) { mutableStateOf(false) }
    var markersAck by remember(proposal) { mutableStateOf(proposal.templateMarkers.isEmpty()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_overwrite_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.import_overwrite_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                InfoRow(
                    stringResource(R.string.import_target_label),
                    if (proposal.directory == "/") {
                        "/${proposal.targetName}"
                    } else {
                        "${proposal.directory}/${proposal.targetName}"
                    },
                )
                proposal.sizeBytes?.let { size ->
                    InfoRow(
                        stringResource(R.string.import_size_label),
                        DeviceIdentity.formatCapacity(size.coerceAtLeast(0L).toULong()),
                    )
                }
                if (proposal.templateMarkers.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.import_markers_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    proposal.templateMarkers.forEach { marker ->
                        Text(
                            text = marker,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = markersAck,
                                enabled = actionsEnabled,
                                role = Role.Checkbox,
                                onValueChange = { markersAck = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = markersAck,
                            onCheckedChange = null,
                            enabled = actionsEnabled,
                        )
                        Text(
                            text = stringResource(R.string.import_markers_ack),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = overwriteAck,
                            enabled = actionsEnabled,
                            role = Role.Checkbox,
                            onValueChange = { overwriteAck = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = overwriteAck,
                        onCheckedChange = null,
                        enabled = actionsEnabled,
                    )
                    Text(
                        text = stringResource(R.string.import_overwrite_ack),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onReplace,
                enabled = actionsEnabled && overwriteAck && markersAck,
            ) { Text(stringResource(R.string.import_overwrite_action)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
