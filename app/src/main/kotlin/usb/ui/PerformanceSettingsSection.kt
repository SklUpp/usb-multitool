package usb.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import usb.device.TransferTuning
import usb.diagnostics.PerformancePreferences
import usb.multitool.R
/**
 * Diagnostics performance knobs. Values live in SharedPreferences and are pushed into
 * [TransferTuning] on release; every operation snapshots them when it starts, so moving
 * a slider never resizes an in-flight transfer.
 */
@Composable
internal fun PerformanceSettingsSection(enabled: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember { PerformancePreferences.prefs(context) }
    val haptic = LocalHapticFeedback.current
    var scsiChunkKib by remember {
        mutableStateOf(
            prefs.getInt(
                PerformancePreferences.KEY_SCSI_CHUNK_KIB,
                TransferTuning.DEFAULT_SCSI_CHUNK_KIB,
            ).coerceIn(TransferTuning.MIN_SCSI_CHUNK_KIB, TransferTuning.MAX_SCSI_CHUNK_KIB),
        )
    }
    var jniCapKib by remember {
        mutableStateOf(
            prefs.getInt(
                PerformancePreferences.KEY_JNI_CAP_KIB,
                TransferTuning.DEFAULT_JNI_CAP_KIB,
            ).coerceIn(TransferTuning.MIN_JNI_CAP_KIB, TransferTuning.MAX_JNI_CAP_KIB),
        )
    }
    var rescueCheckpointMib by remember {
        mutableStateOf(
            prefs.getInt(
                PerformancePreferences.KEY_RESCUE_CHECKPOINT_MIB,
                TransferTuning.DEFAULT_RESCUE_CHECKPOINT_MIB,
            ).coerceIn(
                TransferTuning.MIN_RESCUE_CHECKPOINT_MIB,
                TransferTuning.MAX_RESCUE_CHECKPOINT_MIB,
            ),
        )
    }

    val resetDescription = stringResource(R.string.diagnostics_reset_default)
    fun saveValues() = PerformancePreferences.save(
        context,
        scsiChunkKib = scsiChunkKib,
        jniCapKib = jniCapKib,
        rescueCheckpointMib = rescueCheckpointMib,
    )
    SectionCard(
        title = stringResource(R.string.diagnostics_perf_title),
        subtitle = stringResource(R.string.diagnostics_perf_subtitle),
        modifier = Modifier.animateContentSize(),
    ) {
        PerfSlider(
            title = stringResource(R.string.diagnostics_perf_scsi_title),
            hint = stringResource(R.string.diagnostics_perf_scsi_help_body),
            value = scsiChunkKib,
            range = TransferTuning.MIN_SCSI_CHUNK_KIB..TransferTuning.MAX_SCSI_CHUNK_KIB,
            step = TransferTuning.SCSI_CHUNK_STEP_KIB,
            valueLabel = stringResource(R.string.diagnostics_perf_value_kib, scsiChunkKib),
            resetDescription = resetDescription,
            enabled = enabled,
            onValueChange = { scsiChunkKib = it },
            onValueChangeFinished = { saveValues() },
            onReset = {
                if (scsiChunkKib != TransferTuning.DEFAULT_SCSI_CHUNK_KIB) {
                    scsiChunkKib = TransferTuning.DEFAULT_SCSI_CHUNK_KIB
                    saveValues()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
        )
        PerfSlider(
            title = stringResource(R.string.diagnostics_perf_jni_title),
            hint = stringResource(R.string.diagnostics_perf_jni_help_body),
            value = jniCapKib,
            range = TransferTuning.MIN_JNI_CAP_KIB..TransferTuning.MAX_JNI_CAP_KIB,
            step = TransferTuning.JNI_CAP_STEP_KIB,
            valueLabel = stringResource(R.string.diagnostics_perf_value_kib, jniCapKib),
            resetDescription = resetDescription,
            enabled = enabled,
            onValueChange = { jniCapKib = it },
            onValueChangeFinished = { saveValues() },
            onReset = {
                if (jniCapKib != TransferTuning.DEFAULT_JNI_CAP_KIB) {
                    jniCapKib = TransferTuning.DEFAULT_JNI_CAP_KIB
                    saveValues()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
        )
        PerfSlider(
            title = stringResource(R.string.diagnostics_perf_rescue_title),
            hint = stringResource(R.string.diagnostics_perf_rescue_help_body),
            value = rescueCheckpointMib,
            range =
                TransferTuning.MIN_RESCUE_CHECKPOINT_MIB..TransferTuning.MAX_RESCUE_CHECKPOINT_MIB,
            step = TransferTuning.RESCUE_CHECKPOINT_STEP_MIB,
            valueLabel = stringResource(R.string.diagnostics_perf_value_mib, rescueCheckpointMib),
            resetDescription = resetDescription,
            enabled = enabled,
            onValueChange = { rescueCheckpointMib = it },
            onValueChangeFinished = { saveValues() },
            onReset = {
                if (rescueCheckpointMib != TransferTuning.DEFAULT_RESCUE_CHECKPOINT_MIB) {
                    rescueCheckpointMib = TransferTuning.DEFAULT_RESCUE_CHECKPOINT_MIB
                    saveValues()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
        )
    }
}

@Composable
private fun PerfSlider(
    title: String,
    hint: String,
    value: Int,
    range: IntRange,
    step: Int,
    valueLabel: String,
    resetDescription: String,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var lastHapticValue by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        lastHapticValue = value
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            InfoHelpButton(infoTitle = title, infoText = hint)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                // Value Text carries "$title: $valueLabel"; title stays visual-only.
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { },
            )
            // Current value stays on the label row so the slider never floats alone.
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    contentDescription = "$title: $valueLabel"
                },
            )
            IconButton(
                onClick = onReset,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = resetDescription },
            ) {
                GlyphIcon(Glyph.Refresh, size = 20.dp)
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val snapped = (it.toInt() / step) * step
                if (snapped != lastHapticValue) {
                    lastHapticValue = snapped
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onValueChange(snapped)
            },
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step) - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
