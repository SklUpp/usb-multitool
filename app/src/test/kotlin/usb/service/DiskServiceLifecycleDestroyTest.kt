package usb.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskServiceLifecycleDestroyTest {
    @Test
    fun destroyClearsStartLatchSoLaterStartForegroundRequestsAgain() {
        var starts = 0
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++ },
            stopService = {},
            hasOutstandingWork = { false },
            isServiceForeground = { false },
        )

        lifecycle.startForeground()
        assertEquals(1, starts)
        assertTrue(lifecycle.hasRequestedStart())

        // Simulate service death without stopWhenIdle clearing the latch.
        lifecycle.onServiceDestroyed()
        assertFalse(lifecycle.hasRequestedStart())

        lifecycle.startForeground()
        assertEquals(2, starts)
        assertTrue(lifecycle.hasRequestedStart())
    }

    @Test
    fun startWhileMarkedForegroundDoesNotDoubleRequest() {
        var starts = 0
        var foreground = true
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++ },
            stopService = {},
            hasOutstandingWork = { false },
            isServiceForeground = { foreground },
        )
        lifecycle.startForeground()
        assertEquals(0, starts)
        assertTrue(lifecycle.hasRequestedStart())

        foreground = false
        lifecycle.onServiceDestroyed()
        lifecycle.startForeground()
        assertEquals(1, starts)
    }

    @Test
    fun onServiceDestroyedIsLockFreeAgainstBlockingRequestStart() {
        // requestStart blocks; onServiceDestroyed must still clear the latch without
        // waiting on the lifecycle lock held only around the decision, not the callback.
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val starts = AtomicInteger(0)
        val lifecycle = DiskServiceLifecycle(
            requestStart = {
                starts.incrementAndGet()
                enteredStart.countDown()
                assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
            },
            stopService = {},
            hasOutstandingWork = { false },
            isServiceForeground = { false },
        )

        val starter = Thread {
            lifecycle.startForeground()
        }
        starter.start()
        assertTrue(enteredStart.await(2, TimeUnit.SECONDS))
        // While requestStart is blocked, destroy must not deadlock.
        lifecycle.onServiceDestroyed()
        assertFalse(lifecycle.hasRequestedStart())
        releaseStart.countDown()
        starter.join(2_000)
        assertFalse(starter.isAlive)
        assertEquals(1, starts.get())
    }
}
