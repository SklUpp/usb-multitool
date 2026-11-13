package usb.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
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
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
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
import usb.service.OperationToken
import java.util.Locale

/**
 * Four-tab shell over [AppUiState]. Talks only through [onIntent]; never opens USB.
 *
 * Layout contract:
 * - safe drawing insets bound the whole shell (targetSdk 36 draws edge to edge), so neither
 *   the header, the tabs, the status strip nor the log layer touches the system bars;
 * - top: one compact device header whose title/subtitle surface is separate from the
 *   language, theme and log controls (no max-height clipping);
 * - middle: the active tab (weight 1, own scroll), always composed; a horizontal swipe
 *   selects the adjacent tab through the reducer and the content slides in the tab
 *   direction (RTL included). A child that consumes the drag keeps it; once the shell
 *   qualifies its own gesture it consumes the drag so a clickable row is not tapped, and
 *   a gesture starting in a system back edge is ignored. The full log screen is an opaque
 *   layer above it and the system bottom inset is guarded while it shows; the covered tab
 *   is marked invisible to accessibility but keeps its state;
 * - bottom: one status surface that folds to a thin title strip attached to the navigation
 *   bar (fold only hides it visually, never clears the operation) and, independently, an
 *   inline journal list; plus the navigation bar.
 */
@Composable
fun AppShell(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
    /** Opens the system file picker for a phone-to-stick import of [WindowsImportKind]. */
    onPickWindowsFile: (WindowsImportKind, String?) -> Unit = { _, _ -> },
    /** Opens the phone picker for the pinned Files pick session (directory, session id). */
    onPickWindowsImage: (String, String) -> Unit = { _, _ -> },
    /** Opens OpenDocument for a read-only phone-side Ventoy settings import. */
    onPickVentoySettingsFile: () -> Unit = {},
    onRequestDownload: (FilesDownloadBinding) -> Unit = {},
    onPickBackup: () -> Unit = {},
    onPickBackupFolder: () -> Unit = {},
    onStartRescueBackup: () -> Unit = {},
    onCreateGptCopy: () -> Unit = {},
    onPickRestoreFolder: () -> Unit = {},
    /** Writes the redacted on-device trace to a user-chosen document (SAF). */
    onExportTrace: () -> Unit = {},
) {
    val isDark = state.darkTheme
    val colors = if (isDark) darkDesignColors() else lightDesignColors()
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, state.language) {
        createLocalizedContext(baseContext, state.language)
    }
    // A deliberate swipe is measured in dp so it feels the same at every density.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val swipeThreshold = with(density) {
        ShellSwipePolicy.THRESHOLD_DP.dp.toPx()
    }
    // Gestures starting in these physical edges perform system Back, not a tab swipe.
    val systemGestures = WindowInsets.systemGestures
    val leftBackEdge = systemGestures.getLeft(density, layoutDirection).toFloat()
    val rightBackEdge = systemGestures.getRight(density, layoutDirection).toFloat()
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalResources provides localizedContext.resources,
    ) {
    MaterialTheme(colorScheme = colors, shapes = AppShapes) {
        // Presentation-only state, saved across rotation and restored back-stack entry:
        // collapsing the status or closing the journal never destroys a running operation.
        var panels by rememberSaveable(stateSaver = ShellPanelStateSaver) {
            mutableStateOf(ShellPanelState())
        }
        BackHandler(enabled = panels.journalScreenOpen) {
            panels = panels.closeJournalScreen()
        }
        val logButtonFocus = remember { FocusRequester() }
        val logScreenFocus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        var restoreLogFocus by remember { mutableStateOf(false) }
        LaunchedEffect(panels.journalScreenOpen) {
            if (panels.journalScreenOpen) {
                // Clear focus before fencing the covered group, then focus the journal layer.
                focusManager.clearFocus(force = true)
                logScreenFocus.requestFocusIfAttached("log screen")
            } else if (restoreLogFocus) {
                restoreLogFocus = false
                logButtonFocus.requestFocusIfAttached("log open button")
            }
        }
        LaunchedEffect(state.operation?.state, state.operation?.title) {
            // Evaluate only when operation facts change. A later tab switch must not reopen a
            // status the user deliberately collapsed.
            val operation = state.operation
            if (
                operation?.state == OperationState.Completed &&
                operation.title.shellTab() != null &&
                operation.title.shellTab() != state.tab
            ) {
                panels = panels.expandStatus()
            }
        }
        Scaffold(
            modifier = modifier.fillMaxSize(),
            // targetSdk 36 draws edge to edge: the header, the tab content and the log
            // layer must stay inside the status/navigation bars and any display cutout.
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                Column(
                    // The navigation bar normally supplies the system bottom inset. While
                    // the log screen covers it, the guard adds that inset here so neither
                    // the status block nor the log content touches the system nav bar —
                    // and it still applies when the status block has nothing to show.
                    modifier = if (panels.journalScreenOpen) {
                        // Bottom only: the status surface already applies horizontal safe
                        // insets, and applying both would double the side padding.
                        Modifier.windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                        )
                    } else {
                        Modifier
                    },
                ) {
                    // The operation status stays reachable on the log screen too: a running
                    // operation must keep its progress and cancel action visible.
                    StatusLogPanel(
                        state = state,
                        onIntent = onIntent,
                        panels = panels,
                        onCollapseStatus = { panels = panels.collapseStatus() },
                        onExpandStatus = { panels = panels.expandStatus() },
                    )
                    if (!panels.journalScreenOpen) {
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
                        .fillMaxSize()
                        .focusProperties {
                            // A focus group boundary is the supported subtree gate: keyboard
                            // traversal calls onEnter before choosing any descendant. Cancel
                            // entry while opaque journal layer owns focus.
                            if (panels.journalScreenOpen) {
                                onEnter = { cancelFocusChange() }
                            }
                        }
                        .focusGroup()
                        .then(
                            if (panels.journalScreenOpen) {
                                // Merging node: the whole covered tab is removed from the
                                // accessibility tree in one step, including every child.
                                Modifier.semantics(mergeDescendants = true) {
                                    hideFromAccessibility()
                                }
                            } else {
                                Modifier
                            },
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DeviceHeader(
                        state = state,
                        onIntent = onIntent,
                        logButtonFocus = logButtonFocus,
                        onOpenLog = {
                            restoreLogFocus = true
                            panels = panels.openJournalScreen()
                        },
                    )
                    // Horizontal tab swipe: the parent only watches pointers that no child
                    // consumed, so a range slider, a horizontally scrolled row or a vertical
                    // list keeps its own gesture. A committed swipe goes through the reducer
                    // exactly like a navigation-bar tap.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(
                                state.tab,
                                swipeThreshold,
                                layoutDirection,
                                leftBackEdge,
                                rightBackEdge,
                            ) {
                                detectShellSwipe(
                                    thresholdPx = swipeThreshold,
                                    isRtl = layoutDirection == LayoutDirection.Rtl,
                                    leftBackEdgePx = leftBackEdge,
                                    rightBackEdgePx = rightBackEdge,
                                ) { direction ->
                                    val target = ShellSwipePolicy.target(state.tab, direction)
                                    if (target != null && target != state.tab) {
                                        onIntent(UiIntent.SelectTab(target))
                                    }
                                }
                            },
                    ) {
                        AnimatedContent(
                            targetState = state.tab,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                // Slide in the reading direction of the tab order; both taps
                                // and swipes animate the same way. RTL mirrors the sides.
                                val forward = targetState.ordinal >= initialState.ordinal
                                val base = if (forward) 1 else -1
                                val enterFrom = if (layoutDirection == LayoutDirection.Rtl) {
                                    -base
                                } else {
                                    base
                                }
                                val enter = slideInHorizontally(tween(220)) { width ->
                                    enterFrom * width / 3
                                } + fadeIn(tween(180))
                                val exit = slideOutHorizontally(tween(220)) { width ->
                                    -enterFrom * width / 3
                                } + fadeOut(tween(160))
                                enter.togetherWith(exit)
                            },
                            label = "tab",
                        ) { tab ->
                            when (tab) {
                                ShellTab.Files -> FilesScreen(
                                    state = state,
                                    onIntent = onIntent,
                                    modifier = Modifier.fillMaxSize(),
                                    onRequestDownload = onRequestDownload,
                                    onRequestDeviceImage = onPickWindowsImage,
                                )
                                ShellTab.Ventoy -> VentoyScreen(
                                    state = state,
                                    onIntent = onIntent,
                                    modifier = Modifier.fillMaxSize(),
                                    onPickWindowsFile = onPickWindowsFile,
                                    onPickVentoySettingsFile = onPickVentoySettingsFile,
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
                                    onPickBackupFolder = onPickBackupFolder,
                                    onStartRescueBackup = onStartRescueBackup,
                                    onCreateGptCopy = onCreateGptCopy,
                                    onPickRestoreFolder = onPickRestoreFolder,
                                )
                            }
                        }
                    }
                }

                if (panels.journalScreenOpen) {
                    // Opaque layer, not a replacement: it covers the tab, swallows taps
                    // that miss its own controls so nothing underneath reacts, and Back
                    // closes it. Compose keeps the tab (and its drafts) alive below.
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { } },
                    ) {
                        LogScreen(
                            lines = state.logLines,
                            focusRequester = logScreenFocus,
                            onBack = { panels = panels.closeJournalScreen() },
                            onIntent = onIntent,
                            onExportTrace = onExportTrace,
                        )
                    }
                }
            }
        }

        state.linkOverwriteCandidate
            ?.let { candidate ->
                AlertDialog(
                    onDismissRequest = { onIntent(UiIntent.DismissLinkOverwrite) },
                    title = { Text(stringResource(R.string.status_overwrite_title)) },
                    text = {
                        Text(stringResource(R.string.status_link_exists, candidate.substringAfterLast('/')))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onIntent(
                                    UiIntent.RequestLinkOverwrite(
                                        fileName = candidate,
                                        imagePaths = state.linkOverwriteCandidatePaths,
                                        templatePath = state.linkOverwriteCandidateTemplatePath,
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.status_overwrite)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { onIntent(UiIntent.DismissLinkOverwrite) }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }

        state.pendingConfirmation
            ?.takeIf { state.linkOverwriteCandidate == null }
            ?.let { confirmation ->
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
                                text = stringResource(
                                    if (confirmation.action == DestructiveAction.GPT_REPAIR) {
                                        R.string.confirm_ack_repair
                                    } else {
                                        R.string.confirm_ack
                                    },
                                ),
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

/**
 * Saves the shell presentation flags across rotation and activity recreation. They are
 * plain booleans, so a list saver is enough; nothing here belongs to the reducer or to
 * the running operation.
 */
private val ShellPanelStateSaver = listSaver<ShellPanelState, Boolean>(
    save = {
        listOf(it.statusCollapsed, it.journalScreenOpen)
    },
    restore = {
        ShellPanelState(
            statusCollapsed = it[0],
            journalScreenOpen = it[1],
        )
    },
)

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

/** Fixed project page: the header opens exactly this address, never state or user input. */
private const val PROJECT_URL = "https://github.com/SklUpp/usb-multitool"

/**
 * Installed `versionName`, read from [android.content.pm.PackageManager] instead of
 * `BuildConfig`: a sideloaded or repackaged APK then reports the version actually on the
 * device. The build enables no `buildConfig` field, and the manifest version stays the one
 * source. A read failure is logged and shows as "version unknown"; it never crashes.
 */
@Suppress("DEPRECATION") // versionName does not depend on PackageInfo flags.
private fun installedVersionName(context: Context): String? = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.onFailure {
    android.util.Log.w("UsbMultitool", "installed version unavailable: ${it.message}")
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Version label above the language, theme and log buttons; tapping opens [PROJECT_URL].
 * The intent is implicit and this build declares no package-visibility queries, so the
 * resolve step is the launch itself: [ActivityNotFoundException] means "no handler" and is
 * reported in a toast. A pre-flight `resolveActivity` would answer null for a browser the
 * package filter hides on API 30+.
 */
@Composable
private fun ProjectVersionButton(versionName: String?) {
    val context = LocalContext.current
    val label = if (versionName != null) {
        stringResource(R.string.app_version_label, versionName)
    } else {
        stringResource(R.string.app_version_unknown)
    }
    val description = if (versionName != null) {
        stringResource(R.string.app_version_open, versionName)
    } else {
        stringResource(R.string.app_version_open_unknown)
    }
    val noBrowser = stringResource(R.string.app_version_no_browser)
    TextButton(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                context.startActivity(intent)
            } catch (error: ActivityNotFoundException) {
                android.util.Log.w("UsbMultitool", "no activity for $PROJECT_URL: ${error.message}")
                Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
            }
        },
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Compact top device status header. The title and subtitle sit in their own surface with a
 * top inset; language, theme and log controls stay outside that surface, so no control is
 * painted on top of the block it does not belong to. Height is content-driven — no
 * `heightIn(max)` that could clip a wrapped subtitle on small/landscape screens.
 */
@Composable
private fun DeviceHeader(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    logButtonFocus: FocusRequester,
    onOpenLog: () -> Unit,
) {
    val device = state.device
    val partCount = state.partitions.size
    val context = LocalContext.current
    // Installed version is stable for the process lifetime; avoid a binder read per frame.
    val versionName = remember(context) { installedVersionName(context) }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = device?.label ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.presence == DevicePresence.PermissionRequired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Version and non-journal controls share one compact row. Journal remains a text action.
        Column(horizontalAlignment = Alignment.End) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                ProjectVersionButton(versionName = versionName)
                LanguagePicker(language = state.language, onIntent = onIntent)
                ThemeToggle(darkTheme = state.darkTheme, onIntent = onIntent)
            }
            LogOpenButton(
                lineCount = state.logLines.size,
                focusRequester = logButtonFocus,
                onOpenLog = onOpenLog,
            )
        }
    }
}

/**
 * Requests focus, tolerating a target detached in the same frame.
 * [FocusRequester] has no public `isAttached` check, so only the documented
 * IllegalStateException is handled — and logged, not silently dropped, so unrelated
 * failures remain visible.
 */
private fun FocusRequester.requestFocusIfAttached(target: String) {
    try {
        requestFocus()
    } catch (error: IllegalStateException) {
        android.util.Log.d("UsbMultitool", "focus request for $target skipped: ${error.message}")
    }
}

/**
 * Arbiter between the shell swipe and the active tab. Children receive the Main pass first.
 * A consumed down proves nothing: Compose `clickable` consumes every down, so only a
 * consumed move (range slider, horizontally scrolled row, vertical list) aborts the swipe
 * before it can qualify. Once qualified, every change is consumed: that cancels a clickable
 * row's tap before its up. A terminal up still claimed by another handler counts as a
 * cancellation, never as a second action. A pointer that vanishes or a gesture starting in
 * a system back edge dispatches nothing.
 */
private suspend fun PointerInputScope.detectShellSwipe(
    thresholdPx: Float,
    isRtl: Boolean,
    leftBackEdgePx: Float,
    rightBackEdgePx: Float,
    onSwipe: (ShellSwipeDirection) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (
            ShellSwipePolicy.startsInSystemBackEdge(
                xPx = down.position.x,
                widthPx = size.width.toFloat(),
                leftInsetPx = leftBackEdgePx,
                rightInsetPx = rightBackEdgePx,
            )
        ) {
            return@awaitEachGesture
        }
        val recognizer = ShellSwipeRecognizer(thresholdPx, isRtl)
        recognizer.down(down.position.x, down.position.y, down.isConsumed)
        var endedConsumed = false
        while (!recognizer.isFinished) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                // The system cancelled the stream (back gesture, window change).
                recognizer.cancel()
                break
            }
            endedConsumed = change.isConsumed
            if (recognizer.move(change.position.x, change.position.y, change.isConsumed)) {
                change.consume()
            }
            if (!change.pressed) break
        }
        recognizer.up(consumed = endedConsumed)?.let(onSwipe)
    }
}

