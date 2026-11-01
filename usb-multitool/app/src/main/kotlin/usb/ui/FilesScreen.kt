package usb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FilesScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Partitions")
        if (state.partitions.isEmpty()) {
            Text(UiReducer.presenceMessage(state.presence))
            return
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.partitions.forEach { partition ->
                FilterChip(
                    selected = partition.id == state.selectedPartitionId,
                    onClick = { onIntent(UiIntent.SelectPartition(partition.id)) },
                    label = {
                        Text(
                            if (partition.isVtoyEfi) {
                                "${partition.name} (EFI)"
                            } else {
                                partition.name
                            },
                        )
                    },
                )
            }
        }

        val selected = state.selectedPartition()
        if (selected?.isVtoyEfi == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow VTOYEFI writes")
                Switch(
                    checked = state.vtoyEfiWriteEnabled,
                    onCheckedChange = { onIntent(UiIntent.SetVtoyEfiWritable(it)) },
                    enabled = state.actionsEnabled,
                )
            }
            if (!state.vtoyEfiWriteEnabled) {
                Text("VTOYEFI is read-only until write is enabled.")
            }
        }

        if (!state.actionsEnabled) {
            Text(UiReducer.presenceMessage(state.presence))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.files, key = { it.name }) { entry ->
                FileRow(entry, state.actionsEnabled, onIntent)
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    actionsEnabled: Boolean,
    onIntent: (UiIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (entry.isDirectory) {
                "${entry.name}/"
            } else {
                "${entry.name} · ${DeviceIdentity.formatCapacity(entry.sizeBytes)}"
            },
        )
        if (entry.isIso) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        onIntent(UiIntent.IsoActionRequested(entry.name, IsoAction.LinkXml))
                    },
                    enabled = actionsEnabled,
                ) {
                    Text("Link XML")
                }
                OutlinedButton(
                    onClick = {
                        onIntent(
                            UiIntent.IsoActionRequested(entry.name, IsoAction.SendToRawWriter),
                        )
                    },
                    enabled = actionsEnabled,
                ) {
                    Text("Send to raw writer")
                }
            }
        }
    }
}
