package usb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import usb.multitool.R
import java.util.Locale
import kotlin.math.roundToInt

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
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, state.language) {
        createLocalizedContext(baseContext, state.language)
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalResources provides localizedContext.resources,
    ) {
    MaterialTheme(colorScheme = colors) {
        var logExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(state.logLines.isEmpty()) {
            if (state.logLines.isEmpty()) logExpanded = false
        }
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                Column {
                    StatusLogPanel(
                        state = state,
                        onIntent = onIntent,
                        logExpanded = logExpanded,
                        onToggleLog = { logExpanded = !logExpanded },
                    )
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
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                AnimatedVisibility(
                    visible = logExpanded && state.logLines.isNotEmpty(),
                    enter = fadeIn() + slideInVertically { height -> height / 3 },
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    LogOverlay(
                        lines = state.logLines,
                        onClose = { logExpanded = false },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        state.pendingConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = { onIntent(UiIntent.DismissConfirmation) },
                title = { Text(stringResource(R.string.confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        confirmationLines(confirmation).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onIntent(UiIntent.ConfirmDestructive) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(UiIntent.DismissConfirmation) }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
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
            append(pluralStringResource(R.plurals.partitions_count, partCount, partCount))
            append(" · ")
            append(stringResource(R.string.header_ready))
        }
        DevicePresence.PermissionRequired -> stringResource(R.string.header_permission)
        DevicePresence.Busy -> stringResource(R.string.header_busy)
        DevicePresence.Disconnected -> stringResource(R.string.header_disconnected)
        DevicePresence.Absent -> stringResource(R.string.header_absent)
    }

    val totalSectors = device?.let { it.capacityBytes.toDouble() / state.diskSectorSize.toDouble() } ?: 0.0
    val usedSectors = state.partitions.sumOf { partition ->
        (partition.sectorCount ?: 0uL).toDouble()
    }
    val usedFraction =
        if (totalSectors > 0.0) (usedSectors / totalSectors).toFloat().coerceIn(0f, 1f) else null
    val usedPercent = ((usedFraction ?: 0f) * 100).roundToInt()
    val usedDescription = stringResource(R.string.header_used_accessibility, usedPercent)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device?.label ?: stringResource(R.string.app_name),
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

                LanguagePicker(language = state.language, onIntent = onIntent)
            }

            if (usedFraction != null && state.partitions.isNotEmpty() && usedFraction > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LinearProgressIndicator(
                        progress = { usedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .semantics {
                                contentDescription = usedDescription
                            },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.header_used_caption,
                            usedPercent,
                            pluralStringResource(R.plurals.partitions_count, partCount, partCount),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Collapsed status/operation line above the navigation bar. The operation log itself is
 * an overlay ([LogOverlay]) so expanding it never squeezes the active tab layout.
 */
@Composable
internal fun StatusLogPanel(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    logExpanded: Boolean,
    onToggleLog: () -> Unit,
) {
    val op = state.operation
    val overwrite = state.linkOverwriteCandidate
    val hasLog = state.logLines.isNotEmpty()
    if (op == null && state.statusMessage == null && !hasLog && overwrite == null) return

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
        op != null -> "${operationLabel(op)} · ${operationStateLabel(op.state)}"
        overwrite != null -> stringResource(R.string.status_overwrite_title)
        else -> stringResource(R.string.status_title)
    }
    val detail = when {
        op != null -> op.errorMessage ?: op.resultMessage
        overwrite != null -> stringResource(
            R.string.status_link_exists,
            overwrite.substringAfterLast('/'),
        )
        else -> state.statusMessage?.localized() ?: state.logLines.lastOrNull()
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
                            maxLines = if (logExpanded) 3 else 1,
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
                            text = stringResource(R.string.action_cancel),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = content,
                        )
                    }
                }
                if (hasLog) {
                    TextButton(
                        onClick = onToggleLog,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (logExpanded) R.string.log_collapse else R.string.log_open,
                            ),
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
                            text = stringResource(R.string.status_hide),
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
                            text = stringResource(R.string.status_hide),
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
                        text = stringResource(R.string.status_overwrite),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = content,
                    )
                }
            }
        }
    }
}

/**
 * Operation log shown over the active tab: no scrim and no container background, so the
 * user keeps context. Every line stays a real text node and the close action stays
 * reachable; the log keeps its own scroll and follows new lines.
 */
