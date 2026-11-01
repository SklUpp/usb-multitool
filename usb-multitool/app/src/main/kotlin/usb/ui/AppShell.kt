package usb.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment

/**
 * Four-tab shell over [AppUiState]. Talks only through [onIntent]; never opens USB.
 */
@Composable
fun AppShell(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
    onRequestDownload: (String) -> Unit = {},
    onPickBackup: () -> Unit = {},
) {
    val isDark = when (state.darkTheme) {
        true -> true
        false -> false
        null -> isSystemInDarkTheme()
    }
    val colors = if (isDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    ShellTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { onIntent(UiIntent.SelectTab(tab)) },
                            icon = { Text(tab.shortLabel()) },
                            label = { Text(tab.title()) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .padding(16.dp)
                        .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DeviceBanner(state, onIntent)
                OperationBanner(state, onIntent)
                when (state.tab) {
                    ShellTab.Files -> FilesScreen(state, onIntent, onRequestDownload = onRequestDownload)
                    ShellTab.Ventoy -> VentoyScreen(state, onIntent)
                    ShellTab.Partitions -> PartitionsScreen(state, onIntent, onPickImage = onPickImage)
                    ShellTab.Diagnostics -> DiagnosticsScreen(state, onIntent, onPickBackup = onPickBackup)
                }
            }
        }

        state.pendingConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = { onIntent(UiIntent.DismissConfirmation) },
                title = { Text(confirmation.action) },
                text = { Text(confirmation.message) },
                confirmButton = {
                    Button(onClick = { onIntent(UiIntent.ConfirmDestructive) }) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(UiIntent.DismissConfirmation) }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
fun RememberedAppShell(
    initial: AppUiState = AppUiState(),
    onEffect: (UiEffect) -> Unit = {},
) {
    var state by remember { mutableStateOf(initial) }
    AppShell(
        state = state,
        onIntent = { intent ->
            val result = UiReducer.reduce(state, intent)
            state = result.state
            result.effects.forEach(onEffect)
        },
    )
}

@Composable
private fun DeviceBanner(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    val deviceLine =
        state.device?.let { "${it.identity} · ${it.capacityLabel}" } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "USB Multitool",
                style = MaterialTheme.typography.titleLarge,
            )
            val themeLabel = when (state.darkTheme) {
                null -> "Theme: Auto"
                true -> "Theme: Dark"
                false -> "Theme: Light"
            }
            OutlinedButton(onClick = { onIntent(UiIntent.ToggleTheme) }) {
                Text(themeLabel, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            text = "${state.presence.name}: $deviceLine",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.statusMessage?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OperationBanner(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
) {
    val op = state.operation ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${op.state}: ${op.label}")
        if (op.bytesTotal > 0L) {
            LinearProgressIndicator(
                progress = { op.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${op.bytesDone} / ${op.bytesTotal} B · ${op.bytesPerSecond} B/s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (op.state == OperationState.Running || op.state == OperationState.Queued) {
            Row {
                TextButton(onClick = { onIntent(UiIntent.CancelOperation) }) {
                    Text("Cancel operation")
                }
            }
        }
    }
}

private fun ShellTab.title(): String =
    when (this) {
        ShellTab.Files -> "Files"
        ShellTab.Ventoy -> "Ventoy"
        ShellTab.Partitions -> "Partitions"
        ShellTab.Diagnostics -> "Diagnostics"
    }

private fun ShellTab.shortLabel(): String =
    when (this) {
        ShellTab.Files -> "F"
        ShellTab.Ventoy -> "V"
        ShellTab.Partitions -> "P"
        ShellTab.Diagnostics -> "D"
    }
