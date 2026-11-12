package usb.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R
import usb.ventoy.VentoySetup
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
import usb.ventoy.diskLayoutTotalMb
import usb.ventoy.LocalAccount
import usb.ventoy.SettingStatus
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyControlCatalog
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoyKnownControl
import usb.ventoy.VentoySetting
import usb.ventoy.XmlPath

/**
 * Autounattend subsection of the Ventoy tab: the answer file that already exists, the
 * parameters used to (re)generate it, which images are bound to it, and the global
 * `control` values of `ventoy.json`.
 *
 * Nothing here invents capability: the template is an existing file, generation is opt-in per
 * block, unknown elements are reported as preserved, and the target-PC layout is acknowledged
 * in its own dialog that never shows this stick's identity.
 */
@Composable
internal fun AutounattendSection(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    val template = state.windowsTemplatePath
    val document = (state.autounattendState as? AutounattendState.Parsed)?.document
    var pickerOpen by remember { mutableStateOf(false) }
    var generateOpen by remember { mutableStateOf(false) }

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
                infoText = stringResource(R.string.unattend_help_text),
            )
        },
    ) {
        // The template row is always present: "not selected yet" is a real answer, and a
        // selected path is shown as resolved even before the file has been read. The path
        // wraps instead of using InfoRow, which would ellipsize it to one line.
        Text(
            text = stringResource(R.string.unattend_template_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = template?.let(VentoySetup::resolveXmlPath)
                ?: stringResource(R.string.selection_not_selected),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (template == null) {
            EmptyHint(stringResource(R.string.unattend_no_template))
            Button(
                onClick = { pickerOpen = true },
                enabled = state.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.unattend_choose_template))
            }
        } else {
            when (val file = state.autounattendState) {
                AutounattendState.Missing ->
                    EmptyHint(stringResource(R.string.unattend_missing))

                is AutounattendState.Unreadable -> Text(
                    text = stringResource(unreadableText(file.problem)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                is AutounattendState.Parsed -> {
                    val doc = file.document
                    InfoRow(
                        stringResource(R.string.unattend_bound_images),
                        doc.boundImages.size.toString(),
                    )
                    if (doc.unknownElements.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.unattend_unknown_elements,
                                doc.unknownElements.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        doc.unknownElements.take(6).forEach { element ->
                            Text(
                                text = element.path ?: element.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stringResource(R.string.unattend_unknown_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { pickerOpen = true },
                    enabled = state.actionsEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Text(stringResource(R.string.unattend_choose_template))
                }
                Button(
                    onClick = { generateOpen = true },
                    enabled = state.actionsEnabled &&
                        state.autounattendState !is AutounattendState.Unreadable &&
                        document?.layoutProblems.isNullOrEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Text(stringResource(R.string.unattend_generate))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onIntent(UiIntent.LoadAutounattend(template)) },
                    enabled = state.actionsEnabled,
                ) {
                    Text(stringResource(R.string.unattend_refresh))
                }
                TextButton(onClick = { onIntent(UiIntent.ClearWindowsTemplate) }) {
                    Text(stringResource(R.string.unattend_clear_template))
                }
            }
        }
    }

    if (template != null) {
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

    GlobalSettingsCard(state, onIntent)

    state.diskLayoutConfirmation?.let { confirmation ->
        LayoutConfirmDialog(
            confirmation = confirmation,
            actionsEnabled = state.actionsEnabled,
            onConfirm = { onIntent(UiIntent.ConfirmDiskLayout(it)) },
            onDismiss = { onIntent(UiIntent.DismissDiskLayout) },
        )
    }

    if (pickerOpen) {
        TemplatePickerDialog(
            candidates = state.windowsIsoCandidates?.map { it.path },
            incomplete = state.windowsIsoCandidatesError != null,
            onRefresh = { onIntent(UiIntent.LoadWindowsIsoCandidates) },
            onPick = {
                onIntent(UiIntent.SelectWindowsTemplate(it))
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (generateOpen && template != null) {
        GenerateDialog(
            template = template,
            document = document,
            selectedImages = state.windowsSelectedImages,
            actionsEnabled = state.actionsEnabled,
            onGenerate = { params, allowDestructive, sha ->
                onIntent(
                    UiIntent.RequestGenerateAutounattend(
                        xmlName = template,
                        params = params,
                        imagePaths = state.windowsSelectedImages,
                        overwriteXml = document != null,
                        allowDestructiveTemplate = allowDestructive,
                        expectedTemplateSha256 = sha,
                    ),
                )
                generateOpen = false
            },
            onDismiss = { generateOpen = false },
        )
    }
}

/**
 * Global `control` entries. Missing, empty, and unreadable stay distinct; known keys offer
 * explicit choices, other keys stay editable as key/value, and every edit is staged until
 * Apply now or the confirmed install/update writes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalSettingsCard(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_title),
        trailing = {
            InfoHelpButton(
                infoTitle = stringResource(R.string.settings_help_title),
                infoText = stringResource(R.string.settings_help_text),
            )
        },
    ) {
        InfoRow(
            stringResource(R.string.settings_file_label),
            XmlPath.CONFIG_PATH,
        )
        val settings = state.ventoyGlobalSettings
        val entries = (settings as? VentoyGlobalSettings.Present)?.entries.orEmpty()
        when (settings) {
            null -> {
                // null = not read yet, or nothing is attached to read from. No in-flight flag
                // exists for this catalog, so never claim that a read is in progress.
                EmptyHint(
                    if (state.presence == DevicePresence.Ready) {
                        stringResource(R.string.settings_not_loaded)
                    } else {
                        UiReducer.presenceMessage(state.presence).localized()
                    },
                )
                TextButton(onClick = { onIntent(UiIntent.LoadVentoyGlobalSettings) }) {
                    Text(stringResource(R.string.settings_retry))
                }
                StagedControls(state = state, onIntent = onIntent)
                return@SectionCard
            }
            VentoyGlobalSettings.Missing -> EmptyHint(stringResource(R.string.settings_missing))
            VentoyGlobalSettings.Empty -> EmptyHint(stringResource(R.string.settings_empty))
            is VentoyGlobalSettings.ReadError -> {
                Text(
                    text = stringResource(
                        if (settings.damagedLayout) {
                            R.string.settings_damaged_layout
                        } else {
                            R.string.settings_read_error
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
                TextButton(onClick = { onIntent(UiIntent.LoadVentoyGlobalSettings) }) {
                    Text(stringResource(R.string.settings_retry))
                }
                // Staged choices stay actionable: an install can still write them, and a
                // failed settings retry must remain reachable without re-reading.
                StagedControls(state = state, onIntent = onIntent)
                return@SectionCard
            }
            is VentoyGlobalSettings.Present -> Unit
        }

        Text(
            text = stringResource(R.string.settings_known_header),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        VentoyControlCatalog.known.forEach { known ->
            KnownControlRow(
                known = known,
                entries = entries,
                pending = state.pendingVentoyControls,
                actionsEnabled = state.actionsEnabled,
                onIntent = onIntent,
            )
        }

        val extra = entries.filter { VentoyControlCatalog.known(it.key) == null }
        if (extra.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_extra_header),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            extra.forEach { entry ->
                ExtraControlRow(
                    entry = entry,
                    pending = state.pendingVentoyControls.firstOrNull { it.key == entry.key },
                    actionsEnabled = state.actionsEnabled,
                    onIntent = onIntent,
                )
            }
        }

        AddControlRow(actionsEnabled = state.actionsEnabled, onIntent = onIntent)
        StagedControls(state = state, onIntent = onIntent)
        Text(
            text = stringResource(R.string.settings_applies_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Retry must be reachable from every loaded state, not just the unread one.
        TextButton(
            onClick = { onIntent(UiIntent.LoadVentoyGlobalSettings) },
            enabled = state.actionsEnabled,
        ) {
            Text(stringResource(R.string.unattend_refresh))
        }
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
    val current =
        when {
            staged != null -> staged.value ?: stringResource(R.string.settings_will_remove)
            onDisk != null -> onDisk.rawValue
            else -> stringResource(R.string.settings_not_set)
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = knownControlTitle(known.key),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.settings_current, current),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only keys this app understands get an explanation; unknown keys stay raw.
        val keyHint = when (known.key) {
            "VTOY_WIN11_BYPASS_CHECK" -> R.string.settings_win11_hint
            "VTOY_WIN11_BYPASS_NRO" -> R.string.settings_win11_nro_hint
            else -> null
        }
        keyHint?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val problem = (onDisk?.status as? SettingStatus.Known)?.takeIf { !it.valid }
        problem?.let {
            Text(
                text = stringResource(R.string.settings_invalid, it.expected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            known.allowed.forEach { value ->
                TextButton(
                    onClick = { onIntent(UiIntent.StageVentoyControl(known.key, value)) },
                    enabled = actionsEnabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(knownOptionLabel(known.key, value)) }
            }
            TextButton(
                onClick = { onIntent(UiIntent.StageVentoyControl(known.key, null)) },
                enabled = actionsEnabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) { Text(stringResource(R.string.settings_remove)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ExtraControlRow(
    entry: VentoySetting,
    pending: VentoyControlSetting?,
    actionsEnabled: Boolean,
    onIntent: (UiIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.key,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.settings_raw, entry.rawValue),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry.status == SettingStatus.Unreadable) {
            Text(
                text = stringResource(R.string.settings_unreadable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (pending != null) {
            Text(
                text = stringResource(
                    R.string.settings_staged_row,
                    pending.key,
                    pending.value ?: stringResource(R.string.settings_remove),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(
            onClick = { onIntent(UiIntent.StageVentoyControl(entry.key, null)) },
            enabled = actionsEnabled,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) { Text(stringResource(R.string.settings_remove)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AddControlRow(actionsEnabled: Boolean, onIntent: (UiIntent) -> Unit) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_add_header),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    problem = null
                },
                label = { Text(stringResource(R.string.settings_key_label)) },
                singleLine = true,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.weight(1f),
            )
        }
        problem?.let {
            Text(
                text = stringResource(R.string.settings_validation, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = {
                val trimmed = key.trim()
                val validation =
                    VentoyControlCatalog.keyProblem(trimmed)
                        ?: VentoyControlCatalog.valueProblem(trimmed, value)
                if (validation != null) {
                    problem = validation
                } else {
                    onIntent(UiIntent.StageVentoyControl(trimmed, value))
                    key = ""
                    value = ""
                    problem = null
                }
            },
            enabled = actionsEnabled && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_add)) }
    }
}

@Composable
private fun StagedControls(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    val pending = state.pendingVentoyControls
    if (pending.isEmpty()) return
    val operation = state.operation
    val installPartial =
        operation?.state == OperationState.Partial &&
            operation.title in
            setOf(
                OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                OperationTitle.Destructive(DestructiveAction.VENTOY_UPDATE),
                OperationTitle.Destructive(DestructiveAction.VENTOY_DOWNLOAD_INSTALL),
            )
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_staged),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        pending.forEach { setting ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_staged_row,
                        setting.key,
                        setting.value ?: stringResource(R.string.settings_remove),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onIntent(UiIntent.DiscardStagedVentoyControl(setting.key)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.settings_discard)) }
            }
        }
        Text(
            text = stringResource(R.string.settings_staged_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installPartial) {
            Text(
                text = stringResource(R.string.settings_retry_pending),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { onIntent(UiIntent.RequestApplyVentoyControls) },
            enabled = state.actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (installPartial) {
                        R.string.settings_retry_pending_action
                    } else {
                        R.string.settings_apply_now
                    },
                ),
            )
        }
    }
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

@Composable
private fun TemplatePickerDialog(
    candidates: List<String>?,
    incomplete: Boolean,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unattend_pick_template)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (incomplete) {
                    Text(
                        text = stringResource(R.string.raw_stick_incomplete),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when {
                    candidates == null -> Text(
                        text = stringResource(R.string.raw_stick_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    candidates.isEmpty() -> Text(
                        text = stringResource(R.string.raw_stick_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        candidates.take(40).forEach { path ->
                            TextButton(
                                onClick = { onPick(path) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (candidates != null && candidates.size > 40) {
                    Text(
                        text = stringResource(R.string.list_first_40),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.unattend_refresh)) }
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
    var language by remember { mutableStateOf("en-US") }
    var localeOn by remember { mutableStateOf(false) }
    var locale by remember { mutableStateOf("") }
    var computerOn by remember { mutableStateOf(false) }
    var computer by remember { mutableStateOf("") }
    var accountOn by remember { mutableStateOf(false) }
    var accountName by remember { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val showPasswordLabel = stringResource(
        if (passwordVisible) R.string.params_account_password_hide
        else R.string.params_account_password_show,
    )
    var accountAdmin by remember { mutableStateOf(true) }
    var layoutOn by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    var diskId by remember { mutableStateOf("0") }
    var declaredDiskSize by remember { mutableStateOf("") }
    val partitions = remember { mutableStateListOf<LayoutPartitionDraft>() }
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
    val layoutProblems =
        if (layoutOn) {
            Autounattend.validate(
                AutounattendParams(diskLayout = draftLayout),
                requireDeclaredDiskSize = true,
            ).problems
        } else {
            emptyList()
        }
    val layoutValid = !layoutOn || layoutProblems.isEmpty()
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
                    onGenerate(
                        AutounattendParams(
                            languageTag = language.trim().ifBlank { null },
                            localeId = if (localeOn) locale.trim().ifBlank { null } else null,
                            computerName = if (computerOn) computer.trim().ifBlank { null } else null,
                            localAccount = if (accountOn) {
                                LocalAccount(
                                    name = accountName.trim().ifBlank { null },
                                    password = accountPassword.ifEmpty { null },
                                    inAdministrators = accountAdmin,
                                )
                            } else {
                                null
                            },
                            diskLayout = if (layoutOn) {
                                DiskLayout(
                                    writeLayout = true,
                                    diskId = diskId.toLongOrNull(),
                                    wipe = wipe,
                                    partitions = partitions.map { it.toPartition() },
                                    declaredSizeMb = declaredDiskSize.toLongOrNull(),
                                )
                            } else {
                                null
                            },
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
