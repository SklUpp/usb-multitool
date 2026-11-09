package usb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickBackup: () -> Unit = {},
    onPickRescueImage: () -> Unit = {},
    onPickRescueMap: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // P1 Block: Automated Engine Self-Test
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Automated Engine Self-Test",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "End-to-end self-test of SCSI command encoders, partition geometry math, 4K/512B GPT backup calculations, ddrescue mapfile serialization, and native filesystem drivers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Button(
                    onClick = { onIntent(UiIntent.RunSelfTest) },
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Run Automated Self-Test")
                }
            }
        }

        // P1 Block: Non-Destructive Inspection
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Non-Destructive Inspection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Safely read and examine the storage device without writing or modifying any sectors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // List-style actions: compact rows instead of full-width buttons
                val enabled = state.actionsEnabled
                listOf(
                    Triple("Read Scan", "Detect unreadable sectors (read-only)", enabled) to
                        { onIntent(UiIntent.StartReadScan) },
                    Triple("Backup Image + Map", "ddrescue-style copy to external storage", enabled) to
                        { onIntent(UiIntent.StartRescueBackup) },
                ).forEach { (meta, action) ->
                    val (label, hint, isEnabled) = meta
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isEnabled) 0.5f else 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isEnabled, onClick = action),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(hint, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // Rescue restore: image + map pickers in one compact row
                Text("Restore from rescue image", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Restore writes the dense image back. Geometry must match. " +
                        "Resume skips copied sectors only when the map identity matches; otherwise a full rewrite is required. " +
                        "Partial COPY maps are refused; FAILED sectors become zeros.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clickable(onClick = onPickRescueImage),
                    ) {
                        Text(
                            text = state.rescueImageUri?.substringAfterLast('/') ?: "Choose image…",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clickable(onClick = onPickRescueMap),
                    ) {
                        Text(
                            text = state.rescueMapUri?.substringAfterLast('/') ?: "Choose map…",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Button(
                    onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE)) },
                    enabled = state.actionsEnabled && state.rescueImageUri != null && state.rescueMapUri != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore Rescue Image") }
            }
        }

        // P2 Block: GPT Backup & Recovery
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "GPT Backup & Recovery",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Restore critical primary and backup GPT structures from a previously captured file.",
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
                            text = state.backupUri?.let { it.substringAfterLast('/') }
                                ?: "No GPT backup selected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (state.backupUri != null) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                OutlinedButton(
                    onClick = { onIntent(UiIntent.CaptureGptBackup) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back Up GPT") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onPickBackup, modifier = Modifier.weight(1f)) { Text("Choose File") }
                    Button(
                        onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE)) },
                        enabled = state.actionsEnabled && state.backupUri != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Restore GPT") }
                }
            }
        }

        // P3 Danger Zone: Device Wipe only (fixed-LBA write benchmark removed).
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Danger Zone: Device Wipe",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "Warning: Wipe erases every sector on the attached USB device permanently.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
                Button(
                    onClick = {
                        onIntent(UiIntent.RequestDestructive(DestructiveAction.WIPE))
                    },
                    enabled = state.actionsEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Wipe Device (Zero-Fill Entire Drive)")
                }
            }
        }

        // Operation log — bounded ring buffer; shown only when non-empty.
        if (state.logLines.isNotEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Operation Log",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    val scrollState = rememberScrollState()
                    androidx.compose.runtime.LaunchedEffect(state.logLines.lastOrNull()) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(scrollState),
                    ) {
                        for (line in state.logLines) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
