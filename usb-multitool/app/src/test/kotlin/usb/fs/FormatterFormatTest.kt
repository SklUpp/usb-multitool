package usb.fs

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import usb.partition.VirtualPartition
import usb.partition.ImageBlockDevice

/**
 * FORMAT_PARTITION must run the real FatFs mkfs. No engine stub, no fake success.
 * The native library is absent on the host JVM, so the real path is expected to
 * stop at the JNI boundary there; the mkfs/mount/write/read round trip itself is
 * covered by native-tests/fatfs.
 */
class FormatterFormatTest {
    private val sectorSize = 512

    private class Fixture(sectors: Int, sectorSize: Int) {
        // Pre-existing garbage: formatting must overwrite it.
        val image = ImageBlockDevice(
            ByteArray(sectors * sectorSize) { 0x5A },
            sectorSize,
            sectors.toULong(),
        )
        val partition = VirtualPartition(0UL, sectors.toULong(), image)
    }

    @Test
    fun fatAndExfatSelectTheirMkfsFlavour() {
        assertEquals(FatMkfsType.FAT, fatMkfsType(FilesystemId.FAT))
        assertEquals(FatMkfsType.EXFAT, fatMkfsType(FilesystemId.EXFAT))
    }

    @Test
    fun fatOnLargePartitionSelectsFat32() {
        // 8388608 sectors * 512 = 4 GiB
        val dummyDevice = object : usb.device.BlockDevice {
            override val sectorSize = 512
            override val sectorCount = 8388608UL
            override fun read(lba: ULong, destination: ByteBuffer) {}
            override fun write(lba: ULong, source: ByteBuffer) {}
            override fun sync() {}
        }
        val largePart = VirtualPartition(0UL, 8388608UL, dummyDevice)
        assertEquals(FatMkfsType.FAT32, fatMkfsType(FilesystemId.FAT, largePart))
        val smallPart = VirtualPartition(0UL, 1024UL, dummyDevice)
        assertEquals(FatMkfsType.FAT, fatMkfsType(FilesystemId.FAT, smallPart))
    }

    @Test
    fun nonFatFamilyIdIsRejected() {
        try {
            fatMkfsType(FilesystemId.NTFS)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected: NTFS never routes through the FatFs engine
        }
    }


    @Test
    fun fatFormatRunsTheEngineInsteadOfThrowingEngineMissing() {
        val fixture = Fixture(sectors = 8192, sectorSize = sectorSize)
        try {
            Formatter.format(fixture.partition, FilesystemId.FAT)
        } catch (_: UnsatisfiedLinkError) {
            // Host JVM: libusb_native is absent, but the call reached the JNI
            // boundary, which proves the engine is wired, not stubbed.
            return
        }
        // Native present: a real FAT BPB must be on the partition now.
        val boot = fixture.image.snapshot().copyOfRange(0, sectorSize)
        assertEquals(0x55, boot[510].toInt() and 0xFF)
        assertEquals(0xAA, boot[511].toInt() and 0xFF)
        assertEquals(512, ByteBuffer.wrap(boot, 11, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF)
    }

    @Test
    fun exfatFormatRunsTheEngineInsteadOfThrowingEngineMissing() {
        val fixture = Fixture(sectors = 16384, sectorSize = sectorSize)
        try {
            Formatter.format(fixture.partition, FilesystemId.EXFAT)
        } catch (_: UnsatisfiedLinkError) {
            return
        }
        val boot = fixture.image.snapshot().copyOfRange(0, sectorSize)
        assertEquals("EXFAT   ", String(boot, 3, 8, Charsets.US_ASCII))
    }


    @Test
    fun requireSupportedRejectsNtfs() {
        try {
            Formatter.requireSupported(FilesystemId.NTFS)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }
}
