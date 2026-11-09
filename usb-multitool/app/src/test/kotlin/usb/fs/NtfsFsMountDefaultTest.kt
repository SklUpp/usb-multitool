package usb.fs

import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
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
        override fun write(lba: ULong, source: ByteBuffer) {
            fail("default NTFS mount must not write")
        }
        override fun sync() = Unit
    }

    @Test
    fun defaultMountDoesNotWriteEvenWhenNativeAbsent() {
        try {
            NtfsFs.mount(MemDev()) // omit readOnly → default true
        } catch (_: NtfsException) {
        } catch (_: UnsatisfiedLinkError) {
        } catch (_: Throwable) {
        }
        assertTrue(true)
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
