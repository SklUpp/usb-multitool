package usb.fs

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import usb.device.BlockDevice

/** Host JVM tests: range and write-permission guards reject before any JNI call. */
class FsIoGuardTest {
    private object NoWriteDevice : BlockDevice {
        override val sectorSize: Int = 512
        override val sectorCount: ULong = 8UL
        override fun read(lba: ULong, destination: ByteBuffer) = Unit
        override fun write(lba: ULong, source: ByteBuffer) {
            throw AssertionError("guard must reject before touching the device")
        }
        override fun sync() = Unit
    }

    private fun expectIae(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun fatReadRejectsNegativeOffsetAndOversizedName() {
        val vol: UsbFsVolume = FatVolume(FatKind.FAT32, volumeHandle = 0L, blockHandle = 0L, ownsBlock = false)
        expectIae { vol.read("/a.bin", offset = -1L, length = 16) }
        expectIae { vol.read("/a.bin", offset = 0L, length = MAX_FAT_EXT_IO_BYTES + 1) }
    }

    @Test
    fun fatWriteRejectsNegativeOffset() {
        val vol: UsbFsVolume = FatVolume(FatKind.FAT32, volumeHandle = 0L, blockHandle = 0L, ownsBlock = false)
        expectIae { vol.write("/a.bin", ByteArray(16), offset = -1L) }
    }

    @Test
    fun extReadRejectsNegativeOffsetAndOversizedName() {
        val vol: UsbFsVolume = ExtVolume(handle = 0L, device = NoWriteDevice, readOnly = false)
        expectIae { vol.read("/a.bin", offset = -1L, length = 16) }
        expectIae { vol.read("/a.bin", offset = 0L, length = MAX_FAT_EXT_IO_BYTES + 1) }
    }

    @Test
    fun extMutatorsRefuseReadOnlyVolume() {
        val vol: UsbFsVolume = ExtVolume(handle = 0L, device = NoWriteDevice, readOnly = true)
        for (mutate in listOf<() -> Unit>(
            { vol.write("/a.bin", ByteArray(16)) },
            { vol.mkdir("/d") },
            { vol.delete("/a.bin") },
            { vol.rename("/a.bin", "/b.bin") },
        )) {
            try {
                mutate()
                fail("expected ExtException on read-only EXT volume")
            } catch (e: ExtException) {
                assertEquals("Read-only EXT volume", e.message)
            }
        }
    }

    @Test
    fun ntfsReadRejectsNegativeOffsetAndOversizedName() {
        val vol: UsbFsVolume = NtfsVolume(handle = 0L, device = NoWriteDevice, readOnly = false)
        expectIae { vol.read("/a.bin", offset = -1L, length = 16) }
        expectIae { vol.read("/a.bin", offset = 0L, length = MAX_NTFS_IO_BYTES + 1) }
    }

    @Test
    fun ntfsWriteRejectsNegativeOffset() {
        val vol: UsbFsVolume = NtfsVolume(handle = 0L, device = NoWriteDevice, readOnly = false)
        expectIae { vol.write("/a.bin", ByteArray(16), offset = -1L) }
    }

    @Test
    fun capIsGenerousOverOneMiBChunkButBounded() {
        requireIoRange(offset = 0L, length = 1024 * 1024, maxBytes = MAX_FAT_EXT_IO_BYTES)
        assertEquals(16 * 1024 * 1024, MAX_NTFS_IO_BYTES)
        assertEquals(8 * 1024 * 1024, MAX_FAT_EXT_IO_BYTES)
    }
}
