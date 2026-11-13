package usb.multitool

import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.operation.OperationState
import usb.service.OperationHandle

/**
 * Transitions between the phone-import inspection worker and the main thread. These are the
 * orderings that previously leaked an untracked SAF job or rejected a legal next import.
 */
class InspectionOwnershipTest {
    private val owner = InspectionOwnership()

    private fun ticket(generation: Long = 1L) =
        InspectionTicket(
            generation = generation,
            attachGeneration = 2L,
            deviceKey = "usb:1:2",
            identity = "stick",
        )

    private class FakeHandle : OperationHandle {
        override val token = usb.service.OperationTokenGenerator.next()
        override var state: OperationState = OperationState.Queued
        var cancelled = false

        override suspend fun await(): OperationState = OperationState.Completed

        override fun cancel() {
            cancelled = true
        }
    }

    private fun job() = Job()

    @Test
    fun secondInspectionIsBusyUntilTheOwnerReleases() {
        val first = ticket()
        val second = ticket(generation = 2L)

        assertTrue(owner.begin(first))
        assertFalse(owner.begin(second))
        assertTrue(owner.owned(first))
        assertFalse(owner.owned(second))
        assertSame(first, owner.active)

        owner.release(first)
        assertNull(owner.active)
        assertTrue(owner.begin(second))
    }

    @Test
    fun releaseOfTheOldTicketNeverDisownsTheNewerOne() {
        val first = ticket()
        val second = ticket(generation = 2L)

        assertTrue(owner.begin(first))
        assertTrue(owner.cancel() != null)
        assertTrue(owner.begin(second))

        owner.release(first)

        assertSame(second, owner.active)
        assertTrue(owner.owned(second))
    }

    @Test
    fun cancelBetweenBeginAndRegistrationCancelsTheJobInsteadOfLeakingIt() {
        val only = ticket()
        assertTrue(owner.begin(only))

        // Cancel wins the race before the launched job is registered.
        assertNull(requireNotNull(owner.cancel()).safJob)

        val launched = job()
        owner.trackSafJob(only, launched)

        assertTrue(launched.isCancelled)
        assertNull(owner.active)
        assertNull(owner.cancel())
    }

    @Test
    fun lateRegistrationOfTheOldJobCannotDisplaceTheNewJob() {
        val first = ticket()
        val second = ticket(generation = 2L)
        assertTrue(owner.begin(first))
        owner.cancel()
        assertTrue(owner.begin(second))
        val newJob = job()
        owner.trackSafJob(second, newJob)

        val oldJob = job()
        owner.trackSafJob(first, oldJob)

        assertTrue(oldJob.isCancelled)
        assertFalse(newJob.isCancelled)
        val cancelled = requireNotNull(owner.cancel())
        assertSame(newJob, cancelled.safJob)
    }

    @Test
    fun staleUsbHandleRegistrationIsIgnored() {
        val first = ticket()
        val second = ticket(generation = 2L)
        assertTrue(owner.begin(first))
        owner.cancel()
        assertTrue(owner.begin(second))

        val handle = FakeHandle()
        owner.trackUsbHandle(first, handle)

        val cancelled = requireNotNull(owner.cancel())
        assertNull(cancelled.usbHandle)
        assertFalse(handle.cancelled)
    }

    @Test
    fun releaseFreesTheSlotWithoutCancellingTheRunningJob() {
        val first = ticket()
        val second = ticket(generation = 2L)
        assertTrue(owner.begin(first))
        val running = job()
        owner.trackSafJob(first, running)

        owner.release(first)

        assertNull(owner.active)
        assertFalse(running.isCancelled)
        assertTrue(owner.begin(second))
        // The old job is gated by its own ticket, not by slot state.
        assertFalse(owner.owned(first))
        assertTrue(owner.owned(second))
    }

    @Test
    fun cancelHandsOutTheWorkOnceAndLeavesOwnershipCancelled() {
        val only = ticket()
        val handle = FakeHandle()
        assertTrue(owner.begin(only))
        val saf = job()
        owner.trackSafJob(only, saf)
        owner.trackUsbHandle(only, handle)

        val first = requireNotNull(owner.cancel())
        assertSame(saf, first.safJob)
        assertSame(handle, first.usbHandle)
        assertFalse(handle.cancelled)

        assertNull(owner.cancel())
        assertNull(owner.active)
        assertFalse(owner.owned(only))
    }

    @Test
    fun racingRegistrationAndCancelAlwaysEndsWithTheJobCancelled() {
        val runs = 500
        repeat(runs) {
            val owner = InspectionOwnership()
            val only = ticket()
            assertTrue(owner.begin(only))
            val launched = job()
            val barrier = CyclicBarrier(2)
            val canceller =
                thread {
                    barrier.await()
                    owner.cancel()?.safJob?.cancel()
                }
            val registrar =
                thread {
                    barrier.await()
                    owner.trackSafJob(only, launched)
                }
            canceller.join()
            registrar.join()

            assertTrue("run $it leaked an active SAF job", launched.isCancelled)
            assertNull("run $it kept the slot owned", owner.active)
            assertNull("run $it left work registered", owner.cancel())
        }
    }

    @Test
    fun racingBeginAndCancelNeverLeavesTheSlotOwned() {
        repeat(500) {
            val owner = InspectionOwnership()
            val only = ticket()
            val barrier = CyclicBarrier(2)
            val beginner =
                thread {
                    barrier.await()
                    if (owner.begin(only)) owner.release(only)
                }
            val canceller =
                thread {
                    barrier.await()
                    owner.cancel()
                }
            beginner.join()
            canceller.join()

            assertNull(owner.active)
            assertNull(owner.cancel())
        }
    }
}
