package usb.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import usb.multitool.R

/** One source chooser for all config/image files. Caller closes state before callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigSourceSheet(
    title: String,
    onStick: () -> Unit,
    onPhone: () -> Unit,
    onDismiss: () -> Unit,
    defaultPath: String? = null,
    onDefault: (() -> Unit)? = null,
    stickEnabled: Boolean = true,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (defaultPath != null && onDefault != null) {
            PopupChoiceBlock(enabled = stickEnabled, onClick = onDefault) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.config_use_default)) },
                    supportingContent = { Text(defaultPath) },
                    leadingContent = {
                        Icon(
                            painterResource(R.drawable.ic_pick_file_usb_duotone),
                            contentDescription = null,
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
            }
        }
        PopupChoiceBlock(enabled = stickEnabled, onClick = onStick) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.raw_choose_stick)) },
                supportingContent = if (stickEnabled) null else {
                    { Text(stringResource(R.string.ventoy_pick_stick_unavailable)) }
                },
                leadingContent = {
                    Icon(painterResource(R.drawable.ic_pick_file_usb_duotone), contentDescription = null)
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
        PopupChoiceBlock(onClick = onPhone) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.raw_choose_phone)) },
                leadingContent = {
                    Icon(painterResource(R.drawable.ic_pick_file_phone_duotone), contentDescription = null)
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}
