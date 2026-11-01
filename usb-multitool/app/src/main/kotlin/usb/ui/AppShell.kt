package usb.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Four-tab shell over [AppUiState]. Talks only through [onIntent]; never opens USB.
 */
@Composable
fun AppShell(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
    onRequestDownload: (String) -> Unit = {},
    onPickBackup: () -> Unit = {},
) {
    val isDark = when (state.darkTheme) {
        true -> true
        false -> false
        null -> isSystemInDarkTheme()
    }
    val colors = if (isDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    ShellTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { onIntent(UiIntent.SelectTab(tab)) },
                            icon = { Text(tab.shortLabel(), fontWeight = FontWeight.Bold) },
                            label = { Text(tab.title()) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DeviceBanner(state, onIntent)
                OperationBanner(state, onIntent)
                when (state.tab) {
                    ShellTab.Files -> FilesScreen(
                        state = state,
                        onIntent = onIntent,
                        modifier = Modifier.weight(1f),
                        onRequestDownload = onRequestDownload,
                    )
                    ShellTab.Ventoy -> VentoyScreen(
                        state = state,
                        onIntent = onIntent,
                        modifier = Modifier.weight(1f),
                    )
                    ShellTab.Partitions -> PartitionsScreen(
                        state = state,
                        onIntent = onIntent,
                        modifier = Modifier.weight(1f),
                        onPickImage = onPickImage,
                    )
                    ShellTab.Diagnostics -> DiagnosticsScreen(
                        state = state,
                        onIntent = onIntent,
                        modifier = Modifier.weight(1f),
                        onPickBackup = onPickBackup,
                    )
                }
            }
        }

        state.pendingConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = { onIntent(UiIntent.DismissConfirmation) },
                title = { Text(confirmation.action) },
                text = { Text(confirmation.message) },
                confirmButton = {
                    Button(onClick = { onIntent(UiIntent.ConfirmDestructive) }) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(UiIntent.DismissConfirmation) }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
fun RememberedAppShell(
    initial: AppUiState = AppUiState(),
    onEffect: (UiEffect) -> Unit = {},
) {
    var state by remember { mutableStateOf(initial) }
    AppShell(
        state = state,
        onIntent = { intent ->
            val result = UiReducer.reduce(state, intent)
            state = result.state
            result.effects.forEach(onEffect)
        },
    )
}

@Composable
private fun DeviceBanner(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "USB Multitool",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val themeLabel = when (state.darkTheme) {
                    null -> "Auto"
                    true -> "Dark"
                    false -> "Light"
                }
                OutlinedButton(onClick = { onIntent(UiIntent.ToggleTheme) }) {
                    Text(themeLabel, style = MaterialTheme.typography.labelMedium)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            val device = state.device
            if (device != null && state.presence == DevicePresence.Ready) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = device.identity,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "Ready",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text(device.capacityLabel) },
                        )
                        val partCount = state.partitions.size
                        val partLabel = if (partCount == 1) "1 partition" else "$partCount partitions"
                        AssistChip(
                            onClick = {},
                            label = { Text(partLabel) },
                        )
                    }
                }
            } else {
                val statusText = when (state.presence) {
                    DevicePresence.PermissionRequired -> "Permission required"
                    DevicePresence.Busy -> "Device busy"
                    DevicePresence.Absent, DevicePresence.Disconnected -> "No USB drive connected"
                    else -> state.presence.name
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (state.presence == DevicePresence.PermissionRequired) {
                            "Grant USB permission in the Android dialog."
                        } else {
                            "Plug in a USB mass-storage device via OTG."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.statusMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationBanner(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
) {
    val op = state.operation ?: return
    var expanded by remember(op.label, op.state) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = op.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when (op.state) {
                            OperationState.Running -> MaterialTheme.colorScheme.primary
                            OperationState.Completed -> MaterialTheme.colorScheme.primaryContainer
                            OperationState.Failed -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(
                            text = op.state.name,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (op.state) {
                                OperationState.Running -> MaterialTheme.colorScheme.onPrimary
                                OperationState.Completed -> MaterialTheme.colorScheme.onPrimaryContainer
                                OperationState.Failed -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    if (op.state == OperationState.Completed || op.state == OperationState.Failed || op.state == OperationState.Cancelled) {
                        TextButton(onClick = { onIntent(UiIntent.OperationUpdated(null)) }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            if (op.bytesTotal > 0L && op.state == OperationState.Running) {
                LinearProgressIndicator(
                    progress = { op.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${(op.fraction * 100).toInt()}% (${DeviceIdentity.formatCapacity(op.bytesDone.toULong())} / ${DeviceIdentity.formatCapacity(op.bytesTotal.toULong())})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "${DeviceIdentity.formatCapacity(op.bytesPerSecond.toULong())}/s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            op.resultMessage?.let { res ->
                val lines = res.lines()
                val isMultiLine = lines.size > 2
                val displayText = if (isMultiLine && !expanded) lines.first() else res

                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )

                if (isMultiLine) {
                    TextButton(
                        onClick = { expanded = !expanded },
                    ) {
                        Text(if (expanded) "Hide details" else "Show details (${lines.size} items)")
                    }
                }
            }

            op.errorMessage?.let { err ->
                Text(
                    text = "Error: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (op.state == OperationState.Running || op.state == OperationState.Queued) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onIntent(UiIntent.CancelOperation) }) {
                        Text(
                            "Cancel operation",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun ShellTab.title(): String =
    when (this) {
        ShellTab.Files -> "Files"
        ShellTab.Ventoy -> "Ventoy"
        ShellTab.Partitions -> "Partitions"
        ShellTab.Diagnostics -> "Diagnostics"
    }

private fun ShellTab.shortLabel(): String =
    when (this) {
        ShellTab.Files -> "F"
        ShellTab.Ventoy -> "V"
        ShellTab.Partitions -> "P"
        ShellTab.Diagnostics -> "D"
    }
