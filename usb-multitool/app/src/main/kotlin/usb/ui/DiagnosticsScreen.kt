package usb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickBackup: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostics")
        Text(
            "Read scan and rescue backup only read the device; the rescue image and its map " +
                "land in this app's external files directory. Wipe and GPT restore require " +
                "confirmation with device identity and capacity. Partial rescue stays marked " +
                "partial.",
        )

        Button(
            onClick = { onIntent(UiIntent.RunSelfTest) },
            enabled = !state.isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Run automated self-test")
        }

        if (!state.actionsEnabled) {
            Text(UiReducer.presenceMessage(state.presence))
        }

        OutlinedButton(
            onClick = { onIntent(UiIntent.StartReadScan) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Read scan (non-destructive)")
        }

        OutlinedButton(
            onClick = { onIntent(UiIntent.StartRescueBackup) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Backup image + rescue map (read-only)")
        }

        OutlinedButton(
            onClick = { onIntent(UiIntent.CaptureGptBackup) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back up GPT critical sectors")
        }

        OutlinedButton(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.WRITE_BENCHMARK))
            },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Write throughput benchmark")
        }

        Button(
            onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.WIPE)) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Wipe device")
        }

        Text(
            state.backupUri?.let { "GPT backup: ${it.substringAfterLast('/')}" }
                ?: "No GPT backup selected",
        )

        OutlinedButton(
            onClick = onPickBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Choose GPT backup file")
        }

        Button(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
            },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Restore GPT backup")
        }
    }
}
