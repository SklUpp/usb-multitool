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
            "Install recreates the disk layout. Update keeps the ISO partition and only refreshes " +
                "boot.img, core.img and VTOYEFI. Server download fetches the pinned release and " +
                "verifies every digest before writing anything.",
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
            onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_UPDATE)) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update installed Ventoy")
        }

        OutlinedButton(
            onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_DOWNLOAD_INSTALL)) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Download and install from server")
        }

        Text("Windows unattended setup")
        val isos = state.files.filter { it.isIso }
        if (isos.isEmpty()) {
            Text(
                "No .iso on the Ventoy partition. Copy an ISO into /ISO, refresh the partition list, " +
                    "then link it to an autounattend.xml here.",
            )
        } else {
            isos.forEach { iso ->
                val baseName = iso.name.substringAfterLast('/')
                OutlinedButton(
                    onClick = { onIntent(UiIntent.IsoActionRequested(iso.name, IsoAction.LinkXml)) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Link autounattend.xml for $baseName")
                }
            }
        }
    }
}
