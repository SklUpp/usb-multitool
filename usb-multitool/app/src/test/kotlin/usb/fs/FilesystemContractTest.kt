package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.testutil.FatImage

class FilesystemContractTest {
    @Test fun mountSelectsEngineFromProbe() {
        val volume = Filesystem.mount(FatImage.partition())
        assertEquals(FilesystemId.FAT, volume.id)
        assertTrue(volume.isOpen())
        volume.close()
    }
}
