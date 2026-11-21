package usb.ui

import androidx.compose.animation.animateContentSize
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R
import androidx.compose.ui.res.painterResource
import usb.ventoy.VentoySetup
import usb.ventoy.WindowsTemplate
import usb.ventoy.XmlText
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import usb.ventoy.Autounattend
import usb.ventoy.AutounattendDocument
import usb.ventoy.AutounattendParams
import usb.ventoy.AutounattendProblem
import usb.ventoy.AutounattendState
import usb.ventoy.BindProblem
import usb.ventoy.DiskLayout
import usb.ventoy.DiskPartitionRole
import usb.ventoy.LayoutPartition
import usb.ventoy.diskLayoutInstallTargetPartitionId
import usb.ventoy.diskLayoutRemainingMb
import usb.ventoy.LocalAccount
import usb.ventoy.diskLayoutTotalMb
import usb.ventoy.AutounattendIntents
import usb.ventoy.XmlFieldIntent
import usb.ventoy.SettingStatus
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyControlCatalog
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoyKnownControl
import usb.ventoy.VentoySetting
import usb.ventoy.VentoySettingsSource



/**
 * Autounattend subsection of the Ventoy tab: the answer file that already exists, the
 * parameters used to (re)generate it, which images are bound to it, and the global
 * `control` values of `ventoy.json`.
 *
 * Nothing here invents capability: the template is an existing file, generation is opt-in per
 * block, unknown elements are reported as preserved, and the target-PC layout is acknowledged
 * in its own dialog that never shows this stick's identity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutounattendSection(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onPickVentoySettingsFile: () -> Unit = {},
    onPickWindowsFile: (WindowsImportKind, String?) -> Unit = { _, _ -> },
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val template = state.windowsTemplatePath
    val document = (state.autounattendState as? AutounattendState.Parsed)?.document
    // Detached stick hides USB-gated rows below; help + live import status/cancel stay.
    val xmlGone = state.presence == DevicePresence.Absent || state.presence == DevicePresence.Disconnected
    val templateSourceIdentity = state.device?.identity
    val templateSourceGeneration = state.device?.attachGeneration
    var templateSourcePrompt by remember(
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf(false) }
    var generateOpen by remember { mutableStateOf(false) }
    var xmlEditorOpen by remember { mutableStateOf(false) }
    var folderKind by remember(
        state.device?.identity,
        state.device?.attachGeneration,
    ) { mutableStateOf<WindowsImportKind?>(null) }

    LaunchedEffect(state.pendingImportFolderPick) {
        val kind = state.pendingImportFolderPick ?: return@LaunchedEffect
        if (kind == WindowsImportKind.Image) {
            onIntent(UiIntent.ImportFolderPickOpened)
            return@LaunchedEffect
        }
        folderKind = kind
        onIntent(UiIntent.ImportFolderPickOpened)
    }

    LaunchedEffect(folderKind) {
        if (folderKind != null &&
            state.ventoyFolders == null &&
            !state.ventoyFoldersLoading
        ) {
            onIntent(UiIntent.LoadVentoyFolders)
        }
    }
    LaunchedEffect(state.operation?.state, state.operation?.title) {
        if (xmlEditorOpen &&
            state.operation?.title == OperationTitle.WriteUnattendXml &&
            state.operation?.state == OperationState.Completed
        ) {
            xmlEditorOpen = false
        }
    }
    // Document/template can vanish while the editor is open (detach, reload miss).
    // Close explicitly so a later reopen does not resurrect a discarded draft flag.
    LaunchedEffect(xmlEditorOpen, template, document?.sourceText) {
        if (xmlEditorOpen && (template == null || document?.sourceText == null)) {
            xmlEditorOpen = false
        }
    }
    // Keys = external readiness only. Missing/inFlight stay in the body guard:
    // Loaded(Missing) clears inFlight and would re-fire forever if those were keys.
    // presence/actionsEnabled: Busy→Ready after open-during-scan must retry once.
    LaunchedEffect(
        state.device?.identity,
        state.device?.attachGeneration,
        state.ventoyCacheEpoch,
        state.presence,
        state.actionsEnabled,
    ) {
        val path = state.windowsTemplatePath
        if (path == null ||
            state.presence != DevicePresence.Ready ||
            !state.actionsEnabled ||
            state.autounattendState !is AutounattendState.Missing ||
            state.autounattendLoadInFlight
        ) {
            return@LaunchedEffect
        }
        onIntent(UiIntent.LoadAutounattend(path))
    }

    SectionCard(
        title = stringResource(R.string.unattend_title),
        trailing = {
            InfoHelpButton(
                infoTitle = stringResource(R.string.unattend_help_title),
                infoText = stringResource(R.string.wave4_unattend_help_body),
            )
        },
    ) {
        if (leadingContent != null) {
            leadingContent()
        }
        if (state.filesPickMode?.purpose != FilesPickPurpose.WindowsImage) {
            val isXmlInspecting = state.isoImportSafInFlight != null || state.isoImportUsbInFlight != null
            if (isXmlInspecting) {
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
                )
                TextButton(onClick = { onIntent(UiIntent.CancelWindowsIsoImport) }) {
                    Text(stringResource(R.string.import_error_dismiss))
                }
            }
        }
        val resolvedPath = template?.let(VentoySetup::resolveXmlPath)
        val xmlSizeLabel = document?.let { doc ->
            val text = doc.sourceText ?: return@let null
            val bytes = text.toByteArray(StandardCharsets.UTF_8).size.toLong() +
                doc.sourceBom.size.toLong()
            DeviceIdentity.formatCapacity(bytes.toULong())
        }
        ConfigFileStatusRow(
            pathLabel = resolvedPath,
            sizeLabel = xmlSizeLabel,
            dimmedPath = resolvedPath == null,
            showChanged = false,
            onReset = if (template != null) {
                { onIntent(UiIntent.ClearWindowsTemplate) }
            } else {
                null
            },
            resetEnabled = state.actionsEnabled,
            onPick = if (!xmlGone && state.actionsEnabled) {
                { templateSourcePrompt = true }
            } else {
                null
            },
            pickEnabled = state.actionsEnabled,
            pickLabel = stringResource(R.string.ventoy_pick_windows_template_cd),
        )
        when (val file = state.autounattendState) {
            AutounattendState.Missing ->
                if (template != null) {
                    EmptyHint(stringResource(R.string.unattend_missing))
                }
            is AutounattendState.Unreadable -> Text(
                text = stringResource(unreadableText(file.problem)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            is AutounattendState.Parsed -> {
                val doc = file.document
                if (doc.destructiveMarkers.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.unattend_destructive_note),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    doc.destructiveMarkers.forEach { marker ->
                        Text(
                            text = marker,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (doc.layoutProblems.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.unattend_layout_problems),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    doc.layoutProblems.forEach { problem ->
                        Text(
                            text = autounattendProblemText(problem),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            else -> Unit
        }
        if (!xmlGone) {
            // Choosing or replacing the template is the icon next to the path row.
            FilledTonalButton(
                onClick = { generateOpen = true },
                enabled = state.actionsEnabled &&
                    state.autounattendState !is AutounattendState.Unreadable &&
                    document?.layoutProblems.isNullOrEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.unattend_generate),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Raw XML editor stays reachable when source bytes exist; not part of two-button row.
            if (document?.sourceText != null) {
                OutlinedButton(
                    onClick = { xmlEditorOpen = true },
                    enabled = state.actionsEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.unattend_open_editor))
                }
            }
        }
    }

    if (template != null && !xmlGone) {
        val candidates = state.autounattendCandidates
        SectionCard(
            title = stringResource(R.string.unattend_bindings_title),
            subtitle = stringResource(R.string.unattend_bindings_subtitle),
        ) {
            if (candidates == null) {
                EmptyHint(stringResource(R.string.unattend_bindings_loading))
            } else {
                candidates.scopeLimitedBy?.let { limited ->
                    Text(
                        text = stringResource(R.string.unattend_scope_limited, limited),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (candidates.error != null) {
                    Text(
                        text = stringResource(R.string.raw_stick_incomplete),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (candidates.truncated) {
                    Text(
                        text = stringResource(R.string.unattend_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidates.images.isEmpty()) {
                    EmptyHint(stringResource(R.string.unattend_no_candidates))
                }
                candidates.images.forEach { image ->
                    val selected = state.windowsSelectedImages.any { it == image.imagePath }
                    val imageSelectable =
                        state.actionsEnabled && image.problem == null && !image.bound
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = selected || image.bound,
                                enabled = imageSelectable,
                                role = Role.Checkbox,
                                onValueChange = {
                                    onIntent(UiIntent.ToggleWindowsImage(image.imagePath))
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = selected || image.bound,
                            onCheckedChange = null,
                            enabled = imageSelectable,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = image.imagePath,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (image.bound) {
                                Text(
                                    text = stringResource(R.string.unattend_bound),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                image.problem?.let { problem ->
                                    Text(
                                        text = stringResource(bindProblemText(problem)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        if (image.bound) {
                            TextButton(
                                onClick = {
                                    onIntent(
                                        UiIntent.RequestUnbindAutounattend(template, image.imagePath),
                                    )
                                },
                                enabled = state.actionsEnabled,
                            ) {
                                Text(stringResource(R.string.unattend_unbind))
                            }
                        }
                    }
                }
                val selectedPaths = state.windowsSelectedImages
                val templateMarkers = document?.destructiveMarkers.orEmpty()
                var bindDestructiveAck by remember(template, document) {
                    mutableStateOf(false)
                }
                if (templateMarkers.isNotEmpty()) {
                    // The backend refuses a destructive template without an explicit opt-in and
                    // the exact inspected digest; the same numbers travel with the request.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = bindDestructiveAck,
                                enabled = state.actionsEnabled,
                                role = Role.Checkbox,
                                onValueChange = { bindDestructiveAck = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = bindDestructiveAck,
                            onCheckedChange = null,
                            enabled = state.actionsEnabled,
                        )
                        Text(
                            text = stringResource(R.string.unattend_ack_destructive),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                val bindAllowed = state.actionsEnabled &&
                    selectedPaths.isNotEmpty() &&
                    (templateMarkers.isEmpty() || bindDestructiveAck)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onIntent(UiIntent.ClearWindowsSelection) },
                        enabled = selectedPaths.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.unattend_clear_selection))
                    }
                    Button(
                        onClick = {
                            onIntent(
                                UiIntent.RequestBindAutounattend(
                                    xmlName = template,
                                    imagePaths = selectedPaths,
                                    allowDestructiveTemplate = templateMarkers.isNotEmpty() &&
                                        bindDestructiveAck,
                                    expectedTemplateSha256 = document?.templateSha256,
                                ),
                            )
                        },
                        enabled = bindAllowed,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.unattend_bind))
                    }
                }
            }
        }
    }



    state.diskLayoutConfirmation?.let { confirmation ->
        LayoutConfirmDialog(
            confirmation = confirmation,
            actionsEnabled = state.actionsEnabled,
            onConfirm = { onIntent(UiIntent.ConfirmDiskLayout(it)) },
            onDismiss = { onIntent(UiIntent.DismissDiskLayout) },
        )
    }

    // Stick templates browse the shared Files picker: tap marks, explicit button confirms.
    // The reducer atomically consumes filesPickSelections[WindowsTemplate], so this intent
    // carries no path and cannot inject one. Phone flow uses SAF import below; nothing copies here.
    LaunchedEffect(state.filesPickSelections[FilesPickPurpose.WindowsTemplate]) {
        if (state.filesPickSelections[FilesPickPurpose.WindowsTemplate] == null) {
            return@LaunchedEffect
        }
        onIntent(UiIntent.SelectWindowsTemplate)
    }
    if (templateSourcePrompt) {
        ModalBottomSheet(
            onDismissRequest = { templateSourcePrompt = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = stringResource(R.string.windows_template_choose_source),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.raw_choose_stick)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_pick_file_usb_duotone),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        templateSourcePrompt = false
                        onIntent(
                            UiIntent.BeginFilesPick(
                                purpose = FilesPickPurpose.WindowsTemplate,
                                returnTab = ShellTab.Ventoy,
                                target = FilesPickTarget.File,
                                initialPath = "/ventoy",
                                expectedIdentity = templateSourceIdentity,
                                expectedAttachGeneration = templateSourceGeneration,
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
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        templateSourcePrompt = false
                        folderKind = WindowsImportKind.XmlTemplate
                    },
            )
        }
    }

    folderKind?.let {
        ImportFolderDialog(
            currentPath = state.ventoyFolderPath,
            folders = state.ventoyFolders,
            folderError = state.ventoyFoldersError,
            loading = state.ventoyFoldersLoading,
            blocked = !state.actionsEnabled,
            onEnter = { onIntent(UiIntent.OpenVentoyFolder(it)) },
            onUp = { onIntent(UiIntent.VentoyFolderUp) },
            onRefresh = { onIntent(UiIntent.LoadVentoyFolders) },
            onPick = { directory ->
                folderKind = null
                onPickWindowsFile(WindowsImportKind.XmlTemplate, directory)
            },
            onDismiss = { folderKind = null },
        )
    }
    state.pendingIsoImport?.takeIf { it.kind == WindowsImportKind.XmlTemplate }?.let { proposal ->
        ImportOverwriteDialog(
            proposal = proposal,
            actionsEnabled = state.actionsEnabled,
            onReplace = { onIntent(UiIntent.ConfirmWindowsIsoImport(true)) },
            onCancel = { onIntent(UiIntent.CancelWindowsIsoImport) },
        )
    }

    if (generateOpen) {
        val generateTarget =
            template
                ?: state.windowsSelectedImages.firstOrNull()?.let { VentoySetup.suggestedXmlPath(it) }
                ?: "autounattend.xml"
        GenerateDialog(
            template = generateTarget,
            document = document,
            selectedImages = state.windowsSelectedImages,
            actionsEnabled = state.actionsEnabled,
            onGenerate = { params, allowDestructive, sha ->
                onIntent(
                    UiIntent.RequestGenerateAutounattend(
                        // Create-new reseals name via allocateXmlPath when overwriteXml=false.
                        xmlName = generateTarget,
                        params = params,
                        imagePaths = state.windowsSelectedImages,
                        overwriteXml = template != null && document != null,
                        allowDestructiveTemplate = allowDestructive,
                        expectedTemplateSha256 = sha,
                    ),
                )
                generateOpen = false
            },
            onDismiss = { generateOpen = false },
        )
    }

    if (xmlEditorOpen && template != null && document?.sourceText != null) {
        AutounattendRawEditorDialog(
            template = template,
            document = document,
            device = state.device,
            actionsEnabled = state.actionsEnabled,
            onIntent = onIntent,
            onCancel = { xmlEditorOpen = false },
        )
    }
}

/**
 * Global `control` entries. The file can be Ventoy's own `/ventoy/ventoy.json`, another file
 * on the stick (chosen through the shared Files picker), or a phone document chosen with
 * OpenDocument. Missing, empty, and unreadable stay distinct; known keys are toggles, other
 * keys stay editable as key/value, and every edit is staged until Update or the confirmed
 * install/update writes the default file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlobalSettingsCard(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onPickVentoySettingsFile: () -> Unit,
) {
    // Detached stick hides USB-gated rows; help stays mounted.
    val settingsGone = state.presence == DevicePresence.Absent || state.presence == DevicePresence.Disconnected
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorBaseline by remember { mutableStateOf<List<VentoyControlSetting>?>(null) }
    LaunchedEffect(state.operation?.state, state.operation?.title) {
        if (editorOpen &&
            state.operation?.title == OperationTitle.SetVentoyGlobalSetting &&
            state.operation?.state == OperationState.Completed
        ) {
            editorOpen = false
            editorBaseline = null
        }
    }
    // unrelated selection cannot re-apply here.
    LaunchedEffect(state.filesPickSelections) {
        val picked = state.filesPickSelections[FilesPickPurpose.VentoyConfig] ?: return@LaunchedEffect
        if (picked.purpose != FilesPickPurpose.VentoyConfig || picked.isDirectory) {
            return@LaunchedEffect
        }
        onIntent(
            UiIntent.SelectVentoySettingsUsbFile(
                path = picked.path,
                identity = picked.identity,
                attachGeneration = picked.attachGeneration,
                partitionId = picked.partitionId,
                partitionStartLba = picked.startLba,
                partitionSectorCount = picked.sectorCount,
            ),
        )
        onIntent(UiIntent.ClearFilesPickSelection(FilesPickPurpose.VentoyConfig))
    }
    SectionCard(
        title = stringResource(R.string.settings_title),
        trailing = {
            InfoHelpButton(
                infoTitle = stringResource(R.string.settings_help_title),
                infoText = stringResource(R.string.wave4_settings_help_body),
            )
        },
    ) {
        val source = state.ventoySettingsSource
        val customPath = !source.isDefaultPath()
        val settings = state.ventoyGlobalSettings
        val pending = state.pendingVentoyControls
        val importedFileReady =
            customPath &&
                (settings is VentoyGlobalSettings.Present || settings is VentoyGlobalSettings.Empty)
        val hasPendingWrite = pending.isNotEmpty() || importedFileReady
        val sizeOrStatus = when {
            state.ventoySettingsSourceLoading ->
                stringResource(R.string.settings_source_status_reading)
            settings == null -> stringResource(R.string.settings_source_status_unread)
            settings == VentoyGlobalSettings.Missing ->
                stringResource(R.string.settings_source_status_missing)
            settings == VentoyGlobalSettings.Empty ->
                stringResource(R.string.settings_source_status_empty)
            settings is VentoyGlobalSettings.Present ->
                stringResource(
                    R.string.settings_source_status_valid,
                    settings.entries.size,
                )
            else -> stringResource(R.string.settings_source_status_error)
        }
        ConfigFileStatusRow(
            pathLabel = source.label(),
            sizeLabel = sizeOrStatus,
            dimmedPath = !customPath,
            showChanged = hasPendingWrite,
            onReset = if (customPath) {
                { onIntent(UiIntent.UseDefaultVentoySettingsFile) }
            } else {
                null
            },
            resetEnabled = state.actionsEnabled,
            // The picker also offers a phone document, which is read via SAF and needs no
            // stick; only USB writes need the attachment. A present but Busy stick keeps
            // the reducer guard and the icon disabled.
            onPick = { sourceMenuOpen = true },
            pickEnabled = state.actionsEnabled || settingsGone,
            pickLabel = stringResource(R.string.ventoy_pick_settings_file_cd),
        )
        if (source is VentoySettingsSource.Document) {
            Text(
                text = stringResource(R.string.settings_source_phone_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (settings) {
            null -> {
                if (!settingsGone) {
                    EmptyHint(
                        if (state.presence == DevicePresence.Ready) {
                            stringResource(R.string.settings_not_loaded)
                        } else {
                            UiReducer.presenceMessage(state.presence).localized()
                        },
                    )
                    TextButton(
                        onClick = { onIntent(UiIntent.LoadVentoyGlobalSettings) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.settings_retry))
                    }
                }
            }
            VentoyGlobalSettings.Missing ->
                EmptyHint(
                    stringResource(
                        if (customPath) {
                            R.string.settings_source_status_missing
                        } else {
                            R.string.settings_missing
                        },
                    ),
                )
            VentoyGlobalSettings.Empty -> Unit
            is VentoyGlobalSettings.ReadError -> {
                Text(
                    text = stringResource(
                        when {
                            settings.damagedLayout -> R.string.settings_damaged_layout
                            customPath -> R.string.settings_source_status_error
                            else -> R.string.settings_read_error
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!settings.damagedLayout) {
                    Text(
                        text = settings.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!settingsGone) {
                    TextButton(
                        onClick = { onIntent(UiIntent.LoadVentoyGlobalSettings) },
                        enabled = state.actionsEnabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.settings_retry))
                    }
                }
            }
            is VentoyGlobalSettings.Present -> Unit
        }
        StagedControlsHints(state = state)
        if (pending.isNotEmpty()) {
            CompactStagedControls(
                pending = pending,
                onDiscard = { controlKey -> onIntent(UiIntent.DiscardStagedVentoyControl(controlKey)) },
            )
        }
        if (!settingsGone) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        editorBaseline = state.pendingVentoyControls
                        editorOpen = true
                    },
                    enabled = state.actionsEnabled && settings != null,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.wave4_editor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { onIntent(UiIntent.RequestApplyVentoyControls) },
                    enabled = state.actionsEnabled && hasPendingWrite,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            when {
                                state.operation?.state == OperationState.Partial &&
                                    state.operation?.title in setOf(
                                        OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                                        OperationTitle.Destructive(DestructiveAction.VENTOY_UPDATE),
                                    ) -> R.string.settings_retry_pending_action
                                pending.isEmpty() &&
                                    source is VentoySettingsSource.Document ->
                                    R.string.settings_import_apply
                                pending.isEmpty() && customPath ->
                                    R.string.settings_import_copy_to_default
                                else -> R.string.wave4_write
                            },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (editorOpen) {
            GlobalSettingsEditorDialog(
                state = state,
                onIntent = onIntent,
                onCancel = {
                    editorBaseline?.let { onIntent(UiIntent.RestoreVentoyControls(it)) }
                    editorOpen = false
                    editorBaseline = null
                },
            )
        }
    }
    if (sourceMenuOpen) {
        SettingsSourceDialog(
            onStick = {
                sourceMenuOpen = false
                onIntent(
                    UiIntent.BeginFilesPick(
                        purpose = FilesPickPurpose.VentoyConfig,
                        returnTab = ShellTab.Ventoy,
                        target = FilesPickTarget.File,
                        initialPath = "/ventoy",
                    ),
                )
            },
            onPhone = {
                sourceMenuOpen = false
                onPickVentoySettingsFile()
            },
            onDismiss = { sourceMenuOpen = false },
            stickEnabled = !settingsGone,
        )
    }
}

private data class EditorPairDraft(
    val id: String,
    val originalKey: String?,
    val key: String,
    val originalValue: String,
    val value: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalSettingsEditorDialog(
    state: AppUiState,
    onIntent: (UiIntent) -> Unit,
    onCancel: () -> Unit,
) {
    val entries = (state.ventoyGlobalSettings as? VentoyGlobalSettings.Present)?.entries.orEmpty()
    var applyProblem by remember { mutableStateOf<String?>(null) }
    var addParamOpen by remember { mutableStateOf(false) }
    // Pending changes seed a new dialog session, but must not reset local rows while editing.
    val drafts = remember(entries) {
        mutableStateListOf<EditorPairDraft>().apply {
            entries.forEachIndexed { index, entry ->
                if (VentoyControlCatalog.known(entry.key) == null ||
                    entries.indexOfFirst { it.key == entry.key } != index
                ) add(EditorPairDraft("entry:$index", entry.key, entry.key, entry.rawValue, entry.rawValue))
            }
            state.pendingVentoyControls.forEach { pending ->
                if (VentoyControlCatalog.known(pending.key) != null) return@forEach
                val existing = firstOrNull { it.key == pending.key && it.originalKey != null }
                if (pending.value == null) {
                    removeAll { it.originalKey == pending.key }
                } else if (existing != null) {
                    val index = indexOf(existing)
                    this[index] = existing.copy(value = pending.value)
                } else {
                    add(EditorPairDraft("pending:${pending.key}", null, pending.key, "", pending.value))
                }
            }
        }
    }
    // MD3 full-screen dialog: single TopAppBar (X/title/Save), no bottom duplicate footer.
    // Backend/commit path untouched: save lambda identical to old Apply footer.
    val context = LocalContext.current
    fun commitDrafts() {
        val rawOriginalCounts = entries.groupingBy { it.key }.eachCount()
        val duplicateOriginalCounts = rawOriginalCounts.filterValues { it > 1 }
        val draftOriginalCounts = drafts.mapNotNull { it.originalKey }.groupingBy { it }.eachCount()
        val rawOriginalKeys = rawOriginalCounts.keys.filter { key ->
            VentoyControlCatalog.known(key) == null || rawOriginalCounts[key]!! > 1
        }.toSet()
        val blankNewKey = drafts.any { it.originalKey == null && it.key.isBlank() && it.value.isNotEmpty() }
        val blankExistingKey = drafts.any { it.originalKey != null && it.key.isBlank() }
        val rawSettings = drafts
            .filter { it.originalKey == null || it.key != it.originalKey || it.value != it.originalValue }
            .filter { it.key.isNotBlank() }
            .map { VentoyControlSetting(it.key, it.value) }
        val renamedOrDeleted = (rawOriginalKeys - drafts.mapNotNull { it.originalKey }.toSet()) +
            drafts.mapNotNull { draft -> draft.originalKey?.takeIf { draft.key.isNotBlank() && it != draft.key } }
        val knownSettings = VentoyControlCatalog.known.mapNotNull { known ->
            state.pendingVentoyControls.firstOrNull { it.key == known.key && it.value != null }
        }
        val knownRemoved = state.pendingVentoyControls
            .filter { it.value == null && VentoyControlCatalog.known(it.key) != null }
            .map { it.key }
            .toSet()
        val settings = rawSettings + knownSettings
        val duplicateKeys = duplicateOriginalCounts.keys.filter { key ->
            val expectedDraftCount = duplicateOriginalCounts[key]!! -
                (if (VentoyControlCatalog.known(key) != null) 1 else 0)
            expectedDraftCount != (draftOriginalCounts[key] ?: 0) ||
                renamedOrDeleted.contains(key) ||
                knownRemoved.contains(key) ||
                settings.any { it.key == key }
        }.toSet()
        val merged = (renamedOrDeleted + knownRemoved).map { VentoyControlSetting(it, null) } + settings
        val validation = when {
            blankExistingKey || blankNewKey ->
                context.resources.controlIssueText(VentoyControlCatalog.Issue.EmptyKey)
            duplicateKeys.isNotEmpty() ->
                context.resources.getString(
                    R.string.settings_problem_duplicate_keys,
                    duplicateKeys.joinToString(),
                )
            else -> VentoyControlCatalog.issue(merged)?.let {
                context.resources.controlIssueText(it)
            }
        }
        if (validation != null) {
            applyProblem = validation
        } else if (state.ventoySettingsSource.isDefaultPath() &&
            settings.isEmpty() && renamedOrDeleted.isEmpty() && knownRemoved.isEmpty()
        ) {
            applyProblem = context.resources.getString(R.string.settings_problem_no_changes)
        } else {
            applyProblem = null
            onIntent(UiIntent.CommitAndApplyVentoyControls(
                removedKeys = renamedOrDeleted + knownRemoved,
                settings = settings,
                duplicateKeys = duplicateKeys,
            ))
        }
    }
    val closeLabel = stringResource(R.string.settings_cancel)
    val applyLabel = stringResource(R.string.settings_apply)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.settings_editor_title)) },
                        navigationIcon = {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.semantics { contentDescription = closeLabel },
                            ) { GlyphIcon(Glyph.Close) }
                        },
                        actions = {
                            TextButton(
                                onClick = ::commitDrafts,
                                enabled = state.actionsEnabled,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(applyLabel)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    )
                },
            ) { inner ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    applyProblem?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .verticalScroll(rememberScrollState())
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.wave4_win11_section),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        VentoyControlCatalog.known.forEach { known ->
                            KnownControlRow(
                                known,
                                entries,
                                state.pendingVentoyControls,
                                state.actionsEnabled,
                                onIntent,
                            )
                        }
                        Text(
                            text = stringResource(R.string.wave4_config_params_section),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        drafts.toList().forEach { draft ->
                            key(draft.id) {
                                EditablePairRow(
                                    draft = draft,
                                    actionsEnabled = state.actionsEnabled,
                                    onChange = { changed ->
                                        val i = drafts.indexOfFirst { it.id == draft.id }
                                        if (i >= 0) drafts[i] = changed
                                    },
                                    onDelete = { drafts.removeAll { it.id == draft.id } },
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = { addParamOpen = true },
                            enabled = state.actionsEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.wave4_add_param))
                        }
                    }
                }
            }
        }
    }
    if (addParamOpen) {
        AddParameterDialog(
            actionsEnabled = state.actionsEnabled,
            existingKeys = drafts.map { it.key }.filter { it.isNotBlank() }.toSet() +
                VentoyControlCatalog.known.map { it.key }.toSet(),
            onAdd = { key, value ->
                drafts.add(EditorPairDraft("new:${drafts.size}:$key", null, key, "", value))
                addParamOpen = false
            },
            onDismiss = { addParamOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutounattendRawEditorDialog(
    template: String,
    document: AutounattendDocument,
    device: DeviceIdentity?,
    actionsEnabled: Boolean,
    onIntent: (UiIntent) -> Unit,
    onCancel: () -> Unit,
) {
    val sourceText = requireNotNull(document.sourceText)
    var draft by remember(sourceText) { mutableStateOf(sourceText) }
    var applyProblem by remember { mutableStateOf<String?>(null) }
    var destructiveAck by remember(document.templateSha256) { mutableStateOf(false) }
    val sourceCharset = remember(document.sourceCharsetName) {
        runCatching { document.sourceCharsetName?.let(Charset::forName) }.getOrNull()
            ?: StandardCharsets.UTF_8
    }
    val markers = remember(draft, sourceCharset, document.sourceBom) {
        runCatching {
            WindowsTemplate.inspect(
                XmlText.encode(draft, sourceCharset, document.sourceBom),
                requireUnattendRoot = true,
            ).destructiveMarkers
        }.getOrDefault(emptyList())
    }
    val invalidMessage = stringResource(R.string.unattend_editor_invalid)
    val destructiveAckRequired = stringResource(R.string.unattend_editor_destructive_ack)
    val xmlFieldDescription = stringResource(R.string.unattend_editor_title)
    val closeLabel = stringResource(R.string.settings_cancel)
    val saveLabel = stringResource(R.string.settings_save)
    fun commitDraft() {
        val charset = runCatching {
            document.sourceCharsetName?.let(Charset::forName)
        }.getOrNull() ?: StandardCharsets.UTF_8
        val encoded = runCatching {
            XmlText.encode(draft, charset, document.sourceBom)
        }.getOrElse {
            applyProblem = invalidMessage
            return
        }
        val inspection = runCatching {
            WindowsTemplate.inspect(encoded, requireUnattendRoot = true)
        }.getOrElse {
            applyProblem = invalidMessage
            return
        }
        val needsDestructive =
            inspection.destructiveMarkers.isNotEmpty() ||
                document.destructiveMarkers.isNotEmpty()
        if (needsDestructive && !destructiveAck) {
            applyProblem = destructiveAckRequired
            return
        }
        applyProblem = null
        onIntent(
            UiIntent.RequestWriteAutounattendRaw(
                xmlName = template,
                rawBytes = encoded,
                overwriteXml = true,
                allowDestructiveTemplate = needsDestructive && destructiveAck,
                expectedTemplateSha256 = document.templateSha256,
                identity = device?.identity,
                attachGeneration = device?.attachGeneration ?: 0L,
            ),
        )
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.unattend_editor_title)) },
                        navigationIcon = {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.semantics { contentDescription = closeLabel },
                            ) { GlyphIcon(Glyph.Close) }
                        },
                        actions = {
                            TextButton(onClick = ::commitDraft, enabled = actionsEnabled) {
                                Text(saveLabel)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    )
                },
            ) { inner ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.unattend_editor_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                applyProblem?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        applyProblem = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .semantics { contentDescription = xmlFieldDescription },
                    enabled = actionsEnabled,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                if (markers.isNotEmpty() || document.destructiveMarkers.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = destructiveAck,
                                enabled = actionsEnabled,
                                role = Role.Checkbox,
                                onValueChange = { destructiveAck = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = destructiveAck,
                            onCheckedChange = null,
                            enabled = actionsEnabled,
                        )
                        Text(
                            text = stringResource(R.string.unattend_editor_destructive_ack),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditablePairRow(
    draft: EditorPairDraft,
    actionsEnabled: Boolean,
    onChange: (EditorPairDraft) -> Unit,
    onDelete: () -> Unit,
) {
    val deleteLabel = stringResource(R.string.settings_delete_row)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && actionsEnabled) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = actionsEnabled,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                GlyphIcon(
                    Glyph.Trash,
                    size = 22.dp,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = draft.key,
                    onValueChange = { onChange(draft.copy(key = it)) },
                    placeholder = { Text(stringResource(R.string.settings_key_label)) },
                    singleLine = true,
                    enabled = actionsEnabled,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "=",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = { onChange(draft.copy(value = it)) },
                    placeholder = { Text(stringResource(R.string.settings_value_label)) },
                    singleLine = true,
                    enabled = actionsEnabled,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.weight(1f),
                )
                // Explicit delete affordance — swipe alone is not enough for a11y.
                IconButton(
                    onClick = onDelete,
                    enabled = actionsEnabled,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = deleteLabel },
                ) { GlyphIcon(Glyph.Trash, size = 18.dp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSourceDialog(
    onStick: () -> Unit,
    onPhone: () -> Unit,
    onDismiss: () -> Unit,
    stickEnabled: Boolean = true,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = stringResource(R.string.settings_source_choose),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_source_choose_stick)) },
            supportingContent = if (stickEnabled) {
                null
            } else {
                { Text(stringResource(R.string.ventoy_pick_stick_unavailable)) }
            },
            leadingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_pick_file_usb_duotone),
                    contentDescription = null,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, enabled = stickEnabled, onClick = onStick),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_source_choose_phone)) },
            leadingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_pick_file_phone_duotone),
                    contentDescription = null,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onPhone),
        )
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnownControlRow(
    known: VentoyKnownControl,
    entries: List<VentoySetting>,
    pending: List<VentoyControlSetting>,
    actionsEnabled: Boolean,
    onIntent: (UiIntent) -> Unit,
) {
    val staged = pending.firstOrNull { it.key == known.key }
    val onDisk = entries.firstOrNull { it.key == known.key }
    val value = if (staged != null) staged.value else onDisk?.rawValue
    val current =
        when {
            staged != null -> staged.value ?: stringResource(R.string.settings_will_remove)
            onDisk != null -> onDisk.rawValue
            else -> stringResource(R.string.settings_not_set)
        }
    val booleanKey =
        known.allowed.size == 2 && known.allowed.containsAll(listOf("0", "1"))
    val humanTitle = when (known.key) {
        "VTOY_WIN11_BYPASS_CHECK" -> stringResource(R.string.wave4_win11_bypass_tpm)
        "VTOY_WIN11_BYPASS_NRO" -> stringResource(R.string.wave4_win11_local_account)
        else -> knownControlTitle(known.key)
    }
    val keyHint = when (known.key) {
        "VTOY_WIN11_BYPASS_CHECK" -> R.string.settings_win11_hint
        "VTOY_WIN11_BYPASS_NRO" -> R.string.settings_win11_nro_hint
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (booleanKey) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = value == "1",
                        enabled = actionsEnabled,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            onIntent(
                                UiIntent.StageVentoyControl(
                                    known.key,
                                    if (enabled) "1" else "0",
                                ),
                            )
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = humanTitle, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = known.key,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_current, current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = value == "1", onCheckedChange = null, enabled = actionsEnabled)
                keyHint?.let {
                    InfoHelpButton(infoTitle = humanTitle, infoText = stringResource(it))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = humanTitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = known.key,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_current, current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        known.allowed.forEach { option ->
                            TextButton(
                                onClick = {
                                    onIntent(UiIntent.StageVentoyControl(known.key, option))
                                },
                                enabled = actionsEnabled,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text(knownOptionLabel(known.key, option)) }
                        }
                    }
                }
                keyHint?.let {
                    InfoHelpButton(infoTitle = humanTitle, infoText = stringResource(it))
                }
            }
        }
        val problem = (onDisk?.status as? SettingStatus.Known)?.takeIf { !it.valid }
        problem?.let {
            Text(
                text = stringResource(R.string.settings_invalid, it.expected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (staged != null || onDisk != null) {
            TextButton(
                onClick = { onIntent(UiIntent.StageVentoyControl(known.key, null)) },
                enabled = actionsEnabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_remove)) }
        }
    }
}


/** Localized typed control problem; the key, limit and received value are preserved. */
private fun Resources.controlIssueText(issue: VentoyControlCatalog.Issue): String =
    when (issue) {
        VentoyControlCatalog.Issue.EmptyKey ->
            getString(R.string.settings_problem_key_empty)
        is VentoyControlCatalog.Issue.KeyTooLong ->
            getString(R.string.settings_problem_key_too_long, issue.max)
        VentoyControlCatalog.Issue.KeyCharset ->
            getString(R.string.settings_problem_key_charset)
        is VentoyControlCatalog.Issue.ValueTooLong ->
            getString(R.string.settings_problem_value_too_long, issue.max)
        VentoyControlCatalog.Issue.ValueControlChars ->
            getString(R.string.settings_problem_value_control_chars)
        is VentoyControlCatalog.Issue.ValueNotAllowed ->
            getString(
                R.string.settings_problem_value_not_allowed,
                issue.key,
                issue.expected,
                issue.actual,
            )
        is VentoyControlCatalog.Issue.DuplicateKey ->
            getString(R.string.settings_problem_duplicate_key, issue.key)
    }


