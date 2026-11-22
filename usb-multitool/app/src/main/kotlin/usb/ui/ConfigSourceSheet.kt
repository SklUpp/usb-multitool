package usb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.Role
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
        ListItem(
            headlineContent = { Text(stringResource(R.string.raw_choose_stick)) },
            supportingContent = if (stickEnabled) null else {
                { Text(stringResource(R.string.ventoy_pick_stick_unavailable)) }
            },
            leadingContent = {
                Icon(painterResource(R.drawable.ic_pick_file_usb_duotone), contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth().clickable(
                role = Role.Button,
                enabled = stickEnabled,
                onClick = onStick,
            ),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.raw_choose_phone)) },
            leadingContent = {
                Icon(painterResource(R.drawable.ic_pick_file_phone_duotone), contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onPhone),
        )
    }
}
