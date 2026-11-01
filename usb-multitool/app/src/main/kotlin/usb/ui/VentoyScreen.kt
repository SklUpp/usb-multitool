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
fun VentoyScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ventoy")
        Text(
            "Install recreates the disk layout. Update keeps the ISO partition when the " +
                "existing layout is recognized. Windows setup links an ISO to an answer XML.",
        )

        if (!state.actionsEnabled) {
            Text(UiReducer.presenceMessage(state.presence))
        }

        Button(
            onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL)) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Install Ventoy")
        }

        OutlinedButton(
            onClick = {
                // Non-destructive update path still goes through status; install is the wipe.
                onIntent(UiIntent.Status("Ventoy update requested"))
            },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update Ventoy")
        }

        OutlinedButton(
            onClick = { onIntent(UiIntent.Status("Windows setup / link XML")) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Windows autoinstall setup")
        }
    }
}
