package usb.multitool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.UsbExclusiveClaim

class ProbePublicationTest {
    @Test
    fun closeHappensBeforeReadyPublicationAndCloseFailureFencesReady() = runBlocking {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("release failed")
        val result = ProbePublication.run(
            body = { events += "probe"; null },
            close = { events += "close"; throw closeFailure },
            publish = { events += "ready" },
        )
        assertEquals(listOf("probe", "close"), events)
        assertSame(closeFailure, result.failure)
        assertSame(closeFailure, result.closeFailure)
        assertTrue("Ready must not publish before claim release", "ready" !in events)
    }

    @Test
    fun bodyFailureRemainsPrimaryAndCloseIsSuppressed() = runBlocking {
        val bodyFailure = IllegalStateException("table failed")
        val closeFailure = IllegalStateException("release failed")
        val result = ProbePublication.run(
            body = { bodyFailure },
            close = { throw closeFailure },
            publish = { error("must not publish") },
        )
        assertSame(bodyFailure, result.failure)
        assertSame(closeFailure, result.closeFailure)
        assertSame(closeFailure, bodyFailure.suppressed.single())
    }

    @Test
    fun bodyCancellationReleasesClaimAndDoesNotPublish() = runBlocking {
        val cancellation = CancellationException("stop")
        var closed = false
        var published = false
        val thrown = runCatching {
            ProbePublication.run(
                body = { throw cancellation },
                close = { closed = true },
                publish = { published = true },
            )
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertTrue(closed)
        assertFalse(published)
    }

    @Test
    fun productionCloseThenConsumerAcquireIsDeterministic() = runBlocking {
        val claim = UsbExclusiveClaim()
        val probeLease = claim.tryAcquire()
        val events = mutableListOf<String>()
        var consumerAcquired = false
        val result = ProbePublication.run(
            body = { events += "probe"; null },
            close = { events += "release"; probeLease.close() },
            publish = {
                events += "DeviceChanged(Ready)"
                val consumerLease = claim.tryAcquire()
                events += "LoadFilesystemUsage"
                consumerAcquired = true
                consumerLease.close()
            },
        )
        assertEquals(listOf("probe", "release", "DeviceChanged(Ready)", "LoadFilesystemUsage"), events)
        assertFalse(claim.isHeld())
        assertEquals(null, result.failure)
    }
}
