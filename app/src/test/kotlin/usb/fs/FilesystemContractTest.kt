package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Test
import usb.testutil.ExtImage
import usb.testutil.FatImage

class FilesystemContractTest {
    @Test fun probeSelectsFatFromBootSignature() {
        assertEquals(FilesystemId.FAT, Filesystem.probe(FatImage.partition()))
    }

    @Test fun probeSelectsExtFromSuperblockMagic() {
        assertEquals(FilesystemId.EXT, Filesystem.probe(ExtImage.partition()))
    }
}
