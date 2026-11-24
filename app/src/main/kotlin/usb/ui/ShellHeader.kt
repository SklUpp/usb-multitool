package usb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import usb.multitool.R
import usb.partition.TableScheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellHeader(
    device: DeviceIdentity?,
    presence: DevicePresence,
    partitionScheme: TableScheme?,
    operationLocked: Boolean,
    ejectInFlight: Boolean,
    ejected: Boolean,
    journalOpen: Boolean,
    logButtonFocus: FocusRequester,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onRequestEject: () -> Unit,
    onRequestEjectRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val serialLabel = device?.serialNumber?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.header_serial, it) }
    val subtitle = when (presence) {
        DevicePresence.Ready -> buildString {
            append(device?.capacityLabel ?: "USB")
            partitionScheme?.name?.let { append(", ").append(it) }
            serialLabel?.let { append(" · ").append(it) }
        }
        DevicePresence.PermissionRequired -> stringResource(R.string.header_permission)
        DevicePresence.Busy -> stringResource(R.string.header_busy)
        DevicePresence.Disconnected -> stringResource(R.string.header_disconnected)
        DevicePresence.Absent -> stringResource(R.string.header_absent)
    }
    var moreOpen by remember { mutableStateOf(false) }
    LaunchedEffect(operationLocked) {
        if (operationLocked) moreOpen = false
    }
    val logLabel = stringResource(R.string.log_screen_title)
    val moreLabel = stringResource(R.string.more_actions)
    TopAppBar(
        modifier = modifier,
        expandedHeight = adaptiveTopAppBarHeight(),
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
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shadowElevation = 3.dp,
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_title_screen)) },
                            onClick = { moreOpen = false; onOpenSettings() },
                        )
                        val ejectEnabled =
                            !operationLocked && presence == DevicePresence.Ready &&
                                !ejectInFlight && !ejected
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_eject)) },
                            enabled = ejectEnabled,
                            onClick = { moreOpen = false; onRequestEject() },
                        )
                        if (ejected) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rescan)) },
                                enabled = !operationLocked && !ejectInFlight,
                                onClick = {
                                    moreOpen = false
                                    onRequestEjectRescan()
                                },
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        // Scaffold's safeDrawing inset already places the content below the status bar;
        // the app bar's own status-bar inset would add a second gap above the title.
        // Keep the M3 default 64 dp height so the two-line title is not clipped at large
        // fontScale; status block outer pad stays 4+4 dp so idle stack has no dead band.
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}
