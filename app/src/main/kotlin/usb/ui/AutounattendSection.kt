package usb.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R
import androidx.compose.ui.res.painterResource
import usb.ventoy.VentoySetup
import usb.ventoy.WindowsTemplate
import usb.ventoy.XmlText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import usb.ventoy.VentoyControlKeyKind
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
    // Shared by the path row and GenerateDialog so display cannot disagree with the write target.
    val prospectiveXmlPath =
        state.windowsSelectedImages.firstOrNull()?.let {
            runCatching { VentoySetup.suggestedXmlPath(it) }.getOrNull()
        } ?: VentoySetup.resolveXmlPath("autounattend.xml")
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
    LaunchedEffect(state.device?.identity, state.device?.attachGeneration) {
        generateOpen = false
        xmlEditorOpen = false
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
        // Only a real resolved file is shown. The prospective generate target stays an
        // internal default and is never presented as if the user had selected it.
        val resolvedPath = runCatching {
            template?.let(VentoySetup::resolveXmlPath)
        }.getOrNull()
        ConfigFileStatusRow(
            pathLabel = resolvedPath,
            dimmedPath = resolvedPath == null,
            showChanged = false,
            onReset = if (template != null) {
                { onIntent(UiIntent.ClearWindowsTemplate) }
            } else {
                null
            },
            resetEnabled = state.actionsEnabled,
            // Keep the picker mounted when the stick is gone; only disable it. Phone/stick
            // sheets still refuse unsafe work through their own guards.
            onPick = { templateSourcePrompt = true },
            pickEnabled = !xmlGone && state.actionsEnabled,
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
                if (doc.unknownElements.isNotEmpty()) {
                    val unknownElementFormat =
                        stringResource(R.string.unattend_unknown_element)
                    Text(
                        text = stringResource(
                            R.string.unattend_unknown_title,
                            doc.unknownElements.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    doc.unknownElements.forEach { element ->
                        Text(
                            text = element.path?.let { path ->
                                unknownElementFormat.format(element.name, path)
                            } ?: element.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> Unit
        }
        if (!xmlGone) {
            // Choosing or replacing the template is the icon next to the path row.
            Button(
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
                FilledTonalButton(
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

    if (!xmlGone) {
        val candidates = state.autounattendCandidates
        var showAllImages by remember(state.device?.identity, state.device?.attachGeneration) { mutableStateOf(false) }
        var addImagesOpen by remember(state.device?.identity, state.device?.attachGeneration) { mutableStateOf(false) }
        var destructiveBindAck by remember(state.device?.identity, state.device?.attachGeneration, document?.templateSha256) { mutableStateOf(false) }
        val loadedTemplateHasMarkers = document?.destructiveMarkers?.isNotEmpty() == true
        val configSourceKey = state.ventoySettingsSource.cacheKey()
        var candidatesSourceKey by remember(state.device?.identity, state.device?.attachGeneration) {
            mutableStateOf<String?>(null)
        }
        LaunchedEffect(
            state.device?.identity,
            state.device?.attachGeneration,
            configSourceKey,
            state.autounattendCandidates,
            state.actionsEnabled,
        ) {
            if (state.actionsEnabled &&
                (candidatesSourceKey != configSourceKey || state.autounattendCandidates == null)
            ) {
                candidatesSourceKey = configSourceKey
                onIntent(UiIntent.LoadAutounattendCandidatesOnly)
            }
        }
        LaunchedEffect(
            state.device?.identity,
            state.device?.attachGeneration,
            state.windowsImageLinks,
            state.windowsImageLinksSource,
            configSourceKey,
            state.actionsEnabled,
        ) {
            if (state.actionsEnabled &&
                (state.windowsImageLinks == null ||
                    state.windowsImageLinksSource != configSourceKey)
            ) {
                onIntent(UiIntent.LoadWindowsImageLinks)
            }
        }
        val pickedTemplate = state.filesPickSelections[FilesPickPurpose.WindowsTemplate]
        val linkTarget = state.windowsLinkTargetImage
        LaunchedEffect(pickedTemplate, linkTarget) {
            val picked = pickedTemplate ?: return@LaunchedEffect
            if (picked.isDirectory) return@LaunchedEffect
            val target = linkTarget
            if (target != null) {
                onIntent(UiIntent.ClearFilesPickSelection(FilesPickPurpose.WindowsTemplate))
                onIntent(UiIntent.SetWindowsLinkTarget(null))
                val device = state.device
                val livePartition = state.partitions.firstOrNull {
                    it.id == picked.partitionId &&
                        it.startLba == picked.startLba &&
                        it.sectorCount == picked.sectorCount
                }
                val xmlPath = runCatching {
                    usb.ventoy.normalizeVentoyFilePath(picked.path)
                }.getOrNull()
                if (device == null ||
                    picked.identity != device.identity ||
                    picked.attachGeneration != device.attachGeneration ||
                    picked.partitionId != "1" ||
                    livePartition == null ||
                    xmlPath == null
                ) {
                    onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
                } else {
                    val sameLoaded = document?.sourcePath?.equals(xmlPath, ignoreCase = true) == true
                    onIntent(UiIntent.RequestBindAutounattend(
                        xmlPath,
                        listOf(target),
                        allowDestructiveTemplate =
                            sameLoaded && loadedTemplateHasMarkers && destructiveBindAck,
                        expectedTemplateSha256 = if (sameLoaded) document?.templateSha256 else null,
                        overwrite = true,
                    ))
                }
            } else {
                onIntent(UiIntent.SelectWindowsTemplate)
            }
        }
        LaunchedEffect(state.filesPickMode, pickedTemplate, linkTarget) {
            if (state.filesPickMode == null && pickedTemplate == null && linkTarget != null) {
                onIntent(UiIntent.SetWindowsLinkTarget(null))
            }
        }
        SectionCard(
            title = stringResource(R.string.unattend_bindings_title),
            subtitle = stringResource(R.string.unattend_bindings_subtitle),
        ) {
            if (loadedTemplateHasMarkers) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = destructiveBindAck, onCheckedChange = { destructiveBindAck = it }, enabled = state.actionsEnabled)
                    Text(stringResource(R.string.unattend_ack_destructive), style = MaterialTheme.typography.labelSmall)
                }
            }
            val links = state.windowsImageLinks.orEmpty()
            val removeImageDescription = stringResource(R.string.unattend_remove_image)
            val boundPaths = links.map { it.image }.toSet()
            val rows = (links.map { it.image to it.templates.firstOrNull() } +
                state.windowsSelectedImages.filter { it !in boundPaths }.map { it to null })
                .distinctBy { it.first }
            if (rows.isEmpty()) {
                if (candidates?.images.isNullOrEmpty()) {
                    Text(
                        text = stringResource(R.string.unattend_no_images_add_to_ventoy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { addImagesOpen = true },
                    enabled = state.actionsEnabled && candidates?.images?.isNotEmpty() == true,
                ) { Text(stringResource(R.string.unattend_choose_image)) }
            } else {
                rows.take(if (showAllImages) rows.size else 3).forEach { (image, xml) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(image, style = MaterialTheme.typography.bodySmall)
                            Text(xml ?: stringResource(R.string.unattend_xml_not_selected), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            onIntent(UiIntent.SetWindowsLinkTarget(image))
                            onIntent(UiIntent.BeginFilesPick(FilesPickPurpose.WindowsTemplate, ShellTab.Ventoy, FilesPickTarget.File, initialPath = "/ventoy", expectedIdentity = state.device?.identity, expectedAttachGeneration = state.device?.attachGeneration))
                        }, enabled = state.actionsEnabled) { GlyphIcon(Glyph.File, size = 20.dp) }
                        IconButton(
                            onClick = {
                                if (xml != null) {
                                    onIntent(UiIntent.RequestUnlinkWindowsImages(listOf(image)))
                                } else {
                                    onIntent(UiIntent.ToggleWindowsImage(image))
                                }
                            },
                            enabled = state.actionsEnabled,
                            modifier = Modifier.semantics {
                                contentDescription = removeImageDescription
                            },
                        ) { GlyphIcon(Glyph.Close, size = 20.dp) }
                    }
                }
                if (rows.size > 3) TextButton(onClick = { showAllImages = !showAllImages }) { Text(stringResource(if (showAllImages) R.string.unattend_collapse else R.string.unattend_show_all)) }
            }
            if (rows.isNotEmpty()) {
                Button(onClick = { addImagesOpen = true }, enabled = state.actionsEnabled && candidates?.images?.isNotEmpty() == true) { Text(stringResource(R.string.unattend_add_image)) }
            }
        }
        if (addImagesOpen) {
            var selected by remember(state.windowsSelectedImages) { mutableStateOf(state.windowsSelectedImages.toSet()) }
            ModalBottomSheet(onDismissRequest = { addImagesOpen = false }) {
                Text(stringResource(R.string.unattend_choose_image), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
                candidates?.scopeLimitedBy?.let { Text(stringResource(R.string.unattend_scope_limited, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp)) }
                if (candidates?.truncated == true) Text(stringResource(R.string.unattend_truncated), modifier = Modifier.padding(horizontal = 24.dp))
                candidates?.error?.let { Text(stringResource(R.string.raw_stick_incomplete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp)) }
                candidates?.images.orEmpty().forEach { image ->
                    val selectable = image.problem == null && !image.bound
                    val checked = image.imagePath in selected
                    PopupChoiceBlock(
                        selected = checked,
                        enabled = selectable,
                        role = Role.Checkbox,
                        onClick = {
                            if (selectable) {
                                selected = if (checked) {
                                    selected - image.imagePath
                                } else {
                                    selected + image.imagePath
                                }
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = selectable)
                            Column(Modifier.padding(8.dp)) {
                                Text(image.imagePath)
                                image.problem?.let {
                                    Text(
                                        stringResource(bindProblemText(it)),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
                Button(onClick = {
                    val old = state.windowsSelectedImages.toSet()
                    (old - selected).forEach { onIntent(UiIntent.ToggleWindowsImage(it)) }
                    (selected - old).forEach { onIntent(UiIntent.ToggleWindowsImage(it)) }
                    addImagesOpen = false
                }, enabled = state.actionsEnabled, modifier = Modifier.padding(24.dp)) { Text(stringResource(R.string.action_confirm)) }
            }
        }
        state.pendingWindowsUnlink?.let {
            AlertDialog(
                onDismissRequest = { onIntent(UiIntent.CancelWindowsUnlink) },
                title = { Text(stringResource(R.string.unattend_unlink)) },
                text = { Text(stringResource(R.string.unattend_unlink_confirm)) },
                confirmButton = { TextButton(onClick = { onIntent(UiIntent.ConfirmWindowsUnlink) }) { Text(stringResource(R.string.action_confirm)) } },
                dismissButton = { TextButton(onClick = { onIntent(UiIntent.CancelWindowsUnlink) }) { Text(stringResource(R.string.action_cancel)) } },
            )
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
    if (templateSourcePrompt) {
        ConfigSourceSheet(
            title = stringResource(R.string.windows_template_choose_source),
            onDismiss = { templateSourcePrompt = false },
            defaultPath = VentoySetup.resolveXmlPath("autounattend.xml"),
            onDefault = {
                templateSourcePrompt = false
                onIntent(UiIntent.SetWindowsLinkTarget(null))
                onIntent(
                    UiIntent.LoadAutounattend(
                        VentoySetup.resolveXmlPath("autounattend.xml"),
                    ),
                )
            },
            stickEnabled = !xmlGone,
            onStick = {
                templateSourcePrompt = false
                onIntent(UiIntent.SetWindowsLinkTarget(null))
                onIntent(UiIntent.BeginFilesPick(
                    purpose = FilesPickPurpose.WindowsTemplate,
                    returnTab = ShellTab.Ventoy,
                    target = FilesPickTarget.File,
                    initialPath = "/ventoy",
                    expectedIdentity = templateSourceIdentity,
                    expectedAttachGeneration = templateSourceGeneration,
                ))
            },
            onPhone = {
                templateSourcePrompt = false
                onIntent(UiIntent.SetWindowsLinkTarget(null))
                folderKind = WindowsImportKind.XmlTemplate
            },
        )
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
                ?: prospectiveXmlPath
        GenerateDialog(
            template = generateTarget,
            document = document,
            selectedImages = state.windowsSelectedImages,
            actionsEnabled = state.actionsEnabled,
            onGenerate = { params, allowDestructive, sha, chosenName ->
                val directory = template?.substringBeforeLast('/', "/") ?: "/ventoy"
                val chosenPath = "$directory/$chosenName"
                onIntent(UiIntent.RequestGenerateAutounattend(
                    xmlName = chosenPath,
                    params = params,
                    imagePaths = state.windowsSelectedImages,
                    overwriteXml = template != null && document != null,
                    allowDestructiveTemplate = allowDestructive,
                    expectedTemplateSha256 = sha,
                    renameFromXmlName = template?.takeUnless { it == chosenPath },
                ))
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
    var applyReviewOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.pendingVentoyControls) {
        if (state.pendingVentoyControls.isEmpty()) applyReviewOpen = false
    }
    LaunchedEffect(state.operation?.state, state.operation?.title) {
        if (editorOpen && state.operation?.title == OperationTitle.SetVentoyGlobalSetting && state.operation?.state == OperationState.Completed) {
            editorOpen = false
            editorBaseline = null
        }
    }
    LaunchedEffect(state.device?.identity, state.device?.attachGeneration) {
        sourceMenuOpen = false
        editorOpen = false
        applyReviewOpen = false
        editorBaseline = null
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
        ConfigFileStatusRow(
            pathLabel = source.label(),
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
        if (settings is VentoyGlobalSettings.Missing && source.isDefaultPath()) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.settings_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        if (settings is VentoyGlobalSettings.ReadError) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            when {
                                settings.damagedLayout -> R.string.settings_damaged_layout
                                customPath -> R.string.settings_source_status_error
                                else -> R.string.settings_read_error
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!settings.damagedLayout) {
                        Text(
                            text = normalizeProviderText(settings.message),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (!settingsGone) {
                        TextButton(
                            onClick = { onIntent(UiIntent.LoadVentoyGlobalSettings) },
                            enabled = state.actionsEnabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_retry),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
        StagedControlsHints(state = state)
        if (pending.isNotEmpty()) {
            CompactStagedControls(
                pending = pending,
                onDiscard = { controlKey -> onIntent(UiIntent.DiscardStagedVentoyControl(controlKey)) },
            )
        }
        if (!settingsGone) {
            Button(
                onClick = {
                    editorBaseline = state.pendingVentoyControls
                    editorOpen = true
                },
                enabled = state.actionsEnabled && settings != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.wave4_editor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val fileEntries = (settings as? VentoyGlobalSettings.Present)?.entries.orEmpty()
            if (fileEntries.isNotEmpty()) {
                VentoyFileSettingsPreview(fileEntries)
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
                onApplied = {
                    // Staged successfully; keep pending and drop the cancel baseline.
                    editorOpen = false
                    editorBaseline = null
                    applyReviewOpen = true
                },
            )
        }
        if (applyReviewOpen) {
            VentoyApplyDialog(
                pending = pending,
                enabled = state.actionsEnabled,
                onDismiss = { applyReviewOpen = false },
                onWrite = {
                    applyReviewOpen = false
                    onIntent(UiIntent.RequestApplyVentoyControls)
                },
            )
        }
    }
    if (sourceMenuOpen) {
        ConfigSourceSheet(
            title = stringResource(R.string.settings_source_choose),
            onDismiss = { sourceMenuOpen = false },
            defaultPath = usb.ventoy.DEFAULT_VENTOY_CONFIG_PATH,
            onDefault = {
                sourceMenuOpen = false
                onIntent(UiIntent.UseDefaultVentoySettingsFile)
            },
            stickEnabled = !settingsGone,
            onStick = {
                sourceMenuOpen = false
                onIntent(UiIntent.BeginFilesPick(FilesPickPurpose.VentoyConfig, ShellTab.Ventoy, FilesPickTarget.File, initialPath = "/ventoy"))
            },
            onPhone = {
                sourceMenuOpen = false
                onPickVentoySettingsFile()
            },
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
    onApplied: () -> Unit,
) {
    val entries = (state.ventoyGlobalSettings as? VentoyGlobalSettings.Present)?.entries.orEmpty()
    var applyProblem by remember { mutableStateOf<String?>(null) }
    var customAddOpen by remember { mutableStateOf(false) }
    var customKey by remember { mutableStateOf("") }
    var customValue by remember { mutableStateOf("") }
    var customProblem by remember { mutableStateOf<String?>(null) }
    var officialPickerOpen by remember { mutableStateOf(false) }
    val applyProblemRequester = remember { BringIntoViewRequester() }
    val customProblemRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(applyProblem) {
        if (applyProblem != null) applyProblemRequester.bringIntoView()
    }
    LaunchedEffect(customProblem) {
        if (customProblem != null) customProblemRequester.bringIntoView()
    }
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
        // Pending-only custom rows (originalKey=null when seeded) that the user deleted from
        // the editor must be erased from staging, not resurrected by merge and not written as
        // stick removals (they never existed on the source file).
        val draftKeys = drafts.map { it.key }.filter { it.isNotBlank() }.toSet()
        val discardedPendingUnknown = state.pendingVentoyControls
            .asSequence()
            .filter { pending ->
                pending.value != null &&
                    VentoyControlCatalog.known(pending.key) == null &&
                    pending.key !in draftKeys &&
                    entries.none { it.key == pending.key }
            }
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
            settings.isEmpty() &&
            renamedOrDeleted.isEmpty() &&
            knownRemoved.isEmpty() &&
            discardedPendingUnknown.isEmpty()
        ) {
            applyProblem = context.resources.getString(R.string.settings_problem_no_changes)
        } else {
            applyProblem = null
            // Stage only: card Write / RequestApplyVentoyControls persists to the stick.
            // CommitAndApply here emptied pending and left Write permanently inactive.
            onIntent(
                UiIntent.CommitVentoyControls(
                    removedKeys = renamedOrDeleted + knownRemoved,
                    settings = settings,
                    duplicateKeys = duplicateKeys,
                    discardedKeys = discardedPendingUnknown,
                ),
            )
            onApplied()
        }
    }
    val closeLabel = stringResource(R.string.settings_cancel)
    val applyLabel = stringResource(R.string.settings_apply)
    val listState = rememberLazyListState()
    val officialKeySet = remember(state.ventoyControlKeyCatalog) {
        state.ventoyControlKeyCatalog?.keys?.map { it.key }?.toSet().orEmpty()
    }
    val officialDrafts = drafts.filter { it.key in officialKeySet }
    val customDrafts = drafts.filter { it.key !in officialKeySet }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        expandedHeight = adaptiveTopAppBarHeight(),
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
                            containerColor = MaterialTheme.colorScheme.surface,
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
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.bringIntoViewRequester(applyProblemRequester),
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .imePadding(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "known-title") {
                            Text(
                                text = stringResource(R.string.wave4_win11_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(
                            items = VentoyControlCatalog.known,
                            key = { "known:${it.key}" },
                        ) { known ->
                            KnownControlRow(
                                known,
                                entries,
                                state.pendingVentoyControls,
                                state.actionsEnabled,
                                onIntent,
                            )
                        }

                        item(key = "official-title") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.wave4_official_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.wave4_official_section_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(
                            items = officialDrafts,
                            key = { "official:${it.id}" },
                        ) { draft ->
                            EditablePairRow(
                                draft = draft,
                                actionsEnabled = state.actionsEnabled,
                                onChange = { changed ->
                                    val i = drafts.indexOfFirst { it.id == draft.id }
                                    if (i >= 0) drafts[i] = changed
                                },
                                onDelete = { drafts.removeAll { it.id == draft.id } },
                                keyReadOnly = true,
                                description = ventoyKeyShortDescription(draft.key),
                            )
                        }
                        item(key = "official-add") {
                            FilledTonalButton(
                                onClick = { officialPickerOpen = true },
                                enabled = state.actionsEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.wave4_add_official))
                            }
                        }

                        item(key = "custom-title") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.wave4_custom_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.wave4_custom_section_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(
                            items = customDrafts,
                            key = { "custom:${it.id}" },
                        ) { draft ->
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
                        item(key = "custom-add") {
                        if (customAddOpen) {
                            val existingKeys = drafts.map { it.key }.filter { it.isNotBlank() }.toSet() +
                                VentoyControlCatalog.known.map { it.key }.toSet() +
                                state.ventoyControlKeyCatalog?.keys?.map { it.key }.orEmpty().toSet()
                            OutlinedTextField(
                                value = customKey,
                                onValueChange = {
                                    customKey = it
                                    customProblem = null
                                },
                                label = { Text(stringResource(R.string.settings_key_label)) },
                                singleLine = true,
                                enabled = state.actionsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = customValue,
                                onValueChange = {
                                    customValue = it
                                    customProblem = null
                                },
                                label = { Text(stringResource(R.string.settings_value_label)) },
                                singleLine = true,
                                enabled = state.actionsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            customProblem?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.bringIntoViewRequester(customProblemRequester),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = {
                                        customAddOpen = false
                                        customKey = ""
                                        customValue = ""
                                        customProblem = null
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.settings_cancel))
                                }
                                TextButton(
                                    onClick = {
                                        val trimmed = customKey.trim()
                                        val issue = VentoyControlCatalog.keyIssue(trimmed)
                                            ?: VentoyControlCatalog.valueIssue(trimmed, customValue)
                                        customProblem = when {
                                            issue != null -> context.resources.controlIssueText(issue)
                                            trimmed in existingKeys -> context.resources.getString(
                                                R.string.settings_problem_duplicate_key,
                                                trimmed,
                                            )
                                            else -> null
                                        }
                                        if (customProblem == null) {
                                            drafts.add(
                                                EditorPairDraft(
                                                    "new:${drafts.size}:$trimmed",
                                                    null,
                                                    trimmed,
                                                    "",
                                                    customValue,
                                                ),
                                            )
                                            customAddOpen = false
                                            customKey = ""
                                            customValue = ""
                                        }
                                    },
                                    enabled = state.actionsEnabled && customKey.isNotBlank(),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.wave4_add_param_confirm))
                                }
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { customAddOpen = true },
                                enabled = state.actionsEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.wave4_add_custom))
                            }
                        }
                        }
                    }
                }
            }
        }
    }
    if (officialPickerOpen) {
        OfficialControlKeyPickerDialog(
            state = state,
            existingKeys = drafts.map { it.key }.filter { it.isNotBlank() }.toSet() +
                VentoyControlCatalog.known.map { it.key }.toSet(),
            onIntent = onIntent,
            onAdd = { key, value ->
                drafts.add(
                    EditorPairDraft("official:${drafts.size}:$key", null, key, "", value),
                )
                officialPickerOpen = false
            },
            onDismiss = { officialPickerOpen = false },
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
    var xmlName by remember(template) { mutableStateOf(template.substringAfterLast('/')) }
    var draft by remember(sourceText) { mutableStateOf(sourceText) }
    var applyProblem by remember { mutableStateOf<String?>(null) }
    var destructiveAck by remember(document.templateSha256) { mutableStateOf(false) }
    val sourceCharset = remember(document.sourceCharsetName) {
        runCatching { document.sourceCharsetName?.let(Charset::forName) }.getOrNull()
            ?: StandardCharsets.UTF_8
    }
    val markers by produceState(emptyList<String>(), draft, sourceCharset, document.sourceBom) {
        delay(300)
        value = withContext(Dispatchers.Default) {
            runCatching {
                WindowsTemplate.inspect(
                    XmlText.encode(draft, sourceCharset, document.sourceBom),
                    requireUnattendRoot = true,
                ).destructiveMarkers
            }.getOrDefault(emptyList())
        }
    }
    val invalidMessage = stringResource(R.string.unattend_editor_invalid)
    val destructiveAckRequired = stringResource(R.string.unattend_editor_destructive_ack)
    val xmlFieldDescription = stringResource(R.string.unattend_editor_title)
    val closeLabel = stringResource(R.string.settings_cancel)
    val saveLabel = stringResource(R.string.settings_save)
    val invalidNameMessage = stringResource(R.string.unattend_xml_name_invalid)
    fun commitDraft() {
        if (xmlName.isBlank() || !xmlName.endsWith(".xml", true) || '/' in xmlName || ".." in xmlName) {
            applyProblem = invalidNameMessage
            return
        }
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
                xmlName = template.substringBeforeLast('/', "/") + "/" + xmlName,
                rawBytes = encoded,
                overwriteXml = true,
                allowDestructiveTemplate = needsDestructive && destructiveAck,
                expectedTemplateSha256 = document.templateSha256,
                identity = device?.identity,
                attachGeneration = device?.attachGeneration ?: 0L,
                renameFromXmlName = template,
            ),
        )
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        expandedHeight = adaptiveTopAppBarHeight(),
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
                            containerColor = MaterialTheme.colorScheme.surface,
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
                    OutlinedTextField(
                        value = xmlName,
                        onValueChange = { xmlName = it },
                        label = { Text(stringResource(R.string.unattend_xml_name)) },
                        singleLine = true,
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth(),
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
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
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
    keyReadOnly: Boolean = false,
    description: String? = null,
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
            Column(modifier = Modifier.fillMaxWidth()) {
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp),
                    )
                }
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
                    readOnly = keyReadOnly,
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
}

/** Short, localized semantics for every key bundled with the pinned Ventoy release. */
@Composable
private fun ventoyKeyShortDescription(key: String): String? = when (key) {
    "VTOY_MENU_LANGUAGE" -> stringResource(R.string.ventoy_key_menu_language)
    "VTOY_DEFAULT_SEARCH_ROOT" -> stringResource(R.string.ventoy_key_default_search_root)
    "VTOY_SECURE_BOOT_POLICY" -> stringResource(R.string.ventoy_key_secure_boot_policy)
    "VTOY_WIN_UEFI_RES_LOCK" -> stringResource(R.string.ventoy_key_win_uefi_res_lock)
    "VTOY_WIN11_BYPASS_CHECK" -> stringResource(R.string.ventoy_key_win11_bypass_check)
    "VTOY_WIN11_BYPASS_NRO" -> stringResource(R.string.ventoy_key_win11_bypass_nro)
    "VTOY_DEFAULT_MENU_MODE" -> stringResource(R.string.ventoy_key_default_menu_mode)
    "VTOY_MENU_TIMEOUT" -> stringResource(R.string.ventoy_key_menu_timeout)
    "VTOY_DEFAULT_IMAGE" -> stringResource(R.string.ventoy_key_default_image)
    "VTOY_MAX_SEARCH_LEVEL" -> stringResource(R.string.ventoy_key_max_search_level)
    "VTOY_SECONDARY_BOOT_MENU" -> stringResource(R.string.ventoy_key_secondary_boot_menu)
    "VTOY_SHOW_PASSWORD_ASTERISK" -> stringResource(R.string.ventoy_key_show_password_asterisk)
    "VTOY_SECONDARY_TIMEOUT" -> stringResource(R.string.ventoy_key_secondary_timeout)
    "VTOY_DEFAULT_KBD_LAYOUT" -> stringResource(R.string.ventoy_key_default_kbd_layout)
    "VTOY_TREE_VIEW_MENU_STYLE" -> stringResource(R.string.ventoy_key_tree_view_menu_style)
    "VTOY_FILT_DOT_UNDERSCORE_FILE" -> stringResource(R.string.ventoy_key_filt_dot_underscore_file)
    "VTOY_SORT_CASE_SENSITIVE" -> stringResource(R.string.ventoy_key_sort_case_sensitive)
    "VTOY_VHD_NO_WARNING" -> stringResource(R.string.ventoy_key_vhd_no_warning)
    "VTOY_FILE_FLT_ISO" -> stringResource(R.string.ventoy_key_file_flt_iso)
    "VTOY_FILE_FLT_WIM" -> stringResource(R.string.ventoy_key_file_flt_wim)
    "VTOY_FILE_FLT_EFI" -> stringResource(R.string.ventoy_key_file_flt_efi)
    "VTOY_FILE_FLT_IMG" -> stringResource(R.string.ventoy_key_file_flt_img)
    "VTOY_FILE_FLT_VHD" -> stringResource(R.string.ventoy_key_file_flt_vhd)
    "VTOY_FILE_FLT_VTOY" -> stringResource(R.string.ventoy_key_file_flt_vtoy)
    else -> null
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
    val booleanKey =
        known.allowed.size == 2 && known.allowed.containsAll(listOf("0", "1"))
    // Both official Windows 11 bypass controls default to 1 in Ventoy.
    val effectiveValue = value ?: "1"
    val current =
        when {
            booleanKey && value == null -> stringResource(R.string.settings_default_on)
            booleanKey && effectiveValue == "1" -> stringResource(R.string.settings_enabled)
            booleanKey -> stringResource(R.string.settings_disabled)
            staged != null -> staged.value ?: stringResource(R.string.settings_will_remove)
            onDisk != null -> onDisk.rawValue
            else -> stringResource(R.string.settings_not_set)
        }
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
    val shortHint = ventoyKeyShortDescription(known.key)
    var optionsExpanded by remember(known.key) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (booleanKey) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = effectiveValue == "1",
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
                    shortHint?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_current, current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = effectiveValue == "1",
                    onCheckedChange = null,
                    enabled = actionsEnabled,
                )
                keyHint?.let {
                    InfoHelpButton(infoTitle = humanTitle, infoText = stringResource(it))
                }
            }
        } else {
            val expandLabel = stringResource(
                if (optionsExpanded) R.string.diagnostics_collapse
                else R.string.diagnostics_expand,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = expandLabel) { optionsExpanded = !optionsExpanded },
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    shortHint?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_current, current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (optionsExpanded) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
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
                }
                IconButton(
                    onClick = { optionsExpanded = !optionsExpanded },
                    modifier = Modifier.size(40.dp),
                ) {
                    GlyphIcon(
                        glyph = if (optionsExpanded) Glyph.ChevronUp else Glyph.ChevronDown,
                        size = 18.dp,
                    )
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
            ) {
                Text(
                    stringResource(
                        if (booleanKey) R.string.settings_reset_default
                        else R.string.settings_remove,
                    ),
                )
            }
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

private val BooleanControlKeys = setOf(
    "VTOY_WIN11_BYPASS_CHECK",
    "VTOY_WIN11_BYPASS_NRO",
)

@Composable
private fun controlValueText(key: String, value: String?, removedLabel: String): String =
    if (key in BooleanControlKeys) {
        when (value) {
            null -> stringResource(R.string.settings_default_on)
            "1" -> stringResource(R.string.settings_enabled)
            "0" -> stringResource(R.string.settings_disabled)
            else -> value
        }
    } else {
        value ?: removedLabel
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
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(
                                R.string.settings_staged_row,
                                setting.key,
                                controlValueText(setting.key, setting.value, removeLabel),
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { onDiscard(setting.key) },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = discardLabel },
                        ) {
                            GlyphIcon(Glyph.Close, size = 18.dp)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .heightIn(min = 48.dp),
                )
            }
        }
    }
}


/** One-line preview of the controls read from the selected ventoy.json. */
@Composable
private fun VentoyFileSettingsPreview(entries: List<VentoySetting>) {
    var expanded by remember(entries) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_file_preview_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (entries.size > 1) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (expanded) R.string.settings_file_preview_less
                            else R.string.settings_file_preview_more,
                        ),
                    )
                }
            }
        }
        val visible = if (expanded) entries else entries.take(1)
        visible.forEachIndexed { index, setting ->
            ListItem(
                headlineContent = {
                    Text(
                        text = "${index + 1}. ${setting.key}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        text = controlValueText(setting.key, setting.rawValue, ""),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .heightIn(min = 48.dp),
            )
        }
    }
}

/** Confirms the exact staged control list before the destructive USB write. */
@Composable
private fun VentoyApplyDialog(
    pending: List<VentoyControlSetting>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onWrite: () -> Unit,
) {
    if (pending.isEmpty()) return
    val removeLabel = stringResource(R.string.settings_remove)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_apply_review_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pending.forEach { setting ->
                    Text(
                        text = "${setting.key} = ${controlValueText(setting.key, setting.value, removeLabel)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onWrite, enabled = enabled) {
                Text(stringResource(R.string.settings_apply_review_write))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

/**
 * Shared selected-file row for ventoy.json / unattend.xml / Windows image:
 * one truthful path (or an explicit "not selected") plus optional pick/reset actions.
 */
@Composable
private fun ConfigFileStatusRow(
    pathLabel: String?,
    dimmedPath: Boolean,
    showChanged: Boolean,
    onReset: (() -> Unit)?,
    resetEnabled: Boolean,
    onPick: (() -> Unit)? = null,
    pickEnabled: Boolean = true,
    pickLabel: String? = null,
) {
    val resetLabel = stringResource(R.string.wave4_reset_file)
    val chooseLabel = pickLabel ?: stringResource(R.string.wave4_choose_ellipsis)
    val statusText = if (pathLabel == null) {
        stringResource(R.string.windows_file_none)
    } else {
        stringResource(R.string.windows_file_status, pathLabel)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (pathLabel == null) FontFamily.Default else FontFamily.Monospace,
                ),
                color = if (dimmedPath || pathLabel == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                softWrap = true,
            )
            if (showChanged) {
                Badge(
                    text = stringResource(R.string.wave4_changed_badge),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (onPick != null) {
            FilledTonalIconButton(
                onClick = onPick,
                enabled = pickEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = chooseLabel },
                colors = IconButtonDefaults.filledTonalIconButtonColors(),
            ) {
                GlyphIcon(Glyph.File, size = 20.dp)
            }
        }
        if (onReset != null) {
            FilledTonalIconButton(
                onClick = onReset,
                enabled = resetEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = resetLabel },
                colors = IconButtonDefaults.filledTonalIconButtonColors(),
            ) {
                GlyphIcon(Glyph.Close, size = 20.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OfficialControlKeyPickerDialog(
    state: AppUiState,
    existingKeys: Set<String>,
    onIntent: (UiIntent) -> Unit,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val catalog = state.ventoyControlKeyCatalog
    val defs = catalog?.keys.orEmpty()
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val resources = LocalContext.current.resources
    val listState = rememberLazyListState()
    val selected = defs.firstOrNull { it.key == selectedKey }
    LaunchedEffect(defs) {
        if (selectedKey != null && defs.none { it.key == selectedKey }) {
            selectedKey = null
            value = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        title = { Text(stringResource(R.string.wave4_catalog_keys_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wave4_catalog_source),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    val refreshLabel = stringResource(
                        if (state.ventoyControlKeyCatalogLoading) {
                            R.string.wave4_catalog_refreshing
                        } else {
                            R.string.wave4_catalog_refresh
                        },
                    )
                    FilledTonalIconButton(
                        onClick = { onIntent(UiIntent.RefreshVentoyControlKeyCatalog) },
                        enabled = !state.ventoyControlKeyCatalogLoading,
                        modifier = Modifier.semantics { contentDescription = refreshLabel },
                    ) {
                        GlyphIcon(glyph = Glyph.Refresh, size = 20.dp)
                    }
                }
                when {
                    state.ventoyControlKeyCatalogError != null -> {
                        Text(
                            text = stringResource(
                                R.string.wave4_catalog_error,
                                state.ventoyControlKeyCatalogError!!,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (defs.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.wave4_catalog_kept_previous,
                                    defs.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    catalog == null -> Text(
                        text = stringResource(R.string.wave4_catalog_idle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    defs.isEmpty() -> Text(
                        text = stringResource(R.string.wave4_catalog_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        text = if (catalog.fetchedAtEpochMs == 0L) {
                            stringResource(
                                R.string.wave4_catalog_bundled,
                                defs.size,
                                catalog.ventoyRelease,
                            )
                        } else {
                            stringResource(
                                R.string.wave4_catalog_loaded,
                                defs.size,
                                catalog.ventoyRelease,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = defs,
                        key = { it.key },
                        contentType = { "control-key" },
                    ) { def ->
                        val alreadySet = def.key in existingKeys
                        val selectedRow = selectedKey == def.key
                        PopupChoiceBlock(
                            selected = selectedRow,
                            enabled = state.actionsEnabled && !alreadySet,
                            onClick = {
                                selectedKey = def.key
                                value = ""
                                problem = null
                            },
                        ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .heightIn(min = 48.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = def.key,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (alreadySet) {
                                    Text(
                                        text = stringResource(R.string.wave4_catalog_already_set),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            ventoyKeyShortDescription(def.key)?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selectedRow) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.wave4_catalog_value_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (def.allowed.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            def.allowed.forEach { option ->
                                                FilterChip(
                                                    selected = value == option,
                                                    onClick = {
                                                        value = option
                                                        problem = null
                                                    },
                                                    enabled = state.actionsEnabled,
                                                    label = { Text(option) },
                                                    modifier = Modifier.heightIn(min = 48.dp),
                                                )
                                            }
                                        }
                                    } else {
                                        OutlinedTextField(
                                            value = value,
                                            onValueChange = {
                                                value = it
                                                problem = null
                                            },
                                            label = { Text(stringResource(R.string.settings_value_label)) },
                                            singleLine = true,
                                            enabled = state.actionsEnabled,
                                            keyboardOptions = if (def.kind == VentoyControlKeyKind.INTEGER) {
                                                KeyboardOptions(keyboardType = KeyboardType.Number)
                                            } else {
                                                KeyboardOptions.Default
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                }
                problem?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val def = selected
                    val issue = when {
                        def == null -> resources.getString(R.string.wave4_catalog_select_key)
                        def.key in existingKeys -> resources.getString(
                            R.string.settings_problem_duplicate_key,
                            def.key,
                        )
                        value.isEmpty() -> resources.getString(R.string.wave4_catalog_enter_value)
                        def.allowed.isNotEmpty() && value !in def.allowed ->
                            resources.getString(
                                R.string.settings_problem_value_not_allowed,
                                def.key,
                                def.allowed.joinToString(),
                                value,
                            )
                        else -> VentoyControlCatalog.valueIssue(def.key, value)
                            ?.let { resources.controlIssueText(it) }
                    }
                    if (issue != null) {
                        problem = issue
                    } else if (def != null) {
                        problem = null
                        onAdd(def.key, value)
                    }
                },
                enabled = state.actionsEnabled && selected != null,
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
private fun kindLabel(kind: VentoyControlKeyKind): String =
    when (kind) {
        VentoyControlKeyKind.FLAG -> stringResource(R.string.wave4_key_kind_flag)
        VentoyControlKeyKind.CHOICE -> stringResource(R.string.wave4_key_kind_choice)
        VentoyControlKeyKind.INTEGER -> stringResource(R.string.wave4_key_kind_integer)
        VentoyControlKeyKind.TEXT -> stringResource(R.string.wave4_key_kind_text)
        VentoyControlKeyKind.PATH -> stringResource(R.string.wave4_key_kind_path)
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
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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

private val windowsLanguageChoices = listOf("en-US", "ru-RU", "de-DE", "fr-FR", "es-ES", "ja-JP")
private val windowsLocaleChoices = listOf("en-US", "ru-RU", "de-DE", "fr-FR", "es-ES", "ja-JP")

/** Bundled answer-file profiles; [Custom] exposes the individual fields. */
private enum class WindowsProfile {
    Default,
    Russian,
    English,
    Offline,
    Custom,
}

@Composable
private fun windowsProfileLabel(profile: WindowsProfile): String = stringResource(
    when (profile) {
        WindowsProfile.Default -> R.string.params_profile_default
        WindowsProfile.Russian -> R.string.params_profile_ru
        WindowsProfile.English -> R.string.params_profile_en
        WindowsProfile.Offline -> R.string.params_profile_offline
        WindowsProfile.Custom -> R.string.params_profile_custom
    },
)

/** Parameter editor: every block is opt-in and off by default; off means "not written". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenerateDialog(
    template: String,
    document: AutounattendDocument?,
    selectedImages: List<String>,
    actionsEnabled: Boolean,
    onGenerate: (AutounattendParams, Boolean, String?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = document?.existing
    val existingAccount = existing?.localAccount
    val existingLayout = existing?.diskLayout
    var profile by remember(document) { mutableStateOf(WindowsProfile.Default) }
    var language by remember(document) { mutableStateOf(existing?.languageTag ?: "en-US") }
    var languageCustom by remember(document) { mutableStateOf(existing?.languageTag !in windowsLanguageChoices) }
    var localeOn by remember(document) { mutableStateOf(existing?.localeId != null) }
    var locale by remember(document) { mutableStateOf(existing?.localeId.orEmpty()) }
    var localeCustom by remember(document) { mutableStateOf(existing?.localeId !in windowsLocaleChoices) }
    var xmlName by remember(document, template) { mutableStateOf(template.substringAfterLast('/').ifBlank { "autounattend.xml" }) }
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
    fun selectProfile(next: WindowsProfile) {
        profile = next
        when (next) {
            WindowsProfile.Default -> {
                language = existing?.languageTag ?: "en-US"
                languageCustom = language !in windowsLanguageChoices
                localeOn = existing?.localeId != null
                locale = existing?.localeId.orEmpty()
                localeCustom = locale !in windowsLocaleChoices
                computerOn = existing?.computerName != null
                computer = existing?.computerName.orEmpty()
                accountOn = existingAccount != null
                accountName = existingAccount?.name.orEmpty()
                accountAdmin = existingAccount?.inAdministrators == true
            }
            WindowsProfile.Russian -> {
                language = "ru-RU"
                languageCustom = false
                localeOn = true
                locale = "ru-RU"
                localeCustom = false
            }
            WindowsProfile.English -> {
                language = "en-US"
                languageCustom = false
                localeOn = true
                locale = "en-US"
                localeCustom = false
            }
            WindowsProfile.Offline -> {
                language = "ru-RU"
                languageCustom = false
                localeOn = true
                locale = "ru-RU"
                localeCustom = false
                accountOn = true
                accountAdmin = true
                if (accountName.isBlank()) accountName = "User"
            }
            WindowsProfile.Custom -> Unit
        }
    }

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
    val validXmlName = xmlName.isNotBlank() && xmlName.endsWith(".xml", true) && '/' !in xmlName && ".." !in xmlName
    val canGenerate = actionsEnabled && validXmlName && language.isNotBlank() && layoutValid && accountValid &&
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
                if (template == null) {
                    Text(
                        text = stringResource(R.string.wave4_suggested_xml_path),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = xmlName,
                    onValueChange = { xmlName = it },
                    label = { Text(stringResource(R.string.unattend_xml_name)) },
                    singleLine = true,
                    isError = xmlName.isBlank() || !xmlName.endsWith(".xml", true) || '/' in xmlName || ".." in xmlName,
                    modifier = Modifier.fillMaxWidth(),
                )
                CompactChoice(
                    options = WindowsProfile.entries.map { it to windowsProfileLabel(it) },
                    selected = profile,
                    onSelect = ::selectProfile,
                    enabled = actionsEnabled,
                    label = stringResource(R.string.params_profile),
                )
                if (profile == WindowsProfile.Custom) {
                    val customLabel = stringResource(R.string.unattend_custom_value)
                    CompactChoice(
                        options = windowsLanguageChoices.map { it to it } + (customLabel to customLabel),
                        selected = if (languageCustom) customLabel else language,
                        onSelect = { choice ->
                            languageCustom = choice == customLabel
                            if (!languageCustom) language = choice
                        },
                        enabled = actionsEnabled,
                        label = stringResource(R.string.params_language),
                    )
                    if (languageCustom) {
                        OutlinedTextField(
                            value = language,
                            onValueChange = { language = it },
                            label = { Text(stringResource(R.string.params_language)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.params_locale),
                        checked = localeOn,
                        onCheckedChange = { localeOn = it },
                        enabled = actionsEnabled,
                    )
                    if (localeOn) {
                        CompactChoice(
                            options = windowsLocaleChoices.map { it to it } + (customLabel to customLabel),
                            selected = if (localeCustom) customLabel else locale,
                            onSelect = { choice ->
                                localeCustom = choice == customLabel
                                if (!localeCustom) locale = choice
                            },
                            enabled = actionsEnabled,
                            label = stringResource(R.string.params_locale_value),
                        )
                        if (localeCustom) {
                            OutlinedTextField(
                                value = locale,
                                onValueChange = { locale = it },
                                label = { Text(stringResource(R.string.params_locale_value)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (localeOn && locale.isNotBlank()) "$language / $locale" else language,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiskPartitionRole.entries.forEach { role ->
                            FilledTonalButton(
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
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
            TextButton(
                onClick = {
                    if (xmlName.isBlank() || !xmlName.endsWith(".xml", true) || '/' in xmlName || ".." in xmlName) return@TextButton
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
                        xmlName,
                    )
                },
                enabled = canGenerate,
                colors = if (markers.isNotEmpty()) {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ButtonDefaults.textButtonColors()
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
    val removeLabel = stringResource(R.string.params_remove_partition)
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
            IconButton(
                onClick = onMoveUp,
                enabled = actionsEnabled && canMoveUp,
                modifier = Modifier.semantics { contentDescription = moveUpLabel },
            ) { GlyphIcon(Glyph.ChevronUp, size = 20.dp) }
            IconButton(
                onClick = onMoveDown,
                enabled = actionsEnabled && canMoveDown,
                modifier = Modifier.semantics { contentDescription = moveDownLabel },
            ) { GlyphIcon(Glyph.ChevronDown, size = 20.dp) }
            IconButton(
                onClick = onRemove,
                enabled = actionsEnabled,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.semantics {
                    contentDescription = removeLabel
                },
            ) { GlyphIcon(Glyph.Close, size = 20.dp) }
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
    CompactChoice(
        options = DiskPartitionRole.entries.map { it to stringResource(roleText(it)) },
        selected = role,
        onSelect = onRoleChange,
        modifier = modifier,
        enabled = actionsEnabled,
    )
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
