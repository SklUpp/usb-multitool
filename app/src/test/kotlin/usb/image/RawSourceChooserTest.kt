package usb.image

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.testutil.InMemoryUsbVolume

class RawSourceChooserTest {
    @Test
    fun listsEveryRegularFileWithoutExtensionFilter() {
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/disk.img" to byteArrayOf(1),
                    "/photo.jpg" to byteArrayOf(2, 3),
                    "/noext" to byteArrayOf(4, 5, 6),
                    "/nested/archive.bin" to byteArrayOf(7),
                    "/nested/README" to ByteArray(0),
                ),
            )
        val listing = RawSourceChooser.list(volume, "/", maxEntries = 50, maxDepth = 3)
        assertFalse(listing.truncated)
        assertEquals(
            listOf("/disk.img", "/nested/README", "/nested/archive.bin", "/noext", "/photo.jpg"),
            listing.candidates.map { it.path },
        )
        assertEquals(2L, listing.candidates.first { it.name == "photo.jpg" }.sizeBytes)
    }

    @Test
    fun truncatesAtTheConfiguredEntryLimit() {
        val volume = InMemoryUsbVolume((1..10).associate { "/f$it.bin" to byteArrayOf(it.toByte()) })
        val listing = RawSourceChooser.list(volume, "/", maxEntries = 3, maxDepth = 0)
        assertTrue(listing.truncated)
        // /ISO is a directory entry and now counts toward the same bound.
        assertEquals(2, listing.candidates.size)
    }

    @Test
    fun unreadableSubdirectoryDoesNotHideOtherFiles() {
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/good/one" to byteArrayOf(1),
                    "/bad/hidden" to byteArrayOf(2),
                ),
            )
        volume.unreadableDirectories = setOf("/bad")
        val listing = RawSourceChooser.list(volume, "/", maxEntries = 10, maxDepth = 3)
        assertEquals(listOf("/good/one"), listing.candidates.map { it.path })
        assertEquals(1, listing.unreadable.size)
        assertTrue(listing.unreadable.single().startsWith("/bad"))
    }

    @Test
    fun cancellationStopsBeforeTouchingMoreEntries() {
        val volume = InMemoryUsbVolume((1..5).associate { "/f$it" to byteArrayOf(it.toByte()) })
        var active = true
        val failure =
            assertThrows(CancellationException::class.java) {
                RawSourceChooser.list(volume, "/", isActive = { active.also { active = false } })
            }
        assertTrue(failure.message!!.contains("cancelled"))
    }

    @Test
    fun respectsMaxDepthAndReportsTheOmission() {
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/a/b/deep.bin" to byteArrayOf(1),
                    "/a/top.bin" to byteArrayOf(2),
                    "/b/ok.bin" to byteArrayOf(3),
                ),
            )
        val listing = RawSourceChooser.list(volume, "/", maxEntries = 10, maxDepth = 1)
        // A depth omission under /a must not suppress the eligible sibling /b.
        assertEquals(listOf("/a/top.bin", "/b/ok.bin"), listing.candidates.map { it.path })
        assertTrue("a depth-limited listing must not look complete", listing.truncated)
    }

    @Test
    fun directoriesCountTowardTheEntryLimit() {
        val volume =
            InMemoryUsbVolume(
                (1..10).associate { "/dir$it/file.bin" to byteArrayOf(it.toByte()) },
            )
        val listing = RawSourceChooser.list(volume, "/", maxEntries = 4, maxDepth = 3)
        assertTrue(listing.truncated)
        assertTrue("directory traversal exceeded the bound", listing.candidates.size <= 2)
    }
}
