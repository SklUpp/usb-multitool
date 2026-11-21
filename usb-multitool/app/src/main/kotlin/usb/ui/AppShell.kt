package usb.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import android.provider.Settings
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import usb.formatBinaryBytesPerSecond
import usb.formatEtaDuration
import usb.remainingSeconds
import usb.fs.FilesystemSpace
import usb.fs.FilesystemUsage
import usb.image.ReadbackProblem
import usb.image.ReadbackVerdict
import usb.multitool.R
import usb.service.OperationToken
import java.util.Locale
private fun reducedMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}.getOrDefault(false)

private fun usbContentAvailable(presence: DevicePresence): Boolean =
    presence == DevicePresence.Ready || presence == DevicePresence.Busy

/**
 * Which tab may render its content. An open Files pick session stays mounted even when the
 * stick disappears: the reducer still owns exit/cancel, and hiding it would strand the
 * session behind a disabled header with no visible close control.
 */
internal fun shellTabContentVisible(
    tab: ShellTab,
    presence: DevicePresence,
    pickOpen: Boolean,
): Boolean =
    when {
        tab == ShellTab.Files && pickOpen -> true
        tab == ShellTab.Files || tab == ShellTab.Partitions -> usbContentAvailable(presence)
        else -> true
    }


/**
 * Four-tab shell over [AppUiState]. Talks only through [onIntent]; never opens USB.
 *
 * Layout contract:
 * - safe drawing insets bound the whole shell (targetSdk 36 draws edge to edge), so neither
 *   the header, the tabs, the status block nor the log layer touches the system bars;
 * - top: one compact device header (also on Settings) whose title/subtitle surface is
 *   separate from the language, theme and log controls (no max-height clipping). Directly
 *   under it a permanent status block (inset `surfaceContainer*` band, shared 12 dp side
 *   rhythm) titled `Status: &lt;operation&gt; — &lt;state&gt;` as one line, always fully
 *   tappable (48 dp), no subtitle and no separate expand control; thin progress track only
 *   while running. Gaps header → block → content stay in the 8–12 dp band. The block stays
 *   on Files, Partitions, Ventoy and Service, hides on Settings and while the journal is
 *   open, and opens the centred status dialog even when idle. The block carries no cancel
 *   and no dividers. It is the only status surface: both operation facts and non-operation
 *   rejections/errors land here, never a snackbar;
 * - middle: the active tab (weight 1, own scroll), always composed; a horizontal swipe
 *   selects the adjacent tab through the reducer and the content slides in the tab
 *   direction (RTL included). A child that consumes the drag keeps it; once the shell
 *   qualifies its own gesture it consumes the drag so a clickable row is not tapped, and
 *   a gesture starting in a system back edge is ignored. The full log screen is an opaque
 *   layer that covers the tab content and hides the status block; the header stays so the
 *   journal can close from it. While the journal is open the header log/more controls are
 *   omitted so focus stays inside the journal; the system bottom inset is guarded while it
 *   shows; the covered tab is marked invisible to accessibility but keeps its state;
 * While a Files pick session owns the screen the header, status block, navigation bar and
 * log layer hide; the tab swipe stays off. The existing picker branch is still the tab
 * content with its own continue/back controls, and Back routes to the reducer cancel,
 * which refuses while a copy runs or a confirmation is open.
 * - bottom: only the navigation bar. Status details live in a centred dialog opened from
 *   the status block: title Status, operation name, compact status (≤2 lines), temporary
 *   operation journal in the shared journal format (no device-summary padding), two-step
 *   cancel when running; closing never cancels. Status text lives in the permanent block
 *   and the status dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onRequestBatchDownload: (FilesBatchSelection) -> Unit = {},
    /** Opens OpenMultipleDocuments into the pinned current Files directory. */
    onRequestBatchUpload: () -> Unit = {},
    onPickBackup: () -> Unit = {},
    onPickBackupFolder: () -> Unit = {},
    onStartRescueBackup: () -> Unit = {},
    onCreateGptCopy: () -> Unit = {},
    onPickRestoreFolder: () -> Unit = {},
    /** Live SAF grant predicate for Diagnostics create gates; Activity owns the check. */
    backupFolderGrantValid: () -> Boolean,
    /** Writes the redacted on-device trace to a user-chosen document (SAF). */
    onExportTrace: () -> Unit = {},
) {
    val isDark = state.darkTheme
    val colors = if (isDark) darkDesignColors() else lightDesignColors()
    val baseContext = LocalContext.current
    // One PackageManager read per composition scope, not one per Settings recomposition.
    val installedVersion = remember { installedVersionName(baseContext) }
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
        // closing the journal never destroys a running operation.
        var panels by rememberSaveable(stateSaver = ShellPanelStateSaver) {
            mutableStateOf(ShellPanelState())
        }
        val pickOpen = state.filesPickMode != null
        // Shell status lives in OperationStatusLine only; LogScreen keeps its own host.
        var settingsReturnTab by rememberSaveable { mutableStateOf(ShellTab.Files.name) }
        // Strip opens the status dialog; picker ownership still forces it closed.
        var operationDetailsOpen by rememberSaveable { mutableStateOf(false) }
        // Terminal results get one short snackbar in addition to the permanent block;
        // status spam while running stays out (the block is the only live surface).
        val shellSnackbar = remember { SnackbarHostState() }
        var snackbarToken by rememberSaveable { mutableStateOf<Long?>(null) }
        val shellFacts = operationStatusFacts(state)
        val snackbarText = shellFacts?.let { fact ->
            listOfNotNull(fact.name, fact.result ?: fact.state).joinToString(" · ")
        } ?: state.statusMessage?.localized()
        LaunchedEffect(state.operation?.token, state.operation?.state) {
            val operation = state.operation ?: return@LaunchedEffect
            if (!operation.state.isTerminal() || snackbarToken == operation.token.value) {
                return@LaunchedEffect
            }
            snackbarToken = operation.token.value
            snackbarText?.let { shellSnackbar.showSnackbar(it, duration = SnackbarDuration.Short) }
        }
        // Modal pick task owns Back: cancel routes through the reducer, and Back stays
        // inert while a copy runs or a confirmation is open (the reducer keeps the
        // picker and reports why). Journal/Settings handlers are disabled while pickOpen.
        BackHandler(enabled = pickOpen) {
            onIntent(UiIntent.CancelFilesPick)
        }
        BackHandler(enabled = panels.journalScreenOpen && !pickOpen) {
            panels = panels.closeJournalScreen()
        }
        BackHandler(enabled = !pickOpen && state.tab == ShellTab.Settings) {
            onIntent(
                UiIntent.SelectTab(
                    ShellTabPersistence.decode(settingsReturnTab) ?: ShellTab.Files,
                ),
            )
        }
        val logButtonFocus = remember { FocusRequester() }
        val logScreenFocus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        var restoreLogFocus by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current
        val reducedMotion = reducedMotionEnabled(baseContext)
        var terminalHapticToken by rememberSaveable { mutableStateOf<Long?>(null) }
        var previousPresence by remember { mutableStateOf(state.presence) }
        LaunchedEffect(state.presence) {
            val previous = previousPresence
            val becameAvailable = usbContentAvailable(state.presence) &&
                (previous == null || !usbContentAvailable(previous))
            val becameUnavailable = !usbContentAvailable(state.presence) &&
                previous != null && usbContentAvailable(previous)
            if (becameAvailable || becameUnavailable) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            previousPresence = state.presence
        }
        LaunchedEffect(state.operation?.token, state.operation?.state) {
            val operation = state.operation ?: return@LaunchedEffect
            val token = operation.token.value
            val terminal = when (operation.state) {
                OperationState.Completed,
                OperationState.Failed,
                OperationState.Partial,
                OperationState.Cancelled,
                -> true
                OperationState.Queued,
                OperationState.Running,
                -> false
            }
            // Exactly one haptic per terminal result; Queued/Running never buzz, and the
            // token guard keeps ticks within the same state from repeating it.
            if (terminal && token != terminalHapticToken) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                terminalHapticToken = token
            }
        }
        LaunchedEffect(panels.journalScreenOpen, pickOpen) {
            if (panels.journalScreenOpen && !pickOpen) {
                // Clear focus before fencing the covered group, then focus the journal layer.
                focusManager.clearFocus(force = true)
                logScreenFocus.requestFocusIfAttached("log screen")
            } else if (restoreLogFocus && !pickOpen) {
                restoreLogFocus = false
                logButtonFocus.requestFocusIfAttached("log open button")
            } else if (pickOpen) {
                // Journal is hidden beneath the modal picker: drop any focus stuck
                // on the detached layer so keyboard traversal starts in the picker.
                focusManager.clearFocus(force = true)
            }
        }
        LaunchedEffect(pickOpen) {
            if (pickOpen) operationDetailsOpen = false
        }
        Scaffold(
            modifier = modifier.fillMaxSize(),
            // targetSdk 36 draws edge to edge: the header, the tab content and the log
            // layer must stay inside the status/navigation bars and any display cutout.
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(shellSnackbar) },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .then(
                            // Empty bottomBar still reserves the system nav inset while the
                            // NavigationBar itself is hidden (journal layer or Files pick).
                            // Avoid double padding: Scaffold already pads content for an
                            // occupied bottomBar; only pad the empty column.
                            if (panels.journalScreenOpen || pickOpen) {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                                )
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    if (!panels.journalScreenOpen && !pickOpen) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp,
                        ) {
                            PrimaryShellTabs.forEach { tab ->
                                // Reducer also refuses the switch; the disabled state names it
                                // up front. Presence alone never disables: empty states stay
                                // navigable, only a live operation pins the tab.
                                val switchBlocked = tab != state.tab &&
                                    (state.isRunning || state.operation?.state == OperationState.Queued)
                                NavigationBarItem(
                                    selected = state.tab == tab,
                                    onClick = { onIntent(UiIntent.SelectTab(tab)) },
                                    enabled = !switchBlocked,
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
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header and status block share the 12 dp side rhythm. Settings owns
                    // a single standard TopAppBar (with back) and hides both; the journal
                    // covers the work tabs; the picker owns the whole screen.
                    if (!pickOpen && state.tab != ShellTab.Settings) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        ) {
                            DeviceHeader(
                                state = state,
                                logButtonFocus = logButtonFocus,
                                journalOpen = panels.journalScreenOpen,
                                onIntent = onIntent,
                                onOpenSettings = {
                                    if (state.tab in PrimaryShellTabs) {
                                        settingsReturnTab = state.tab.name
                                    }
                                    onIntent(UiIntent.SelectTab(ShellTab.Settings))
                                },
                                onOpenLog = {
                                    restoreLogFocus = true
                                    panels = panels.openJournalScreen()
                                },
                            )
                        }
                        if (!panels.journalScreenOpen) {
                            OperationStatusLine(
                                state = state,
                                onOpenDetails = { operationDetailsOpen = true },
                            )
                        }
                    }
                    // Tab content and the journal share this weight box: the journal covers
                    // the tab content only, never the status line above it.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                                .padding(top = 8.dp)
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
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                    // Horizontal tab swipe: the parent only watches pointers that no child
                    // consumed, so a range slider, a horizontally scrolled row or a vertical
                    // list keeps its own gesture. A committed swipe goes through the reducer
                    // exactly like a navigation-bar tap.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(
                                if (pickOpen) {
                                    // Modal pick task owns its gestures; the tab swipe stays
                                    // off so a sideways drag can never cancel the session.
                                    Modifier
                                } else {
                                    Modifier.pointerInput(
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
                                    }
                                },
                            ),
                    ) {
                        AnimatedContent(
                            targetState = state.tab,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                if (reducedMotion) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    (fadeIn(tween(200)) +
                                        scaleIn(initialScale = 0.96f, animationSpec = tween(200)))
                                        .togetherWith(fadeOut(tween(100)))
                                }
                            },
                            label = "tab",
                        ) { target ->
                            // P2: BeginFilesPick flips tab to Files while the old tab fades
                            // out. The outgoing tab is not composed at all while pickOpen:
                            // no pointer, focus, semantics, gestures, or already-running
                            // handlers can leak. The incoming Files picker stays interactive.
                            // AnimatedContent still animates: outgoing has no content, so
                            // only the picker fades/scales in.
                            if (pickOpen && target != ShellTab.Files) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                }
                            } else {
                            // Nested presence transition: tab switches fade/scale above;
                            // USB attach/detach fades the empty state within the same tab.
                            val visible = shellTabContentVisible(target, state.presence, pickOpen)
                            AnimatedContent(
                                targetState = visible,
                                transitionSpec = {
                                    if (reducedMotion) {
                                        EnterTransition.None togetherWith ExitTransition.None
                                    } else {
                                        (fadeIn(tween(200)) + scaleIn(
                                            initialScale = 0.96f,
                                            animationSpec = tween(200),
                                        )).togetherWith(fadeOut(tween(100)))
                                    }
                                },
                                label = "presence",
                            ) { showContent ->
                            if (!showContent) {
                                UsbEmptyState(
                                    presence = state.presence,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                            when (target) {
                                ShellTab.Files -> FilesScreen(
                                    state = state,
                                    onIntent = onIntent,
                                    modifier = Modifier.fillMaxSize(),
                                    onRequestDownload = onRequestDownload,
                                    onRequestBatchDownload = onRequestBatchDownload,
                                    onRequestBatchUpload = onRequestBatchUpload,
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
                                    onStartRescueBackup = onStartRescueBackup,
                                    onCreateGptCopy = onCreateGptCopy,
                                    onPickRestoreFolder = onPickRestoreFolder,
                                    onOpenBackupSettings = {
                                        settingsReturnTab = ShellTab.Diagnostics.name
                                        onIntent(UiIntent.SelectTab(ShellTab.Settings))
                                    },
                                    backupFolderGrantValid = backupFolderGrantValid,
                                )
                                ShellTab.Settings -> SettingsScreen(
                                    state = state,
                                    versionName = installedVersion,
                                    onIntent = onIntent,
                                    onBack = {
                                        onIntent(
                                            UiIntent.SelectTab(
                                                ShellTabPersistence.decode(settingsReturnTab)
                                                    ?: ShellTab.Files,
                                            ),
                                        )
                                    },
                                    onPickBackupFolder = onPickBackupFolder,
                                )
                            }
                        }
                    }
                }
                }
                }
                }

                if (panels.journalScreenOpen && !pickOpen) {
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
        }
        }

        if (operationDetailsOpen && !pickOpen) {
            // Centred status dialog: works idle and during an operation. Closing never
            // cancels; only the explicit cancel button reaches the reducer.
            StatusDialog(
                state = state,
                onIntent = onIntent,
                onDismiss = { operationDetailsOpen = false },
            )
        }

        state.linkOverwriteCandidate
            ?.let { candidate ->
                AlertDialog(
                    onDismissRequest = { onIntent(UiIntent.DismissLinkOverwrite) },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
            // Explicit acknowledgement remains mandatory. Wipe also waits three seconds;
            // no hold gesture or text input can bypass this delay.
            var acknowledged by remember(confirmation) { mutableStateOf(false) }
            var wipeDelayReady by remember(confirmation) {
                mutableStateOf(confirmation.action != DestructiveAction.WIPE)
            }
            LaunchedEffect(confirmation) {
                if (confirmation.action == DestructiveAction.WIPE) {
                    wipeDelayReady = false
                    delay(3_000L)
                    wipeDelayReady = true
                } else {
                    wipeDelayReady = true
                }
            }
            AlertDialog(
                onDismissRequest = { onIntent(UiIntent.DismissConfirmation) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                title = {
                    Text(
                        text = confirmationTitle(confirmation),
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
                                    text = line.text,
                                    style = if (line.monospace) {
                                        MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .toggleable(
                                    value = acknowledged,
                                    role = Role.Checkbox,
                                    onValueChange = { acknowledged = it },
                                )
                                .padding(12.dp),
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
                    val confirmEnabled = acknowledged && wipeDelayReady
                    val risk = isDataLosingConfirmation(confirmation)
                    val confirmColors = when {
                        !confirmEnabled -> ButtonDefaults.textButtonColors(
                            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        // Data loss reads as an error surface; GPT repair stays primary.
                        risk -> ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        else -> ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(
                        onClick = { onIntent(UiIntent.ConfirmDestructive) },
                        enabled = confirmEnabled,
                        colors = confirmColors,
                    ) {
                        Text(
                            text = stringResource(R.string.action_confirm),
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
 * Saves the shell presentation flags across rotation and activity recreation. It is a
 * plain boolean, so a list saver is enough; nothing here belongs to the reducer or to
 * the running operation.
 */
private val ShellPanelStateSaver = listSaver<ShellPanelState, Boolean>(
    save = {
        listOf(it.journalScreenOpen)
    },
    restore = {
        ShellPanelState(journalScreenOpen = it[0])
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
        // Preview/host helper: no live SAF grant; create gates stay blocked.
        backupFolderGrantValid = { false },
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
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceHeader(
    state: AppUiState,
    logButtonFocus: FocusRequester,
    /** The opaque journal covers the tab content; its own open/more controls are inert. */
    journalOpen: Boolean,
    onIntent: (UiIntent) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val device = state.device
    val operationLocked = state.isRunning || state.operation?.state == OperationState.Queued
    val subtitle = when (state.presence) {
        DevicePresence.Ready -> buildString {
            append(device?.capacityLabel ?: "USB")
            state.partitionScheme?.name?.let { append(" · ").append(it) }
        }
        DevicePresence.PermissionRequired -> stringResource(R.string.header_permission)
        DevicePresence.Busy -> stringResource(R.string.header_busy)
        DevicePresence.Disconnected -> stringResource(R.string.header_disconnected)
        DevicePresence.Absent -> stringResource(R.string.header_absent)
    }
    var moreOpen by remember { mutableStateOf(false) }
    val logLabel = stringResource(R.string.log_screen_title)
    val moreLabel = stringResource(R.string.more_actions)
TopAppBar(
        title = {
            Column {
                Text(
                    text = device?.label ?: stringResource(R.string.app_name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            // Req 16: omit journal/menu controls while the journal layer owns the screen.
            if (!journalOpen) {
                IconButton(
                    onClick = onOpenLog,
                    modifier = Modifier
                        .size(48.dp)
                        .focusRequester(logButtonFocus)
                        .semantics { contentDescription = logLabel },
                ) { GlyphIcon(Glyph.Log, size = 22.dp) }
                Box {
                    IconButton(
                        onClick = { moreOpen = true },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = moreLabel },
                    ) { GlyphIcon(Glyph.More, size = 22.dp) }
                    DropdownMenu(
                        expanded = moreOpen,
                        onDismissRequest = { moreOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 3.dp,
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_title_screen)) },
                            enabled = !operationLocked,
                            onClick = { moreOpen = false; onOpenSettings() },
                        )
                        val ejectEnabled =
                            !operationLocked && state.presence == DevicePresence.Ready &&
                                !state.ejectInFlight && !state.ejected
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_eject)) },
                            enabled = ejectEnabled,
                            onClick = { moreOpen = false; onIntent(UiIntent.RequestEject) },
                        )
                        if (state.ejected) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rescan)) },
                                enabled = !operationLocked && !state.ejectInFlight,
                                onClick = {
                                    moreOpen = false
                                    onIntent(UiIntent.RequestEjectRescan)
                                },
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        // Scaffold's safeDrawing inset already places the content below the status bar;
        // the app bar's own status-bar inset would add a second gap above the title.
        // Keep the M3 default 64 dp height so the two-line title is not clipped at large
        // fontScale; status block outer pad stays 4+4 dp so idle stack has no dead band.
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
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
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * One numeric progress line: percent, throughput and ETA only when measured. Each fact
 * stays separate so a missing speed never hides a known ETA and vice versa.
 */
internal fun operationProgressFacts(metric: String?, speed: String?, eta: String?): String =
    listOfNotNull(metric, speed, eta).filter { it.isNotEmpty() }.joinToString(FACT_SEPARATOR)

/** Field separator shared by every status surface: strip, dialog and journal prefix. */
private const val FACT_SEPARATOR = " · "

/** Colour family of one status fact; `error` is reserved for real failures. */
private enum class StatusTone { Neutral, Success, Warning, Error }

@Composable
private fun StatusTone.contentColor(): Color =
    when (this) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.Success -> MaterialTheme.colorScheme.primary
        StatusTone.Warning -> MaterialTheme.colorScheme.tertiary
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }

/**
 * One fact set for every surface, in one fixed order: name, state, progress
 * ([operationProgressFacts]), readback verdict, result. Missing values stay null and are
 * omitted by the renderer instead of printing an empty or placeholder line.
 */
private data class OperationStatusFacts(
    val name: String,
    val state: String,
    val progress: String?,
    val readback: String?,
    val readbackTone: StatusTone,
    val result: String?,
    val resultTone: StatusTone,
    val tone: StatusTone,
) {
    /** The one-line strip text: `name · state · progress`, blanks omitted. */
    val summary: String get() = listOfNotNull(name, state, progress).joinToString(FACT_SEPARATOR)
}

@Composable
private fun operationStatusFacts(state: AppUiState): OperationStatusFacts? {
    val op = state.operation ?: return null
    val running = op.state == OperationState.Running || op.state == OperationState.Queued
    // The state is its own field, so the metric never falls back to repeating it.
    val metric = when {
        op.bytesTotal > 0L && op.title.reportsCurrentFileBytes() ->
            stringResource(R.string.op_batch_current_file_pct, (op.fraction * 100).toInt())
        op.bytesTotal > 0L -> "${(op.fraction * 100).toInt()}%"
        op.bytesDone > 0L -> DeviceIdentity.formatCapacity(op.bytesDone.toULong())
        else -> null
    }
    val speed =
        if (running && op.bytesPerSecond > 0L) formatBinaryBytesPerSecond(op.bytesPerSecond) else null
    val etaSeconds =
        if (running) remainingSeconds(op.bytesDone, op.bytesTotal, op.bytesPerSecond) else null
    val eta = etaSeconds?.let { stringResource(R.string.op_eta, formatEtaDuration(it)) }
    val progress = operationProgressFacts(metric, speed, eta).takeIf { it.isNotEmpty() }
    // Data-integrity result: stays visible after the operation ends, unlike a snackbar.
    val readback = op.rawWriteResult?.let { result ->
        val text = when (val verdict = result.readback) {
            ReadbackVerdict.Verified ->
                if (result.writtenBytes.toULong() == result.range.byteLength(state.diskSectorSize)) {
                    stringResource(R.string.raw_readback_verified)
                } else {
                    stringResource(R.string.raw_readback_verified_partial, result.writtenBytes)
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
        val tone = when (result.readback) {
            is ReadbackVerdict.Mismatch, is ReadbackVerdict.Incomplete -> StatusTone.Error
            is ReadbackVerdict.Unverified -> StatusTone.Warning
            ReadbackVerdict.Verified -> StatusTone.Success
        }
        text to tone
    }
    // Terminal outcome (error, read-scan summary or result); it must outlive the
    // operation, so it cannot be a snackbar.
    val result = op.errorMessage
        ?: op.readScanResult?.let { readScanSummary(it) }
        ?: op.resultMessage
    val tone = when {
        op.state == OperationState.Failed || op.errorMessage != null -> StatusTone.Error
        op.state == OperationState.Partial -> StatusTone.Warning
        op.state == OperationState.Completed -> StatusTone.Success
        else -> StatusTone.Neutral
    }
    return OperationStatusFacts(
        name = operationLabel(op),
        state = operationStateLabel(op.state),
        progress = progress,
        readback = readback?.first,
        readbackTone = readback?.second ?: StatusTone.Neutral,
        result = result,
        resultTone = when {
            op.errorMessage != null || op.state == OperationState.Failed -> StatusTone.Error
            op.state == OperationState.Partial -> StatusTone.Warning
            else -> StatusTone.Neutral
        },
        tone = tone,
    )
}

/**
 * Permanent status block under the header: inset `surfaceContainer*` band (not a card,
 * not an error banner). Whole block is tappable (48 dp). One-line title
 * `Status: &lt;operation&gt; — &lt;state&gt;` (no subtitle, no separate expand control).
 * Idle/standalone use neutral ink. The title announces running operation state and
 * standalone messages, while the progress track remains visible without becoming a live
 * region. Thin rounded progress track only while running. No dividers; cancel lives only in
 * the dialog.
 */
@Composable
private fun OperationStatusLine(
    state: AppUiState,
    onOpenDetails: () -> Unit,
) {
    val facts = operationStatusFacts(state)
    val operation = state.operation
    val running = operation?.state == OperationState.Running ||
        operation?.state == OperationState.Queued
    val standaloneMessage = facts == null && state.statusMessage != null
    val openLabel = stringResource(R.string.shell_status_open_details)
    val titleText = when {
        facts != null -> stringResource(
            R.string.shell_status_headline,
            facts.name,
            facts.state,
        )
        state.statusMessage != null -> stringResource(
            R.string.shell_status_title,
            state.statusMessage.localized(),
        )
        else -> stringResource(
            R.string.shell_status_title,
            stringResource(R.string.shell_status_ready),
        )
    }
    // Idle / standalone: neutral ink. Active error: error ink. Else onSurface.
    val titleColor = when {
        facts == null -> MaterialTheme.colorScheme.onSurfaceVariant
        facts.tone == StatusTone.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    // Passive idle / standalone message: quieter low container. Active/terminal: high.
    // Never errorContainer — failure is ink, not a banner.
    val containerColor = if (facts == null) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 4.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = openLabel,
                onClick = onOpenDetails,
            )
            .heightIn(min = 48.dp),
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .then(
                        if (running || standaloneMessage) {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    ),
            )
            if (running) {
                val total = operation?.bytesTotal ?: 0L
                val fraction = (operation?.fraction ?: 0f).coerceIn(0f, 1f)
                val track = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                if (total > 0L) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = track,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    // Running without a known total: real indeterminate track, never on Ready.
                    LinearProgressIndicator(
                        modifier = track,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
        }
    }
}

/**
 * Centred status dialog from the status block. Title is always Status; below it the
 * operation name and a compact status (≤2 lines), then the temporary operation journal in
 * the shared journal format. No device-summary padding. Two-step cancel when running; Close
 * only dismisses — cancel stays the destructive confirmButton.
 */
@Composable
private fun StatusDialog(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val op = state.operation
    val facts = operationStatusFacts(state)
    val running = op?.state == OperationState.Running || op?.state == OperationState.Queued
    val cancelable = running == true && op?.cancelable == true
    data class CancelRequest(val token: OperationToken, val name: String)
    var cancelRequest by remember { mutableStateOf<CancelRequest?>(null) }
    val closeLabel = stringResource(R.string.shell_status_close)
    val techLines = state.operationLogLines
    val compactStatus = facts?.let { fact ->
        val progress = listOfNotNull(fact.state, fact.progress)
            .joinToString(FACT_SEPARATOR)
            .takeIf { it.isNotEmpty() }
            ?.let { it to fact.tone }
        // Prefer the localized readback verdict in the second line when present;
        // otherwise show the terminal operation result with its own tone.
        val output = (fact.readback ?: fact.result)?.let { text ->
            text to if (fact.readback != null) fact.readbackTone else fact.resultTone
        }
        listOfNotNull(progress, output).take(2)
    } ?: state.statusMessage?.let {
        listOf(it.localized() to StatusTone.Neutral)
    }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.status_dialog_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val name = facts?.name
                if (name != null || compactStatus.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        name?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        compactStatus.forEach { (text, tone) ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = tone.contentColor(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // Temporary operation journal only — shared journal renderer, no device summary.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (techLines.isEmpty()) {
                            Text(
                                text = stringResource(R.string.log_empty),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            techLines.forEach { line ->
                                JournalLine(line = line)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (cancelable && op != null && facts != null) {
                TextButton(
                    onClick = { cancelRequest = CancelRequest(op.token, facts.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(closeLabel) }
        },
    )
    cancelRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { cancelRequest = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.cancel_operation_title)) },
            text = { Text(stringResource(R.string.cancel_operation_body, request.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        cancelRequest = null
                        onIntent(UiIntent.CancelOperation(request.token))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.cancel_operation_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { cancelRequest = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** One monospace journal row shared by the status dialog and the full log screen. */
@Composable
private fun JournalLine(
    line: String,
    modifier: Modifier = Modifier,
) {
    val row = journalRow(line)
    val stampColor = MaterialTheme.colorScheme.onSurfaceVariant
    val levelColor = row.tone.contentColor()
    Text(
        text = buildAnnotatedString {
            row.time?.let {
                withStyle(SpanStyle(color = stampColor)) { append(it) }
                append(FACT_SEPARATOR)
            }
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Medium)) {
                append(row.level)
            }
            append(FACT_SEPARATOR)
            append(row.text)
        },
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontFeatureSettings = "tnum",
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** One journal row: `HH:mm:ss · Level · text`, blanks omitted. */
private data class JournalRow(
    val time: String?,
    val level: String,
    val text: String,
    val tone: StatusTone,
)

private val JOURNAL_STAMP = Regex("^\\[([0-9]{1,2}:[0-9]{2}:[0-9]{2})]\\s*(.*)$")

private val JOURNAL_ERROR_WORDS = listOf("failed", "error", "refused", "denied", "i/o")
private val JOURNAL_WARNING_WORDS =
    listOf("stale", "cancel", "partial", "not verified", "unverified", "timeout", "busy", "lost")
private val JOURNAL_SUCCESS_WORDS =
    listOf("verified", "completed", "copied", "success", "done", "ready")

// ponytail: AppendLog carries no level, so the row infers one from the message words.
// Replace this classifier with a stored level as soon as that intent grows the field.
private fun journalRow(line: String): JournalRow {
    val match = JOURNAL_STAMP.find(line.trim())
    val time = match?.groupValues?.get(1)
    val text = (match?.groupValues?.get(2) ?: line).trim()
    val lower = text.lowercase(Locale.ROOT)
    val tone = when {
        JOURNAL_ERROR_WORDS.any(lower::contains) -> StatusTone.Error
        JOURNAL_WARNING_WORDS.any(lower::contains) -> StatusTone.Warning
        JOURNAL_SUCCESS_WORDS.any(lower::contains) -> StatusTone.Success
        else -> StatusTone.Neutral
    }
    return JournalRow(
        time = time,
        level = when (tone) {
            StatusTone.Error -> "Error"
            StatusTone.Warning -> "Warning"
            StatusTone.Success -> "Success"
            StatusTone.Neutral -> "Info"
        },
        text = text,
        tone = tone,
    )
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val logPaneTitle = stringResource(R.string.log_screen_title)
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var confirmCopy by remember { mutableStateOf(false) }
    val backText = stringResource(R.string.log_back)
    val copyLabel = stringResource(R.string.log_copy_all)
    val clearLabel = stringResource(R.string.log_clear)
    val exportLabel = stringResource(R.string.log_export_trace)
    val clearSnackbar = stringResource(R.string.log_clear_snackbar)
    Column(
        modifier = modifier
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            IconButton(
                onClick = { confirmCopy = true },
                enabled = lines.isNotEmpty(),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = copyLabel },
            ) {
                GlyphIcon(glyph = Glyph.Copy, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = {
                    onIntent(UiIntent.ClearLog)
                    snackbarScope.launch { snackbarHostState.showSnackbar(clearSnackbar) }
                },
                enabled = lines.isNotEmpty(),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = clearLabel },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_sweep_outlined),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onExportTrace,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = exportLabel },
            ) {
                GlyphIcon(glyph = Glyph.Download, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(lines.size) { index ->
                    JournalLine(
                        line = lines[index],
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }

    if (confirmCopy) {
        AlertDialog(
            onDismissRequest = { confirmCopy = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
        ) {
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

/** AMOLED palette: true black background and dark tonal containers. */
private fun darkDesignColors() = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF0E1E4A),
    primaryContainer = Color(0xFF25252B), onPrimaryContainer = Color(0xFFE6E6EA),
    secondaryContainer = Color(0xFF25252B), onSecondaryContainer = Color(0xFFE6E6EA),
    tertiaryContainer = Color(0xFF25252B), onTertiaryContainer = Color(0xFFE6E6EA),
    background = Color.Black, onBackground = Color(0xFFE6E6EA),
    surface = Color.Black, onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF202027), onSurfaceVariant = Color(0xFFA3A3AD),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF121216),
    surfaceContainerHigh = Color(0xFF1D1D22),
    surfaceContainerHighest = Color(0xFF292930),
    outline = Color(0xFF3A3A42), outlineVariant = Color(0xFF303038),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6E6EA), inverseOnSurface = Color.Black,
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
 * Real free space of [partition], or null unless a measured snapshot still describes this
 * exact attachment: same attach generation, device identity, start LBA, sector count, sector
 * size and filesystem. Reads the durable batch map first (via [partitionUsageSnapshot]), then
 * the legacy selected slot when its key still matches. A reformatted partition or a re-plugged
 * stick with the same model but no serial therefore reports "unavailable" instead of stale
 * numbers, and the table's free regions are never substituted here.
 */
internal fun AppUiState.filesystemSpace(partition: PartitionTab): FilesystemSpace? {
    val snapshot = partitionUsageSnapshot(partition) ?: return null
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
                    .animateContentSize()
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
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
internal fun InfoRow(label: String, value: String, monospaceValue: Boolean = false) {
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
            style = if (monospaceValue) {
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                )
            } else {
                MaterialTheme.typography.bodySmall
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = if (monospaceValue) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
            softWrap = false,
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
 * Shared detached-drive header: USB icon + title + OTG hint (permission text only for
 * PermissionRequired). Busy never renders here; callers gate on Absent/Disconnected.
 */
@Composable
internal fun UsbEmptyState(
    presence: DevicePresence,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(Glyph.Usb, size = 48.dp)
        Text(stringResource(R.string.usb_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                if (presence == DevicePresence.PermissionRequired) R.string.usb_empty_permission
                else R.string.usb_empty_otg,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoHelpButton(infoTitle: String, infoText: String) {
    var showInfo by remember { mutableStateOf(false) }
    val closeLabel = stringResource(R.string.info_close)
    IconButton(
        onClick = { showInfo = true },
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = infoTitle },
    ) {
        // Material Symbols outlined "info" (google/material-design-icons,
        // src/action/info/materialiconsoutlined/24px.svg, Apache 2.0).
        Icon(
            painter = painterResource(R.drawable.ic_info_outlined),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            // Single scroll for title + body + close, so the close action is never
            // clipped on short screens or at large font scales; one close only.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = infoTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                // Body is authored as blocks (blank-line separated, **term** bold); the
                // same renderer serves every help sheet, so plain prose is one block.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val blocks = remember(infoText) { helpBlocks(infoText) }
                    blocks.forEach { block ->
                        Text(text = block)
                    }
                }
                FilledTonalButton(
                    onClick = { showInfo = false },
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                ) { Text(closeLabel) }
            }
        }
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
            modifier = Modifier
                .widthIn(max = 160.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    Check,
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
    /** Downward arrow: fetch bytes from the network into app storage. */
    Download,
    Pencil,
    Trash,
    ChevronDown,
    ChevronUp,
    ChevronLeft,
    /** Two crossing strokes: dismiss or clear. */
    Close,
    /** Filled square: cancel a running operation. */
    Stop,
    /** Three stacked lines: the in-app operation log. */
    Log,
    /** Overflow menu action. */
    More,
    /** Reveal a masked value. */
    Eye,
    /** Hide a revealed value. */
    EyeOff,
    /** Release bundled with the app. */
    Package,
    /** Release reported by the upstream server. */
    Cloud,
    /** Filled triangle: run the action now. */
    Play,
}

/** Duotone [Glyph]s that have a real `res/drawable` counterpart; null keeps the Canvas path. */
private fun Glyph.duotoneRes(): Int? = when (this) {
    Glyph.File -> R.drawable.ic_file
    Glyph.Iso -> R.drawable.ic_file_iso
    Glyph.Copy -> R.drawable.ic_copy
    Glyph.Refresh -> R.drawable.ic_refresh
    Glyph.Stop -> R.drawable.ic_stop
    Glyph.More -> R.drawable.ic_more
    Glyph.Play -> R.drawable.ic_play
    else -> null
}

@Composable
internal fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    // The packaged vectors keep their two tones because each path carries its own alpha and
    // only the RGB is replaced by [tint]; the rest are drawn below.
    val duotone = glyph.duotoneRes()
    if (duotone != null) {
        Icon(
            painter = painterResource(duotone),
            contentDescription = null,
            tint = tint,
            modifier = modifier
                .size(size)
                .clearAndSetSemantics { },
        )
        return
    }
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
                Glyph.Check -> drawPath(
                    Path().apply {
                        moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f)
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
                // Every other glyph is painted by the painterResource branch above.
                else -> Unit
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
        ShellTab.Settings -> stringResource(R.string.settings_title_screen)
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
                ShellTab.Settings -> R.drawable.ic_theme
            },
        ),
        contentDescription = null,
        tint = LocalContentColor.current,
        modifier = Modifier.size(24.dp),
    )
}
