package usb.image

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.testutil.RecordingDisk

class RawWriterTest {
    @Test fun compressedSourceWritesDecodedBytesAndStopsAtCapacity() {
        val source = ImageSource.fromBytes(gzip(ByteArray(6000) { 7 }))
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        try {
            RawWriter.write(source, disk)
            fail("expected ImageTooLargeException")
        } catch (_: ImageTooLargeException) {
        }
        assertEquals(0, disk.bytesWritten)
    }

    @Test fun rawImageWritesExactSectors() {
        val payload = ByteArray(1024) { 3 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 1024L)
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        RawWriter.write(source, disk)
        assertEquals(1024, disk.bytesWritten)
        assertTrue(disk.bytes().copyOf(1024).contentEquals(payload))
    }

    @Test fun partialFinalSectorIsPadded() {
        val payload = ByteArray(100) { 9 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = 100L)
        val disk = RecordingDisk(sectorCount = 2UL, sectorSize = 512)
        RawWriter.write(source, disk)
        assertEquals(512, disk.bytesWritten)
        assertEquals(9, disk.bytes()[0].toInt())
        assertEquals(0, disk.bytes()[100].toInt())
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
