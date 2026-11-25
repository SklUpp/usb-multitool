package usb.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import usb.multitool.R

internal fun reducedMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}.getOrDefault(false)

internal fun usbContentAvailable(presence: DevicePresence): Boolean =
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

internal fun shellTabSwitchBlocked(
    tab: ShellTab,
    selected: ShellTab,
    operationLocked: Boolean,
    readOnlyReturn: ShellTab?,
): Boolean =
    tab != selected && tab != ShellTab.Diagnostics &&
        !((selected == ShellTab.Settings || selected == ShellTab.Diagnostics) &&
            tab == readOnlyReturn) && operationLocked

@Immutable
internal data class ShellTabEntry(val tab: ShellTab, val enabled: Boolean)

@Immutable
internal data class ShellTabsModel(val entries: List<ShellTabEntry>)

@Composable
internal fun ShellTabBar(
    selected: ShellTab,
    entries: ShellTabsModel,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        entries.entries.forEach { entry ->
            val tab = entry.tab
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                enabled = entry.enabled,
                icon = { TabIcon(tab) },
                label = {
                    Text(
                        text = tab.title(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
internal fun ShellTabRail(
    selected: ShellTab,
    entries: ShellTabsModel,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        entries.entries.forEach { entry ->
            val tab = entry.tab
            NavigationRailItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                enabled = entry.enabled,
                icon = { TabIcon(tab) },
                label = {
                    Text(
                        text = tab.title(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
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
internal suspend fun PointerInputScope.detectShellSwipe(
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

@Composable
internal fun ShellTabs(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onPickImage: () -> Unit,
    onPickWindowsFile: (WindowsImportKind, String?) -> Unit,
    onPickWindowsImage: (String, String) -> Unit,
    onPickVentoySettingsFile: () -> Unit,
    onRequestDownload: (FilesDownloadBinding) -> Unit,
    onRequestBatchDownload: (FilesBatchSelection) -> Unit,
    onRequestBatchUpload: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onPickRescueFile: () -> Unit,
    onPickRescueMap: () -> Unit,
    onPickRescueFolder: () -> Unit,
    onPickGptFile: () -> Unit,
    onStartRescueBackup: () -> Unit,
    onCreateGptCopy: () -> Unit,
    backupFolderGrantValid: () -> Boolean,
    onOpenBackupSettings: () -> Unit,
    onBackFromSettings: () -> Unit,
    versionName: String?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val context = LocalContext.current
    val reducedMotion = remember(context) { reducedMotionEnabled(context) }
    val tabStateHolder = rememberSaveableStateHolder()
    val swipeThreshold = with(density) {
        ShellSwipePolicy.THRESHOLD_DP.dp.toPx()
    }
    val systemGestures = WindowInsets.systemGestures
    val leftBackEdge = systemGestures.getLeft(density, layoutDirection).toFloat()
    val rightBackEdge = systemGestures.getRight(density, layoutDirection).toFloat()
    val pickOpen = state.filesPickMode != null
    val operationLocked = state.deviceBusy

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (pickOpen || operationLocked) {
                    // Modal pick/operation pins navigation; a sideways drag
                    // must not move away from the originating work tab.
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
                    fadeIn(tween(120)) togetherWith fadeOut(tween(90))
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
                // Nested presence transition: tab switches fade above;
                // USB attach/detach fades the empty state within the same tab.
                val visible = shellTabContentVisible(target, state.presence, pickOpen)
                AnimatedContent(
                    targetState = visible,
                    transitionSpec = {
                        if (reducedMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(tween(120)) togetherWith fadeOut(tween(90))
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
                        tabStateHolder.SaveableStateProvider(target.name) {
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
                                onPickBackupFolder = onPickBackupFolder,
                                onPickRescueFile = onPickRescueFile,
                                onPickRescueMap = onPickRescueMap,
                                onPickRescueFolder = onPickRescueFolder,
                                onPickGptFile = onPickGptFile,
                                onStartRescueBackup = onStartRescueBackup,
                                onCreateGptCopy = onCreateGptCopy,
                                onOpenBackupSettings = onOpenBackupSettings,
                                backupFolderGrantValid = backupFolderGrantValid,
                            )
                            ShellTab.Settings -> SettingsScreen(
                                state = state,
                                versionName = versionName,
                                onIntent = onIntent,
                                onBack = onBackFromSettings,
                                onPickBackupFolder = onPickBackupFolder,
                            )
                        }
                        }
                    }
                }
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
