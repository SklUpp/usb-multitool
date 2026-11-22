package usb.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveTest {
    private fun entry(
        name: String,
        directory: Boolean = false,
    ) = BackupArchive.Entry(name = name, uri = "content://doc/$name", isDirectory = directory)

    @Test
    fun exactlyOneImageAndMapIsAccepted() {
        val selection = BackupArchive.select(
            listOf(
                entry(BackupArchive.IMAGE_NAME),
                entry(BackupArchive.MAP_NAME),
                entry(".nomedia"),
                entry("nested", directory = true),
            ),
        )
        assertTrue(selection is BackupArchive.Selection.Ready)
        val pair = (selection as BackupArchive.Selection.Ready).pair
        assertEquals(BackupArchive.IMAGE_NAME, pair.image.name)
        assertEquals(BackupArchive.MAP_NAME, pair.map.name)
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        val selection = BackupArchive.select(listOf(entry("DISK.IMG"), entry("DISK.MAP")))
        assertTrue(selection is BackupArchive.Selection.Ready)
    }

    @Test
    fun canonicalComesBeforeGenerationsNewestFirst() {
        val selection = BackupArchive.select(
            listOf(
                entry("disk.img"),
                entry("disk.map.1"),
                entry("disk.map"),
                entry("disk.map.3"),
                entry("disk.map.2"),
            ),
        )
        val pair = (selection as BackupArchive.Selection.Ready).pair
        assertEquals(
            listOf("disk.map", "disk.map.3", "disk.map.2", "disk.map.1"),
            pair.maps.map { it.name },
        )
    }

    @Test
    fun generationsWithoutCanonicalAreAccepted() {
        val selection = BackupArchive.select(listOf(entry("disk.img"), entry("disk.map.7")))
        val pair = (selection as BackupArchive.Selection.Ready).pair
        assertEquals(listOf("disk.map.7"), pair.maps.map { it.name })
    }

    @Test
    fun foreignMapStemIsNotPaired() {
        val selection = BackupArchive.select(listOf(entry("disk.img"), entry("other.map")))
        assertEquals(
            BackupArchive.Selection.Invalid(BackupArchive.Problem.MissingMap, listOf("disk.img")),
            selection,
        )
    }

    @Test
    fun emptyFolderIsRejected() {
        val selection = BackupArchive.select(listOf(entry("nested", directory = true)))
        assertEquals(
            BackupArchive.Selection.Invalid(BackupArchive.Problem.Empty),
            selection,
        )
    }

    @Test
    fun imageWithoutMapIsRejected() {
        val selection = BackupArchive.select(listOf(entry("disk.img")))
        assertEquals(
            BackupArchive.Selection.Invalid(BackupArchive.Problem.MissingMap, listOf("disk.img")),
            selection,
        )
    }

    @Test
    fun mapWithoutImageIsRejected() {
        val selection = BackupArchive.select(listOf(entry("disk.map")))
        assertEquals(
            BackupArchive.Selection.Invalid(BackupArchive.Problem.MissingImage),
            selection,
        )
    }

    @Test
    fun multipleImagesAreRejectedWithNames() {
        val selection = BackupArchive.select(
            listOf(entry("a.img"), entry("b.img"), entry("disk.map")),
        )
        assertEquals(
            BackupArchive.Selection.Invalid(
                BackupArchive.Problem.MultipleImages,
                listOf("a.img", "b.img"),
            ),
            selection,
        )
    }

    @Test
    fun unexpectedFileRejectsTheWholeFolder() {
        val selection = BackupArchive.select(
            listOf(entry("disk.img"), entry("disk.map"), entry("gpt-critical-1.bin")),
        )
        assertEquals(
            BackupArchive.Selection.Invalid(
                BackupArchive.Problem.UnexpectedFiles,
                listOf("gpt-critical-1.bin"),
            ),
            selection,
        )
    }

    @Test
    fun unknownMapSuffixIsUnexpected() {
        val selection = BackupArchive.select(
            listOf(entry("disk.img"), entry("disk.map"), entry("disk.map.tmp")),
        )
        assertEquals(
            BackupArchive.Selection.Invalid(
                BackupArchive.Problem.UnexpectedFiles,
                listOf("disk.map.tmp"),
            ),
            selection,
        )
    }

    @Test
    fun expectedImageBytesHasOverflowGuard() {
        assertEquals(1024UL, BackupArchive.expectedImageBytes(2UL, 512))
        assertNull(BackupArchive.expectedImageBytes(ULong.MAX_VALUE, 512))
        assertNull(BackupArchive.expectedImageBytes(2UL, null))
    }
}
