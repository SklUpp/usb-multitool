package usb.files

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import usb.diagnostics.DestructiveConfirmation
import usb.diagnostics.Diagnostics
import usb.operation.DiskOperation
import usb.testutil.RecordingDisk

/**
 * Close-before-mutation gate: a failed files-volume close must refuse the write path
 * so the disk is not opened for wipe/raw while a half-closed volume may still hold USB.
 */
class RequireFilesVolumeClosedTest {
    @Test
    fun nullCloseErrorAllowsCallerToProceed() {
        requireFilesVolumeClosed(null)
    }

    @Test
    fun closeErrorPropagatesUnchanged() {
        val err = IOException("volume sync unconfirmed")
        val thrown = assertThrows(IOException::class.java) {
            requireFilesVolumeClosed(err)
        }
        assertSame(err, thrown)
    }

    @Test
    fun closeFailureRefusesWipeBeforeAnySectorWrite() {
        val disk = RecordingDisk(sectorCount = 64UL, sectorSize = 512)
        val confirmation = DestructiveConfirmation(
            identity = "test-stick",
            capacityBytes = disk.geometry.capacityBytes,
        )
        val closeErr = IOException("files volume close failed")
        val op = DiskOperation(name = "wipe")
        var wipeEntered = false

        try {
            requireFilesVolumeClosed(closeErr)
            wipeEntered = true
            Diagnostics.wipe(
                disk = disk,
                identity = confirmation.identity,
                confirmation = confirmation,
                operation = op,
            )
            fail("close failure must abort before wipe")
        } catch (e: IOException) {
            assertSame(closeErr, e)
        }

        assertEquals("wipe body must not run after close failure", false, wipeEntered)
        assertEquals("no sectors written", 0, disk.bytesWritten)
        assertEquals(false, op.mutationAttempted)
    }
}
