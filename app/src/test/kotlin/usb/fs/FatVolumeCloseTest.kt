package usb.fs

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Host JVM tests for the FAT teardown order (no native library). */
class FatVolumeCloseTest {
    @Test
    fun closesBothStepsInOrder() {
        val calls = mutableListOf<String>()
        closeVolumeResources(
            umount = { calls += "umount" },
            closeBlock = { calls += "block" },
        )
        assertEquals(listOf("umount", "block"), calls)
    }

    @Test
    fun umountFailureStillReleasesBlockAndKeepsFirstError() {
        val umountErr = FatFsException("umount failed: 1")
        val calls = mutableListOf<String>()
        try {
            closeVolumeResources(
                umount = { calls += "umount"; throw umountErr },
                closeBlock = { calls += "block" },
            )
            fail("expected FatFsException")
        } catch (e: FatFsException) {
            assertSame(umountErr, e)
        }
        assertEquals(listOf("umount", "block"), calls)
    }

    @Test
    fun blockFailureSuppressedOntoUmountFailure() {
        val umountErr = FatFsException("umount failed: 1")
        val blockErr = IllegalStateException("block close failed")
        try {
            closeVolumeResources(
                umount = { throw umountErr },
                closeBlock = { throw blockErr },
            )
            fail("expected FatFsException")
        } catch (e: FatFsException) {
            assertSame(umountErr, e)
            assertEquals(1, e.suppressed.size)
            assertSame(blockErr, e.suppressed[0])
        }
    }

    @Test
    fun blockFailureSurfacesWhenUmountSucceeds() {
        val blockErr = IOException("block close failed")
        try {
            closeVolumeResources(umount = {}, closeBlock = { throw blockErr })
            fail("expected IOException")
        } catch (e: IOException) {
            assertSame(blockErr, e)
            assertTrue(e.suppressed.isEmpty())
        }
    }
}
