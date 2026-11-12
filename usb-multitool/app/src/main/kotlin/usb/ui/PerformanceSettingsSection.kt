package usb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import usb.device.TransferTuning
import usb.diagnostics.PerformancePreferences
import usb.multitool.R

/**
 * Diagnostics performance knobs. Values live in SharedPreferences and are pushed into
 * [TransferTuning] on release; every operation snapshots them when it starts, so moving
 * a slider never resizes an in-flight transfer.
 */
@Composable
internal fun PerformanceSettingsSection() {
    val context = LocalContext.current
    val prefs = remember { PerformancePreferences.prefs(context) }
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

    SectionCard(
        title = stringResource(R.string.diagnostics_perf_title),
        subtitle = stringResource(R.string.diagnostics_perf_subtitle),
    ) {
        PerfSlider(
            title = stringResource(R.string.diagnostics_perf_scsi_title),
            hint = stringResource(R.string.diagnostics_perf_scsi_hint),
            value = scsiChunkKib,
            range = TransferTuning.MIN_SCSI_CHUNK_KIB..TransferTuning.MAX_SCSI_CHUNK_KIB,
            step = TransferTuning.SCSI_CHUNK_STEP_KIB,
            valueLabel = stringResource(R.string.diagnostics_perf_value_kib, scsiChunkKib),
            onValueChange = { scsiChunkKib = it },
            onValueChangeFinished = {
                PerformancePreferences.save(
                    context,
                    scsiChunkKib = scsiChunkKib,
                    jniCapKib = jniCapKib,
                    rescueCheckpointMib = rescueCheckpointMib,
                )
            },
        )
        PerfSlider(
            title = stringResource(R.string.diagnostics_perf_jni_title),
            hint = stringResource(R.string.diagnostics_perf_jni_hint),
            value = jniCapKib,
            range = TransferTuning.MIN_JNI_CAP_KIB..TransferTuning.MAX_JNI_CAP_KIB,
            step = TransferTuning.JNI_CAP_STEP_KIB,
            valueLabel = stringResource(R.string.diagnostics_perf_value_kib, jniCapKib),
            onValueChange = { jniCapKib = it },
            onValueChangeFinished = {
                PerformancePreferences.save(
                    context,
                    scsiChunkKib = scsiChunkKib,
                    jniCapKib = jniCapKib,
                    rescueCheckpointMib = rescueCheckpointMib,
                )
            },
        )
        PerfSlider(
            title = stringResource(R.string.diagnostics_perf_rescue_title),
            hint = stringResource(R.string.diagnostics_perf_rescue_hint),
            value = rescueCheckpointMib,
            range =
                TransferTuning.MIN_RESCUE_CHECKPOINT_MIB..TransferTuning.MAX_RESCUE_CHECKPOINT_MIB,
            step = TransferTuning.RESCUE_CHECKPOINT_STEP_MIB,
            valueLabel = stringResource(R.string.diagnostics_perf_value_mib, rescueCheckpointMib),
            onValueChange = { rescueCheckpointMib = it },
            onValueChangeFinished = {
                PerformancePreferences.save(
                    context,
                    scsiChunkKib = scsiChunkKib,
                    jniCapKib = jniCapKib,
                    rescueCheckpointMib = rescueCheckpointMib,
                )
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
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange((it.toInt() / step) * step) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step) - 1,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
