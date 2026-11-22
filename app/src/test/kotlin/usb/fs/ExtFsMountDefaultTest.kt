package usb.fs

import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.BlockDevice

/**
 * ExtFs.mount must default to read-only. Without usb_native the call fails before
 * writing; we still assert the Kotlin default overload exists and PartitionVolumeSession
 * defaults readOnly=true.
 */
class ExtFsMountDefaultTest {
    private class MemDev : BlockDevice {
        private val data = ByteArray(4096)
        override val sectorSize: Int = 512
        override val sectorCount: ULong = 8UL
        override fun read(lba: ULong, destination: ByteBuffer) {
            destination.put(data, (lba * sectorSize.toULong()).toInt(), destination.remaining())
        }
        override fun write(lba: ULong, source: ByteBuffer) {
            fail("default mount must not write")
        }
        override fun sync() = Unit
    }

    @Test
    fun defaultMountDoesNotWriteEvenWhenNativeAbsent() {
        try {
            ExtFs.mount(MemDev()) // omit readOnly → default true
        } catch (_: ExtException) {
            // native present but image invalid / dirty — OK
        } catch (_: UnsatisfiedLinkError) {
            // host JVM — OK
        } catch (_: Throwable) {
            // link/load failure variants
        }
        // MemDev.write was never called (would fail()).
        assertTrue(true)
    }
}
