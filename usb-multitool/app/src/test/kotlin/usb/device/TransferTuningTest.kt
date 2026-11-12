package usb.device

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferTuningTest {

    @Test
    fun applyClampsToDocumentedRangesAndAlignsTheScsiChunk() {
        try {
            TransferTuning.apply(
                scsiChunkKib = 1,
                jniCapKib = Int.MAX_VALUE,
                rescueCheckpointMib = 0,
                sectorSize = 4096,
            )
            assertEquals(16 * 1024, TransferTuning.scsiChunkBytes)
            assertEquals(8 * 1024 * 1024, TransferTuning.jniCapBytes)
            assertEquals(32L * 1024 * 1024, TransferTuning.rescueCheckpointBytes)
        } finally {
            TransferTuning.apply(
                TransferTuning.DEFAULT_SCSI_CHUNK_KIB,
                TransferTuning.DEFAULT_JNI_CAP_KIB,
                TransferTuning.DEFAULT_RESCUE_CHECKPOINT_MIB,
            )
        }
    }

    @Test
    fun rescueChunkUsesWholeSectorAtLargeSectorBoundary() {
        try {
            TransferTuning.apply(
                scsiChunkKib = TransferTuning.DEFAULT_SCSI_CHUNK_KIB,
                jniCapKib = TransferTuning.DEFAULT_JNI_CAP_KIB,
                rescueCheckpointMib = TransferTuning.DEFAULT_RESCUE_CHECKPOINT_MIB,
                sectorSize = 4096,
            )
            assertEquals(1024 * 1024, TransferTuning.jniCapBytes)
            assertEquals(1024 * 1024 / 4096 * 4096, TransferTuning.jniCapBytes)
        } finally {
            TransferTuning.apply(
                TransferTuning.DEFAULT_SCSI_CHUNK_KIB,
                TransferTuning.DEFAULT_JNI_CAP_KIB,
                TransferTuning.DEFAULT_RESCUE_CHECKPOINT_MIB,
            )
        }
    }
}
