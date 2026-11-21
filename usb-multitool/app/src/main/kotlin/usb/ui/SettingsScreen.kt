package usb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import usb.multitool.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: AppUiState,
    versionName: String?,
    onIntent: (UiIntent) -> Unit,
    onBack: () -> Unit,
    onPickBackupFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        val backLabel = stringResource(R.string.settings_back)
        TopAppBar(
            expandedHeight = adaptiveTopAppBarHeight(),
            title = { Text(stringResource(R.string.settings_title_screen)) },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = backLabel },
                ) { GlyphIcon(Glyph.ChevronLeft) }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            // Scaffold already applied safeDrawing; a second status-bar inset would gap
            // the title below the main DeviceHeader.
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsAppearance(state = state, onIntent = onIntent)
            SettingsPerformance()
            SettingsBackups(state = state, onPickBackupFolder = onPickBackupFolder)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_about_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = versionName?.let { stringResource(R.string.app_version_label, it) }
                            ?: stringResource(R.string.app_version_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAppearance(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_appearance_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SettingRow(
                label = stringResource(R.string.theme_label),
                value = themeLabel(state.darkTheme),
                onClick = { onIntent(UiIntent.ToggleTheme) },
            )
            LanguageSettingRow(state.language, onIntent)
        }
    }
}

@Composable
private fun LanguageSettingRow(language: AppLanguage, onIntent: (UiIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // DropdownMenu positions against the nearest parent layout bounds; wrap the
    // SettingRow so the popup anchors to the row, not the surrounding Column.
    Box(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            label = stringResource(R.string.language_label),
            value = languageName(language),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 168.dp, max = 320.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
        ) {
            AppLanguage.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = languageName(option),
                            softWrap = true,
                        )
                    },
                    onClick = {
                        expanded = false
                        onIntent(UiIntent.SetLanguage(option))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    trailingIcon = if (option == language) {
                        {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsPerformance() {
    PerformanceSettingsSection()
}

@Composable
private fun SettingsBackups(state: AppUiState, onPickBackupFolder: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.diagnostics_backups_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = state.backupFolderUri?.let { uri ->
                    state.backupFolderName?.takeIf { it.isNotBlank() }?.let { "$it\n$uri" } ?: uri
                } ?: stringResource(R.string.diagnostics_backup_folder_none),
                style = MaterialTheme.typography.bodyMedium,
                softWrap = true,
                color = if (state.backupFolderUri != null && !state.backupFolderUsable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (state.backupFolderUri != null && !state.backupFolderUsable) {
                Text(
                    text = stringResource(R.string.msg_backup_folder_permission_lost),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SettingRow(
                label = stringResource(
                    if (state.backupFolderUri == null) R.string.diagnostics_pick_folder
                    else R.string.diagnostics_change_folder,
                ),
                value = "",
                onClick = onPickBackupFolder,
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = if (value.isBlank()) label else "$label: $value" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                softWrap = true,
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    softWrap = true,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun languageName(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(R.string.language_system)
    AppLanguage.Russian -> stringResource(R.string.language_russian)
    AppLanguage.English -> stringResource(R.string.language_english)
}

@Composable
private fun themeLabel(darkTheme: Boolean): String =
    if (darkTheme) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light)
