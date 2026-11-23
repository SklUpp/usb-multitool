package usb.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskServiceLifecycleDestroyTest {
    @Test
    fun destroyClearsStartLatchSoLaterStartForegroundRequestsAgain() = runBlocking {
        var starts = 0
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++; CompletableDeferred(Unit) },
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
    fun startWhileMarkedForegroundDoesNotDoubleRequest() = runBlocking {
        var starts = 0
        var foreground = true
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++; CompletableDeferred(Unit) },
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
                CompletableDeferred(Unit)
            },
            stopService = {},
            hasOutstandingWork = { false },
            isServiceForeground = { false },
        )

        val starter = Thread {
            runBlocking { lifecycle.startForeground() }
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
    @Test
    fun completedReadinessDoesNotSuppressNextStart() = runBlocking {
        var starts = 0
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++; CompletableDeferred(Unit) },
            stopService = {},
            hasOutstandingWork = { false },
            isServiceForeground = { false },
        )

        lifecycle.startForeground()
        assertEquals(1, starts)
        // After an idle teardown isForeground is false but the service instance
        // still exists; a later submit must request a fresh start.
        lifecycle.startForeground()
        assertEquals(2, starts)
    }

    @Test
    fun concurrentStartsWaitForSameReadiness() = runTest {
        val ready = CompletableDeferred<Unit>()
        var starts = 0
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++; ready },
            stopService = {},
            hasOutstandingWork = { true },
        )
        val first = async { lifecycle.startForeground() }
        val second = async { lifecycle.startForeground() }
        runCurrent()
        assertEquals(1, starts)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        ready.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun timeoutClearsStartLatchForRetry() = runTest {
        var starts = 0
        val lifecycle = DiskServiceLifecycle(
            requestStart = { starts++; if (starts == 1) CompletableDeferred() else CompletableDeferred(Unit) },
            stopService = {},
            hasOutstandingWork = { true },
        )
        try {
            lifecycle.startForeground()
            org.junit.Assert.fail("expected timeout")
        } catch (_: TimeoutCancellationException) {
        }
        assertFalse(lifecycle.hasRequestedStart())
        assertEquals(1, starts)
        lifecycle.startForeground()
        assertEquals(2, starts)
    }
}
