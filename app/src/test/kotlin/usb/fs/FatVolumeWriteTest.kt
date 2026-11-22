package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Lightweight host JVM tests for FatVolume write policy.
 * No native library / Gradle required.
 */
class FatVolumeWriteTest {
    @Test
    fun plannedTotalSizeRejectsChunkedWriteAboveFat32Limit() {
        val max = 0xFFFFFFFFL
        val chunk = ByteArray(1024)
        val planned = resolvePlannedTotal(offset = 0L, dataSize = chunk.size, plannedTotalSize = max + 1)
        assertEquals(max + 1, planned)
        try {
            requireWithinFileLimit(planned, max, FatKind.FAT32)
            fail("expected FileTooLargeException")
        } catch (_: FileTooLargeException) {
            // ok
        }
    }

    @Test
    fun plannedTotalDefaultsToOffsetPlusChunk() {
        assertEquals(150L, resolvePlannedTotal(offset = 100L, dataSize = 50, plannedTotalSize = 150L))
        assertEquals(200L, resolvePlannedTotal(offset = 100L, dataSize = 50, plannedTotalSize = 200L))
        assertEquals(150L, resolvePlannedTotal(offset = 100L, dataSize = 50, plannedTotalSize = 10L))
    }

    @Test
    fun shortWriteThrowsFatFsException() {
        try {
            requireFullWrite(wrote = 3, expected = 10)
            fail("expected FatFsException")
        } catch (e: FatFsException) {
            assertEquals("short write: wrote 3 of 10 bytes", e.message)
        }
    }

    @Test
    fun fullWriteAccepted() {
        requireFullWrite(wrote = 10, expected = 10)
    }

    @Test
    fun parameterSizedWriteWithinLimitPasses() {
        requireWithinFileLimit(planned = 1024L, max = 0xFFFFFFFFL, kind = FatKind.FAT32)
    }

    @Test
    fun writeRejectsNegativeOffsetBeforeNative() {
        val volume = FatVolume(FatKind.FAT32, volumeHandle = 0L, blockHandle = 0L, ownsBlock = false)
        try {
            volume.write("/x", ByteArray(4), offset = -1L, create = true, plannedTotalSize = 4L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("negative offset: -1", e.message)
        }
    }

    @Test
    fun writeRejectsOffsetPlusSizeOverflowBeforeNative() {
        val volume = FatVolume(FatKind.FAT32, volumeHandle = 0L, blockHandle = 0L, ownsBlock = false)
        try {
            volume.write(
                "/x",
                ByteArray(2),
                offset = Long.MAX_VALUE,
                create = true,
                plannedTotalSize = Long.MAX_VALUE,
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("offset overflow: ${Long.MAX_VALUE} + 2", e.message)
        }
    }

    @Test
    fun readRejectsNegativeOffsetBeforeNative() {
        val volume = FatVolume(FatKind.FAT32, volumeHandle = 0L, blockHandle = 0L, ownsBlock = false)
        try {
            volume.read("/x", offset = -8L, length = 16)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("negative offset: -8", e.message)
        }
    }

    @Test
    fun readRejectsNegativeLengthBeforeNative() {
        val volume = FatVolume(FatKind.FAT32, volumeHandle = 0L, blockHandle = 0L, ownsBlock = false)
        try {
            volume.read("/x", offset = 0L, length = -1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("negative length: -1", e.message)
        }
    }

    @Test
    fun readRejectsOffsetPlusLengthOverflowBeforeNative() {
        val volume = FatVolume(FatKind.FAT32, volumeHandle = 0L, blockHandle = 0L, ownsBlock = false)
        try {
            volume.read("/x", offset = Long.MAX_VALUE, length = 2)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("offset overflow: ${Long.MAX_VALUE} + 2", e.message)
        }
    }
}
