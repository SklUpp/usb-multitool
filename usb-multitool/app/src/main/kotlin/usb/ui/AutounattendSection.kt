package usb.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R
import usb.ventoy.VentoySetup
import usb.ventoy.AutounattendDocument
import usb.ventoy.AutounattendParams
import usb.ventoy.AutounattendProblem
import usb.ventoy.AutounattendState
import usb.ventoy.BindProblem
import usb.ventoy.DiskLayout
import usb.ventoy.DiskPartitionRole
import usb.ventoy.LayoutPartition
import usb.ventoy.LocalAccount
import usb.ventoy.SettingStatus
import usb.ventoy.UnreadableProblem

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

    LaunchedEffect(state.device?.identity) {
        onIntent(UiIntent.LoadVentoyGlobalSettings)
        onIntent(UiIntent.LoadVentoyFolders)
        // The template picker lists real files on the stick, so the list must exist before
        // any file can be chosen.
        onIntent(UiIntent.LoadWindowsIsoCandidates)
    }
    LaunchedEffect(state.device?.identity, template) {
        // One intent: the reducer asks for the document and the image list under a single
        // generation, so neither result can be discarded as stale.
        if (template != null) onIntent(UiIntent.LoadAutounattend(template))
    }

    SectionCard(
        title = stringResource(R.string.unattend_title),
        subtitle = stringResource(R.string.unattend_subtitle),
    ) {
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
            InfoRow(
                stringResource(R.string.unattend_template_label),
                VentoySetup.resolveXmlPath(template),
            )
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
                                text = stringResource(problemText(problem)),
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
            TextButton(onClick = { onIntent(UiIntent.ClearWindowsTemplate) }) {
                Text(stringResource(R.string.unattend_clear_template))
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
                    val selected = state.windowsSelectedImages.any { it.path == image.imagePath }
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
                val selectedPaths = state.windowsSelectedImages.map { it.path }
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
            selectedImages = state.windowsSelectedImages.map { it.path },
            actionsEnabled = state.actionsEnabled,
            onGenerate = { params, allowDestructive, sha ->
                onIntent(
                    UiIntent.RequestGenerateAutounattend(
                        xmlName = template,
                        params = params,
                        imagePaths = state.windowsSelectedImages.map { it.path },
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

/** Global `control` entries: raw value verbatim, no normalisation, unknown keys read-only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalSettingsCard(state: AppUiState, onIntent: (UiIntent) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
    ) {
        val entries = state.ventoyGlobalSettings
        if (entries == null) {
            EmptyHint(stringResource(R.string.settings_unreadable))
            TextButton(onClick = { onIntent(UiIntent.LoadVentoyGlobalSettings) }) {
                Text(stringResource(R.string.unattend_refresh))
            }
            return@SectionCard
        }
        if (entries.isEmpty()) {
            EmptyHint(stringResource(R.string.settings_empty))
        }
        entries.forEach { setting ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = setting.key,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_raw, setting.rawValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val status = setting.status) {
                    is SettingStatus.Known -> {
                        Text(
                            text = if (status.valid) {
                                stringResource(R.string.settings_valid)
                            } else {
                                stringResource(R.string.settings_invalid, status.expected)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status.valid) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = {
                                    onIntent(
                                        UiIntent.RequestSetVentoyGlobalSetting(setting.key, "1"),
                                    )
                                },
                                enabled = state.actionsEnabled,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text(stringResource(R.string.settings_set_one)) }
                            TextButton(
                                onClick = {
                                    onIntent(
                                        UiIntent.RequestSetVentoyGlobalSetting(setting.key, "0"),
                                    )
                                },
                                enabled = state.actionsEnabled,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text(stringResource(R.string.settings_set_zero)) }
                            TextButton(
                                onClick = {
                                    onIntent(
                                        UiIntent.RequestSetVentoyGlobalSetting(setting.key, null),
                                    )
                                },
                                enabled = state.actionsEnabled,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text(stringResource(R.string.settings_remove)) }
                        }
                    }
                    SettingStatus.UnknownKey -> Text(
                        text = stringResource(R.string.settings_unknown_key),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingStatus.Unreadable -> Text(
                        text = stringResource(R.string.settings_unreadable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
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
                Text(
                    text = stringResource(R.string.layout_partitions, confirmation.partitionCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (confirmation.writeLayout) {
                    Text(
                        text = stringResource(R.string.layout_wipe),
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
    var accountAdmin by remember { mutableStateOf(true) }
    var layoutOn by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    var diskId by remember { mutableStateOf("0") }
    val partitions = remember { mutableStateListOf<LayoutPartition>() }
    val markers = document?.destructiveMarkers.orEmpty()
    var destructiveAck by remember(document) { mutableStateOf(markers.isEmpty()) }

    val layoutValid = !layoutOn || partitions.isNotEmpty()
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
                    SwitchRow(
                        label = stringResource(R.string.params_layout_wipe),
                        checked = wipe,
                        onCheckedChange = { wipe = it },
                        enabled = actionsEnabled,
                    )
                    partitions.forEachIndexed { index, partition ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.params_partition_size,
                                    partition.sizeMb,
                                    stringResource(roleText(partition.role)),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { partitions.removeAt(index) },
                                enabled = actionsEnabled,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) { Text(stringResource(R.string.params_remove_partition)) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiskPartitionRole.entries.forEach { role ->
                            TextButton(
                                onClick = {
                                    partitions.add(
                                        LayoutPartition(
                                            sizeMb = defaultSizeMb(role),
                                            role = role,
                                        ),
                                    )
                                },
                                enabled = actionsEnabled,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) { Text(stringResource(roleText(role))) }
                        }
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
                                    partitions = partitions.toList(),
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

internal fun unreadableText(problem: UnreadableProblem): Int = when (problem) {
    UnreadableProblem.NotUnattendRoot -> R.string.unattend_not_unattend
    UnreadableProblem.MalformedXml -> R.string.unattend_malformed
    UnreadableProblem.TooLarge -> R.string.unattend_too_large
    UnreadableProblem.IoFailure -> R.string.unattend_io_failure
    UnreadableProblem.Empty -> R.string.unattend_empty_file
}

private fun problemText(problem: AutounattendProblem): Int = when (problem) {
    AutounattendProblem.BadLanguageTag -> R.string.unattend_problem_language
    AutounattendProblem.BadComputerName -> R.string.unattend_problem_computer
    AutounattendProblem.BadAccountName -> R.string.unattend_problem_account
    AutounattendProblem.LayoutOverlaps -> R.string.unattend_problem_layout_overlap
    AutounattendProblem.LayoutTooLarge -> R.string.unattend_problem_layout_size
    AutounattendProblem.LayoutIncomplete -> R.string.unattend_problem_layout_incomplete
    AutounattendProblem.LayoutImageInstallNotSupported -> R.string.unattend_problem_image_install
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
