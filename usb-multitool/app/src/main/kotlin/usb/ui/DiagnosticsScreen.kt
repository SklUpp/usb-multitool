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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiagTile(
                glyph = Glyph.Shield,
                title = "Самотест",
                hint = "SCSI, GPT, ФС",
                enabled = !state.isRunning,
                highlighted = true,
                onClick = { onIntent(UiIntent.RunSelfTest) },
                modifier = Modifier.weight(1f),
            )
            DiagTile(
                glyph = Glyph.Scan,
                title = "Скан чтения",
                hint = "Битые сектора",
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
                glyph = Glyph.Copy,
                title = "Образ + карта",
                hint = "Backup ddrescue",
                enabled = state.actionsEnabled,
                onClick = { onIntent(UiIntent.StartRescueBackup) },
                modifier = Modifier.weight(1f),
            )
            DiagTile(
                glyph = Glyph.Gpt,
                title = "Копия GPT",
                hint = "Сохранить структуру",
                enabled = state.actionsEnabled,
                onClick = { onIntent(UiIntent.CaptureGptBackup) },
                modifier = Modifier.weight(1f),
            )
        }

        if (!state.actionsEnabled) {
            EmptyHint(UiReducer.presenceMessage(state.presence))
        }

        SectionCard(
            title = "Восстановление из образа",
            subtitle = "Геометрия должна совпадать с образом.",
        ) {
            Text(
                text = "Resume пропускает скопированные сектора только при совпадении карты. " +
                    "Partial COPY отклоняется; FAILED сектора записываются нулями.",
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
                        text = state.rescueImageUri?.substringAfterLast('/')?.let { "Образ: $it" }
                            ?: "Выбрать образ…",
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
                        text = state.rescueMapUri?.substringAfterLast('/')?.let { "Карта: $it" }
                            ?: "Выбрать карту…",
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
                Text("Восстановить образ")
            }
        }

        SectionCard(
            title = "GPT",
            subtitle = "Резервная копия первичной и запасной таблиц.",
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
                            ?: "Файл GPT не выбран",
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
                        Text("Выбрать", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Button(
                onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE)) },
                enabled = state.actionsEnabled && state.backupUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Восстановить GPT")
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
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (dangerExpanded) "Свернуть" else "Развернуть",
                        ) { dangerExpanded = !dangerExpanded }
                        .semantics {
                            stateDescription = if (dangerExpanded) "Развёрнуто" else "Свёрнуто"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Опасная зона: стирание устройства",
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
                    text = "Стирание затирает все сектора нулями без возможности отмены.",
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
                        Text("Стереть устройство (нули)")
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
            .heightIn(min = 96.dp)
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
