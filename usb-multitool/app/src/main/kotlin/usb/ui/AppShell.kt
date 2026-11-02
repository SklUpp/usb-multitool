package usb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                            icon = { TabIcon(tab) },
                            label = { Text(tab.title()) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeviceHeader(state, onIntent)
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

/**
 * Compact top device status header: P0 context without eating screen height.
 */
@Composable
private fun DeviceHeader(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    val device = state.device
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = device?.identity ?: "USB Multitool",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (device != null && state.presence == DevicePresence.Ready) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = device.capacityLabel,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                val subtitle = when {
                    device != null && state.presence == DevicePresence.Ready -> {
                        val partCount = state.partitions.size
                        val partText = if (partCount == 1) "1 partition" else "$partCount partitions"
                        "$partText · Ready"
                    }
                    state.presence == DevicePresence.PermissionRequired -> "Permission required"
                    state.presence == DevicePresence.Busy -> "Device busy"
                    else -> "No USB drive connected"
                }

                Text(
                    text = state.statusMessage ?: subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.presence == DevicePresence.PermissionRequired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val themeLabel = when (state.darkTheme) {
                null -> "Auto"
                true -> "Dark"
                false -> "Light"
            }
            OutlinedButton(
                onClick = { onIntent(UiIntent.ToggleTheme) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(themeLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Compact, prioritized operation status bar.
 * P0 focus during execution; unobtrusive dismissable summary upon completion.
 */
@Composable
private fun OperationBanner(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
) {
    val op = state.operation ?: return
    var expanded by remember(op.label, op.state) { mutableStateOf(false) }

    if (op.state == OperationState.Running) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = op.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (op.bytesTotal > 0L) {
                        Text(
                            text = "${(op.fraction * 100).toInt()}% · ${DeviceIdentity.formatCapacity(op.bytesPerSecond.toULong())}/s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (op.bytesTotal > 0L) {
                    LinearProgressIndicator(
                        progress = { op.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (op.state == OperationState.Failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${op.label}: ${op.state.name}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (op.state == OperationState.Failed) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        )
                        op.resultMessage?.let { res ->
                            val lines = res.lines()
                            val isMultiLine = lines.size > 2
                            val displayText = if (isMultiLine && !expanded) lines.first() else res

                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (expanded) 8 else 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (op.state == OperationState.Failed) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        op.resultMessage?.let { res ->
                            if (res.lines().size > 2) {
                                TextButton(
                                    onClick = { expanded = !expanded },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        if (expanded) "Less" else "Details",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        if (op.state == OperationState.Completed || op.state == OperationState.Failed || op.state == OperationState.Cancelled) {
                            TextButton(
                                onClick = { onIntent(UiIntent.OperationUpdated(null)) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                            }
                        }
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

@Composable
private fun TabIcon(tab: ShellTab) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val path = Path().apply {
                when (tab) {
                    ShellTab.Files -> {
                        moveTo(3f, 6f); lineTo(10f, 6f); lineTo(12f, 8f); lineTo(21f, 8f)
                        lineTo(21f, 19f); lineTo(3f, 19f); close()
                    }
                    ShellTab.Ventoy -> {
                        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 8f); lineTo(18f, 8f)
                        lineTo(18f, 21f); lineTo(6f, 21f); lineTo(6f, 8f); lineTo(9f, 8f); close()
                        moveTo(11f, 5f); lineTo(11f, 6f); moveTo(13f, 5f); lineTo(13f, 6f)
                    }
                    ShellTab.Partitions -> {
                        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
                        moveTo(10f, 5f); lineTo(10f, 19f); moveTo(16f, 5f); lineTo(16f, 19f)
                    }
                    ShellTab.Diagnostics -> {
                        moveTo(3f, 12f); lineTo(7f, 12f); lineTo(9f, 7f); lineTo(12f, 17f)
                        lineTo(15f, 9f); lineTo(17f, 12f); lineTo(21f, 12f)
                    }
                }
            }
            drawPath(path, color, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
