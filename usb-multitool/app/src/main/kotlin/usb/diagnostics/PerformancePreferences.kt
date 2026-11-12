package usb.diagnostics

import android.content.Context
import android.content.SharedPreferences
import usb.device.TransferTuning

/**
 * Persistence for the Diagnostics performance knobs. [load] runs at app start so an
 * operation always sees the last saved values; the screen saves on slider release.
 */
object PerformancePreferences {
    const val PREFS_NAME = "usb-multitool-performance"
    const val KEY_SCSI_CHUNK_KIB = "scsi_chunk_kib"
    const val KEY_JNI_CAP_KIB = "jni_cap_kib"
    const val KEY_RESCUE_CHECKPOINT_MIB = "rescue_checkpoint_mib"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val prefs = prefs(context)
        TransferTuning.apply(
            scsiChunkKib = prefs.getInt(KEY_SCSI_CHUNK_KIB, TransferTuning.DEFAULT_SCSI_CHUNK_KIB),
            jniCapKib = prefs.getInt(KEY_JNI_CAP_KIB, TransferTuning.DEFAULT_JNI_CAP_KIB),
            rescueCheckpointMib =
                prefs.getInt(
                    KEY_RESCUE_CHECKPOINT_MIB,
                    TransferTuning.DEFAULT_RESCUE_CHECKPOINT_MIB,
                ),
        )
    }

    fun save(
        context: Context,
        scsiChunkKib: Int,
        jniCapKib: Int,
        rescueCheckpointMib: Int,
    ) {
        prefs(context)
            .edit()
            .putInt(KEY_SCSI_CHUNK_KIB, scsiChunkKib)
            .putInt(KEY_JNI_CAP_KIB, jniCapKib)
            .putInt(KEY_RESCUE_CHECKPOINT_MIB, rescueCheckpointMib)
            .apply()
        TransferTuning.apply(scsiChunkKib, jniCapKib, rescueCheckpointMib)
    }
}
