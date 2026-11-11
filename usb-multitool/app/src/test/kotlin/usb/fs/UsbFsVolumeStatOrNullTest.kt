package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Behavioral checks for the production [UsbFsVolume.statOrNull] mappers.
 * These call the same [mapFatStatOrNullFailure] / [mapExtStatOrNullMiss] used by
 * FatVolume / ExtVolume — not a fake volume that echoes a canned map.
 */
class UsbFsVolumeStatOrNullTest {
    @Test
    fun fatAbsentFrNoFileIsNull() {
        val failure = FatFsException("stat failed: $FR_NO_FILE")
        assertNull(mapFatStatOrNullFailure("/missing.bin", failure))
    }

    @Test
    fun fatAbsentFrNoPathIsNull() {
        // FR_NO_PATH is the same "does not exist" contract for a missing parent directory.
        val failure = FatFsException("stat failed: $FR_NO_PATH")
        assertNull(mapFatStatOrNullFailure("/missing/dir/file.bin", failure))
    }

    @Test
    fun fatIoFaultDoesNotLookAbsent() {
        val failure = FatFsException("stat failed: 1") // FR_DISK_ERR
        try {
            mapFatStatOrNullFailure("/maybe.bin", failure)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("/maybe.bin"))
            assertTrue(e.cause === failure)
        }
    }

    @Test
    fun fatDeniedIsNotAbsence() {
        val failure = FatFsException("stat failed: $FR_DENIED")
        try {
            mapFatStatOrNullFailure("/locked.bin", failure)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.cause is FatFsException)
        }
    }

    @Test
    fun extEnoentIsNull() {
        assertNull(mapExtStatOrNullMiss("/gone.txt", -2))
    }

    @Test
    fun extPositiveTwoIsNotEnoent() {
        // Positive 2 must not be treated as absence (engine returns negative errno).
        try {
            mapExtStatOrNullMiss("/x", 2)
            fail("expected ExtException")
        } catch (e: ExtException) {
            assertTrue(e.message!!.contains("errno=2"))
        }
    }

    @Test
    fun extIoErrnoThrows() {
        try {
            mapExtStatOrNullMiss("/io.txt", -5) // EIO
            fail("expected ExtException")
        } catch (e: ExtException) {
            assertEquals("stat failed for /io.txt (errno=-5)", e.message)
        }
    }

    @Test
    fun fatHostTextMentioningAbsenceIsNotAbsence() {
        // Absence is the exact FR_ code; host text like "ENOENT" or "No such file" must
        // never be sniffed for cleanup/stat decisions (that false-positive class was removed).
        val misleading =
            listOf(
                "No such file or directory",
                "ENOENT",
                "delete failed: $FR_NO_FILE",
                "stat failed: $FR_NO_FILE (ENOENT)",
            )
        for (msg in misleading) {
            try {
                mapFatStatOrNullFailure("/maybe.bin", FatFsException(msg))
                fail("expected IllegalStateException for \"$msg\"")
            } catch (e: IllegalStateException) {
                assertTrue(e.cause is FatFsException)
            }
        }
    }

    @Test
    fun fatNonemptyDeleteMapsToDirectoryNotEmpty() {
        val failure = FatFsException("delete failed: $FR_DENIED")
        try {
            mapFatDeleteFailure("/data", failure)
            fail("expected FatFsException")
        } catch (e: FatFsException) {
            assertEquals("Directory not empty: /data", e.message)
        }
    }

    @Test
    fun fatOtherDeletePropagates() {
        val failure = FatFsException("delete failed: $FR_NO_FILE")
        try {
            mapFatDeleteFailure("/gone", failure)
            fail("expected FatFsException")
        } catch (e: FatFsException) {
            assertEquals("delete failed: $FR_NO_FILE", e.message)
        }
    }
}
