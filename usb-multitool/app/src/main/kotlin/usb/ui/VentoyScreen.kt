package usb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R

@Composable
fun VentoyScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinned = usb.ventoy.VentoyAssets.PINNED_RELEASE
    val latest = state.latestVentoyRelease
    val updateAvailable = latest != null && latest != pinned

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionCard(
            title = stringResource(R.string.ventoy_section_title),
            subtitle = buildString {
                append(stringResource(R.string.ventoy_pinned_prefix, pinned))
                if (latest != null) {
                    append(stringResource(R.string.ventoy_latest_suffix, latest))
                }
            },
            trailing = {
                Badge(
                    text = stringResource(
                        if (updateAvailable) {
                            R.string.ventoy_badge_update
                        } else {
                            R.string.ventoy_badge_builtin
                        },
                    ),
                    container = if (updateAvailable) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    content = if (updateAvailable) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
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
            subtitle = stringResource(R.string.efi_section_subtitle),
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
                InfoRow(
                    label = stringResource(R.string.efi_protection_label),
                    value = stringResource(
                        if (unlocked) R.string.efi_protection_off else R.string.efi_protection_on,
                    ),
                )
                SwitchRow(
                    label = stringResource(R.string.efi_allow_writes),
                    checked = unlocked,
                    onCheckedChange = { onIntent(UiIntent.SetVtoyEfiWritable(target.id, it)) },
                    enabled = state.actionsEnabled,
                    hint = stringResource(R.string.efi_allow_hint),
                )
                InfoHint(
                    text = stringResource(
                        if (unlocked) R.string.efi_unlocked_short else R.string.efi_locked_short,
                    ),
                    infoTitle = stringResource(R.string.efi_policy_info_title),
                    infoText = stringResource(R.string.efi_policy_info_text),
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.ventoy_windows_title),
            subtitle = stringResource(R.string.ventoy_windows_subtitle),
        ) {
            CompactChoice(
                options = listOf(
                    null to stringResource(R.string.ventoy_checks_keep),
                    true to stringResource(R.string.ventoy_checks_bypass),
                    false to stringResource(R.string.ventoy_checks_require),
                ),
                selected = state.vtoyBypassChecks,
                onSelect = { onIntent(UiIntent.SetVtoyBypassChecks(it)) },
                label = stringResource(R.string.ventoy_checks_label),
                enabled = state.actionsEnabled,
            )
            Text(
                text = stringResource(R.string.ventoy_checks_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactChoice(
                options = listOf(
                    null to stringResource(R.string.ventoy_local_keep),
                    true to stringResource(R.string.ventoy_local_enable),
                    false to stringResource(R.string.ventoy_local_disable),
                ),
                selected = state.vtoyLocalAccount,
                onSelect = { onIntent(UiIntent.SetVtoyLocalAccount(it)) },
                label = stringResource(R.string.ventoy_local_label),
                enabled = state.actionsEnabled,
            )
            Text(
                text = stringResource(R.string.ventoy_local_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = stringResource(R.string.ventoy_iso_title),
            subtitle = stringResource(R.string.ventoy_iso_subtitle),
        ) {
            val isIsoDir = state.currentPath.trimEnd('/').equals("/ISO", ignoreCase = true)
            val isos = if (isIsoDir) state.files.filter { it.isIso } else emptyList()
            if (isos.isEmpty()) {
                EmptyHint(
                    if (isIsoDir) {
                        stringResource(R.string.ventoy_iso_empty)
                    } else {
                        stringResource(R.string.ventoy_iso_open)
                    },
                )
            } else {
                isos.forEach { iso ->
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = iso.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = DeviceIdentity.formatCapacity(iso.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    onIntent(UiIntent.IsoActionRequested(iso.name, IsoAction.LinkXml))
                                },
                                enabled = state.actionsEnabled,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    stringResource(R.string.ventoy_link_xml),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.ventoy_link_overwrite_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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

@Composable
private fun Badge(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
        )
    }
}