/** 48 dp text entry to the full log screen; status controls stay separate. */
@Composable
private fun LogOpenButton(
    lineCount: Int,
    focusRequester: FocusRequester,
    onOpenLog: () -> Unit,
) {
    val label = stringResource(R.string.log_screen_title)
    TextButton(
        onClick = onOpenLog,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .focusRequester(focusRequester)
            .semantics { contentDescription = "$label · $lineCount" },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Operation status surface; Journal and overwrite prompts stay outside status. */
@Composable
internal fun StatusLogPanel(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    panels: ShellPanelState,
    onCollapseStatus: () -> Unit,
    onExpandStatus: () -> Unit,
) {
    val op = state.operation
    if (op == null && state.statusMessage == null) return
    val collapsed = panels.statusCollapsed
    val failed = op?.state == OperationState.Failed
    val partial = op?.state == OperationState.Partial
    val cancelled = op?.state == OperationState.Cancelled
    val completed = op?.state == OperationState.Completed
    val running = op?.state == OperationState.Running || op?.state == OperationState.Queued
    val cancelable = op?.let { running && it.cancelable } == true
    val terminal = failed || partial || cancelled || completed
    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        partial || cancelled -> MaterialTheme.colorScheme.tertiaryContainer
        completed -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.inverseSurface
    }
    val content = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        partial || cancelled -> MaterialTheme.colorScheme.onTertiaryContainer
        completed -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.inverseOnSurface
    }
    val operationName = op?.let { operationLabel(it) }
    val title = if (op != null) {
        "$operationName · ${operationStateLabel(op.state)}"
    } else {
        stringResource(R.string.status_title)
    }
    val detail = if (op != null) op.errorMessage ?: op.readScanResult?.let { readScanSummary(it) } ?: op.resultMessage else state.statusMessage?.localized()
    val statusAnnouncement = when {
        op != null && terminal -> title
        op == null && state.statusMessage != null && state.statusMessage !is UiMessage.Raw -> state.statusMessage.localized()
        else -> null
    }
    val metric = when {
        op == null -> null
        completed -> "✓"
        op.bytesTotal > 0L -> "${(op.fraction * 100).toInt()}%"
        op.bytesDone > 0L -> DeviceIdentity.formatCapacity(op.bytesDone.toULong())
        else -> operationStateLabel(op.state)
    }
    val readbackNote = op?.rawWriteResult?.let { result ->
        when (val verdict = result.readback) {
            ReadbackVerdict.Verified -> if (result.writtenBytes.toULong() == result.range.byteLength(state.diskSectorSize)) stringResource(R.string.raw_readback_verified) else stringResource(R.string.raw_readback_verified_partial, result.writtenBytes)
            is ReadbackVerdict.Mismatch -> stringResource(R.string.raw_readback_mismatch, verdict.mismatchedSectors.toLong(), verdict.firstMismatchLba.toLong())
            is ReadbackVerdict.Unverified -> stringResource(R.string.raw_readback_unverified, verdict.range.startLba.toLong(), verdict.range.sectorCount.toLong())
            is ReadbackVerdict.Incomplete -> stringResource(R.string.raw_readback_incomplete, stringResource(when (verdict.reason) { ReadbackProblem.Cancelled -> R.string.raw_readback_reason_cancelled; ReadbackProblem.SourceChanged -> R.string.raw_readback_reason_source_changed; ReadbackProblem.IoError -> R.string.raw_readback_reason_io_error }))
        }
    }
    data class CancelRequest(val token: OperationToken, val name: String)
    var cancelRequest by remember { mutableStateOf<CancelRequest?>(null) }
    val cancelDescription = stringResource(R.string.action_cancel)
    val statusDescription = if (collapsed) stringResource(R.string.status_expand) else stringResource(R.string.status_collapse)
    fun requestCancel() {
        op?.takeIf { cancelable }?.let {
            cancelRequest = CancelRequest(it.token, operationName ?: title)
        }
    }
    val statusSurface = Modifier
        .then(if (collapsed) Modifier.size(144.dp) else Modifier.fillMaxWidth())
        .then(if (collapsed) Modifier else Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp))
        .combinedClickable(role = Role.Button, onClickLabel = statusDescription, onLongClickLabel = if (collapsed && cancelable) cancelDescription else null, onClick = if (collapsed) onExpandStatus else onCollapseStatus, onLongClick = if (collapsed && cancelable) ::requestCancel else null)
        .semantics { if (statusAnnouncement != null) { liveRegion = LiveRegionMode.Polite; contentDescription = statusAnnouncement } }
    Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = if (collapsed) CircleShape else RoundedCornerShape(16.dp), color = container, modifier = statusSurface) {
            if (collapsed) {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(metric ?: title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = content, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    if (cancelable) IconButton(onClick = ::requestCancel, modifier = Modifier.semantics { contentDescription = cancelDescription }) { GlyphIcon(glyph = Glyph.Stop, size = 18.dp, tint = content) }
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            readbackNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        metric?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = content) }
                        if (cancelable) IconButton(onClick = ::requestCancel, modifier = Modifier.semantics { contentDescription = cancelDescription }) { GlyphIcon(glyph = Glyph.Stop, size = 18.dp, tint = content) }
                    }
                    if (op != null && running && op.bytesTotal > 0L) LinearProgressIndicator(progress = { op.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primary, trackColor = content.copy(alpha = 0.2f))
                }
            }
        }
    }
    cancelRequest?.let { request ->
        AlertDialog(onDismissRequest = { cancelRequest = null }, title = { Text(stringResource(R.string.cancel_operation_title)) }, text = { Text(stringResource(R.string.cancel_operation_body, request.name)) }, confirmButton = { TextButton(onClick = { cancelRequest = null; onIntent(UiIntent.CancelOperation(request.token)) }) { Text(stringResource(R.string.cancel_operation_confirm)) } }, dismissButton = { TextButton(onClick = { cancelRequest = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
/**
 * Full log screen: every entry, an explicit "copy all" (warned about paths and serials),
 * an export of the redacted on-device trace, and an immediate clear. Back leaves the
 * screen without touching the selected tab.
 */
@Composable
private fun LogScreen(
    lines: List<String>,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onIntent: (UiIntent) -> Unit,
    onExportTrace: () -> Unit,
) {
    val context = LocalContext.current
    val logPaneTitle = stringResource(R.string.log_screen_title)
    var confirmCopy by remember { mutableStateOf(false) }
    val backText = stringResource(R.string.log_back)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .semantics {
                paneTitle = logPaneTitle
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = backText },
            ) {
                GlyphIcon(
                    glyph = Glyph.ChevronLeft,
                    size = 20.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.log_title_count, lines.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(
                onClick = { confirmCopy = true },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.log_copy_all))
            }
            TextButton(
                onClick = { onIntent(UiIntent.ClearLog) },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.log_clear))
            }
        }
        // Own row: two text buttons beside the title already fill the header at RU lengths.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onExportTrace,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.log_export_trace))
            }
        }
        if (lines.isEmpty()) {
            EmptyHint(stringResource(R.string.log_empty))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics {
                        collectionInfo = CollectionInfo(
                            rowCount = lines.size,
                            columnCount = 1,
                        )
                    },
            ) {
                items(lines.size) { index ->
                    Text(
                        text = lines[index],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics {
                            collectionItemInfo = CollectionItemInfo(
                                rowIndex = index,
                                rowSpan = 1,
                                columnIndex = 0,
                                columnSpan = 1,
                            )
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.log_clear_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }

    if (confirmCopy) {
        AlertDialog(
            onDismissRequest = { confirmCopy = false },
            title = { Text(stringResource(R.string.log_copy_all)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.log_copy_warning))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCopy = false
                        val clipboard =
                            context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("log", logClipboardText(lines)),
                        )
                        onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.LogCopied)))
                    },
                ) {
                    Text(stringResource(R.string.log_copy_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCopy = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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

/** Toggles dark and light only; the icon and its description follow the current value. */
@Composable
private fun ThemeToggle(darkTheme: Boolean, onIntent: (UiIntent) -> Unit) {
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
private fun themeLabel(darkTheme: Boolean): String =
    if (darkTheme) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light)

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
 * Section block: one surface carrying the uppercase heading, an optional one-line
 * subtitle, the optional trailing control and the content. The heading shares the
 * surface with what it names, so no label floats above an unrelated background.
 * The trailing control (help, badge) is centred on the heading line only — the
 * subtitle sits below that row, so a 48 dp help button cannot drift downward.
 * [titleColor] lets a screen use full-contrast onSurface without changing every block.
 */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Overrides the muted section label; the default keeps the existing section styling. */
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Uppercase must follow the in-app language, not the system one: a Turkish device
    // with the English UI would otherwise render "EFİ". LocalResources is the localized
    // context provided by AppShell, so its configuration carries the chosen language.
    val locale = LocalResources.current.configuration.locales[0]
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (danger) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
        } else {
            null
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.uppercase(locale),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    color = when {
                        danger -> MaterialTheme.colorScheme.error
                        else -> titleColor
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                trailing?.invoke()
            }
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
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

/**
 * Decorative vector glyphs. Icon-only buttons must put their label in the button's semantics,
 * because [GlyphIcon] itself is not exposed to accessibility services.
 */
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
    /** Up one level in the file browser. */
    ArrowUp,
    Refresh,
    FolderPlus,
    /** Copy from the phone into the mounted volume. */
    Upload,
    /** Save a volume entry onto the phone. */
    Download,
    Pencil,
    Trash,
    ChevronDown,
    ChevronUp,
    ChevronLeft,
    /** Two crossing strokes: folding the status block without clearing it. */
    Close,
    /** Filled square: compact cancel while the status is folded. */
    Stop,
    /** Three stacked lines: the in-app operation log. */
    Log,
    /** Reveal a masked value. */
    Eye,
    /** Hide a revealed value. */
    EyeOff,
    /** Release bundled with the app. */
    Package,
    /** Release reported by the upstream server. */
    Cloud,
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
                Glyph.ArrowUp -> {
                    drawLine(tint, androidx.compose.ui.geometry.Offset(12f, 20f), androidx.compose.ui.geometry.Offset(12f, 5f), 1.8f)
                    drawPath(
                        Path().apply { moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) },
                        tint,
                        style = stroke,
                    )
                }
                Glyph.Refresh -> {
                    drawArc(
                        tint,
                        startAngle = -55f,
                        sweepAngle = 285f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(4f, 4f),
                        size = androidx.compose.ui.geometry.Size(16f, 16f),
                        style = stroke,
                    )
                    drawPath(
                        Path().apply { moveTo(13f, 3f); lineTo(19f, 5f); lineTo(17f, 11f) },
                        tint,
                        style = stroke,
                    )
                }
                Glyph.FolderPlus -> {
                    drawPath(
                        Path().apply {
                            moveTo(3f, 6f); lineTo(10f, 6f); lineTo(12f, 8f); lineTo(21f, 8f)
                            lineTo(21f, 19f); lineTo(3f, 19f); close()
                        },
                        tint,
                        style = stroke,
                    )
                    drawLine(tint, androidx.compose.ui.geometry.Offset(12f, 11f), androidx.compose.ui.geometry.Offset(12f, 17f), 1.8f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(9f, 14f), androidx.compose.ui.geometry.Offset(15f, 14f), 1.8f)
                }
                Glyph.Upload -> {
                    drawPath(
                        Path().apply {
                            moveTo(4f, 16f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 16f)
                        },
                        tint,
                        style = stroke,
                    )
                    drawLine(tint, androidx.compose.ui.geometry.Offset(12f, 16f), androidx.compose.ui.geometry.Offset(12f, 4f), 1.8f)
                    drawPath(
                        Path().apply { moveTo(6f, 10f); lineTo(12f, 4f); lineTo(18f, 10f) },
                        tint,
                        style = stroke,
                    )
                }
                Glyph.Download -> {
                    drawPath(
                        Path().apply {
                            moveTo(4f, 16f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 16f)
                        },
                        tint,
                        style = stroke,
                    )
                    drawLine(tint, androidx.compose.ui.geometry.Offset(12f, 4f), androidx.compose.ui.geometry.Offset(12f, 16f), 1.8f)
                    drawPath(
                        Path().apply { moveTo(6f, 10f); lineTo(12f, 16f); lineTo(18f, 10f) },
                        tint,
                        style = stroke,
                    )
                }
                Glyph.Pencil -> {
                    drawPath(
                        Path().apply {
                            moveTo(4f, 20f); lineTo(5.5f, 15f); lineTo(15f, 5.5f)
                            lineTo(18.5f, 9f); lineTo(9f, 18.5f); close()
                        },
                        tint,
                        style = stroke,
                    )
                    drawLine(tint, androidx.compose.ui.geometry.Offset(13f, 7.5f), androidx.compose.ui.geometry.Offset(16.5f, 11f), 1.8f)
                }
                Glyph.Trash -> {
                    drawLine(tint, androidx.compose.ui.geometry.Offset(4f, 7f), androidx.compose.ui.geometry.Offset(20f, 7f), 1.8f)
                    drawPath(
                        Path().apply {
                            moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
                        },
                        tint,
                        style = stroke,
                    )
                    drawPath(
                        Path().apply {
                            moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
                        },
                        tint,
                        style = stroke,
                    )
                    drawLine(tint, androidx.compose.ui.geometry.Offset(10f, 10f), androidx.compose.ui.geometry.Offset(10f, 17f), 1.6f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(14f, 10f), androidx.compose.ui.geometry.Offset(14f, 17f), 1.6f)
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
                Glyph.Close -> {
                    drawLine(tint, androidx.compose.ui.geometry.Offset(7f, 7f), androidx.compose.ui.geometry.Offset(17f, 17f), 1.8f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(17f, 7f), androidx.compose.ui.geometry.Offset(7f, 17f), 1.8f)
                }
                Glyph.Stop -> drawRect(
                    tint,
                    topLeft = androidx.compose.ui.geometry.Offset(7f, 7f),
                    size = androidx.compose.ui.geometry.Size(10f, 10f),
                )
                Glyph.Log -> {
                    drawLine(tint, androidx.compose.ui.geometry.Offset(5f, 7f), androidx.compose.ui.geometry.Offset(19f, 7f), 1.8f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(5f, 12f), androidx.compose.ui.geometry.Offset(19f, 12f), 1.8f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(5f, 17f), androidx.compose.ui.geometry.Offset(13f, 17f), 1.8f)
                }
                Glyph.Eye -> {
                    drawPath(
                        Path().apply {
                            moveTo(3f, 12f)
                            quadraticBezierTo(12f, 4f, 21f, 12f)
                            quadraticBezierTo(12f, 20f, 3f, 12f)
                            close()
                        },
                        tint,
                        style = stroke,
                    )
                    drawCircle(
                        tint,
                        radius = 2.4f,
                        center = androidx.compose.ui.geometry.Offset(12f, 12f),
                        style = stroke,
                    )
                }
                Glyph.EyeOff -> {
                    drawPath(
                        Path().apply {
                            moveTo(3f, 12f)
                            quadraticBezierTo(12f, 4f, 21f, 12f)
                            quadraticBezierTo(12f, 20f, 3f, 12f)
                            close()
                        },
                        tint,
                        style = stroke,
                    )
                    drawCircle(
                        tint,
                        radius = 2.4f,
                        center = androidx.compose.ui.geometry.Offset(12f, 12f),
                        style = stroke,
                    )
                    drawLine(
                        tint,
                        androidx.compose.ui.geometry.Offset(4f, 20f),
                        androidx.compose.ui.geometry.Offset(20f, 4f),
                        1.8f,
                    )
                }
                Glyph.Package -> {
                    drawRect(
                        tint,
                        topLeft = androidx.compose.ui.geometry.Offset(4f, 6f),
                        size = androidx.compose.ui.geometry.Size(16f, 14f),
                        style = stroke,
                    )
                    drawLine(tint, androidx.compose.ui.geometry.Offset(4f, 11f), androidx.compose.ui.geometry.Offset(20f, 11f), 1.8f)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(12f, 6f), androidx.compose.ui.geometry.Offset(12f, 20f), 1.8f)
                }
                Glyph.Cloud -> drawPath(
                    Path().apply {
                        moveTo(7f, 18f)
                        lineTo(17f, 18f)
                        cubicTo(21f, 18f, 22f, 12f, 18f, 11f)
                        cubicTo(17f, 6f, 9f, 6f, 8f, 11f)
                        cubicTo(4f, 11f, 3f, 18f, 7f, 18f)
                        close()
                    },
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
