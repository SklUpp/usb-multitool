package usb.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * While a Files pick session owns the screen the header, navigation bar and log
 * layer hide; the tab swipe stays off and status stays non-interactive. The
 * existing picker branch is still the tab content with its own continue/back
 * controls, and Back routes to the reducer cancel, which refuses while a copy
 * runs or a confirmation is open.
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
    onRequestBatchDownload: (FilesBatchSelection) -> Unit = {},
    /** Opens OpenMultipleDocuments into the pinned current Files directory. */
    onRequestBatchUpload: () -> Unit = {},
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
        val pickOpen = state.filesPickMode != null
        var settingsReturnTab by rememberSaveable { mutableStateOf(ShellTab.Files.name) }
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
        var completedHapticToken by rememberSaveable { mutableStateOf<Long?>(null) }
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
            val token = state.operation?.token?.value
            if (state.operation?.state == OperationState.Completed && token != null &&
                token != completedHapticToken
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                completedHapticToken = token
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
        LaunchedEffect(state.operation?.token, state.operation?.state) {
            // Evaluate only when operation facts change. A later tab switch must not reopen a
            // status the user deliberately collapsed.
            val operation = state.operation ?: return@LaunchedEffect
            val isFinished = operation.state == OperationState.Completed ||
                operation.state == OperationState.Failed ||
                operation.state == OperationState.Partial
            val ownerTab = operation.title.shellTab()
            if (isFinished && ownerTab != null && ownerTab != state.tab) {
                panels = panels.expandStatus()
            }
        }
        val statusVisible = state.operation != null || state.statusMessage != null
        Scaffold(
            modifier = modifier.fillMaxSize(),
            // targetSdk 36 draws edge to edge: the header, the tab content and the log
            // layer must stay inside the status/navigation bars and any display cutout.
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                Column(
                    modifier = Modifier
                        .then(
                            if (panels.journalScreenOpen) {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .focusProperties {
                            // Keyboard traversal must not enter background status
                            // while the pick task owns the screen.
                            if (pickOpen) {
                                onEnter = { cancelFocusChange() }
                            }
                        }
                        .then(
                            if (pickOpen) {
                                // Background status is visible but inert: remove the whole
                                // bar from the accessibility tree while the picker owns input.
                                Modifier.semantics(mergeDescendants = true) {
                                    hideFromAccessibility()
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // Expanded status participates in layout; collapsed status is an overlay below.
                    AnimatedVisibility(
                        visible = !panels.statusCollapsed && statusVisible,
                        enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(200)),
                        exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(100)),
                    ) {
                        StatusLogPanel(
                            state = state,
                            onIntent = onIntent,
                            panels = panels,
                            onCollapseStatus = { panels = panels.collapseStatus() },
                            onExpandStatus = { panels = panels.expandStatus() },
                            interactive = statusVisible && !panels.statusCollapsed && !pickOpen,
                        )
                    }
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.tab != ShellTab.Settings && !pickOpen) {
                        DeviceHeader(
                            state = state,
                            logButtonFocus = logButtonFocus,
                            onIntent = onIntent,
                            onOpenSettings = {
                                if (state.tab in PrimaryShellTabs) settingsReturnTab = state.tab.name
                                onIntent(UiIntent.SelectTab(ShellTab.Settings))
                            },
                            onOpenLog = {
                                restoreLogFocus = true
                                panels = panels.openJournalScreen()
                            },
                        )
                    }
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
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    bottom = if (panels.statusCollapsed &&
                                        (state.operation != null || state.statusMessage != null)
                                    ) 72.dp else 0.dp,
                                ),
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
                            val gated = target in listOf(ShellTab.Files, ShellTab.Partitions)
                            val visible = !gated || usbContentAvailable(state.presence)
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
                                    onPickBackupFolder = onPickBackupFolder,
                                    onStartRescueBackup = onStartRescueBackup,
                                    onCreateGptCopy = onCreateGptCopy,
                                    onPickRestoreFolder = onPickRestoreFolder,
                                )
                                ShellTab.Settings -> SettingsScreen(
                                    state = state,
                                    versionName = installedVersionName(LocalContext.current),
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
                            modifier = Modifier.padding(
                                bottom = if (panels.statusCollapsed &&
                                    (state.operation != null || state.statusMessage != null)
                                ) 80.dp else 0.dp,
                            ),
                        )
                    }
                }
                AnimatedVisibility(
                    visible = panels.statusCollapsed && statusVisible && !pickOpen,
                    enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(200)),
                    exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(100)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .focusProperties {
                            if (pickOpen) {
                                onEnter = { cancelFocusChange() }
                            }
                        }
                        .then(
                            if (pickOpen) {
                                Modifier.semantics(mergeDescendants = true) {
                                    hideFromAccessibility()
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    StatusLogPanel(
                        state = state,
                        onIntent = onIntent,
                        panels = panels,
                        onCollapseStatus = { panels = panels.collapseStatus() },
                        onExpandStatus = { panels = panels.expandStatus() },
                        interactive = statusVisible && panels.statusCollapsed && !pickOpen,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
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
                    val confirmEnabled = acknowledged && wipeDelayReady
                    TextButton(
                        onClick = { onIntent(UiIntent.ConfirmDestructive) },
                        enabled = confirmEnabled,
                    ) {
                        Text(
                            text = stringResource(R.string.action_confirm),
                            color = if (confirmEnabled) {
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
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        modifier = Modifier
            .heightIn(min = 36.dp)
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
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
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
                            onClick = { moreOpen = false; onIntent(UiIntent.RequestEjectRescan) },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
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

/** Operation status surface; Journal and overwrite prompts stay outside status. */
@Composable
internal fun StatusLogPanel(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    panels: ShellPanelState,
    onCollapseStatus: () -> Unit,
    onExpandStatus: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
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
    val operationDetail = op?.errorMessage ?: op?.readScanResult?.let { readScanSummary(it) } ?: op?.resultMessage
    val statusText = state.statusMessage?.localized()
    val details = buildList {
        operationDetail?.let(::add)
        statusText?.takeIf { it != operationDetail }?.let(::add)
    }
    val batchCurrentFile = op?.title?.reportsCurrentFileBytes() == true
    val etaSeconds = if (op != null && running) remainingSeconds(op.bytesDone, op.bytesTotal, op.bytesPerSecond) else null
    val etaLabel = etaSeconds?.let { stringResource(R.string.op_eta, formatEtaDuration(it)) }
    val metric = when {
        op == null -> null
        completed -> "✓"
        op.bytesTotal > 0L && batchCurrentFile ->
            stringResource(R.string.op_batch_current_file_pct, (op.fraction * 100).toInt())
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
    val accessibleMessage = buildList {
        add(title)
        metric?.let(::add)
        etaLabel?.let(::add)
        details.forEach(::add)
        readbackNote?.takeIf { it !in details }?.let(::add)
    }.joinToString(" · ")
    data class CancelRequest(val token: OperationToken, val name: String)
    var cancelRequest by remember { mutableStateOf<CancelRequest?>(null) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    val cancelDescription = stringResource(R.string.action_cancel)
    val statusDescription = if (collapsed) stringResource(R.string.status_expand) else stringResource(R.string.status_collapse)
    val detailsDescription = stringResource(R.string.status_title)
    fun requestCancel() {
        op?.takeIf { cancelable }?.let {
            cancelRequest = CancelRequest(it.token, operationName ?: title)
        }
    }
    val circleDiameter = 64.dp
    val circleRadius = 32.dp
    val statusSurface = Modifier
        .then(
            if (collapsed) {
                Modifier
                    .size(circleDiameter)
                    .clip(CircleShape)
                    .combinedClickable(
                        enabled = interactive,
                        role = Role.Button,
                        onClickLabel = statusDescription,
                        onLongClickLabel = if (interactive && cancelable) cancelDescription else null,
                        onClick = onExpandStatus,
                        onLongClick = if (interactive && cancelable) ::requestCancel else null,
                    )
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(circleDiameter)
            },
        )
        .semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = accessibleMessage
        }
    Column(
        modifier = modifier
            .then(if (collapsed) Modifier else Modifier.fillMaxWidth())
            .animateContentSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = if (collapsed) CircleShape else RoundedCornerShape(circleRadius),
            color = container,
            modifier = statusSurface,
        ) {
            if (collapsed) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Line 1: Primary progress metric
                    if (completed) {
                        GlyphIcon(Glyph.Check, size = 20.dp, tint = content)
                    } else if (failed) {
                        Text(
                            text = "!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = content,
                            textAlign = TextAlign.Center,
                        )
                    } else if (partial) {
                        Text(
                            text = "±",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = content,
                            textAlign = TextAlign.Center,
                        )
                    } else if (cancelled) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = content,
                            textAlign = TextAlign.Center,
                        )
                    } else if (metric != null) {
                        Text(
                            text = metric,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = content,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (op != null) {
                        Text(
                            text = operationStateLabel(op.state),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = content,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.status_title),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = content,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Line 2: Secondary progress value / status text
                    if (completed) {
                        Text(
                            text = stringResource(R.string.op_completed),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (failed) {
                        Text(
                            text = stringResource(R.string.op_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (partial) {
                        Text(
                            text = stringResource(R.string.op_partial),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (cancelled) {
                        Text(
                            text = stringResource(R.string.op_cancelled),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (etaSeconds != null) {
                        Text(
                            text = formatEtaDuration(etaSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Clip,
                        )
                    } else if (op != null && op.bytesPerSecond > 0L) {
                        Text(
                            text = formatBinaryBytesPerSecond(op.bytesPerSecond),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (op != null && op.bytesTotal > 0L && op.bytesDone > 0L) {
                        Text(
                            text = DeviceIdentity.formatCapacity(op.bytesDone.toULong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (operationName != null) {
                        Text(
                            text = operationName,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            text = statusText ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.9f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (completed) {
                            GlyphIcon(Glyph.Check, size = 20.dp, tint = content)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState(), enabled = interactive),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = content,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val primaryDetail = details.firstOrNull() ?: readbackNote
                            if (primaryDetail != null) {
                                Text(
                                    text = primaryDetail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = content,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        metric?.let {
                            Text(
                                text = if (running && etaLabel != null && op != null && op.bytesTotal > 0L) "$it · $etaLabel" else it,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = content,
                            )
                        }
                        if (interactive && cancelable) {
                            IconButton(
                                onClick = ::requestCancel,
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = cancelDescription },
                            ) {
                                GlyphIcon(glyph = Glyph.Stop, size = 18.dp, tint = content)
                            }
                        }
                        IconButton(
                            onClick = { showDetailsDialog = true },
                            enabled = interactive,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = detailsDescription },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info_outlined),
                                contentDescription = null,
                                tint = content,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = onCollapseStatus,
                            enabled = interactive,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = statusDescription },
                        ) {
                            GlyphIcon(glyph = Glyph.ChevronDown, size = 18.dp, tint = content)
                        }
                    }
                    if (op != null && running) {
                        if (op.bytesTotal > 0L) {
                            LinearProgressIndicator(
                                progress = { op.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .height(3.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = content.copy(alpha = 0.2f),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .height(3.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = content.copy(alpha = 0.2f),
                            )
                        }
                    }
                }
            }
        }
    }
    if (showDetailsDialog && interactive) {
        val context = LocalContext.current
        val fullDetailsText = buildList {
            add(title)
            if (op != null) {
                add("#${op.token.value}")
                if (op.bytesTotal > 0L && batchCurrentFile) {
                    add(
                        stringResource(
                            R.string.op_batch_current_file_bytes,
                            DeviceIdentity.formatCapacity(op.bytesDone.toULong()),
                            DeviceIdentity.formatCapacity(op.bytesTotal.toULong()),
                        ),
                    )
                } else if (op.bytesTotal > 0L) {
                    add("${(op.fraction * 100).toInt()}% · ${DeviceIdentity.formatCapacity(op.bytesDone.toULong())} / ${DeviceIdentity.formatCapacity(op.bytesTotal.toULong())}")
                } else if (op.bytesDone > 0L) {
                    add(DeviceIdentity.formatCapacity(op.bytesDone.toULong()))
                }
                if (op.bytesPerSecond > 0L) {
                    add(formatBinaryBytesPerSecond(op.bytesPerSecond))
                }
                if (running && etaLabel != null) etaLabel.let(::add)
                op.errorMessage?.let(::add)
                op.resultMessage?.let(::add)
                op.readScanResult?.let { add(readScanSummary(it)) }
                readbackNote?.let { if (it !in details) add(it) }
            }
            statusText?.takeIf { it != op?.errorMessage && it != op?.resultMessage }?.let(::add)
        }.joinToString("\n")
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = fullDetailsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("status", fullDetailsText))
                        onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.LogCopied)))
                    },
                ) {
                    Text(stringResource(R.string.log_copy_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetailsDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    cancelRequest?.takeIf { interactive }?.let { request ->
        AlertDialog(
            onDismissRequest = { cancelRequest = null },
            title = { Text(stringResource(R.string.cancel_operation_title)) },
            text = { Text(stringResource(R.string.cancel_operation_body, request.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        cancelRequest = null
                        onIntent(UiIntent.CancelOperation(request.token))
                    },
                ) {
                    Text(stringResource(R.string.cancel_operation_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelRequest = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
                GlyphIcon(glyph = Glyph.Trash, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            ) {
                items(lines.size) { index ->
                    Text(
                        text = lines[index],
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                        ),
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
        SnackbarHost(
            hostState = snackbarHostState,
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
                Text(text = infoText)
                TextButton(
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
                Glyph.More -> {
                    drawCircle(tint, radius = 1.7f, center = androidx.compose.ui.geometry.Offset(12f, 5.5f))
                    drawCircle(tint, radius = 1.7f, center = androidx.compose.ui.geometry.Offset(12f, 12f))
                    drawCircle(tint, radius = 1.7f, center = androidx.compose.ui.geometry.Offset(12f, 18.5f))
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
