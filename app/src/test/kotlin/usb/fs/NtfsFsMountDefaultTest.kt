package usb.fs

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.EngineMissingException
import usb.device.BlockDevice

/** NtfsFs.mount defaults readOnly=true and must not write during default mount. */
class NtfsFsMountDefaultTest {
    private class MemDev : BlockDevice {
        private val data = ByteArray(4096)
        override val sectorSize: Int = 512
        override val sectorCount: ULong = 8UL
        override fun read(lba: ULong, destination: ByteBuffer) {
            destination.put(data, (lba * sectorSize.toULong()).toInt(), destination.remaining())
        }
        var writeCount = 0
            private set
        override fun write(lba: ULong, source: ByteBuffer) {
            writeCount++
        }
        override fun write(lba: Long, source: ByteBuffer) {
            writeCount++
        }
        override fun sync() = Unit
    }

    @Test
    fun defaultMountDoesNotWriteEvenWhenNativeAbsent() {
        val dev = MemDev()
        try {
            NtfsFs.mount(dev) // omit readOnly → default true
        } catch (_: NtfsException) {
            // native present but image invalid / dirty — OK
        } catch (_: EngineMissingException) {
            // native ntfs-3g engine not linked / host JVM — OK
        } catch (_: UnsatisfiedLinkError) {
            // host JVM — OK
        }
        assertEquals(0, dev.writeCount)
    }

    @Test
    fun mapNtfsStatOrNullMissOnlyAbsence() {
        org.junit.Assert.assertNull(mapNtfsStatOrNullMiss("/x", 2))
        try {
            mapNtfsStatOrNullMiss("/x", 5)
            fail("expected throw")
        } catch (e: NtfsException) {
            assertTrue(e.message!!.contains("errno=5"))
        }
    }
}
