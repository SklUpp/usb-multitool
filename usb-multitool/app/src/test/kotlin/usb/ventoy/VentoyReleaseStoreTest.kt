package usb.ventoy

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-private persistence against the real pinned release shipped in `assets/ventoy`:
 * save/readback, independent-pin enforcement and crash windows. Synthetic bytes cannot
 * pass these tests because a self-consistent `sha256.txt` is not a trust anchor.
 */
class VentoyReleaseStoreTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun assetDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val current = dir ?: return@repeat
            for (candidate in listOf(
                File(current, "usb-multitool/app/src/main/assets/ventoy"),
                File(current, "app/src/main/assets/ventoy"),
            )) {
                if (candidate.isDirectory) return candidate
            }
            dir = current.parentFile
        }
        throw AssertionError("cannot locate assets/ventoy from ${File("").absolutePath}")
    }

    private fun realDownload(): VentoyDownloader.VentoyReleaseDownload {
        val assets = VentoyAssets.loadFromDirectory(assetDir(), strict = true)
        val source = VentoyDownloader.pinnedSourceOrNull(assets.release)
            ?: throw AssertionError("bundled release ${assets.release} has no independent pin")
        return VentoyDownloader.VentoyReleaseDownload(
            assets = assets,
            archiveSha256 = source.archiveSha256,
            sourceUrl = source.urls.first(),
        )
    }

    private fun filesDir(): File = Files.createTempDirectory("ventoy-store").toFile()

    private fun copyDir(from: File, to: File) {
        to.mkdirs()
        from.listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) copyDir(child, File(to, child.name))
            else child.copyTo(File(to, child.name), overwrite = true)
        }
    }

    @Test
    fun savedReleaseIsReadBackAndSurvivesTheNextLoad() {
        val root = filesDir()
        val download = realDownload()
        val first = VentoyReleaseStore.save(root, download)
        assertEquals(VentoyAssets.PINNED_RELEASE, first.assets.release)
        assertEquals(download.archiveSha256, first.archiveSha256)

        val loaded = requireNotNull(VentoyReleaseStore.load(root))
        assertEquals(first.assets.release, loaded.assets.release)
        assertEquals(first.sourceUrl, loaded.sourceUrl)
        assertArrayEquals(first.assets.coreImg, loaded.assets.coreImg)
    }

    @Test
    fun secondSaveReplacesThePreviousCurrentSafely() {
        val root = filesDir()
        VentoyReleaseStore.save(root, realDownload())
        VentoyReleaseStore.save(root, realDownload())
        val loaded = VentoyReleaseStore.load(root)
        assertEquals(VentoyAssets.PINNED_RELEASE, loaded?.assets?.release)
        assertNull(File(root, VentoyReleaseStore.BACKUP).takeIf { it.exists() })
    }

    @Test
    fun tamperedMemberIsRejectedInsteadOfLoaded() {
        val root = filesDir()
        VentoyReleaseStore.save(root, realDownload())
        File(root, "${VentoyReleaseStore.DIR}/core.img.xz").writeBytes(ByteArray(96) { 7 })
        assertNull(VentoyReleaseStore.load(root))
    }

    @Test
    fun selfConsistentShaFileDoesNotOverrideTheIndependentPin() {
        val root = filesDir()
        VentoyReleaseStore.save(root, realDownload())
        val dir = File(root, VentoyReleaseStore.DIR)
        val boot = File(dir, "boot.img")
        val tampered = boot.readBytes().also { it[10] = (it[10].toInt() xor 0x7F).toByte() }
        boot.writeBytes(tampered)
        val shaFile = File(dir, "sha256.txt")
        shaFile.writeText(
            shaFile.readLines().joinToString("\n") { line ->
                when {
                    line.endsWith("boot.img") -> "${sha256(tampered)} *boot.img"
                    else -> line
                }
            } + "\n",
        )
        // The directory is internally consistent, but no longer matches the pinned digest.
        assertNull(VentoyReleaseStore.load(root))
    }

    @Test
    fun manifestArchiveDigestMustBeTheIndependentPin() {
        val root = filesDir()
        VentoyReleaseStore.save(root, realDownload())
        val manifest = File(root, "${VentoyReleaseStore.DIR}/release.properties")
        manifest.writeText(
            manifest.readText().replace(
                Regex("archiveSha256=.*"),
                "archiveSha256=${"0".repeat(64)}",
            ),
        )
        assertNull(VentoyReleaseStore.load(root))
    }

    @Test
    fun verifiedStagingFromAnInterruptedPublicationIsRecovered() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        val recovered = VentoyReleaseStore.load(root)
        assertEquals(VentoyAssets.PINNED_RELEASE, recovered?.assets?.release)
    }

    @Test
    fun stagingDirectoryAndParentAreFsynced() {
        val root = filesDir()
        val synced = mutableListOf<String>()
        VentoyReleaseStore.save(
            root,
            realDownload(),
            syncDirectory = { dir -> synced += dir.name },
        )
        assertTrue("staging entries must be fsynced", synced.contains(VentoyReleaseStore.STAGING))
        assertTrue(
            "parent directory must be fsynced around staging/publish",
            synced.count { it == root.name } >= 2,
        )
    }

    @Test
    fun verifiedStagingSurvivesAFailedReplacementWrite() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        val bad = VentoyDownloader.VentoyReleaseDownload(
            assets = syntheticVentoyAssets(release = VentoyAssets.PINNED_RELEASE),
            archiveSha256 = "0".repeat(64),
            sourceUrl = "https://example.invalid/ventoy.tar.gz",
        )
        val failure = assertThrows(VentoyReleaseStore.StoreChangedException::class.java) {
            VentoyReleaseStore.save(root, bad)
        }
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.stored.assets.release)
        val survived = VentoyReleaseStore.load(root)
        assertEquals(VentoyAssets.PINNED_RELEASE, survived?.assets?.release)
    }

    @Test
    fun postPublishCleanupFailureStillReturnsThePublishedRelease() {
        val root = filesDir()
        VentoyReleaseStore.save(root, realDownload())
        var published = false
        var postPublishSyncs = 0
        val stored = VentoyReleaseStore.save(
            root,
            realDownload(),
            rename = { from, to ->
                val moved = from.renameTo(to)
                if (moved &&
                    from.name == VentoyReleaseStore.STAGING &&
                    to.name == VentoyReleaseStore.DIR
                ) {
                    published = true
                }
                moved
            },
            syncDirectory = {
                if (published) {
                    postPublishSyncs++
                    if (postPublishSyncs > 1) throw IOException("cleanup fsync failed")
                }
            },
        )
        assertEquals(VentoyAssets.PINNED_RELEASE, stored.assets.release)
        assertEquals(VentoyAssets.PINNED_RELEASE, VentoyReleaseStore.load(root)?.assets?.release)
    }

    @Test
    fun publishFsyncFailureReportsPublishedButUnconfirmedAndSurvivesRestart() {
        val root = filesDir()
        VentoyReleaseStore.save(root, realDownload())
        var published = false
        val failure = assertThrows(
            VentoyReleaseStore.PublishedButUnconfirmedException::class.java,
        ) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                rename = { from, to ->
                    val moved = from.renameTo(to)
                    if (moved &&
                        from.name == VentoyReleaseStore.STAGING &&
                        to.name == VentoyReleaseStore.DIR
                    ) {
                        published = true
                    }
                    moved
                },
                syncDirectory = {
                    if (published) throw IOException("publish fsync failed")
                },
            )
        }
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.stored.assets.release)
        val reloaded = VentoyReleaseStore.load(root)
        assertEquals(VentoyAssets.PINNED_RELEASE, reloaded?.assets?.release)
        assertEquals(failure.stored.archiveSha256, reloaded?.archiveSha256)
    }

    @Test
    fun promotionFsyncFailureReportsTheRecoveredRelease() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        var promoted = false
        val requested = VentoyDownloader.VentoyReleaseDownload(
            assets = syntheticVentoyAssets(release = "1.1.05"),
            archiveSha256 = "0".repeat(64),
            sourceUrl = "https://example.invalid/ventoy-1.1.05.tar.gz",
        )
        val failure = assertThrows(
            VentoyReleaseStore.PromotionButUnconfirmedException::class.java,
        ) {
            VentoyReleaseStore.save(
                root,
                requested,
                rename = { from, to ->
                    val moved = from.renameTo(to)
                    if (moved &&
                        from.name == VentoyReleaseStore.STAGING &&
                        to.name == VentoyReleaseStore.DIR
                    ) {
                        promoted = true
                    }
                    moved
                },
                syncDirectory = {
                    if (promoted) throw IOException("promotion fsync failed")
                },
            )
        }
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.stored.assets.release)
        assertEquals("1.1.05", failure.requestedRelease)
        val reloaded = VentoyReleaseStore.load(root)
        assertEquals(VentoyAssets.PINNED_RELEASE, reloaded?.assets?.release)
    }

    @Test
    fun postPromotionSyncFailureKeepsTheRecoveredRelease() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        var promoted = false
        var promotedSyncs = 0
        val failure = assertThrows(
            VentoyReleaseStore.StoreChangedException::class.java,
        ) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                rename = { from, to ->
                    val moved = from.renameTo(to)
                    if (moved &&
                        from.name == VentoyReleaseStore.STAGING &&
                        to.name == VentoyReleaseStore.DIR
                    ) {
                        promoted = true
                    }
                    moved
                },
                syncDirectory = {
                    if (promoted && ++promotedSyncs > 1) {
                        throw IOException("post-promotion fsync failed")
                    }
                },
            )
        }
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.stored.assets.release)
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.requestedRelease)
        assertEquals(VentoyAssets.PINNED_RELEASE, VentoyReleaseStore.load(root)?.assets?.release)
    }

    @Test
    fun postPromotionMkdirFailureKeepsTheRecoveredRelease() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        var createCalls = 0
        val failure = assertThrows(
            VentoyReleaseStore.StoreChangedException::class.java,
        ) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                createDirectory = {
                    createCalls++
                    false
                },
            )
        }
        assertEquals(1, createCalls)
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.stored.assets.release)
        assertEquals(VentoyAssets.PINNED_RELEASE, VentoyReleaseStore.load(root)?.assets?.release)
    }

    @Test
    fun postPromotionVerifyFailureReportsRecoveredAndRequestedSeparately() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        val requested = VentoyDownloader.VentoyReleaseDownload(
            assets = syntheticVentoyAssets(release = "1.1.05"),
            archiveSha256 = "0".repeat(64),
            sourceUrl = "https://example.invalid/ventoy-1.1.05.tar.gz",
        )
        val failure = assertThrows(
            VentoyReleaseStore.StoreChangedException::class.java,
        ) {
            VentoyReleaseStore.save(root, requested)
        }
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.stored.assets.release)
        assertEquals("1.1.05", failure.requestedRelease)
        val reloaded = VentoyReleaseStore.load(root)
        assertEquals(VentoyAssets.PINNED_RELEASE, reloaded?.assets?.release)
    }

    @Test
    fun postPromotionReplacementRenameFailureKeepsTheRecoveredRelease() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        val failure = assertThrows(
            VentoyReleaseStore.StoreChangedException::class.java,
        ) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                rename = { from, to ->
                    if (from.name == VentoyReleaseStore.DIR &&
                        to.name == VentoyReleaseStore.BACKUP
                    ) {
                        false
                    } else {
                        from.renameTo(to)
                    }
                },
            )
        }
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.stored.assets.release)
        assertEquals(VentoyAssets.PINNED_RELEASE, VentoyReleaseStore.load(root)?.assets?.release)
    }

    @Test
    fun inspectDistinguishesAbsentCorruptAndVerified() {
        val root = filesDir()
        assertEquals(
            VentoyReleaseStore.Inspection.Absent,
            VentoyReleaseStore.inspect(root),
        )
        VentoyReleaseStore.save(root, realDownload())
        assertEquals(
            VentoyReleaseStore.Inspection.Verified::class.java,
            VentoyReleaseStore.inspect(root).javaClass,
        )
        File(root, "${VentoyReleaseStore.DIR}/core.img.xz").writeBytes(ByteArray(96) { 7 })
        assertEquals(
            VentoyReleaseStore.Inspection.Corrupt,
            VentoyReleaseStore.inspect(root),
        )
    }

    @Test
    fun inspectFailsClosedForNonDirectoryUnreadableAndErrorRoots() {
        val missing = File(filesDir(), "missing")
        assertEquals(
            VentoyReleaseStore.Inspection.Absent,
            VentoyReleaseStore.inspect(missing),
        )

        val fileRoot = File(filesDir(), "store-root").apply { writeText("not a directory") }
        assertEquals(
            VentoyReleaseStore.Inspection.Corrupt,
            VentoyReleaseStore.inspect(fileRoot),
        )

        val emptyRoot = filesDir()
        assertEquals(
            VentoyReleaseStore.Inspection.Unknown,
            VentoyReleaseStore.inspect(emptyRoot, listDirectory = { null }),
        )
        assertEquals(
            VentoyReleaseStore.Inspection.Unknown,
            VentoyReleaseStore.inspect(
                emptyRoot,
                listDirectory = { throw IOException("permission denied") },
            ),
        )

        val parent = filesDir()
        val missingUnderParent = File(parent, "missing")
        assertEquals(
            VentoyReleaseStore.Inspection.Unknown,
            VentoyReleaseStore.inspect(
                missingUnderParent,
                listDirectory = { dir -> if (dir == parent) null else dir.listFiles() },
            ),
        )
        assertEquals(
            VentoyReleaseStore.Inspection.Unknown,
            VentoyReleaseStore.inspect(
                missingUnderParent,
                listDirectory = { arrayOf(missingUnderParent) },
            ),
        )
    }

    @Test
    fun inspectProvesAbsenceAcrossSeveralMissingAncestors() {
        val root = filesDir()
        val deepMissing = File(root, "a/b/c/ventoy-release-store")
        assertEquals(
            VentoyReleaseStore.Inspection.Absent,
            VentoyReleaseStore.inspect(deepMissing),
        )
    }

    @Test
    fun inspectPropagatesFatalFromParentListing() {
        val parent = filesDir()
        val missing = File(parent, "missing")
        val cancellation = java.util.concurrent.CancellationException("cancel")
        val thrown = assertThrows(java.util.concurrent.CancellationException::class.java) {
            VentoyReleaseStore.inspect(
                missing,
                listDirectory = { throw IOException("wrapped", cancellation) },
            )
        }
        assertTrue(thrown === cancellation)
    }

    @Test
    fun inspectTreatsListedInvalidCandidateAsCorrupt() {
        val root = filesDir()
        File(root, VentoyReleaseStore.DIR).writeText("not a directory")
        assertEquals(
            VentoyReleaseStore.Inspection.Corrupt,
            VentoyReleaseStore.inspect(root),
        )
    }

    @Test
    fun mutationWithoutPromotionReportsUnknownWhenReadbackFails() {
        val root = filesDir()
        val failure = assertThrows(VentoyReleaseStore.StoreUnknownException::class.java) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                rename = { from, to ->
                    if (from.name == VentoyReleaseStore.STAGING &&
                        to.name == VentoyReleaseStore.DIR
                    ) {
                        from.deleteRecursively()
                        false
                    } else {
                        from.renameTo(to)
                    }
                },
            )
        }
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.requestedRelease)
        assertNull(VentoyReleaseStore.load(root))
    }

    @Test
    fun fatalInPostPublishCleanupIsNotSwallowed() {
        val root = filesDir()
        VentoyReleaseStore.save(root, realDownload())
        var published = false
        var postPublishSyncs = 0
        val fatal = OutOfMemoryError("cleanup")
        val thrown = assertThrows(OutOfMemoryError::class.java) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                rename = { from, to ->
                    val moved = from.renameTo(to)
                    if (moved &&
                        from.name == VentoyReleaseStore.STAGING &&
                        to.name == VentoyReleaseStore.DIR
                    ) {
                        published = true
                    }
                    moved
                },
                syncDirectory = {
                    if (published && ++postPublishSyncs > 1) throw fatal
                },
            )
        }
        assertTrue(thrown === fatal)
    }

    @Test
    fun fatalCauseTraversalIsCycleSafeAndKeepsErrorsFatal() {
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        first.initCause(second)
        second.initCause(first)
        assertNull(VentoyReleaseStore.fatalCauseOrNull(first))
        assertTrue(
            VentoyReleaseStore.fatalCauseOrNull(OutOfMemoryError("oom")) is OutOfMemoryError,
        )
    }

    @Test
    fun mutationThenUnreadableStoreReportsUnknown() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        val failure = assertThrows(
            VentoyReleaseStore.StoreUnknownException::class.java,
        ) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                createDirectory = {
                    // Promotion already made this readable; lose it before reporting.
                    File(root, VentoyReleaseStore.DIR).deleteRecursively()
                    false
                },
            )
        }
        assertEquals(VentoyAssets.PINNED_RELEASE, failure.requestedRelease)
        assertNull(VentoyReleaseStore.load(root))
    }

    @Test
    fun fatalOrCancellationIsNeverConvertedToStoreChanged() {
        val root = filesDir()
        val other = filesDir()
        VentoyReleaseStore.save(other, realDownload())
        copyDir(
            File(other, VentoyReleaseStore.DIR),
            File(root, VentoyReleaseStore.STAGING),
        )
        val cancellation = java.util.concurrent.CancellationException("cancelled")
        val thrown = assertThrows(java.util.concurrent.CancellationException::class.java) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                syncDirectory = { throw IOException("wrapped", cancellation) },
            )
        }
        assertTrue(thrown === cancellation)
    }

    @Test
    fun failedPublicationDoesNotDeleteTheOnlyBackup() {
        val root = filesDir()
        VentoyReleaseStore.save(root, realDownload())
        val current = File(root, VentoyReleaseStore.DIR)
        val backup = File(root, VentoyReleaseStore.BACKUP)
        current.renameTo(backup)
        assertNotNull(VentoyReleaseStore.load(root))
        val synced = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            VentoyReleaseStore.save(
                root,
                realDownload(),
                rename = { from, to ->
                    if (from.name == VentoyReleaseStore.STAGING &&
                        to.name == VentoyReleaseStore.DIR
                    ) {
                        false
                    } else {
                        from.renameTo(to)
                    }
                },
                syncDirectory = { dir -> synced += dir.name },
            )
        }
        // mkdir parent + staging entries + restored backup→current parent.
        assertTrue(
            "backup recovery must fsync the parent again",
            synced.count { it == root.name } >= 2,
        )
        val survived = VentoyReleaseStore.load(root)
        assertEquals(VentoyAssets.PINNED_RELEASE, survived?.assets?.release)
        assertTrue(current.isDirectory || backup.isDirectory)
    }
}