@Composable
private fun LogOverlay(
    lines: List<String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logScroll = rememberScrollState()
    LaunchedEffect(lines.lastOrNull()) {
        logScroll.animateScrollTo(logScroll.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            // Swallow taps that land on the transparent overlay itself so they cannot
            // reach buttons underneath; the log scroll and its own buttons still work.
            .pointerInput(Unit) { detectTapGestures { } },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.log_title_count, lines.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(
                onClick = onClose,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.log_collapse),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(logScroll),
        ) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Applies [language] without touching the process-wide locale. */
private fun createLocalizedContext(base: Context, language: AppLanguage): Context {
    val tag = when (language) {
        AppLanguage.System -> return base
        AppLanguage.Russian -> "ru"
        AppLanguage.English -> "en"
    }
    val config = Configuration(base.resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    return base.createConfigurationContext(config)
}

@Composable
private fun LanguagePicker(language: AppLanguage, onIntent: (UiIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.language_label)
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Text(languageCode(language), style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(languageName(option)) },
                    onClick = {
                        expanded = false
                        onIntent(UiIntent.SetLanguage(option))
                    },
                    trailingIcon = if (option == language) {
                        {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun languageCode(language: AppLanguage): String =
    when (language) {
        AppLanguage.System -> "AUTO"
        AppLanguage.Russian -> "RU"
        AppLanguage.English -> "EN"
    }

@Composable
private fun languageName(language: AppLanguage): String =
    when (language) {
        AppLanguage.System -> stringResource(R.string.language_system)
        AppLanguage.Russian -> stringResource(R.string.language_russian)
        AppLanguage.English -> stringResource(R.string.language_english)
    }

@Composable
private fun themeLabel(darkTheme: Boolean?): String =
    when (darkTheme) {
        null -> stringResource(R.string.theme_auto)
        true -> stringResource(R.string.theme_dark)
        false -> stringResource(R.string.theme_light)
    }

/**
 * Section block: bold heading, optional one-line subtitle, hairline divider, then content.
 * No container fill — hierarchy comes from typography, spacing and the divider, so nested
 * row surfaces stay the only backgrounds and no vertical space is wasted on padding.
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
    Column(modifier = modifier.fillMaxWidth()) {
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
                    )
                }
            }
            trailing?.invoke()
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            color = if (danger) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/**
 * Compact single-choice dropdown: one 48 dp row with the current value, full option list
 * on demand. Used where chips/segmented rows cost too much vertical space.
 */
@Composable
internal fun <T> CompactChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "choiceChevron",
    )
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "—"
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                label?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GlyphIcon(
                glyph = Glyph.ChevronDown,
                size = 20.dp,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    trailingIcon = if (value == selected) {
                        {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
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
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Short always-visible hint plus an info button with the longer explanation. Used where
 * the short text alone could mislead (write-protection policy, partition boundaries).
 * Both strings come from resources and the icon keeps a 48 dp target.
 */
@Composable
internal fun InfoHint(
    text: String,
    infoTitle: String,
    infoText: String,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),
        )
        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = infoTitle },
        ) {
            GlyphIcon(
                glyph = Glyph.Info,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(infoTitle) },
            text = { Text(infoText) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.info_close))
                }
            },
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
    Info,
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
                Glyph.Info -> {
                    drawCircle(tint, radius = 9f, style = stroke)
                    drawCircle(tint, radius = 1.4f, center = androidx.compose.ui.geometry.Offset(12f, 8f))
                    drawLine(
                        tint,
                        androidx.compose.ui.geometry.Offset(12f, 11f),
                        androidx.compose.ui.geometry.Offset(12f, 17f),
                        1.8f,
                    )
                }
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

@Composable
private fun ShellTab.title(): String =
    when (this) {
        ShellTab.Files -> stringResource(R.string.tab_files)
        ShellTab.Ventoy -> stringResource(R.string.tab_ventoy)
        ShellTab.Partitions -> stringResource(R.string.tab_partitions)
        ShellTab.Diagnostics -> stringResource(R.string.tab_diagnostics)
    }

@Composable
private fun TabIcon(tab: ShellTab) {
    // Provided vector icons keep their transparency and follow the theme content color;
    // the NavigationBarItem label already exposes the tab name to accessibility services.
    Icon(
        painter = painterResource(
            when (tab) {
                ShellTab.Files -> R.drawable.ic_tab_files
                ShellTab.Ventoy -> R.drawable.ic_tab_ventoy
                ShellTab.Partitions -> R.drawable.ic_tab_partitions
                ShellTab.Diagnostics -> R.drawable.ic_tab_diagnostics
            },
        ),
        contentDescription = null,
        tint = LocalContentColor.current,
        modifier = Modifier.size(24.dp),
    )
}
