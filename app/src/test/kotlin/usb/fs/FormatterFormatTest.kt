package usb.fs

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import usb.partition.VirtualPartition

/**
 * Pure selection logic only. Real mkfs/mount/read/write behaviour is verified
 * by the native host tests (scripts/run-ntfs-host-test.sh,
 * scripts/run-ext-host-test.sh, native-tests/fatfs), not by catching a missing
 * JNI library on the host JVM.
 */
class FormatterFormatTest {
    @Test
    fun fatAndExfatSelectTheirMkfsFlavour() {
        assertEquals(FatMkfsType.FAT, fatMkfsType(FilesystemId.FAT))
        assertEquals(FatMkfsType.EXFAT, fatMkfsType(FilesystemId.EXFAT))
    }

    @Test
    fun fatOnLargePartitionSelectsFat32() {
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
    fun requireSupportedAcceptsNtfs() {
        assertEquals(FilesystemId.NTFS, Formatter.requireSupported(FilesystemId.NTFS))
    }
}
