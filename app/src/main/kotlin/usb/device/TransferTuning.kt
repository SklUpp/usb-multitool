package usb.device

/**
 * Process-wide transfer knobs exposed on the Diagnostics screen. Values are applied when a
 * settings change is saved or at app start, and read once when an operation starts, so an
 * edit never changes an in-flight transfer.
 *
 * Defaults match the previous constants; a missing or corrupt preference leaves them.
 */
object TransferTuning {
    const val DEFAULT_SCSI_CHUNK_KIB = 64
    const val MIN_SCSI_CHUNK_KIB = 16
    const val MAX_SCSI_CHUNK_KIB = 512
    const val SCSI_CHUNK_STEP_KIB = 16

    const val DEFAULT_JNI_CAP_KIB = 1024
    const val MIN_JNI_CAP_KIB = 128
    const val MAX_JNI_CAP_KIB = 8 * 1024
    const val JNI_CAP_STEP_KIB = 128

    const val DEFAULT_RESCUE_CHECKPOINT_MIB = 256
    const val MIN_RESCUE_CHECKPOINT_MIB = 32
    const val MAX_RESCUE_CHECKPOINT_MIB = 512
    const val RESCUE_CHECKPOINT_STEP_MIB = 32

    @Volatile
    var scsiChunkBytes: Int = DEFAULT_SCSI_CHUNK_KIB * 1024
        private set

    @Volatile
    var jniCapBytes: Int = DEFAULT_JNI_CAP_KIB * 1024
        private set

    @Volatile
    var rescueCheckpointBytes: Long = DEFAULT_RESCUE_CHECKPOINT_MIB.toLong() * 1024 * 1024
        private set

    /**
     * Applies the three values after clamping them to their documented ranges. The SCSI
     * chunk is further aligned down to a whole sector so [TransferPlan.plan] never splits
     * mid-sector.
     */
    fun apply(
        scsiChunkKib: Int,
        jniCapKib: Int,
        rescueCheckpointMib: Int,
        sectorSize: Int = 512,
    ) {
        val chunk = scsiChunkKib.coerceIn(MIN_SCSI_CHUNK_KIB, MAX_SCSI_CHUNK_KIB) * 1024
        val step = sectorSize.coerceAtLeast(1)
        scsiChunkBytes = (chunk / step) * step
        jniCapBytes = jniCapKib.coerceIn(MIN_JNI_CAP_KIB, MAX_JNI_CAP_KIB) * 1024
        rescueCheckpointBytes =
            rescueCheckpointMib.coerceIn(MIN_RESCUE_CHECKPOINT_MIB, MAX_RESCUE_CHECKPOINT_MIB)
                .toLong() * 1024 * 1024
    }
}
