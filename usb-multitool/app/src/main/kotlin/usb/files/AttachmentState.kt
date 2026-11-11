package usb.files

/**
 * Atomically published USB attachment identity.
 *
 * Device and generation are replaced together in one volatile write, so a reader can never
 * pair a new generation with the previous device (or the reverse). Attach/detach publishes
 * a complete snapshot; [snapshot] returns one.
 *
 * The generation is the identity of one attachment: a same-device re-probe may republish
 * the device without bumping it. A detach/attach bumps it.
 */
class AttachmentState<T>(initialGeneration: Long = 0L, initialDevice: T? = null) {
    /** One consistent device+generation pair. */
    data class Snapshot<D>(val generation: Long, val device: D?)

    private val publishLock = Any()

    @Volatile private var snapshot = Snapshot(initialGeneration, initialDevice)

    /** Consistent read: both fields belong to the same publish. */
    fun snapshot(): Snapshot<T> = snapshot

    /**
     * Replaces the attachment and, when [bumpGeneration], its generation. Returns the
     * generation now live. Publishers are serialized so concurrent bumps cannot collide.
     */
    fun publish(device: T?, bumpGeneration: Boolean): Long =
        synchronized(publishLock) {
            val generation =
                if (bumpGeneration) snapshot.generation + 1 else snapshot.generation
            snapshot = Snapshot(generation, device)
            generation
        }
}
