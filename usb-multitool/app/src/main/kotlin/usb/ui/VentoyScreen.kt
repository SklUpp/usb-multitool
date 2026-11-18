package usb.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
    // Absent/Disconnected hides device-gated actions; Busy keeps them with enabled guards.
    val usbGone = state.presence == DevicePresence.Absent || state.presence == DevicePresence.Disconnected
    // Remember epoch we already asked catalogs for so actionsEnabled flicker mid-flight
    // does not triple-mount while caches are still null. Cleared when all three land or
    // epoch moves (invalidate).
    var catalogLoadEpoch by remember { mutableStateOf<Long?>(null) }
    // One automatic upstream check per attachment while no release is known: a failed
    // check ends as n/a and does not retry on every recomposition. The check itself
    // is global, so a landed release is kept and not re-requested.
    var upstreamCheckedGeneration by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(
        state.ventoyGlobalSettings,
        state.ventoyFolders,
        state.windowsIsoCandidates,
        state.ventoyCacheEpoch,
    ) {
        if (state.ventoyGlobalSettings != null &&
            state.ventoyFolders != null &&
            state.windowsIsoCandidates != null
        ) {
            catalogLoadEpoch = null
        } else if (catalogLoadEpoch != null && catalogLoadEpoch != state.ventoyCacheEpoch) {
            catalogLoadEpoch = null
        }
    }

    // Keys = external readiness only. Do not key on cache==null: fill/empty toggles would
    // re-enter while catalogLoadEpoch is cleared and remount. Null checks stay in the body.
    LaunchedEffect(
        state.presence,
        state.device?.identity,
        state.device?.attachGeneration,
        state.actionsEnabled,
        state.ventoyCacheEpoch,
    ) {
        if (state.presence != DevicePresence.Ready ||
            state.device == null ||
            !state.actionsEnabled
        ) {
            return@LaunchedEffect
        }
        val need =
            state.ventoyGlobalSettings == null ||
                state.ventoyFolders == null ||
                state.windowsIsoCandidates == null
        if (!need) return@LaunchedEffect
        if (catalogLoadEpoch == state.ventoyCacheEpoch) {
            return@LaunchedEffect
        }
        catalogLoadEpoch = state.ventoyCacheEpoch
        if (state.ventoyGlobalSettings == null) onIntent(UiIntent.LoadVentoyGlobalSettings)
        if (state.ventoyFolders == null && !state.ventoyFoldersLoading) {
            onIntent(UiIntent.LoadVentoyFolders)
        }
        if (state.windowsIsoCandidates == null) onIntent(UiIntent.LoadWindowsIsoCandidates)
    }

    LaunchedEffect(
        state.device?.attachGeneration,
        state.ventoyUpstreamChecking,
    ) {
        val generation = state.device?.attachGeneration ?: return@LaunchedEffect
        if (upstreamCheckedGeneration == generation ||
            state.ventoyUpstreamChecking ||
            state.upstreamVentoyRelease != null
        ) {
            return@LaunchedEffect
        }
        upstreamCheckedGeneration = generation
        onIntent(UiIntent.CheckVentoyUpstream)
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
                .verticalScroll(scrollState),
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
                subtitle = stringResource(R.string.ventoy_version_subtitle),
                titleColor = MaterialTheme.colorScheme.onSurface,
                trailing = {
                    InfoHelpButton(
                        infoTitle = stringResource(R.string.ventoy_help_title),
                        infoText = stringResource(R.string.ventoy_help_text) +
                            "\n\n" + stringResource(R.string.ventoy_help_details),
                    )
                },
            ) {
                VentoyVersionRow(state = state, onIntent = onIntent)
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
                if (!usbGone) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL)) },
                            enabled = state.actionsEnabled && state.ventoyInstallAvailable &&
                                !state.ventoyDownloadInFlight &&
                                (state.downloadedVentoyRelease != null || state.bundledVentoyRelease != null),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        ) {
                            Text(stringResource(R.string.ventoy_install), fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onIntent(UiIntent.RequestDestructive(DestructiveAction.VENTOY_UPDATE)) },
                            enabled = state.actionsEnabled && state.ventoyInstallAvailable &&
                                !state.ventoyDownloadInFlight &&
                                (state.downloadedVentoyRelease != null || state.bundledVentoyRelease != null),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = ventoyUpdateLabel(comparison),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
 * Three real version facts, plus the two read-only actions. A missing fact renders as
 * n/a instead of silently borrowing another release. The check and download actions never
 * mount the stick; download persists only verified bytes in app-private storage.
 */
@Composable
private fun VentoyVersionRow(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
) {
    val checkDownloadLabel = stringResource(R.string.ventoy_check_download)
    var pendingDownloadCheck by remember { mutableStateOf(false) }
    var downloadPrompt by remember { mutableStateOf<DownloadPrompt?>(null) }
    var promptRelease by remember { mutableStateOf<String?>(null) }
    var promptPinned by remember { mutableStateOf<Boolean?>(null) }
    var promptRequest by remember { mutableStateOf<Long?>(null) }
    // Device-dependent prompt: AlreadyLatest compares this stick's installed
    // version against the global upstream. An attachment change invalidates the
    // open prompt without touching the global upstream fence.
    LaunchedEffect(
        state.device?.identity,
        state.device?.attachGeneration,
    ) {
        downloadPrompt = null
        promptRelease = null
        promptPinned = null
        promptRequest = null
        pendingDownloadCheck = false
    }
    LaunchedEffect(
        state.ventoyUpstreamRequest,
        state.upstreamVentoyRelease,
        state.ventoyUpstreamChecking,
    ) {
        if (promptRequest != null && promptRequest != state.ventoyUpstreamRequest) {
            downloadPrompt = null
            promptRelease = null
            promptPinned = null
            promptRequest = null
        } else if (pendingDownloadCheck && !state.ventoyUpstreamChecking) {
            pendingDownloadCheck = false
            val upstream = state.upstreamVentoyRelease
            if (upstream == null) {
                downloadPrompt = null
                promptRelease = null
                promptPinned = null
                promptRequest = null
            } else {
                val current = state.downloadedVentoyRelease?.release
                    ?: state.installedVentoyRelease
                    ?: state.bundledVentoyRelease
                val comparison = ventoyReleaseComparison(current, upstream)
                downloadPrompt = when {
                    comparison.order != null && comparison.order <= 0 -> DownloadPrompt.AlreadyLatest
                    state.upstreamVentoyPinned == false -> DownloadPrompt.Unpinned
                    else -> DownloadPrompt.Generic
                }
                promptRelease = upstream
                promptPinned = state.upstreamVentoyPinned
                promptRequest = state.ventoyUpstreamRequest
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Every fact names its own source; the bundled value never borrows the download.
        VentoyVersionFact(
            glyph = Glyph.Package,
            label = stringResource(R.string.ventoy_version_bundled),
            value = state.bundledVentoyRelease,
        )
        state.downloadedVentoyRelease?.let { downloaded ->
            VentoyVersionFact(
                glyph = Glyph.Download,
                label = stringResource(
                    if (downloaded.independentlyPinned) {
                        R.string.ventoy_version_downloaded_pinned
                    } else {
                        R.string.ventoy_version_downloaded
                    },
                ),
                value = downloaded.release,
                valueColor = MaterialTheme.colorScheme.tertiary,
            )
        }
        VentoyVersionFact(
            glyph = Glyph.Usb,
            label = stringResource(R.string.ventoy_version_installed),
            value = state.installedVentoyRelease,
            readError = state.installedVentoyReadError,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VentoyVersionFact(
                glyph = Glyph.Cloud,
                label = stringResource(R.string.ventoy_version_upstream),
                value = state.upstreamVentoyRelease,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                IconButton(
                    onClick = {
                        pendingDownloadCheck = true
                        onIntent(UiIntent.CheckVentoyUpstream)
                    },
                    enabled = !state.ventoyDownloadInFlight && !state.ventoyUpstreamChecking,
                    modifier = Modifier.semantics { contentDescription = checkDownloadLabel },
                ) {
                    if (state.ventoyDownloadInFlight || state.ventoyUpstreamChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        GlyphIcon(Glyph.Download, size = 18.dp)
                    }
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
                        DownloadPrompt.Unpinned -> stringResource(R.string.ventoy_download_unpinned_warning_title)
                        DownloadPrompt.AlreadyLatest -> stringResource(R.string.ventoy_download_already_latest_title)
                        DownloadPrompt.Generic -> stringResource(R.string.ventoy_download_warning_title)
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
                        DownloadPrompt.Generic -> stringResource(R.string.ventoy_download_warning_body)
                    },
                )
            },
            confirmButton = {
                if (prompt != DownloadPrompt.AlreadyLatest) {
                    TextButton(onClick = {
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
                    }) {
                        Text(stringResource(R.string.ventoy_download_warning_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    downloadPrompt = null
                    promptRelease = null
                    promptPinned = null
                    promptRequest = null
                }) {
                    Text(stringResource(R.string.ventoy_download_warning_cancel))
                }
            },
        )
    }
}


@Composable
private fun VentoyVersionFact(
    glyph: Glyph,
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    readError: String? = null,
    valueColor: Color? = null,
) {
    val errorText = stringResource(R.string.ventoy_version_error)
    val naText = stringResource(R.string.ventoy_version_na)
    val displayValue = when {
        readError != null -> errorText
        value != null -> value
        else -> naText
    }
    val factDescription = "$label: ${readError ?: value ?: naText}"

    // Label above, value below: both wrap, so a large font scale never lets the
    // source label eat the width of the version and never ellipsizes the value.
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = factDescription
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GlyphIcon(
                glyph = glyph,
                size = 14.dp,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = displayValue,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: if (readError != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(start = 18.dp),
        )
    }
}
@Composable
private fun ventoyUpdateLabel(comparison: VentoyReleaseComparison): String =
    when {
        comparison.installed != null &&
            comparison.target != null &&
            comparison.same ->
            stringResource(R.string.ventoy_update_current, comparison.target)
        comparison.installed != null && comparison.target != null ->
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
        isImageInspecting ->
            stringResource(R.string.import_inspecting)
        selectedImage != null ->
            selectedImage.name.ifBlank { selectedImage.path }
        else ->
            stringResource(R.string.selection_not_selected)
    }

    val chooseDescription = stringResource(R.string.import_choose)
    // Detached stick hides the USB-gated picker row; Busy keeps it with enabled guards.
    // Live import status + cancel below stays in all states.
    val showImagePicker = state.presence != DevicePresence.Absent && state.presence != DevicePresence.Disconnected

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showImagePicker) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.windows_selected_image),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selectedImage != null || isImageImportTarget) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontFamily = if (selectedImage != null || isImageImportTarget) FontFamily.Monospace else FontFamily.Default,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        softWrap = false,
                    )
                }
                IconButton(
                    onClick = { imageSourcePrompt = true },
                    enabled = state.actionsEnabled,
                    modifier = Modifier.semantics { contentDescription = chooseDescription },
                ) {
                    GlyphIcon(Glyph.Folder, size = 20.dp)
                }
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
        ModalBottomSheet(onDismissRequest = { imageSourcePrompt = false }) {
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
