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
}
