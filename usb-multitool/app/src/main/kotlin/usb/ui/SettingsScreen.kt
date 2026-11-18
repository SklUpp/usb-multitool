package usb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.res.stringResource
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
            title = { Text(stringResource(R.string.settings_title_screen)) },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = backLabel },
                ) { GlyphIcon(Glyph.ChevronLeft) }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
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
            Text(stringResource(R.string.settings_appearance_title), style = MaterialTheme.typography.titleMedium)
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
    SettingRow(
        label = stringResource(R.string.language_label),
        value = languageName(language),
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        AppLanguage.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(languageName(option)) },
                onClick = {
                    expanded = false
                    onIntent(UiIntent.SetLanguage(option))
                },
            )
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
            Text(stringResource(R.string.diagnostics_backups_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.backupFolderName ?: state.backupFolderUri?.substringAfterLast('/')
                    ?: stringResource(R.string.diagnostics_backup_folder_none),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.backupFolderUri != null && !state.backupFolderUsable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = if (value.isBlank()) label else "$label: $value" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            if (value.isNotBlank()) {
                Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