/** Partial-write errors only; apply lives on the compact Write button. */
@Composable
private fun StagedControlsHints(state: AppUiState) {
    val operation = state.operation
    val installPartial =
        operation?.state == OperationState.Partial &&
            operation.title in
            setOf(
                OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                OperationTitle.Destructive(DestructiveAction.VENTOY_UPDATE),
            )
    val writePartial =
        operation?.state == OperationState.Partial &&
            operation.title == OperationTitle.SetVentoyGlobalSetting
    if (!installPartial && !writePartial) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (installPartial) {
            Text(
                text = stringResource(R.string.settings_retry_pending),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (writePartial) {
            Text(
                text = stringResource(
                    if (state.ventoySettingsSource is VentoySettingsSource.Document) {
                        R.string.settings_source_partial
                    } else {
                        R.string.settings_write_partial
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Compact pending-control rows with 48 dp discard. */
@Composable
private fun CompactStagedControls(
    pending: List<VentoyControlSetting>,
    onDiscard: (String) -> Unit,
) {
    if (pending.isEmpty()) return
    val discardLabel = stringResource(R.string.settings_discard)
    val removeLabel = stringResource(R.string.settings_remove)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_staged),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        pending.forEach { setting ->
            key(setting.key) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.settings_staged_row,
                            setting.key,
                            setting.value ?: removeLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = { onDiscard(setting.key) },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = discardLabel },
                    ) {
                        GlyphIcon(Glyph.Close, size = 18.dp)
                    }
                }
            }
        }
    }
}


/**
 * Shared compact status line for ventoy.json / unattend.xml cards:
 * monospace path · size, optional Changed badge, optional pick icon, optional ✕ reset.
 */
@Composable
private fun ConfigFileStatusRow(
    pathLabel: String?,
    sizeLabel: String?,
    dimmedPath: Boolean,
    showChanged: Boolean,
    onReset: (() -> Unit)?,
    resetEnabled: Boolean,
    onPick: (() -> Unit)? = null,
    pickEnabled: Boolean = true,
    pickLabel: String? = null,
) {
    val resetLabel = stringResource(R.string.wave4_reset_file)
    val statusText = when {
        pathLabel == null -> stringResource(R.string.wave4_file_not_selected)
        sizeLabel.isNullOrBlank() -> pathLabel
        else -> stringResource(R.string.wave4_status_path_size, pathLabel, sizeLabel)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = if (dimmedPath || pathLabel == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showChanged) {
            Badge(
                text = stringResource(R.string.wave4_changed_badge),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (onPick != null) {
            IconButton(
                onClick = onPick,
                enabled = pickEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        if (pickLabel != null) contentDescription = pickLabel
                    },
            ) {
                GlyphIcon(Glyph.File, size = 20.dp)
            }
        }
        if (onReset != null) {
            IconButton(
                onClick = onReset,
                enabled = resetEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = resetLabel },
            ) {
                GlyphIcon(Glyph.Close, size = 20.dp)
            }
        }
    }
}

private data class PopularVentoyKey(val key: String, val labelRes: Int)

private val POPULAR_VENTOY_KEYS = listOf(
    PopularVentoyKey("VTOY_MENU_TIMEOUT", R.string.wave4_popular_menu_timeout),
    PopularVentoyKey("theme", R.string.wave4_popular_theme),
    PopularVentoyKey("VTOY_DEFAULT_MENU_MODE", R.string.wave4_popular_boot_mode),
)

@Composable
private fun AddParameterDialog(
    actionsEnabled: Boolean,
    existingKeys: Set<String>,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val resources = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wave4_add_param_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.wave4_popular_keys),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                POPULAR_VENTOY_KEYS.forEach { popular ->
                    val taken = popular.key in existingKeys
                    FilledTonalButton(
                        onClick = {
                            key = popular.key
                            if (value.isEmpty()) {
                                value = when (popular.key) {
                                    "VTOY_MENU_TIMEOUT" -> "10"
                                    "VTOY_DEFAULT_MENU_MODE" -> "0"
                                    else -> value
                                }
                            }
                            problem = null
                        },
                        enabled = actionsEnabled && !taken,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(popular.labelRes),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        problem = null
                    },
                    label = { Text(stringResource(R.string.settings_key_label)) },
                    singleLine = true,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        problem = null
                    },
                    label = { Text(stringResource(R.string.settings_value_label)) },
                    singleLine = true,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = key.trim()
                    val issue =
                        VentoyControlCatalog.keyIssue(trimmed)
                            ?: VentoyControlCatalog.valueIssue(trimmed, value)
                    when {
                        issue != null -> problem = resources.controlIssueText(issue)
                        trimmed in existingKeys ->
                            problem = resources.getString(
                                R.string.settings_problem_duplicate_key,
                                trimmed,
                            )
                        else -> onAdd(trimmed, value)
                    }
                },
                enabled = actionsEnabled && key.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.wave4_add_param_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun knownControlTitle(key: String): String =
    when (key) {
        "VTOY_WIN11_BYPASS_CHECK" -> stringResource(R.string.settings_known_bypass_checks)
        "VTOY_WIN11_BYPASS_NRO" -> stringResource(R.string.settings_known_bypass_nro)
        else -> key
    }

