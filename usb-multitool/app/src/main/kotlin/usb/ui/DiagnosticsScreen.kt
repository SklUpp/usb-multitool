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
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostics")
        Text(
            "Read scan never writes. Fake-capacity, wipe, and restore require confirmation " +
                "with device identity and capacity. Partial rescue stays marked partial.",
        )

        if (!state.actionsEnabled) {
            Text(UiReducer.presenceMessage(state.presence))
        }

        OutlinedButton(
            onClick = { onIntent(UiIntent.Status("Read scan requested")) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Read scan (non-destructive)")
        }

        OutlinedButton(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.FAKE_CAPACITY))
            },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Fake-capacity probe")
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

        OutlinedButton(
            onClick = { onIntent(UiIntent.Status("Backup with rescue map requested")) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Backup (rescue map)")
        }

        Button(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.BACKUP_RESTORE))
            },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Restore from backup")
        }
    }
}
