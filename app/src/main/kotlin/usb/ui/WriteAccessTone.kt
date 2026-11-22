package usb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * One RO/RW tone for every chip in the app.
 *
 * The palettes collapse `primaryContainer`, `secondaryContainer` and `tertiaryContainer`
 * to the same neutral, so a container tint cannot separate the two states. RW keeps the
 * brand `primary`; RO uses an amber that reads as "restricted" in both themes without
 * competing with `error`, which stays reserved for failures and destructive actions.
 * The chip itself is always the neutral `surfaceContainerHighest` with a hairline border;
 * only this tone differs, so no extra words are needed next to `RO`/`RW`.
 */
@Composable
internal fun writeAccessTone(readWrite: Boolean): Color =
    if (readWrite) {
        MaterialTheme.colorScheme.primary
    } else if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFFFFC46B)
    } else {
        Color(0xFF8A5300)
    }
