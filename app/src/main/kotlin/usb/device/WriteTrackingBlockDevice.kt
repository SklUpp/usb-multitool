package usb.device

import java.nio.ByteBuffer

/**
 * Wraps [this] so every write notifies [onWrite] before the inner write.
 *
 * Both LBA overloads are overridden. Kotlin `by` delegation alone only customizes
 * [BlockDevice.write] `(ULong, …)`; Ventoy (and JNI) call `(Long, …)`, which would
 * forward straight to the inner device and skip the tracker.
 */
fun BlockDevice.withWriteTracking(onWrite: () -> Unit): BlockDevice {
    val inner = this
    return object : BlockDevice by inner {
        override fun write(lba: ULong, source: ByteBuffer) {
            onWrite()
            inner.write(lba, source)
        }

        override fun write(lba: Long, source: ByteBuffer) {
            onWrite()
            inner.write(lba, source)
        }
    }
}
