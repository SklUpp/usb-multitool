package usb.image

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageStagingTest {
    private fun root(): File =
        Files.createTempDirectory("img-stage").toFile().also { it.deleteOnExit() }

    @Before
    fun clearRegistry() {
        ImageStaging.clearSessionRegistryForTest()
    }

    @Test
    fun createUniqueRegistersAndIsManaged() {
        val root = root()
        val a = ImageStaging.createUnique(root, "ubuntu.iso")
        assertTrue(a.isFile)
        assertEquals(0L, a.length())
        assertTrue(ImageStaging.isManaged(root, a.absolutePath))
        assertTrue(a.canonicalPath in ImageStaging.sessionOwnedPaths())
        val b = ImageStaging.createUnique(root, "ubuntu.iso")
        assertTrue(a.absolutePath != b.absolutePath)
    }

    @Test
    fun samePathNotManagedWithoutRegistry() {
        val root = root()
        val a = ImageStaging.createUnique(root, "a.img")
        a.writeText("data")
        val path = a.absolutePath
        assertTrue(ImageStaging.isManaged(root, path))
        ImageStaging.clearSessionRegistryForTest()
        // Name still looks like ours, but session registry empty → fail closed.
        assertFalse(ImageStaging.isManaged(root, path))
        assertFalse(ImageStaging.deleteIfManaged(root, path))
        assertTrue(a.exists())
    }

    @Test
    fun foreignUnderStagingNeverManaged() {
        val root = root()
        val dir = ImageStaging.stagingDir(root)
        val foreign = File(dir, "dropped-by-user.bin")
        foreign.writeText("foreign")
        assertFalse(ImageStaging.isManaged(root, foreign.absolutePath))
        assertFalse(ImageStaging.deleteIfManaged(root, foreign.absolutePath))
        assertTrue(foreign.exists())
    }

    @Test
    fun deleteUnregistersOwned() {
        val root = root()
        val staged = ImageStaging.createUnique(root, "a.img")
        staged.writeText("data")
        assertTrue(ImageStaging.deleteIfManaged(root, staged.absolutePath))
        assertFalse(staged.exists())
        assertFalse(ImageStaging.isManaged(root, staged.absolutePath))
        assertTrue(ImageStaging.deleteIfManaged(root, staged.absolutePath))
    }

    @Test
    fun createNewFileCollisionReturnsFalseOnExisting() {
        val root = root()
        val f = ImageStaging.createUnique(root, "c.img")
        assertTrue(f.exists())
        assertFalse(f.createNewFile())
    }

    @Test
    fun listStagingFilesDoesNotDelete() {
        val root = root()
        val owned = ImageStaging.createUnique(root, "x.img")
        val foreign = File(ImageStaging.stagingDir(root), "other.bin")
        foreign.writeText("z")
        val listed = ImageStaging.listStagingFiles(root)
        assertTrue(listed.any { it.absolutePath == owned.absolutePath })
        assertTrue(listed.any { it.absolutePath == foreign.absolutePath })
        assertTrue(owned.exists())
        assertTrue(foreign.exists())
    }

    @Test
    fun sanitizeStripsPathParts() {
        assertEquals("boot.iso", ImageStaging.sanitize("../x/boot.iso"))
        assertEquals("boot.iso", ImageStaging.sanitize("C:\\dir\\boot.iso"))
        assertFalse(ImageStaging.sanitize("a b.iso").contains(' '))
    }

    @Test
    fun spaceEstimateAllowsZeroNeed() {
        ImageStaging.requireUsableSpaceEstimate(root(), 0L)
    }
}
