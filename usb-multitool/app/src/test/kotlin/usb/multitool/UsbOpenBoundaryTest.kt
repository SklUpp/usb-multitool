package usb.multitool

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk
import usb.diagnostics.AppTraceLogger
import usb.files.AttachmentChangedException
import usb.files.AttachmentPin
import usb.files.AttachmentState
import usb.files.FilesVolumeSession

class UsbOpenBoundaryTest {

    private fun boundary(
        attachment: AttachmentState<UsbDevice>,
        session: FilesVolumeSession,
        openDisk: (UsbManager, UsbDevice, usb.device.UsbExclusiveClaim.Lease) -> UsbDisk,
    ): UsbOpenBoundary =
        UsbOpenBoundary(
            currentAttachment = { attachment.snapshot() },
            usbManager = { mockk(relaxed = true) },
            filesSession = session,
            trace = AppTraceLogger(Files.createTempDirectory("usb-open-boundary").toFile()),
            deviceKey = { it?.let { device -> "${device.deviceId}:${device.deviceName}" } },
            openDisk = openDisk,
        )

    private fun session(attachment: AttachmentState<UsbDevice>): FilesVolumeSession =
        FilesVolumeSession(
            currentAttachment = {
                attachment.snapshot().let { live ->
                    live.device?.let { AttachmentPin(live.generation, "${it.deviceId}:${it.deviceName}") }
                }
            },
            openMounted = { _, _, _, _ -> error("mount not used") },
        )

    private fun device(id: Int): UsbDevice = mockk {
        every { deviceId } returns id
        every { deviceName } returns "/dev/bus/usb/001/${id.toString().padStart(3, '0')}"
    }

    private fun disk(
        lease: usb.device.UsbExclusiveClaim.Lease,
        session: FakeBotSession,
    ): UsbDisk = UsbDisk(session, BlockGeometry(512, 100UL), lease)

    @Test
    fun slowOpenReplugFailsPostOpenFenceAndReleasesClaim() {
        val first = device(7)
        val second = device(8)
        val attachment = AttachmentState(1L, first)
        val session = session(attachment)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val boundary = boundary(attachment, session) { _, _, lease ->
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            disk(lease, FakeBotSession())
        }
        val thrown = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                boundary.openRawDisk()
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        attachment.publish(second, bumpGeneration = true)
        release.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(thrown.get() is AttachmentChangedException)
        assertFalse("fenced disk close releases claim", session.claim.isHeld())
    }

    @Test
    fun postOpenFencePreservesCloseFailureAsSuppressedAndKeepsClaimHeld() {
        val first = device(7)
        val second = device(8)
        val attachment = AttachmentState(1L, first)
        val session = session(attachment)
        val closeFailure = IOException("USB close failed")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val boundary = boundary(attachment, session) { _, _, lease ->
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            disk(
                lease,
                object : FakeBotSession() {
                    override fun close() {
                        throw closeFailure
                    }
                },
            )
        }
        val thrown = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                boundary.openRawDisk()
                error("expected attachment fence")
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        attachment.publish(second, bumpGeneration = true)
        release.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        val thrownError = thrown.get()
        assertTrue(thrownError is AttachmentChangedException)
        assertEquals("USB attachment changed", thrownError?.message)
        assertSame(closeFailure, thrownError?.suppressed?.single())
        assertTrue("unconfirmed disk close keeps claim fail-closed", session.claim.isHeld())
    }
}
