package usb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Four-tab shell over [AppUiState]. Talks only through [onIntent]; never opens USB.
 *
 * Layout contract:
 * - top: one compact, height-unbounded device header (no max-height clipping);
 * - middle: the active tab (weight 1, own scroll);
 * - bottom: one non-interrupting status/log surface plus the navigation bar.
 */
@Composable
fun AppShell(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
    onRequestDownload: (String) -> Unit = {},
    onPickBackup: () -> Unit = {},
    onPickRescueImage: () -> Unit = {},
    onPickRescueMap: () -> Unit = {},
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
                Column {
                    StatusLogPanel(state, onIntent)
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
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DeviceHeader(state, onIntent)
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
                        onPickRescueImage = onPickRescueImage,
                        onPickRescueMap = onPickRescueMap,
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
                    Button(
                        onClick = { onIntent(UiIntent.ConfirmDestructive) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("Подтвердить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(UiIntent.DismissConfirmation) }) {
                        Text("Отмена")
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
 * Compact top device status header. Height is content-driven — no `heightIn(max)` that
 * could clip a wrapped subtitle or the progress strip on small/landscape screens.
 */
@Composable
private fun DeviceHeader(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    val device = state.device
    val partCount = state.partitions.size
    val subtitle = when (state.presence) {
        DevicePresence.Ready -> buildString {
            append(device?.capacityLabel ?: "USB")
            append(" · ")
            append(partitionsLabel(partCount))
            append(" · Готово")
        }
        DevicePresence.PermissionRequired -> "Нужно разрешение USB"
        DevicePresence.Busy -> "Устройство занято"
        DevicePresence.Disconnected -> "Устройство отключено"
        DevicePresence.Absent -> "Накопитель не подключён"
    }

    val totalSectors = device?.let { it.capacityBytes.toDouble() / state.diskSectorSize.toDouble() } ?: 0.0
    val usedSectors = state.partitions.sumOf { partition ->
        (partition.sectorCount ?: 0uL).toDouble()
    }
    val usedFraction =
        if (totalSectors > 0.0) (usedSectors / totalSectors).toFloat().coerceIn(0f, 1f) else null

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device?.label ?: "USB Multitool",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = subtitle,
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

                OutlinedButton(
                    onClick = { onIntent(UiIntent.ToggleTheme) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(themeLabel(state.darkTheme), style = MaterialTheme.typography.labelSmall)
                }
            }

            if (usedFraction != null) {
                LinearProgressIndicator(
                    progress = { usedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

/**
 * Single non-interrupting status + operation + log surface. Sits above the navigation
 * bar, never covers tab content with a dialog, and collapses to one line.
 */
@Composable
internal fun StatusLogPanel(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    val op = state.operation
    val overwrite = state.linkOverwriteCandidate
    val hasLog = state.logLines.isNotEmpty()
    if (op == null && state.statusMessage == null && !hasLog && overwrite == null) return

    var expanded by remember(hasLog) { mutableStateOf(false) }

    val failed = op?.state == OperationState.Failed
    val running = op?.state == OperationState.Running || op?.state == OperationState.Queued
    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        running -> MaterialTheme.colorScheme.primaryContainer
        overwrite != null -> MaterialTheme.colorScheme.tertiaryContainer
        op != null -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val content = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        running -> MaterialTheme.colorScheme.onPrimaryContainer
        overwrite != null -> MaterialTheme.colorScheme.onTertiaryContainer
        op != null -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when {
        op != null -> "${op.label} · ${operationStateLabel(op.state)}"
        overwrite != null -> "Перезапись связи Ventoy"
        else -> "Статус"
    }
    val detail = when {
        op != null -> op.errorMessage ?: op.resultMessage
        overwrite != null ->
            "Ссылка уже существует для ${overwrite.substringAfterLast('/')}. " +
                "Нужно явное подтверждение перезаписи."
        else -> state.statusMessage ?: state.logLines.lastOrNull()
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    detail?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.85f),
                            maxLines = if (expanded) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (op != null && op.bytesTotal > 0L) {
                    Text(
                        text = "${(op.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = content,
                    )
                }
                if (op != null && op.state == OperationState.Running && op.cancelable) {
                    TextButton(
                        onClick = { onIntent(UiIntent.CancelOperation) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Отмена",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = content,
                        )
                    }
                }
                if (hasLog) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (expanded) "Свернуть" else "Журнал",
                            style = MaterialTheme.typography.labelSmall,
                            color = content,
                        )
                    }
                }
                if (op != null && !running) {
                    TextButton(
                        onClick = { onIntent(UiIntent.OperationUpdated(null)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Скрыть",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = content,
                        )
                    }
                } else if (op == null && state.statusMessage != null) {
                    TextButton(
                        onClick = { onIntent(UiIntent.Status(null)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Скрыть",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = content,
                        )
                    }
                }
            }

            if (op != null && running && op.bytesTotal > 0L) {
                LinearProgressIndicator(
                    progress = { op.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = content.copy(alpha = 0.2f),
                )
            }

            if (overwrite != null) {
                TextButton(
                    onClick = { onIntent(UiIntent.RequestLinkOverwrite(overwrite)) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Перезаписать связь",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = content,
                    )
                }
            }

            if (hasLog && expanded) {
                val logScroll = rememberScrollState()
                LaunchedEffect(state.logLines.lastOrNull()) {
                    logScroll.animateScrollTo(logScroll.maxValue)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(logScroll),
                ) {
                    state.logLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

private fun themeLabel(darkTheme: Boolean?): String =
    when (darkTheme) {
        null -> "Авто"
        true -> "Тёмная"
        false -> "Светлая"
    }

private fun operationStateLabel(state: OperationState): String =
    when (state) {
        OperationState.Queued -> "в очереди"
        OperationState.Running -> "выполняется"
        OperationState.Completed -> "готово"
        OperationState.Cancelled -> "отменено"
        OperationState.Failed -> "ошибка"
        OperationState.Partial -> "частично"
    }

internal fun partitionsLabel(count: Int): String =
    when (count % 10) {
        1 -> if (count % 100 == 11) "$count разделов" else "$count раздел"
        2, 3, 4 -> if (count % 100 in 12..14) "$count разделов" else "$count раздела"
        else -> "$count разделов"
    }

/**
 * Section container shared by all tabs: one M3 surface, optional error border.
 */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = if (danger) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f))
        } else {
            null
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

/**
 * Weighted segmented single-choice control (format picker). Radio semantics.
 */
@Composable
internal fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .selectableGroup()
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    ),
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    hint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Decorative vector glyphs; callers provide adjacent text labels. */
internal enum class Glyph {
    Folder,
    File,
    Iso,
    Usb,
    Shield,
    Scan,
    Copy,
    Gpt,
    Pulse,
    Plus,
    ChevronDown,
    ChevronUp,
    ChevronLeft,
}

@Composable
internal fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { },
    ) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (glyph) {
                Glyph.Folder -> drawPath(
                    Path().apply {
                        moveTo(3f, 6f); lineTo(10f, 6f); lineTo(12f, 8f); lineTo(21f, 8f)
                        lineTo(21f, 19f); lineTo(3f, 19f); close()
                    },
                    tint,
                    style = stroke,
                )
                Glyph.File -> {
                    drawPath(
                        Path().apply {
                            moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f)
                            lineTo(19f, 21f); lineTo(6f, 21f); close()
                        },
                        tint,
                        style = stroke,
                    )
                    drawPath(
                        Path().apply { moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f) },
                        tint,
                        style = stroke,
                    )
                }
                Glyph.Iso -> {
                    drawCircle(tint, radius = 8f, style = stroke)
                    drawCircle(tint, radius = 2.5f)
                }
                Glyph.Usb -> {
                    drawPath(
                        Path().apply {
                            moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 8f); lineTo(18f, 8f)
                            lineTo(18f, 21f); lineTo(6f, 21f); lineTo(6f, 8f); lineTo(9f, 8f); close()
                        },
                        tint,
                        style = stroke,
                    )
                    drawLine(tint, androidx.compose.ui.geometry.Offset(11f, 11f), androidx.compose.ui.geometry.Offset(11f, 17f), 1.8f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(13f, 11f), androidx.compose.ui.geometry.Offset(13f, 17f), 1.8f)
                }
                Glyph.Shield -> drawPath(
                    Path().apply {
                        moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 11f); lineTo(12f, 21f)
                        lineTo(4f, 11f); lineTo(4f, 6f); close()
                    },
                    tint,
                    style = stroke,
                )
                Glyph.Scan -> {
                    drawPath(
                        Path().apply {
                            moveTo(4f, 8f); lineTo(4f, 4f); lineTo(8f, 4f)
                            moveTo(16f, 4f); lineTo(20f, 4f); lineTo(20f, 8f)
                            moveTo(20f, 16f); lineTo(20f, 20f); lineTo(16f, 20f)
                            moveTo(8f, 20f); lineTo(4f, 20f); lineTo(4f, 16f)
                        },
                        tint,
                        style = stroke,
                    )
                    drawLine(tint, androidx.compose.ui.geometry.Offset(7f, 12f), androidx.compose.ui.geometry.Offset(17f, 12f), 1.8f)
                }
                Glyph.Copy -> {
                    drawPath(
                        Path().apply { moveTo(8f, 8f); lineTo(20f, 8f); lineTo(20f, 20f); lineTo(8f, 20f); close() },
                        tint,
                        style = stroke,
                    )
                    drawPath(
                        Path().apply {
                            moveTo(16f, 8f); lineTo(16f, 4f); lineTo(4f, 4f)
                            lineTo(4f, 16f); lineTo(8f, 16f)
                        },
                        tint,
                        style = stroke,
                    )
                }
                Glyph.Gpt -> {
                    drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(3f, 5f), size = androidx.compose.ui.geometry.Size(18f, 14f), style = stroke)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(10f, 5f), androidx.compose.ui.geometry.Offset(10f, 19f), 1.8f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(16f, 5f), androidx.compose.ui.geometry.Offset(16f, 19f), 1.8f)
                }
                Glyph.Pulse -> drawPath(
                    Path().apply {
                        moveTo(3f, 12f); lineTo(7f, 12f); lineTo(9f, 7f); lineTo(12f, 17f)
                        lineTo(15f, 9f); lineTo(17f, 12f); lineTo(21f, 12f)
                    },
                    tint,
                    style = stroke,
                )
                Glyph.Plus -> {
                    drawLine(tint, androidx.compose.ui.geometry.Offset(12f, 5f), androidx.compose.ui.geometry.Offset(12f, 19f), 1.8f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(5f, 12f), androidx.compose.ui.geometry.Offset(19f, 12f), 1.8f)
                }
                Glyph.ChevronDown -> drawPath(
                    Path().apply { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) },
                    tint,
                    style = stroke,
                )
                Glyph.ChevronUp -> drawPath(
                    Path().apply { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) },
                    tint,
                    style = stroke,
                )
                Glyph.ChevronLeft -> drawPath(
                    Path().apply { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) },
                    tint,
                    style = stroke,
                )
            }
        }
    }
}

private fun ShellTab.title(): String =
    when (this) {
        ShellTab.Files -> "Файлы"
        ShellTab.Ventoy -> "Ventoy"
        ShellTab.Partitions -> "Разделы"
        ShellTab.Diagnostics -> "Диагностика"
    }

@Composable
private fun TabIcon(tab: ShellTab) {
    val glyph = when (tab) {
        ShellTab.Files -> Glyph.Folder
        ShellTab.Ventoy -> Glyph.Usb
        ShellTab.Partitions -> Glyph.Gpt
        ShellTab.Diagnostics -> Glyph.Pulse
    }
    // The NavigationBarItem label already exposes the tab name to accessibility services.
    GlyphIcon(glyph, size = 24.dp)
}
