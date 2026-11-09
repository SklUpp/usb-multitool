package usb.ui

import usb.fs.FormatCatalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // P1 Block: Partition Table Management
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
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        val count = state.partitions.size
                        Text(
                            text = if (count == 1) "1 partition" else "$count partitions",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = "Inspect and edit partition structures. Create new partitions in unallocated space, format filesystems, or restore corrupted backup GPT headers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                if (state.partitions.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (!state.tableValid) (state.statusMessage ?: "Invalid partition table") else UiReducer.presenceMessage(state.presence),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!state.tableValid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.partitions.forEach { partition ->
                            val isSelected = partition.id == state.selectedPartitionId
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
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
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = (if (partition.readOnly) "Read-only" else "Read/write") +
                                            if (partition.isVtoyEfi) " · EFI" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // Partition action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.CREATE_PARTITION))
                        },
                        enabled = state.actionsEnabled && state.tableValid,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Create")
                    }

                    OutlinedButton(
                        onClick = {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION))
                        },
                        enabled = state.actionsEnabled && state.selectedPartitionId != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Delete")
                    }
                }

                Text(
                    text = "Format filesystem",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FormatCatalog.supportedFormatIds().forEach { id ->
                        FilterChip(
                            selected = state.selectedFormatId == id,
                            onClick = { onIntent(UiIntent.SelectFormatFilesystem(id)) },
                            enabled = state.actionsEnabled,
                            label = { Text(FormatCatalog.label(id)) },
                        )
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
                        enabled = state.actionsEnabled && state.selectedPartitionId != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Format as ${FormatCatalog.label(state.selectedFormatId)}")
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

        // P2 Block: Raw Image Flasher (Dangerous Direct LBA Write)
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
                    text = "Sector-by-sector write for OS disk images (.iso, .img, .gz, .xz). Verifies image bounds before writing directly to USB storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.stagedRawImageLabel
                                ?: state.imageUri?.let { it.substringAfterLast('/') }
                                ?: "No image selected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (state.imageUri != null) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(
                            onClick = onPickImage,
                            enabled = state.actionsEnabled,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("Choose Image", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Button(
                    onClick = {
                        if (state.imageUri == null && state.stagedRawImagePath == null) {
                            onPickImage()
                        } else {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE))
                        }
                    },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.stagedRawImagePath != null) {
                            "Write Staged Image to USB"
                        } else {
                            "Write Raw Image to USB"
                        },
                    )
                }
                if (state.stagedRawImagePath != null) {
                    val stagingBusy = state.operation?.state == OperationState.Running ||
                        state.operation?.state == OperationState.Queued
                    TextButton(
                        onClick = { onIntent(UiIntent.ClearStagedRawImage(deleteFile = true)) },
                        enabled = !stagingBusy,
                    ) {
                        Text("Discard staged image")
                    }
                }
            }
        }
    }
}
