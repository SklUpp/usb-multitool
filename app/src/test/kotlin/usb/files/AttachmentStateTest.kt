package usb.files

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Attach/detach publishes device and generation together. A pin built from [snapshot] must
 * never pair the previous device with a new generation (or the reverse).
 */
class AttachmentStateTest {
    @Test
    fun publishBumpsGenerationOnlyWhenAsked() {
        val state = AttachmentState<String>()
        assertEquals(1L, state.publish("A", bumpGeneration = true))
        assertEquals("A", state.snapshot().device)
        // Same-device re-probe keeps the generation so stick-scoped state survives.
        assertEquals(1L, state.publish("A2", bumpGeneration = false))
        assertEquals("A2", state.snapshot().device)
        assertEquals(2L, state.publish(null, bumpGeneration = true))
        assertNull(state.snapshot().device)
        assertEquals(2L, state.snapshot().generation)
    }

    @Test
    fun concurrentReaderNeverMixesGenerationAndDevice() {
        // Invariant: odd generation publishes "A", even publishes "B". A pair from two
        // different publishes would break it.
        val state = AttachmentState<String>()
        state.publish("A", bumpGeneration = true)
        val done = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)

        val writer =
            Thread {
                start.await()
                var expectA = false
                repeat(100_000) {
                    state.publish(if (expectA) "A" else "B", bumpGeneration = true)
                    expectA = !expectA
                }
                done.set(true)
            }
        val reader =
            Thread {
                start.await()
                while (!done.get()) {
                    val snap = state.snapshot()
                    val deviceA = snap.device == "A"
                    if ((snap.generation % 2L == 1L) != deviceA) {
                        failure.compareAndSet(null, AssertionError("mixed snapshot: $snap"))
                    }
                }
            }
        writer.start()
        reader.start()
        start.countDown()
        writer.join()
        reader.join()
        failure.get()?.let { throw it }
    }
}
