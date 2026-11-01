package usb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickBackup: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Block 1: Automated Self-Test
        ElevatedCard(
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
                    text = "🩺 Automated Engine Self-Test",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "End-to-end self-test of SCSI command encoders, partition geometry math, 4K/512B GPT backup calculations, ddrescue mapfile serialization, and native filesystem drivers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Button(
                    onClick = { onIntent(UiIntent.RunSelfTest) },
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Run Automated Self-Test")
                }
            }
        }

        // Block 2: Non-Destructive Inspection
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
                    text = "🔍 Non-Destructive Inspection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Safely read and examine the storage device without writing or modifying any sectors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                OutlinedButton(
                    onClick = { onIntent(UiIntent.StartReadScan) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Read Scan (Detect Unreadable Sectors)")
                }

                OutlinedButton(
                    onClick = { onIntent(UiIntent.RescueWholeDisk) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Backup Image + Rescue Map (ddrescue Copy)")
                }

                OutlinedButton(
                    onClick = { onIntent(UiIntent.CaptureGptBackup) },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Back Up GPT Critical Sectors")
                }
            }
        }

        // Block 3: GPT Backup & Restore
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
                    text = "🛡️ GPT Backup & Recovery",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Restore critical primary and backup GPT structures from a previously captured file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.backupUri?.let { "📁 ${it.substringAfterLast('/')}" }
                            ?: "No GPT backup selected",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (state.backupUri != null) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onPickBackup,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Choose Backup")
                    }

                    Button(
                        onClick = {
                            onIntent(UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
                        },
                        enabled = state.actionsEnabled && state.backupUri != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Restore GPT")
                    }
                }
            }
        }

        // Block 4: Dangerous Operations & Wipe
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
                    text = "⚠️ Benchmarks & Device Wipe",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Warning: Write operations alter disk sectors directly and require explicit confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                OutlinedButton(
                    onClick = {
                        onIntent(UiIntent.RequestDestructive(DestructiveAction.WRITE_BENCHMARK))
                    },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Write Throughput Benchmark")
                }

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
    }
}
