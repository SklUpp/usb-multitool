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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(
            title = "Ventoy на флешке",
            subtitle = buildString {
                append("Мультизагрузка · встроенная версия v")
                append(pinned)
                if (latest != null) {
                    append(" · последняя v")
                    append(latest)
                }
            },
            trailing = {
                Badge(
                    text = if (updateAvailable) "Обновление" else "Встроенная",
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
                EmptyHint(UiReducer.presenceMessage(state.presence))
            }
            Button(
                onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_UPDATE)) },
                enabled = state.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Обновить (ISO сохранятся)")
            }
            OutlinedButton(
                onClick = {
                    onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_DOWNLOAD_INSTALL))
                },
                enabled = state.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Скачать новую версию и установить")
            }
            Text(
                text = "Обновление перезаписывает загрузочные файлы и сохраняет ISO на разделе данных.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "Параметры Windows",
            subtitle = "Применяются при связывании autounattend.xml с ISO",
        ) {
            SwitchRow(
                label = "Обход TPM",
                checked = state.vtoyBypassTpm,
                onCheckedChange = { onIntent(UiIntent.SetVtoyBypassTpm(it)) },
                enabled = state.actionsEnabled,
                hint = "Пропустить требование TPM 2.0",
            )
            SwitchRow(
                label = "Обход проверки RAM",
                checked = state.vtoyBypassRam,
                onCheckedChange = { onIntent(UiIntent.SetVtoyBypassRam(it)) },
                enabled = state.actionsEnabled,
                hint = "Пропустить минимум памяти Windows 11",
            )
            SwitchRow(
                label = "Локальная учётная запись",
                checked = state.vtoyLocalAccount,
                onCheckedChange = { onIntent(UiIntent.SetVtoyLocalAccount(it)) },
                enabled = state.actionsEnabled,
                hint = "Без обязательного входа в Microsoft",
            )
        }

        SectionCard(
            title = "Windows ISO",
            subtitle = "/ISO на вкладке «Файлы» — здесь можно связать образы",
        ) {
            val isIsoDir = state.currentPath.trimEnd('/').equals("/ISO", ignoreCase = true)
            val isos = if (isIsoDir) state.files.filter { it.isIso } else emptyList()
            if (isos.isEmpty()) {
                EmptyHint(
                    if (isIsoDir) {
                        "В /ISO нет ISO-образов."
                    } else {
                        "Откройте /ISO на вкладке «Файлы», чтобы связать Windows ISO."
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
                                Text("Связать XML", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Text(
                    text = "Существующая связь требует явного подтверждения перезаписи.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            title = "Чистая установка",
            subtitle = "Сотрёт все разделы и файлы на флешке.",
            danger = true,
        ) {
            Text(
                text = "Записывает свежую разметку Ventoy и форматирует раздел данных. " +
                    "Перед записью потребуется подтверждение.",
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
                Text("Установить с нуля…", fontWeight = FontWeight.Bold)
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
