package usb.device

import java.io.IOException
import java.nio.ByteBuffer
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
    fun diskCloseReleasesLeaseEvenWhenSessionCloseFails() {
        val claim = UsbExclusiveClaim()
        val bot =
            object : FakeBotSession() {
                override fun close() {
                    throw IOException("channel busy")
                }
            }
        val disk = UsbDisk(bot, BlockGeometry(512, 8UL), claim.tryAcquire())
        assertThrows(IOException::class.java) { disk.close() }
        assertFalse("unconfirmed close must still free the claim", claim.isHeld())
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
}
