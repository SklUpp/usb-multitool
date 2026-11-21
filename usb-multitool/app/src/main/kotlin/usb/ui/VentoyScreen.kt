package usb.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import usb.multitool.R
import usb.ventoy.FolderReadProblem

private enum class DownloadPrompt {
    Generic,
    Unpinned,
    AlreadyLatest,
}

@Composable
fun VentoyScreen(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    modifier: Modifier = Modifier,
    onPickWindowsFile: (WindowsImportKind, String?) -> Unit = { _, _ -> },
    onPickVentoySettingsFile: () -> Unit = {},
) {
    val bundled = state.bundledVentoyRelease
    val target = ventoyTargetRelease(bundled, state.downloadedVentoyRelease?.release)
    val comparison = ventoyReleaseComparison(state.installedVentoyRelease, target)
    // The update is always the same official operation whatever the version pair is; the
    // versions explain what changes, they never rename the action.
    val updateLabel = ventoyUpdateLabel(comparison)
    // Absent/Disconnected hides device-gated actions; Busy keeps them with enabled guards.
    val usbGone = state.presence == DevicePresence.Absent || state.presence == DevicePresence.Disconnected
    val releaseActionEnabled = state.actionsEnabled && state.ventoyInstallAvailable &&
        !state.ventoyDownloadInFlight &&
        (state.downloadedVentoyRelease != null || state.bundledVentoyRelease != null)
    // Remember epoch+part1 geometry we already asked catalogs for so actionsEnabled
    // flicker mid-flight does not triple-mount while caches are still null. Cleared when
    // all three land, epoch moves (invalidate), or part1 geometry changes after install.
    var catalogLoadEpoch by remember { mutableStateOf<Long?>(null) }
    var catalogLoadPart1Start by remember { mutableStateOf<ULong?>(null) }
    var catalogLoadPart1Count by remember { mutableStateOf<ULong?>(null) }

    val dataPart = state.ventoyDataPartition()
    val dataPartStart = dataPart?.startLba
    val dataPartCount = dataPart?.sectorCount

    LaunchedEffect(
        state.ventoyGlobalSettings,
        state.ventoyFolders,
        state.windowsIsoCandidates,
        state.ventoyCacheEpoch,
        dataPartStart,
        dataPartCount,
    ) {
        if (state.ventoyGlobalSettings != null &&
            state.ventoyFolders != null &&
            state.windowsIsoCandidates != null
        ) {
            // Keep geometry markers so a later part1 move still re-pins settings.
            catalogLoadEpoch = null
        } else if (catalogLoadEpoch != null && catalogLoadEpoch != state.ventoyCacheEpoch) {
            catalogLoadEpoch = null
            catalogLoadPart1Start = null
            catalogLoadPart1Count = null
        } else if (
            catalogLoadEpoch != null &&
            (catalogLoadPart1Start != dataPartStart || catalogLoadPart1Count != dataPartCount)
        ) {
            // refreshPartitions after install/update: allow another automatic pass.
            catalogLoadEpoch = null
            catalogLoadPart1Start = null
            catalogLoadPart1Count = null
        }
    }

    // Keys = external readiness + live part1 geometry. Do not key on cache==null:
    // fill/empty toggles would re-enter while catalogLoadEpoch is cleared and remount.
    // Null checks stay in the body. Part1 keys cover the post-install DeviceChanged that
    // used to leave settings stuck on "not loaded" after a failed first pass.
    LaunchedEffect(
        state.presence,
        state.device?.identity,
        state.device?.attachGeneration,
        state.actionsEnabled,
        state.ventoyCacheEpoch,
        dataPartStart,
        dataPartCount,
    ) {
        if (state.presence != DevicePresence.Ready ||
            state.device == null ||
            !state.actionsEnabled
        ) {
            return@LaunchedEffect
        }
        val pinsStale =
            state.ventoySettingsSource !is usb.ventoy.VentoySettingsSource.Document &&
                state.ventoySettingsSourceStartLba != null &&
                dataPart != null &&
                (state.ventoySettingsSourceStartLba != dataPart.startLba ||
                    state.ventoySettingsSourceSectorCount != dataPart.sectorCount)
        val pinsMissingNeedBind =
            state.ventoySettingsSource !is usb.ventoy.VentoySettingsSource.Document &&
                dataPart != null &&
                (state.ventoySettingsSourceStartLba == null ||
                    state.ventoySettingsSourceSectorCount == null)
        val needCatalogs =
            state.ventoyGlobalSettings == null ||
                state.ventoyFolders == null ||
                state.windowsIsoCandidates == null
        val needSettingsRepin = pinsStale || pinsMissingNeedBind
        if (!needCatalogs && !needSettingsRepin) return@LaunchedEffect
        if (needCatalogs &&
            catalogLoadEpoch == state.ventoyCacheEpoch &&
            catalogLoadPart1Start == dataPartStart &&
            catalogLoadPart1Count == dataPartCount
        ) {
            return@LaunchedEffect
        }
        if (needCatalogs) {
            catalogLoadEpoch = state.ventoyCacheEpoch
            catalogLoadPart1Start = dataPartStart
            catalogLoadPart1Count = dataPartCount
        }
        if (state.ventoyGlobalSettings == null || needSettingsRepin) {
            onIntent(UiIntent.LoadVentoyGlobalSettings)
        }
        if (needCatalogs) {
            if (state.ventoyFolders == null && !state.ventoyFoldersLoading) {
                onIntent(UiIntent.LoadVentoyFolders)
            }
            if (state.windowsIsoCandidates == null) onIntent(UiIntent.LoadWindowsIsoCandidates)
        }
    }



    val scrollState = rememberScrollState()
    // A detach inserts the empty state above the cards, so a remembered offset would leave
    // the message off-screen. Only the presence change rewinds; later user scrolls and open
    // dialogs/editors are untouched.
    LaunchedEffect(usbGone) {
        if (usbGone) scrollState.scrollTo(0)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // One scroll container: a short viewport or a large font scale never clips the
        // empty state, and every card stays reachable by scrolling.
        val viewportHeight = if (maxHeight.isFinite) maxHeight else 0.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (usbGone) {
                // Centered in the visible viewport. All cards stay mounted below, so an
                // open editor or dialog keeps its composition and unsaved drafts.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewportHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    UsbEmptyState(
                        presence = state.presence,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
            SectionCard(
                title = stringResource(R.string.ventoy_section_title),
                titleColor = MaterialTheme.colorScheme.onSurface,
                trailing = {
                    // Explanations (install erase, non-atomic update, check/download) live
                    // only behind help — no subtitle and no body paragraph under actions.
                    InfoHelpButton(
                        infoTitle = stringResource(R.string.ventoy_help_title),
                        infoText = stringResource(R.string.ventoy_help_text) +
                            "\n\n" + stringResource(R.string.ventoy_help_details),
                    )
                },
            ) {
                VentoyVersionRow(
                    state = state,
                    onIntent = onIntent,
                    updateLabel = updateLabel,
                    releaseActionEnabled = releaseActionEnabled,
                    showDeviceActions = !usbGone,
                )
                // The centered empty state already names the missing device; only Busy
                // and PermissionRequired still need the per-card hint.
                if (!state.actionsEnabled && !usbGone) {
                    EmptyHint(UiReducer.presenceMessage(state.presence).localized())
                }
                if (!state.ventoyInstallAvailable) {
                    Text(
                        text = stringResource(R.string.msg_ventoy_store_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.ventoyDownloadError?.let { error ->
                    Text(
                        text = stringResource(R.string.ventoy_download_failed, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.upstreamVentoyError?.let { error ->
                    Text(
                        text = stringResource(R.string.ventoy_upstream_check_failed, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }


            GlobalSettingsCard(
                state = state,
                onIntent = onIntent,
                onPickVentoySettingsFile = onPickVentoySettingsFile,
            )

            AutounattendSection(
                state = state,
                onIntent = onIntent,
                onPickVentoySettingsFile = onPickVentoySettingsFile,
                onPickWindowsFile = onPickWindowsFile,
                leadingContent = {
                    WindowsImageBlock(
                        state = state,
                        onIntent = onIntent,
                    )
                },
            )
        }
    }
}

/**
 * Compact Ventoy version card body: installed / bundled / server key-value rows,
 * server refresh, equal Update/Install when a stick is present. Refresh is check-only;
 * no auto upstream check. No uninstall action exists — do not invent one.
 */
@Composable
private fun VentoyVersionRow(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    updateLabel: String,
    releaseActionEnabled: Boolean,
    showDeviceActions: Boolean,
) {
    val naText = stringResource(R.string.ventoy_version_na)
    val errorText = stringResource(R.string.ventoy_version_error)
    val installedText = when {
        state.installedVentoyReadError != null -> errorText
        else -> state.installedVentoyRelease ?: naText
    }
    val bundledText = state.bundledVentoyRelease ?: naText
    val upstreamText = state.upstreamVentoyRelease ?: naText
    val checkLabel = stringResource(R.string.ventoy_check_version)
    val versionStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
    )
    val serverBusy = state.ventoyUpstreamChecking || state.ventoyDownloadInFlight


    // Download offer for Update: uses a checked upstream fact only. Refresh never opens this.
    var downloadPrompt by remember { mutableStateOf<DownloadPrompt?>(null) }
    var promptRelease by remember { mutableStateOf<String?>(null) }
    var promptPinned by remember { mutableStateOf<Boolean?>(null) }
    var promptRequest by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        downloadPrompt = null
        promptRelease = null
        promptPinned = null
        promptRequest = null
    }
    // Drop a stale prompt if a newer manual check replaced the bound upstream request.
    LaunchedEffect(state.ventoyUpstreamRequest) {
        if (promptRequest != null && promptRequest != state.ventoyUpstreamRequest) {
            downloadPrompt = null
            promptRelease = null
            promptPinned = null
            promptRequest = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ventoy_version_installed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = installedText,
                style = versionStyle,
                color = if (state.installedVentoyReadError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                softWrap = false,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ventoy_version_bundled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = bundledText,
                style = versionStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                softWrap = false,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ventoy_version_upstream),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = upstreamText,
                style = versionStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                softWrap = false,
            )
            IconButton(
                onClick = { onIntent(UiIntent.CheckVentoyUpstream) },
                enabled = !serverBusy,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = checkLabel },
            ) {
                RefreshingIcon(busy = serverBusy)
            }
        }

        if (showDeviceActions) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val upstream = state.upstreamVentoyRelease
                        val pinned = state.upstreamVentoyPinned
                        if (upstream != null && pinned != null) {
                            val current = state.downloadedVentoyRelease?.release
                                ?: state.installedVentoyRelease
                                ?: state.bundledVentoyRelease
                            val cmp = ventoyReleaseComparison(current, upstream)
                            if (cmp.order != null && cmp.order <= 0) {
                                onIntent(
                                    UiIntent.RequestDestructive(DestructiveAction.VENTOY_UPDATE),
                                )
                            } else {
                                downloadPrompt = if (pinned == false) {
                                    DownloadPrompt.Unpinned
                                } else {
                                    DownloadPrompt.Generic
                                }
                                promptRelease = upstream
                                promptPinned = pinned
                                promptRequest = state.ventoyUpstreamRequest
                            }
                        } else {
                            // No checked upstream yet — write bundled/downloaded only.
                            onIntent(
                                UiIntent.RequestDestructive(DestructiveAction.VENTOY_UPDATE),
                            )
                        }
                    },
                    enabled = releaseActionEnabled && !serverBusy,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = updateLabel },
                ) {
                    Text(stringResource(R.string.ventoy_update), fontWeight = FontWeight.SemiBold)
                }
                // Existing destructive format+install (not uninstall — none exists).
                Button(
                    onClick = {
                        onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL))
                    },
                    enabled = releaseActionEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ),
                ) {
                    Text(
                        stringResource(R.string.ventoy_install),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    val prompt = downloadPrompt
    val promptVersion = promptRelease
    if (prompt != null && promptVersion != null) {
        val upstream = promptVersion
        AlertDialog(
            onDismissRequest = {
                downloadPrompt = null
                promptRelease = null
                promptPinned = null
                promptRequest = null
            },
            title = {
                Text(
                    when (prompt) {
                        DownloadPrompt.Unpinned ->
                            stringResource(R.string.ventoy_download_unpinned_warning_title)
                        DownloadPrompt.AlreadyLatest ->
                            stringResource(R.string.ventoy_download_already_latest_title)
                        DownloadPrompt.Generic ->
                            stringResource(R.string.ventoy_download_warning_title)
                    },
                )
            },
            text = {
                Text(
                    when (prompt) {
                        DownloadPrompt.Unpinned -> stringResource(
                            R.string.ventoy_download_unpinned_warning_body,
                            upstream,
                        )
                        DownloadPrompt.AlreadyLatest -> stringResource(
                            R.string.ventoy_download_already_latest_body,
                            state.downloadedVentoyRelease?.release
                                ?: state.installedVentoyRelease
                                ?: state.bundledVentoyRelease.orEmpty(),
                            upstream,
                        )
                        DownloadPrompt.Generic ->
                            stringResource(R.string.ventoy_download_warning_body)
                    },
                )
            },
            confirmButton = {
                if (prompt != DownloadPrompt.AlreadyLatest) {
                    TextButton(
                        onClick = {
                            val release = promptRelease
                            val pinned = promptPinned
                            val request = promptRequest
                            downloadPrompt = null
                            promptRelease = null
                            promptPinned = null
                            promptRequest = null
                            if (release != null && pinned != null && request != null) {
                                onIntent(
                                    UiIntent.ConfirmVentoyReleaseDownload(
                                        release = release,
                                        pinned = pinned,
                                        upstreamRequest = request,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.ventoy_download_warning_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        downloadPrompt = null
                        promptRelease = null
                        promptPinned = null
                        promptRequest = null
                    },
                ) {
                    Text(stringResource(R.string.ventoy_download_warning_cancel))
                }
            },
        )
    }
}

/** Spinning refresh only while [busy]; idle draws a static icon (no per-frame transition). */
@Composable
private fun RefreshingIcon(busy: Boolean) {
    if (busy) {
        val transition = rememberInfiniteTransition(label = "ventoyUpstreamRefresh")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ventoyUpstreamRefreshRotation",
        )
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}




/**
 * The action is always "Update"; only the version pair changes. The official operation
 * keeps the data partition even when the installed release already equals the target, so
 * there is no "reinstall" label: that word implied reformatting that never happens.
 */
@Composable
private fun ventoyUpdateLabel(comparison: VentoyReleaseComparison): String =
    when {
        comparison.installed != null &&
            comparison.target != null &&
            !comparison.same ->
            stringResource(
                R.string.ventoy_update_from_to,
                comparison.installed,
                comparison.target,
            )
        comparison.target != null ->
            stringResource(R.string.ventoy_update_to, comparison.target)
        else -> stringResource(R.string.ventoy_update)
    }


/**
 * Windows image selection and copy status block placed inside the unified Windows section.
 * Phone import and device file selection via real callbacks, with
 * honest copying progress and confirmation gates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowsImageBlock(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
) {
    val sourceDialogIdentity = state.device?.identity
    val sourceDialogGeneration = state.device?.attachGeneration
    var imageSourcePrompt by remember(
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf(false) }

    val selectedImage = state.filesPickSelections[FilesPickPurpose.WindowsImage]
    val operation = state.operation
    val isOpActive = operation?.state == OperationState.Running || operation?.state == OperationState.Queued
    val isImageImportTarget = isOpActive &&
        operation?.title is OperationTitle.ImportTarget &&
        state.filesPickMode?.purpose == FilesPickPurpose.WindowsImage

    val isImageInspecting = (state.isoImportSafInFlight != null || state.isoImportUsbInFlight != null) &&
        state.filesPickMode?.purpose == FilesPickPurpose.WindowsImage

    val copyingTarget = (operation?.title as? OperationTitle.ImportTarget)?.targetName
        ?: selectedImage?.name

    val statusText = when {
        isImageImportTarget && copyingTarget != null ->
            stringResource(R.string.windows_copying_image, copyingTarget)
        isImageImportTarget ->
            stringResource(R.string.windows_copying_generic)
        selectedImage != null ->
            selectedImage.name.ifBlank { selectedImage.path }
        else ->
            stringResource(R.string.selection_not_selected)
    }

    val chooseDescription = stringResource(R.string.ventoy_pick_windows_image_cd)
    // Detached stick keeps the row and its empty state, only the pick action is disabled:
    // the image is written to the stick, so no source can be chosen without one.
    val canPickImage = state.actionsEnabled && state.presence != DevicePresence.Absent &&
        state.presence != DevicePresence.Disconnected

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.windows_selected_image),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedImage != null || isImageImportTarget) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontFamily = if (selectedImage != null || isImageImportTarget) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { imageSourcePrompt = true },
                enabled = canPickImage,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = chooseDescription },
            ) {
                GlyphIcon(Glyph.File, size = 20.dp)
            }
        }

        if (state.filesPickMode?.purpose == FilesPickPurpose.WindowsImage) {
            if (isImageInspecting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.import_inspecting),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onIntent(UiIntent.CancelWindowsIsoImport) }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }

            state.isoImportError?.let { problem ->
                Text(
                    text = stringResource(unreadableText(problem)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.animateContentSize(),
                )
                TextButton(onClick = { onIntent(UiIntent.CancelWindowsIsoImport) }) {
                    Text(stringResource(R.string.import_error_dismiss))
                }
            }
        }
    }

    if (imageSourcePrompt) {
        ModalBottomSheet(
            onDismissRequest = { imageSourcePrompt = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = chooseDescription,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.raw_choose_stick)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_pick_file_usb_duotone),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        imageSourcePrompt = false
                        onIntent(
                            UiIntent.BeginFilesPick(
                                purpose = FilesPickPurpose.WindowsImage,
                                returnTab = ShellTab.Ventoy,
                                launchSource = FilesPickLaunchSource.Files,
                                expectedIdentity = sourceDialogIdentity,
                                expectedAttachGeneration = sourceDialogGeneration,
                            ),
                        )
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.raw_choose_phone)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_pick_file_phone_duotone),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        imageSourcePrompt = false
                        onIntent(
                            UiIntent.BeginFilesPick(
                                purpose = FilesPickPurpose.WindowsImage,
                                returnTab = ShellTab.Ventoy,
                                launchSource = FilesPickLaunchSource.Phone,
                                expectedIdentity = sourceDialogIdentity,
                                expectedAttachGeneration = sourceDialogGeneration,
                            ),
                        )
                    },
            )
        }
    }

    state.pendingIsoImport?.takeIf { it.kind == WindowsImportKind.Image }?.let { proposal ->
        ImportOverwriteDialog(
            proposal = proposal,
            actionsEnabled = state.actionsEnabled,
            onReplace = { onIntent(UiIntent.ConfirmWindowsIsoImport(true)) },
            onCancel = { onIntent(UiIntent.CancelWindowsIsoImport) },
        )
    }
}

/**
 * One copy target: what is copied, where the last pick landed (or that nothing is chosen
 * yet), and the action that opens the folder list. The destination is always shown, so the
 * copy is never implied to go somewhere unstated.
 */
@Composable
private fun ImportRow(
    label: String,
    destination: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // One wrapping line: a folder path is longer than an InfoRow value slot and must not
        // be cut to "…/very/long/fol…".
        Text(
            text = stringResource(
                R.string.import_destination_line,
                destination ?: stringResource(R.string.selection_not_selected),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (destination == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontFamily = if (destination == null) FontFamily.Default else FontFamily.Monospace,
        )
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.import_choose))
        }
    }
}

/**
 * One existing directory level at a time. Tapping a row enters that folder, Up goes to the
 * parent, and the confirm button chooses the folder that is open right now. A listing that
 * was not read for the current path, or that failed, cannot confirm anything: it is
 * "unreadable", never "gone", and it never emits a hint about a remembered destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportFolderDialog(
    currentPath: String,
    folders: List<String>?,
    folderError: FolderReadProblem?,
    loading: Boolean,
    blocked: Boolean,
    onEnter: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val listed = folders.orEmpty()
    val trusted = folders != null && folderError == null && !loading
    val cancelDescription = stringResource(R.string.action_cancel)
    val parent = usb.ventoy.folderParent(currentPath)
    val errorText =
        when (folderError) {
            FolderReadProblem.Missing -> stringResource(R.string.import_folder_missing)
            FolderReadProblem.NotDirectory -> stringResource(R.string.import_folder_not_directory)
            FolderReadProblem.Unreadable -> stringResource(R.string.import_folder_read_failed)
            null -> null
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.import_folder_title), style = MaterialTheme.typography.titleLarge)
                Text(text = stringResource(R.string.import_folder_current, currentPath), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), softWrap = true, modifier = Modifier.semantics { heading() })
            }
            IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = cancelDescription }) { GlyphIcon(Glyph.Close) }
        }
        Text(text = stringResource(R.string.import_folder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        if (blocked) {
            Text(text = stringResource(R.string.import_folder_blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        }
        if (loading && listed.isNotEmpty()) {
            Text(text = stringResource(R.string.import_folder_refreshing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        }
        when {
            errorText != null -> ListItem(headlineContent = { Text(text = errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) })
            loading && listed.isEmpty() -> ListItem(headlineContent = { Text(text = stringResource(R.string.import_folder_loading), style = MaterialTheme.typography.bodySmall) })
            folders == null && !blocked -> ListItem(headlineContent = { Text(text = stringResource(R.string.import_folder_not_loaded), style = MaterialTheme.typography.bodySmall) })
            folders == null -> Unit
            listed.isEmpty() -> ListItem(headlineContent = { Text(text = stringResource(R.string.import_folder_empty), style = MaterialTheme.typography.bodySmall) })
            else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(items = listed, key = { it }) { folder ->
                    val name = usb.ventoy.folderName(folder)
                    val openLabel = stringResource(R.string.import_folder_open, name)
                    ListItem(
                        headlineContent = { Text(text = "$name/", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.StartEllipsis, softWrap = false) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_file_folder),
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { onEnter(folder) }, enabled = !blocked).clearAndSetSemantics { contentDescription = openLabel },
                    )
                }
            }
        }
        ListItem(headlineContent = { Text(stringResource(R.string.import_folder_use)) }, supportingContent = { Text(text = stringResource(R.string.import_destination_line, currentPath), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), softWrap = true) }, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, enabled = trusted && !blocked, onClick = { onPick(currentPath) }))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (parent != null) {
                TextButton(onClick = onUp, enabled = !blocked) { Text(stringResource(R.string.import_folder_up)) }
            }
            TextButton(onClick = onRefresh, enabled = !blocked) { Text(stringResource(R.string.unattend_refresh)) }
        }
    }
}

/**
 * Existing target: replacing it is destructive, and a template that brings layout or
 * destructive sections needs a second explicit tick. Nothing here deletes the phone copy.
 */
@Composable
internal fun ImportOverwriteDialog(
    proposal: IsoImportProposal,
    actionsEnabled: Boolean,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    var overwriteAck by remember(proposal) { mutableStateOf(false) }
    var markersAck by remember(proposal) { mutableStateOf(proposal.templateMarkers.isEmpty()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_overwrite_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.import_overwrite_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                InfoRow(
                    stringResource(R.string.import_target_label),
                    if (proposal.directory == "/") {
                        "/${proposal.targetName}"
                    } else {
                        "${proposal.directory}/${proposal.targetName}"
                    },
                    monospaceValue = true,
                )
                proposal.sizeBytes?.let { size ->
                    InfoRow(
                        stringResource(R.string.import_size_label),
                        DeviceIdentity.formatCapacity(size.coerceAtLeast(0L).toULong()),
                        monospaceValue = true,
                    )
                }
                if (proposal.templateMarkers.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.import_markers_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    proposal.templateMarkers.forEach { marker ->
                        Text(
                            text = marker,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = markersAck,
                                enabled = actionsEnabled,
                                role = Role.Checkbox,
                                onValueChange = { markersAck = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = markersAck,
                            onCheckedChange = null,
                            enabled = actionsEnabled,
                        )
                        Text(
                            text = stringResource(R.string.import_markers_ack),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = overwriteAck,
                            enabled = actionsEnabled,
                            role = Role.Checkbox,
                            onValueChange = { overwriteAck = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = overwriteAck,
                        onCheckedChange = null,
                        enabled = actionsEnabled,
                    )
                    Text(
                        text = stringResource(R.string.import_overwrite_ack),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onReplace,
                enabled = actionsEnabled && overwriteAck && markersAck,
            ) { Text(stringResource(R.string.import_overwrite_action)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
