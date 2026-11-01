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
fun PartitionsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Partitions / Raw")
        Text(
            "Create partitions only in free space. Format is a separate confirmed step. " +
                "Raw write accepts .iso / .img / .gz / .xz after size checks.",
        )

        if (state.partitions.isEmpty()) {
            Text(UiReducer.presenceMessage(state.presence))
        } else {
            state.partitions.forEach { partition ->
                val mode = if (partition.readOnly) "RO" else "RW"
                Text("• ${partition.name} [$mode]")
            }
        }

        if (!state.actionsEnabled) {
            Text(UiReducer.presenceMessage(state.presence))
        }

        OutlinedButton(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.CREATE_PARTITION))
            },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create partition in free space")
        }

        OutlinedButton(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
            },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete selected partition")
        }

        OutlinedButton(
            onClick = {
                onIntent(UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
            },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Format selected partition")
        }

        OutlinedButton(
            onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_REPAIR)) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Repair GPT backup")
        }

        Button(
            onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE)) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Write raw image")
        }
    }
}
