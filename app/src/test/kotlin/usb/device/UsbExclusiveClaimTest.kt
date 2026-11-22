package usb.device

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One live claim per device. A second opener must fail fast instead of stealing the
 * interface (`claimInterface(force = true)`), and a lease must be released exactly once.
 */
class UsbExclusiveClaimTest {
    @Test
    fun secondAcquireFailsWhileLeaseHeld() {
        val claim = UsbExclusiveClaim()
        val first = claim.tryAcquire()
        assertTrue(claim.isHeld())
        assertThrows(UsbExclusiveClaim.BusyException::class.java) { claim.tryAcquire() }
        first.close()
        assertFalse(claim.isHeld())
    }

    @Test
    fun releaseAllowsReacquire() {
        val claim = UsbExclusiveClaim()
        val first = claim.tryAcquire()
        first.close()
        val second = claim.tryAcquire()
        assertTrue(claim.isHeld())
        second.close()
        assertFalse(claim.isHeld())
    }

    @Test
    fun sequentialReleaseLeavesClaimFreeAndReacquirable() {
        // Sequential contract only: after close() returns, no claim remains and the next
        // acquire succeeds. The concurrent half-release window (released visible before the
        // claim is cleared) is prevented by keeping both in one monitor hold; that interleaving
        // cannot be produced deterministically without a production test hook, so it is not
        // asserted here.
        val claim = UsbExclusiveClaim()
        repeat(1_000) {
            val lease = claim.tryAcquire()
            assertTrue(claim.isHeld())
            lease.close()
            assertFalse(claim.isHeld())
        }
    }

    @Test
    fun staleDoubleCloseCannotFreeTheSuccessorLease() {
        val claim = UsbExclusiveClaim()
        val first = claim.tryAcquire()
        first.close()
        val second = claim.tryAcquire()
        first.close()
        assertTrue("closing an old lease must not free the live one", claim.isHeld())
        assertThrows(UsbExclusiveClaim.BusyException::class.java) { claim.tryAcquire() }
        second.close()
        assertFalse(claim.isHeld())
    }
    @Test
    fun staleDiskCloseCannotFreeLiveSuccessorLease() {
        val claim = UsbExclusiveClaim()
        val first = UsbDisk(FakeBotSession(), BlockGeometry(512, 8UL), claim.tryAcquire())
        first.close()
        val successor = claim.tryAcquire()

        first.close()
        assertTrue("stale disk close must not free successor lease", claim.isHeld())
        assertThrows(UsbExclusiveClaim.BusyException::class.java) { claim.tryAcquire() }

        successor.close()
        assertFalse(claim.isHeld())
    }
    @Test
    fun externalLeaseCloseCannotReleaseBoundSession() {
        val claim = UsbExclusiveClaim()
        val lease = claim.tryAcquire()
        lease.bindSession()

        lease.close()
        assertTrue("external cleanup must not release session-owned claim", claim.isHeld())
        assertThrows(UsbExclusiveClaim.BusyException::class.java) { claim.tryAcquire() }

        lease.releaseAfterSessionClose()
        assertFalse(claim.isHeld())
    }

    @Test
    fun diskCloseFailureKeepsLeaseAndBlocksNextClaim() {
        val claim = UsbExclusiveClaim()
        val bot =
            object : FakeBotSession() {
                override fun close() {
                    throw IOException("channel busy")
                }
            }
        val disk = UsbDisk(bot, BlockGeometry(512, 8UL), claim.tryAcquire())
        assertThrows(IOException::class.java) { disk.close() }
        assertTrue("unconfirmed close must retain claim", claim.isHeld())
        assertThrows(UsbExclusiveClaim.BusyException::class.java) { claim.tryAcquire() }
        assertThrows(IOException::class.java) { disk.close() }
        assertTrue("repeated close must stay fail-closed", claim.isHeld())
    }
    @Test
    fun uncertainWriteFailureReleasesLeaseForNextSession() {
        val claim = UsbExclusiveClaim()
        val first = UsbDisk(
            FakeBotSession(writeIoFailureAfterData = true),
            BlockGeometry(512, 8UL),
            claim.tryAcquire(),
        )
        assertThrows(DiskException.IoFailure::class.java) {
            first.write(0UL, ByteBuffer.allocate(512))
        }
        assertTrue(first.isClosed)
        assertFalse("uncertain write close must release claim", claim.isHeld())

        val secondLease = claim.tryAcquire()
        secondLease.close()
        first.close()
        assertFalse(claim.isHeld())
    }

    @Test
    fun concurrentTryAcquireAllowsAtMostOneLiveLease() {
        val claim = UsbExclusiveClaim()
        val startBarrier = CountDownLatch(1)
        val doneBarrier = CountDownLatch(2)
        val winners = CopyOnWriteArrayList<UsbExclusiveClaim.Lease>()
        val busyCount = AtomicInteger(0)
        val otherErrors = CopyOnWriteArrayList<Throwable>()

        val contender = {
            Thread {
                startBarrier.await()
                try {
                    winners.add(claim.tryAcquire())
                } catch (busy: UsbExclusiveClaim.BusyException) {
                    busyCount.incrementAndGet()
                } catch (t: Throwable) {
                    otherErrors.add(t)
                } finally {
                    doneBarrier.countDown()
                }
            }
        }

        val t1 = contender()
        val t2 = contender()
        t1.start()
        t2.start()
        startBarrier.countDown()
        assertTrue(doneBarrier.await(5, TimeUnit.SECONDS))
        t1.join()
        t2.join()

        assertTrue("no unexpected errors", otherErrors.isEmpty())
        assertEquals("exactly one lease granted", 1, winners.size)
        assertEquals("exactly one contender blocked by BusyException", 1, busyCount.get())
        assertTrue("claim is held by winning lease", claim.isHeld())

        winners.first().close()
        assertFalse("closing winner frees claim", claim.isHeld())

        val next = claim.tryAcquire()
        assertTrue(claim.isHeld())
        next.close()
        assertFalse(claim.isHeld())
    }
}
