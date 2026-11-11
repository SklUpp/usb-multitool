package usb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import usb.fs.FilesystemSpace
import usb.fs.FilesystemUsage
import usb.image.ReadbackProblem
import usb.image.ReadbackVerdict
import usb.multitool.R
import java.util.Locale

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
    /** Opens the system file picker for a phone-to-stick import of [WindowsImportKind]. */
    onPickWindowsFile: (WindowsImportKind, String?) -> Unit = { _, _ -> },
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
    val colors = if (isDark) darkDesignColors() else lightDesignColors()
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, state.language) {
        createLocalizedContext(baseContext, state.language)
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalResources provides localizedContext.resources,
    ) {
    MaterialTheme(colorScheme = colors, shapes = AppShapes) {
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
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp,
                    ) {
                        ShellTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = state.tab == tab,
                                onClick = { onIntent(UiIntent.SelectTab(tab)) },
                                icon = { TabIcon(tab) },
                                label = {
                                    Text(
                                        text = tab.title(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
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
                        .padding(horizontal = 16.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DeviceHeader(state, onIntent)
                    AnimatedContent(
                        targetState = state.tab,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                        },
                        label = "tab",
                    ) { tab ->
                        when (tab) {
                            ShellTab.Files -> FilesScreen(
                                state = state,
                                onIntent = onIntent,
                                modifier = Modifier.fillMaxSize(),
                                onRequestDownload = onRequestDownload,
                            )
                            ShellTab.Ventoy -> VentoyScreen(
                                state = state,
                                onIntent = onIntent,
                                modifier = Modifier.fillMaxSize(),
                                onPickWindowsFile = onPickWindowsFile,
                            )
                            ShellTab.Partitions -> PartitionsScreen(
                                state = state,
                                onIntent = onIntent,
                                modifier = Modifier.fillMaxSize(),
                                onPickImage = onPickImage,
                            )
                            ShellTab.Diagnostics -> DiagnosticsScreen(
                                state = state,
                                onIntent = onIntent,
                                modifier = Modifier.fillMaxSize(),
                                onPickBackup = onPickBackup,
                                onPickRescueImage = onPickRescueImage,
                                onPickRescueMap = onPickRescueMap,
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = logExpanded && state.logLines.isNotEmpty(),
                    enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { it / 4 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 4 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    LogOverlay(
                        lines = state.logLines,
                        onClose = { logExpanded = false },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        state.pendingConfirmation?.let { confirmation ->
            // Explicit acknowledgement: the target board requires a checked box before the
            // destructive button becomes reachable. Reset per confirmation, never sticky.
            var acknowledged by remember(confirmation) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { onIntent(UiIntent.DismissConfirmation) },
                shape = RoundedCornerShape(28.dp),
                title = {
                    Text(
                        text = stringResource(R.string.confirm_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Normal,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Long confirmations (a full rescue-restore warning at RU lengths) do
                        // not fit the height-bounded dialog text slot. Scrolling the warning
                        // keeps the acknowledgement row and both buttons permanently visible.
                        Column(
                            // The acknowledgement row is mandatory for the destructive
                            // button. Making the warning the weighted child (fill = false)
                            // means the row is measured first and the warning scrolls inside
                            // whatever height is left, instead of pushing the row out of the
                            // height-bounded dialog slot at large font scales.
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            confirmationLines(confirmation).forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = acknowledged,
                                    role = Role.Checkbox,
                                    onValueChange = { acknowledged = it },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(checked = acknowledged, onCheckedChange = null)
                            Text(
                                text = stringResource(R.string.confirm_ack),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onIntent(UiIntent.ConfirmDestructive) },
                        enabled = acknowledged,
                    ) {
                        Text(
                            text = stringResource(R.string.action_confirm),
                            color = if (acknowledged) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                            fontWeight = FontWeight.Bold,
                        )
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
        }
        DevicePresence.PermissionRequired -> stringResource(R.string.header_permission)
        DevicePresence.Busy -> stringResource(R.string.header_busy)
        DevicePresence.Disconnected -> stringResource(R.string.header_disconnected)
        DevicePresence.Absent -> stringResource(R.string.header_absent)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device?.label ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Beside the title block, never inside it: the header carries no background of its
        // own (the boards use a flat header), so nothing behind these controls has to end.
        LanguagePicker(language = state.language, onIntent = onIntent)
        ThemeToggle(darkTheme = state.darkTheme, onIntent = onIntent)
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
    // Actions live on their own row: keeping them beside a two-line title squeezed the
    // operation name to roughly twenty characters on a 360 dp screen.
    val hasAction = hasLog || overwrite != null ||
        (op != null && !running) ||
        (op == null && state.statusMessage != null) ||
        (op != null && op.state == OperationState.Running && op.cancelable)
    // A failure must stay readable: error-on-inverseSurface is ~1.5:1 in both themes.
    val container = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.inverseSurface
    }
    val content = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.inverseOnSurface
    }
    val title = when {
        op != null -> "${operationLabel(op)} · ${operationStateLabel(op.state)}"
        overwrite != null -> stringResource(R.string.status_overwrite_title)
        else -> stringResource(R.string.status_title)
    }
    val detail = when {
        op != null -> op.errorMessage
            ?: op.readScanResult?.let { readScanSummary(it) }
            ?: op.resultMessage
        overwrite != null -> stringResource(
            R.string.status_link_exists,
            overwrite.substringAfterLast('/'),
        )
        else -> state.statusMessage?.localized() ?: state.logLines.lastOrNull()
    }

    // "Verified" is claimed only for Verified, and for a partial write only for the bytes that
    // were actually written.
    val readbackNote = op?.rawWriteResult?.let { result ->
        when (val verdict = result.readback) {
            ReadbackVerdict.Verified -> {
                val whole = result.writtenBytes.toULong() ==
                    result.range.byteLength(state.diskSectorSize)
                if (whole) {
                    stringResource(R.string.raw_readback_verified)
                } else {
                    stringResource(R.string.raw_readback_verified_partial, result.writtenBytes)
                }
            }
            is ReadbackVerdict.Mismatch -> stringResource(
                R.string.raw_readback_mismatch,
                verdict.mismatchedSectors.toLong(),
                verdict.firstMismatchLba.toLong(),
            )
            is ReadbackVerdict.Unverified -> stringResource(
                R.string.raw_readback_unverified,
                verdict.range.startLba.toLong(),
                verdict.range.sectorCount.toLong(),
            )
            is ReadbackVerdict.Incomplete -> stringResource(
                R.string.raw_readback_incomplete,
                stringResource(
                    when (verdict.reason) {
                        ReadbackProblem.Cancelled -> R.string.raw_readback_reason_cancelled
                        ReadbackProblem.SourceChanged -> R.string.raw_readback_reason_source_changed
                        ReadbackProblem.IoError -> R.string.raw_readback_reason_io_error
                    },
                ),
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .heightIn(min = 48.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    detail?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    readbackNote?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = content,
                            maxLines = 2,
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
            }
            if (hasAction) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
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
        }
    }
}

/**
 * Operation log shown over the active tab as a single overlay surface: the themed
 * background wraps the heading and every visible line, so the whole block grows and
 * scrolls together and stays readable over the tab content. The bottom navigation and
 * the tab layout are not displaced. Height is capped, the list keeps its own scroll,
 * and taps that land on the surface never reach the controls underneath.
 */
@Composable
private fun LogOverlay(
    lines: List<String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logList = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) logList.animateScrollToItem(lines.lastIndex)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            // Swallow taps that land on the overlay surface itself so they cannot reach
            // buttons underneath; the log scroll and its own buttons still work.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
            LazyColumn(
                state = logList,
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(lines.size) { index ->
                    Text(
                        text = lines[index],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
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
    val current = languageName(language)
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "$label: $current" },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_language),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** Cycles System → dark → light; the icon and its description follow the current value. */
@Composable
private fun ThemeToggle(darkTheme: Boolean?, onIntent: (UiIntent) -> Unit) {
    val name = themeLabel(darkTheme)
    val description = stringResource(R.string.theme_label) + ": " + name
    IconButton(
        onClick = { onIntent(UiIntent.ToggleTheme) },
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_theme),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

/** Design tokens of the target boards: flat background, card and row surfaces, one accent. */
private fun darkDesignColors() = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF0E1E4A),
    primaryContainer = Color(0xFF2A2A30), onPrimaryContainer = Color(0xFFE6E6EA),
    secondaryContainer = Color(0xFF2A2A30), onSecondaryContainer = Color(0xFFE6E6EA),
    tertiaryContainer = Color(0xFF2A2A30), onTertiaryContainer = Color(0xFFE6E6EA),
    background = Color(0xFF111113), onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF111113), onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF2A2A30), onSurfaceVariant = Color(0xFFA3A3AD),
    surfaceContainer = Color(0xFF1B1B1F),
    surfaceContainerHigh = Color(0xFF2A2A30),
    surfaceContainerHighest = Color(0xFF33333A),
    outline = Color(0xFF3A3A42), outlineVariant = Color(0xFF3A3A42),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6E6EA), inverseOnSurface = Color(0xFF111113),
)

private fun lightDesignColors() = lightColorScheme(
    primary = Color(0xFF2D55C8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E9EF), onPrimaryContainer = Color(0xFF1A1A1E),
    secondaryContainer = Color(0xFFE9E9EF), onSecondaryContainer = Color(0xFF1A1A1E),
    tertiaryContainer = Color(0xFFE9E9EF), onTertiaryContainer = Color(0xFF1A1A1E),
    background = Color(0xFFF7F7FA), onBackground = Color(0xFF1A1A1E),
    surface = Color(0xFFF7F7FA), onSurface = Color(0xFF1A1A1E),
    surfaceVariant = Color(0xFFE9E9EF), onSurfaceVariant = Color(0xFF5B5B66),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9E9EF),
    surfaceContainerHighest = Color(0xFFDCDCE4),
    outline = Color(0xFFD3D3DB), outlineVariant = Color(0xFFD3D3DB),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF1A1A1E), inverseOnSurface = Color(0xFFF7F7FA),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
)

/**
 * Real free space of [partition], or null unless the measured snapshot still describes this
 * exact attachment: same attach generation, device identity, start LBA, sector count, sector
 * size and filesystem. A reformatted partition or a re-plugged stick with the same model but no
 * serial therefore reports "unavailable" instead of stale numbers, and the table's free regions
 * are never substituted here.
 */
internal fun AppUiState.filesystemSpace(partition: PartitionTab): FilesystemSpace? {
    val snapshot = filesystemUsage ?: return null
    // The reducer owns the key shape; sharing it keeps row and measurement from drifting apart.
    val key = filesystemUsageKey(partition) ?: return null
    if (!snapshot.matches(key)) return null
    return (snapshot.usage as? FilesystemUsage.Known)?.space
}

/**
 * Readable name of a document URI for a source row: percent-decoded, last path segment,
 * document-id prefixes removed. Never claims the file was written — it only names it.
 */
internal fun rawSourceName(uri: String?): String? = uri
    ?.let { runCatching { android.net.Uri.decode(it) }.getOrNull() }
    ?.substringAfterLast('/')
    ?.substringAfterLast(':')
    ?.takeIf { it.isNotBlank() }

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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Uppercase must follow the in-app language, not the system one: a Turkish device
        // with the English UI would otherwise render "EFİ". LocalResources is the localized
        // context provided by AppShell, so its configuration carries the chosen language.
        val locale = LocalResources.current.configuration.locales[0]
        // Heading lives outside the card, like the target boards: an uppercase label above
        // its surface, separated from the content it names.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.uppercase(locale),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    color = if (danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics { heading() },
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = if (danger) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
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
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
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
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        InfoHelpButton(infoTitle = infoTitle, infoText = infoText)
    }
}

/**
 * 48 dp help target for a [SectionCard] `trailing` slot. The glyph is centred in the
 * button and in the section heading row, so it no longer floats at a different baseline.
 */
@Composable
internal fun InfoHelpButton(infoTitle: String, infoText: String) {
    var showInfo by remember { mutableStateOf(false) }
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
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(infoTitle) },
            text = {
                // Policy explanations run long at large font scales; the text slot does not
                // scroll by itself, so the paragraph keeps its own scroll.
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(infoText)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.info_close))
                }
            },
        )
    }
}

/** Small section badge; one implementation shared by every tab. */
@Composable
internal fun Badge(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
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