@Composable
private fun knownOptionLabel(key: String, value: String): String =
    when (key to value) {
        "VTOY_WIN11_BYPASS_CHECK" to "1" -> stringResource(R.string.settings_bypass_checks_on)
        "VTOY_WIN11_BYPASS_CHECK" to "0" -> stringResource(R.string.settings_bypass_checks_off)
        "VTOY_WIN11_BYPASS_NRO" to "1" -> stringResource(R.string.settings_nro_on)
        "VTOY_WIN11_BYPASS_NRO" to "0" -> stringResource(R.string.settings_nro_off)
        else -> "$key=$value"
    }

/**
 * Target-PC layout acknowledgement. Deliberately has no device identity: the disk belongs to
 * the machine being installed, not to the stick in the phone.
 */
@Composable
private fun LayoutConfirmDialog(
    confirmation: DiskLayoutConfirmation,
    actionsEnabled: Boolean,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var acknowledged by remember(confirmation) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_confirm_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.layout_confirm_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                confirmation.diskId?.let { id ->
                    Text(
                        text = stringResource(R.string.layout_disk_id, id),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                confirmation.declaredSizeMb?.let { size ->
                    Text(
                        text = stringResource(R.string.layout_disk_size_declared, size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                confirmation.totalMb?.let { total ->
                    Text(
                        text = stringResource(R.string.layout_total, total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                confirmation.remainingMb?.let { remaining ->
                    Text(
                        text = stringResource(R.string.layout_remaining, remaining),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                confirmation.installTargetPartitionId?.let { partition ->
                    Text(
                        text = stringResource(R.string.layout_install_target, partition),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                } ?: Text(
                    text = stringResource(R.string.layout_no_install_target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.layout_partitions, confirmation.partitionCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                confirmation.partitions.forEachIndexed { index, partition ->
                    Text(
                        text = stringResource(
                            R.string.layout_partition_line,
                            index + 1,
                            stringResource(roleText(partition.role)),
                            partition.sizeMb,
                            partition.label ?: "—",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (confirmation.wipe) {
                    Text(
                        text = stringResource(R.string.layout_wipe),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.layout_no_wipe_rewrite),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = acknowledged,
                            enabled = actionsEnabled,
                            role = Role.Checkbox,
                            onValueChange = { acknowledged = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = null,
                        enabled = actionsEnabled,
                    )
                    Text(
                        text = stringResource(R.string.layout_confirm_ack),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(true) },
                enabled = actionsEnabled && acknowledged,
            ) { Text(stringResource(R.string.layout_confirm_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Parameter editor: every block is opt-in and off by default; off means "not written". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenerateDialog(
    template: String,
    document: AutounattendDocument?,
    selectedImages: List<String>,
    actionsEnabled: Boolean,
    onGenerate: (AutounattendParams, Boolean, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = document?.existing
    val existingAccount = existing?.localAccount
    val existingLayout = existing?.diskLayout
    var language by remember(document) { mutableStateOf(existing?.languageTag ?: "en-US") }
    var localeOn by remember(document) { mutableStateOf(existing?.localeId != null) }
    var locale by remember(document) { mutableStateOf(existing?.localeId.orEmpty()) }
    var computerOn by remember(document) { mutableStateOf(existing?.computerName != null) }
    var computer by remember(document) { mutableStateOf(existing?.computerName.orEmpty()) }
    var accountOn by remember(document) { mutableStateOf(existingAccount != null) }
    var accountName by remember(document) { mutableStateOf(existingAccount?.name.orEmpty()) }
    var accountPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val showPasswordLabel = stringResource(
        if (passwordVisible) R.string.params_account_password_hide
        else R.string.params_account_password_show,
    )
    var accountAdmin by remember(document) { mutableStateOf(existingAccount?.inAdministrators == true) }
    var layoutOn by remember(document) { mutableStateOf(existingLayout != null) }
    var wipe by remember(document) { mutableStateOf(existingLayout?.wipe ?: false) }
    var diskId by remember(document) { mutableStateOf(existingLayout?.diskId?.toString() ?: "0") }
    var declaredDiskSize by remember(document) { mutableStateOf(existingLayout?.declaredSizeMb?.toString().orEmpty()) }
    val partitions = remember(document) {
        mutableStateListOf<LayoutPartitionDraft>().apply {
            existingLayout?.partitions?.forEach { partition ->
                add(LayoutPartitionDraft(partition.sizeMb.toString(), partition.role, partition.label.orEmpty()))
            }
        }
    }
    val markers = document?.destructiveMarkers.orEmpty()
    var destructiveAck by remember(document) { mutableStateOf(markers.isEmpty()) }

    val draftLayout =
        DiskLayout(
            writeLayout = true,
            diskId = diskId.toLongOrNull(),
            wipe = wipe,
            partitions = partitions.map { it.toPartition() },
            declaredSizeMb = declaredDiskSize.toLongOrNull(),
        )
    val layoutChangedForValidation = existingLayout == null || draftLayout.copy(declaredSizeMb = null) != existingLayout.copy(declaredSizeMb = null)
    val layoutProblems =
        if (layoutOn) {
            Autounattend.validate(
                AutounattendParams(diskLayout = draftLayout),
                requireDeclaredDiskSize = layoutChangedForValidation,
            ).problems
        } else {
            emptyList()
        }
    val layoutValid = !layoutOn || !layoutChangedForValidation || layoutProblems.isEmpty()
    val accountValid = !accountOn || accountName.isNotBlank()
    // Destructive markers are only accepted with an explicit tick; a null digest would be
    // refused by the backend, so the button stays disabled instead.
    val digestReady = markers.isEmpty() || (destructiveAck && document?.templateSha256 != null)
    val canGenerate = actionsEnabled && language.isNotBlank() && layoutValid && accountValid &&
        digestReady

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.params_title)) },
        text = {
            // Up to ten fields plus an unbounded layout list: the form scrolls instead of
            // being clipped by the M3 dialog text slot, so the acknowledgement and the last
            // fields stay reachable on short screens.
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = VentoySetup.resolveXmlPath(template),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text(stringResource(R.string.params_language)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    label = stringResource(R.string.params_locale),
                    checked = localeOn,
                    onCheckedChange = { localeOn = it },
                    enabled = actionsEnabled,
                )
                if (localeOn) {
                    OutlinedTextField(
                        value = locale,
                        onValueChange = { locale = it },
                        label = { Text(stringResource(R.string.params_locale_value)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.params_computer),
                    checked = computerOn,
                    onCheckedChange = { computerOn = it },
                    enabled = actionsEnabled,
                )
                if (computerOn) {
                    OutlinedTextField(
                        value = computer,
                        onValueChange = { computer = it },
                        label = { Text(stringResource(R.string.params_computer_value)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.params_account),
                    checked = accountOn,
                    onCheckedChange = { accountOn = it },
                    enabled = actionsEnabled,
                )
                if (accountOn) {
                    OutlinedTextField(
                        value = accountName,
                        onValueChange = { accountName = it },
                        label = { Text(stringResource(R.string.params_account_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = accountPassword,
                        onValueChange = { accountPassword = it },
                        label = { Text(stringResource(R.string.params_account_password)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.semantics {
                                    contentDescription = showPasswordLabel
                                },
                            ) {
                                GlyphIcon(
                                    glyph = if (passwordVisible) Glyph.EyeOff else Glyph.Eye,
                                    size = 20.dp,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(
                        label = stringResource(R.string.params_account_admin),
                        checked = accountAdmin,
                        onCheckedChange = { accountAdmin = it },
                        enabled = actionsEnabled,
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.params_layout),
                    checked = layoutOn,
                    onCheckedChange = { layoutOn = it },
                    enabled = actionsEnabled,
                    hint = stringResource(R.string.params_layout_hint),
                )
                if (layoutOn) {
                    OutlinedTextField(
                        value = diskId,
                        onValueChange = { diskId = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.params_disk_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = declaredDiskSize,
                        onValueChange = { declaredDiskSize = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.params_layout_disk_size)) },
                        singleLine = true,
                        isError = draftLayout.declaredSizeMb?.let { it > 0 } != true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (draftLayout.declaredSizeMb?.let { it > 0 } != true) {
                        Text(
                            text = stringResource(R.string.params_layout_disk_size_missing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val totalMb = diskLayoutTotalMb(draftLayout.partitions)
                    val remainingMb = diskLayoutRemainingMb(draftLayout)
                    when {
                        totalMb == null ->
                            Text(
                                text = stringResource(R.string.params_layout_overflow),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        remainingMb != null && remainingMb < 0 ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(R.string.layout_total, totalMb),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    text = stringResource(R.string.params_layout_over_by, -remainingMb),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        remainingMb != null ->
                            Text(
                                text = stringResource(
                                    R.string.params_layout_usage,
                                    totalMb,
                                    draftLayout.declaredSizeMb ?: 0L,
                                    remainingMb,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        else ->
                            Text(
                                text = stringResource(R.string.layout_total, totalMb),
                                style = MaterialTheme.typography.labelSmall,
                            )
                    }
                    diskLayoutInstallTargetPartitionId(draftLayout.partitions)?.let { target ->
                        Text(
                            text = stringResource(R.string.params_layout_install_target, target),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } ?: Text(
                        text = stringResource(R.string.params_layout_install_target_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SwitchRow(
                        label = stringResource(R.string.params_layout_wipe),
                        checked = wipe,
                        onCheckedChange = { wipe = it },
                        enabled = actionsEnabled,
                    )
                    partitions.forEachIndexed { index, draft ->
                        LayoutPartitionRow(
                            index = index,
                            draft = draft,
                            installTarget =
                                diskLayoutInstallTargetPartitionId(draftLayout.partitions) == index + 1,
                            actionsEnabled = actionsEnabled,
                            canMoveUp = index > 0,
                            canMoveDown = index < partitions.lastIndex,
                            onChange = { changed -> partitions[index] = changed },
                            onMoveUp = {
                                val previous = partitions[index - 1]
                                partitions[index - 1] = draft
                                partitions[index] = previous
                            },
                            onMoveDown = {
                                val next = partitions[index + 1]
                                partitions[index + 1] = draft
                                partitions[index] = next
                            },
                            onRemove = { partitions.removeAt(index) },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiskPartitionRole.entries.forEach { role ->
                            TextButton(
                                onClick = {
                                    partitions.add(
                                        LayoutPartitionDraft(
                                            sizeText = defaultSizeMb(role).toString(),
                                            role = role,
                                            label = "",
                                        ),
                                    )
                                },
                                enabled = actionsEnabled,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) { Text(stringResource(roleText(role))) }
                        }
                    }
                    layoutProblems
                        .filterNot { it == AutounattendProblem.LayoutDiskSizeMissing }
                        .forEach { problem ->
                            Text(
                                text = autounattendProblemText(problem),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    Text(
                        text = stringResource(R.string.params_layout_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (markers.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.unattend_destructive_note),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    markers.forEach { marker ->
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
                                value = destructiveAck,
                                enabled = actionsEnabled,
                                role = Role.Checkbox,
                                onValueChange = { destructiveAck = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = destructiveAck,
                            onCheckedChange = null,
                            enabled = actionsEnabled,
                        )
                        Text(
                            text = stringResource(R.string.unattend_ack_destructive),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (selectedImages.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.params_images, selectedImages.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val accountValue = if (accountOn) {
                        val existingValue = existingAccount
                        val groupChanged = existingValue == null || accountAdmin != (existingValue.inAdministrators == true)
                        LocalAccount(
                            name = accountName.trim().ifBlank { null },
                            password = accountPassword.ifEmpty { null },
                            inAdministrators = accountAdmin,
                            preservedGroup = if (groupChanged) null else existingValue?.preservedGroup,
                            passwordIntent = if (accountPassword.isEmpty()) XmlFieldIntent.Preserve else XmlFieldIntent.Set,
                            groupIntent = if (groupChanged) XmlFieldIntent.Set else XmlFieldIntent.Preserve,
                        )
                    } else null
                    val accountIntent = when {
                        !accountOn && existingAccount != null -> XmlFieldIntent.Remove
                        !accountOn -> XmlFieldIntent.Preserve
                        existingAccount == null -> XmlFieldIntent.Set
                        accountName.trim().ifBlank { null } != existingAccount.name ||
                            accountPassword.isNotEmpty() ||
                            accountAdmin != (existingAccount.inAdministrators == true) -> XmlFieldIntent.Set
                        else -> XmlFieldIntent.Preserve
                    }
                    val localeValue = locale.trim().ifBlank { null }
                    val computerValue = computer.trim().ifBlank { null }
                    val layoutChanged = existingLayout == null || draftLayout != existingLayout
                    val layoutIntent = when {
                        !layoutOn && existingLayout != null -> XmlFieldIntent.Remove
                        !layoutOn -> XmlFieldIntent.Preserve
                        existingLayout == null || layoutChanged -> XmlFieldIntent.Set
                        else -> XmlFieldIntent.Preserve
                    }
                    val languageValue = language.trim().ifBlank { null }
                    val languageIntent = when {
                        document != null && existing?.languageTag == null && languageValue == "en-US" -> XmlFieldIntent.Preserve
                        existing?.languageTag == languageValue -> XmlFieldIntent.Preserve
                        else -> XmlFieldIntent.Set
                    }
                    val localeIntent = when {
                        localeOn && existing?.localeId == localeValue -> XmlFieldIntent.Preserve
                        localeOn -> XmlFieldIntent.Set
                        existing?.localeId != null -> XmlFieldIntent.Remove
                        else -> XmlFieldIntent.Preserve
                    }
                    val computerIntent = when {
                        computerOn && existing?.computerName == computerValue -> XmlFieldIntent.Preserve
                        computerOn -> XmlFieldIntent.Set
                        existing?.computerName != null -> XmlFieldIntent.Remove
                        else -> XmlFieldIntent.Preserve
                    }
                    onGenerate(
                        AutounattendParams(
                            languageTag = languageValue,
                            localeId = localeValue,
                            computerName = computerValue,
                            localAccount = accountValue,
                            diskLayout = if (layoutOn) draftLayout else null,
                            intents = AutounattendIntents(
                                language = languageIntent,
                                locale = localeIntent,
                                computerName = computerIntent,
                                localAccount = accountIntent,
                                diskLayout = layoutIntent,
                            ),
                            originalDiskLayout = existingLayout,
                        ),
                        markers.isNotEmpty() && destructiveAck,
                        document?.templateSha256,
                    )
                },
                enabled = canGenerate,
                colors = if (markers.isNotEmpty()) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(stringResource(R.string.unattend_generate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Locally editable partition text; conversion never hides an invalid size as a valid one. */
private data class LayoutPartitionDraft(
    val sizeText: String,
    val role: DiskPartitionRole,
    val label: String,
) {
    fun toPartition(): LayoutPartition =
        LayoutPartition(
            sizeMb = sizeText.toLongOrNull() ?: 0L,
            role = role,
            label = label.trim().ifBlank { null },
        )
}

@Composable
private fun LayoutPartitionRow(
    index: Int,
    draft: LayoutPartitionDraft,
    installTarget: Boolean,
    actionsEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (LayoutPartitionDraft) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val moveUpLabel = stringResource(R.string.params_layout_move_up)
    val moveDownLabel = stringResource(R.string.params_layout_move_down)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.params_layout_partition, index + 1),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onMoveUp,
                enabled = actionsEnabled && canMoveUp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.semantics { contentDescription = moveUpLabel },
            ) { Text("↑") }
            TextButton(
                onClick = onMoveDown,
                enabled = actionsEnabled && canMoveDown,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.semantics { contentDescription = moveDownLabel },
            ) { Text("↓") }
            TextButton(
                onClick = onRemove,
                enabled = actionsEnabled,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) { Text(stringResource(R.string.params_remove_partition)) }
        }
        if (installTarget) {
            Text(
                text = stringResource(R.string.params_layout_install_target, index + 1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = draft.sizeText,
                onValueChange = { text ->
                    onChange(draft.copy(sizeText = text.filter { it.isDigit() }))
                },
                label = { Text(stringResource(R.string.params_layout_size)) },
                singleLine = true,
                isError = draft.sizeText.toLongOrNull()?.let { it > 0 } != true,
                modifier = Modifier.weight(1f),
            )
            LayoutRolePicker(
                role = draft.role,
                actionsEnabled = actionsEnabled,
                onRoleChange = { role -> onChange(draft.copy(role = role)) },
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = draft.label,
            onValueChange = { text -> onChange(draft.copy(label = text)) },
            label = { Text(stringResource(R.string.params_layout_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LayoutRolePicker(
    role: DiskPartitionRole,
    actionsEnabled: Boolean,
    onRoleChange: (DiskPartitionRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(roleText(role))) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DiskPartitionRole.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(roleText(choice))) },
                    onClick = {
                        expanded = false
                        onRoleChange(choice)
                    },
                    enabled = actionsEnabled,
                )
            }
        }
    }
}

internal fun unreadableText(problem: UnreadableProblem): Int = when (problem) {
    UnreadableProblem.NotUnattendRoot -> R.string.unattend_not_unattend
    UnreadableProblem.MalformedXml -> R.string.unattend_malformed
    UnreadableProblem.TooLarge -> R.string.unattend_too_large
    UnreadableProblem.IoFailure -> R.string.unattend_io_failure
    UnreadableProblem.Empty -> R.string.unattend_empty_file
}

private fun bindProblemText(problem: BindProblem): Int = when (problem) {
    BindProblem.NotOnPartition -> R.string.unattend_bind_not_on_partition
    BindProblem.TemplateUnreadable -> R.string.unattend_bind_template_unreadable
    BindProblem.OutsideScope -> R.string.unattend_bind_outside_scope
    BindProblem.DuplicateBinding -> R.string.unattend_bind_duplicate
    BindProblem.Unknown -> R.string.unattend_bind_unknown
}

private fun roleText(role: DiskPartitionRole): Int = when (role) {
    DiskPartitionRole.Efi -> R.string.params_role_efi
    DiskPartitionRole.Msr -> R.string.params_role_msr
    DiskPartitionRole.Primary -> R.string.params_role_primary
    DiskPartitionRole.Recovery -> R.string.params_role_recovery
}

private fun defaultSizeMb(role: DiskPartitionRole): Long = when (role) {
    DiskPartitionRole.Efi -> 100
    DiskPartitionRole.Msr -> 16
    DiskPartitionRole.Primary -> 4096
    DiskPartitionRole.Recovery -> 512
}
