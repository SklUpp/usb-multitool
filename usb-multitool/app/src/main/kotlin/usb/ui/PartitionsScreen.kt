package usb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PartitionsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Block 1: Partition Management
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Partition Table",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${state.partitions.size} detected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Manage MBR/GPT partition tables. Create partitions in free space, format filesystems, or repair backup GPT headers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (state.partitions.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = UiReducer.presenceMessage(state.presence),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.partitions.forEach { partition ->
                            val isSelected = partition.id == state.selectedPartitionId
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = state.actionsEnabled) {
                                        onIntent(UiIntent.SelectPartition(partition.id))
                                    },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (isSelected) "${partition.name} [Selected]" else partition.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(if (partition.readOnly) "RO" else "RW") },
                                        )
                                        if (partition.isVtoyEfi) {
                                            AssistChip(
                                                onClick = {},
                                                label = { Text("EFI") },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.CREATE_PARTITION))
                        },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Create")
                    }

                    OutlinedButton(
                        onClick = {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
                        },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Delete")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION))
                        },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Format")
                    }

                    OutlinedButton(
                        onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_REPAIR)) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Repair GPT")
                    }
                }
            }
        }

        // Block 2: Raw Image Flasher
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Raw Image Flasher",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Sector-by-sector write for OS disk images (.iso, .img, .gz, .xz). Verifies image bounds before writing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.imageUri?.let { it.substringAfterLast('/') }
                                ?: "No image selected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (state.imageUri != null) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onPickImage) {
                            Text("Choose Image")
                        }
                    }
                }

                Button(
                    onClick = {
                        if (state.imageUri == null) {
                            onPickImage()
                        } else {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE))
                        }
                    },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Write Raw Image to USB")
                }
            }
        }
    }
}
