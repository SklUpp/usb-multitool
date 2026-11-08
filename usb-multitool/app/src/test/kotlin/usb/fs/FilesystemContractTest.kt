package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.testutil.FatImage

class FilesystemContractTest {
    @Test fun probeSelectsFatFromBootSignature() {
        assertEquals(FilesystemId.FAT, Filesystem.probe(FatImage.partition()))
    }

    @Test fun mountRefusesWhenNativeBridgeUnlinked() {
        // Host JVM: usb_native absent or fs_bridge symbols missing. Must not
        // return a MountedVolume that looks open while sync/close are no-ops.
        val failure = assertThrows(UnsupportedFilesystemException::class.java) {
            Filesystem.mount(FatImage.partition())
        }
        assertTrue(
            failure.message!!.contains("not linked") ||
                failure.message!!.contains("native mount failed"),
        )
    }
}
