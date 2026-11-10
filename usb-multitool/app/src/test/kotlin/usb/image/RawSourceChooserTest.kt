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
        assertEquals(3, listing.candidates.size)
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
    fun respectsMaxDepth() {
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/a/b/deep.bin" to byteArrayOf(1),
                    "/a/top.bin" to byteArrayOf(2),
                ),
            )
        val listing = RawSourceChooser.list(volume, "/", maxEntries = 10, maxDepth = 1)
        assertEquals(listOf("/a/top.bin"), listing.candidates.map { it.path })
    }
}
