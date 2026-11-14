package usb.files

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.saf.SafDocument
import usb.ui.FilesConflict
import usb.ui.FilesConflictDecision

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FilesOperationsBatchTest {
    @Test
    fun deleteRecursivelyRemovesNestedTreeAndSyncsOnce() {
        val volume = MemoryVolume()
        volume.mkdir("/docs")
        volume.write("/docs/a.bin", byteArrayOf(1), create = true)
        volume.mkdir("/docs/nested")
        volume.write("/docs/nested/b.bin", byteArrayOf(2, 3), create = true)
        volume.events.clear()
        val op = DiskOperation("delete-tree")
        FilesOperations.deleteRecursively(volume, "/docs", op)
        assertNull(volume.statOrNull("/docs"))
        assertTrue(op.mutationAttempted)
        assertEquals(1, volume.events.count { it == "sync" })
        assertEquals(
            listOf("delete:/docs/a.bin", "delete:/docs/nested/b.bin", "delete:/docs/nested", "delete:/docs", "sync"),
            volume.events,
        )
    }

    @Test
    fun deleteRecursivelyCancelBeforeMutationStaysClean() {
        val volume = MemoryVolume()
        volume.mkdir("/docs")
        volume.write("/docs/a.bin", byteArrayOf(1), create = true)
        volume.events.clear()
        val op = DiskOperation("delete-cancel", active = { false })
        assertThrows(CancellationException::class.java) {
            FilesOperations.deleteRecursively(volume, "/docs", op)
        }
        assertFalse(op.mutationAttempted)
        assertEquals(OperationState.Queued, op.state)
        assertEquals(UsbFsDirEntry("a.bin", false, 1), volume.stat("/docs/a.bin"))
        assertTrue(volume.events.none { it.startsWith("delete:") })
    }

    @Test
    fun downloadTreePreservesNestedAndEmptyDirs() {
        val volume = MemoryVolume()
        volume.mkdir("/docs")
        volume.mkdir("/docs/empty")
        volume.mkdir("/docs/nested")
        volume.write("/docs/nested/a.bin", byteArrayOf(9, 8), create = true)
        val (saf, resolver) = RecordingResolver.paired()
        val dest = saf.root
        val op = DiskOperation("dl-tree")
        FilesOperations.downloadTree(
            resolver = resolver.resolver,
            volume = volume,
            sourcePath = "/docs",
            destTree = saf,
            destParent = dest,
            operation = op,
            conflict = { error("unexpected conflict $it") },
        )
        assertTrue(saf.isDirectory(dest, "docs"))
        val docs = saf.child(dest, "docs")!!
        assertTrue(saf.isDirectory(docs, "empty"))
        assertEquals(0, saf.listChildren(saf.child(docs, "empty")!!).size)
        assertEquals(byteArrayOf(9, 8).toList(), resolver.written[saf.child(saf.child(docs, "nested")!!, "a.bin")!!]!!.toList())
        assertTrue(op.mutationAttempted)
    }

    @Test
    fun downloadChildrenMultiRootsIntoFlatDestination() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(1), create = true)
        volume.mkdir("/folder")
        volume.write("/folder/b.bin", byteArrayOf(2), create = true)
        val (saf, resolver) = RecordingResolver.paired()
        val op = DiskOperation("dl-multi")
        FilesOperations.downloadChildren(
            resolver = resolver.resolver,
            volume = volume,
            parentPath = "/",
            names = listOf("a.bin", "folder"),
            destTree = saf,
            destParent = saf.root,
            operation = op,
            conflict = { error("unexpected $it") },
        )
        assertEquals(byteArrayOf(1).toList(), resolver.written[saf.child(saf.root, "a.bin")!!]!!.toList())
        assertEquals(
            byteArrayOf(2).toList(),
            resolver.written[saf.child(saf.child(saf.root, "folder")!!, "b.bin")!!]!!.toList(),
        )
    }

    @Test
    fun nonemptyDestinationDirectoryCannotReplaceOnDownload() {
        val volume = MemoryVolume()
        volume.mkdir("/docs")
        volume.write("/docs/a.bin", byteArrayOf(1), create = true)
        val (saf, resolver) = RecordingResolver.paired()
        val existing = saf.createExclusiveSubfolder(saf.root, "docs")
        saf.createExclusiveFile(existing, "keep.bin", "application/octet-stream")
        val decisions = mutableListOf<FilesConflict>()
        val op = DiskOperation("dl-nonempty")
        val thrown = assertThrows(IOException::class.java) {
            FilesOperations.downloadTree(
                resolver = RecordingResolver.paired().second.resolver,
                volume = volume,
                sourcePath = "/docs",
                destTree = saf,
                destParent = saf.root,
                operation = op,
                conflict = {
                    decisions += it
                    FilesConflictDecision.ReplaceFile
                },
            )
        }
        assertTrue(thrown.message!!.contains("nonempty directory") || thrown.message!!.contains("only Skip or Cancel"))
        assertEquals(1, decisions.size)
        assertTrue(decisions.single().isDirectory)
        assertEquals(false, decisions.single().destinationEmpty)
        assertEquals(1, saf.listChildren(existing).size)
    }

    @Test
    fun emptyDestinationDirectoryIsReusedWithoutReplace() {
        val volume = MemoryVolume()
        volume.mkdir("/docs")
        volume.write("/docs/a.bin", byteArrayOf(4), create = true)
        val (saf, resolver) = RecordingResolver.paired()
        val existing = saf.createExclusiveSubfolder(saf.root, "docs")
        FilesOperations.downloadTree(
            resolver = resolver.resolver,
            volume = volume,
            sourcePath = "/docs",
            destTree = saf,
            destParent = saf.root,
            operation = DiskOperation("dl-empty-reuse"),
            conflict = { error("reuse must not conflict: $it") },
        )
        assertEquals(existing, saf.child(saf.root, "docs"))
        assertEquals(byteArrayOf(4).toList(), resolver.written[saf.child(existing, "a.bin")!!]!!.toList())
    }

    @Test
    fun safFileReplaceFailsClosedWithoutAtomicRename() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(9), create = true)
        val (saf, resolver) = RecordingResolver.paired()
        val old = saf.createExclusiveFile(saf.root, "a.bin", "application/octet-stream")
        resolver.written[old] = byteArrayOf(1, 2, 3)
        val op = DiskOperation("dl-replace-closed")
        val thrown = assertThrows(IOException::class.java) {
            FilesOperations.downloadTree(
                resolver = resolver.resolver,
                volume = volume,
                sourcePath = "/a.bin",
                destTree = saf,
                destParent = saf.root,
                operation = op,
                conflict = {
                    assertFalse(it.isDirectory)
                    FilesConflictDecision.ReplaceFile
                },
            )
        }
        assertTrue(thrown.message!!.contains("atomic rename") || thrown.message!!.contains("Cannot safely replace"))
        // Original destination untouched — no delete-before-write.
        assertEquals(old, saf.child(saf.root, "a.bin"))
        assertEquals(byteArrayOf(1, 2, 3).toList(), resolver.written[old]!!.toList())
        assertFalse(op.mutationAttempted)
        assertEquals(OperationState.Queued, op.state)
    }


    @Test
    fun downloadCancelBeforeWriteStaysClean() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(1), create = true)
        val (saf, resolver) = RecordingResolver.paired()
        val op = DiskOperation("dl-cancel", active = { false })
        assertThrows(CancellationException::class.java) {
            FilesOperations.downloadTree(
                resolver = resolver.resolver,
                volume = volume,
                sourcePath = "/a.bin",
                destTree = saf,
                destParent = saf.root,
                operation = op,
                conflict = { error("no") },
            )
        }
        assertFalse(op.mutationAttempted)
        assertEquals(0, saf.listChildren(saf.root).size)
        assertEquals(OperationState.Queued, op.state)
    }

    @Test
    fun downloadCancelAfterMutationIsPartial() {
        val volume = MemoryVolume()
        volume.mkdir("/docs")
        volume.write("/docs/a.bin", byteArrayOf(1), create = true)
        volume.write("/docs/b.bin", byteArrayOf(2), create = true)
        var writes = 0
        val (saf, resolver) = RecordingResolver.paired(onWrite = { writes++ })
        val op = DiskOperation("dl-partial", active = { writes < 1 })
        assertThrows(CancellationException::class.java) {
            FilesOperations.downloadTree(
                resolver = resolver.resolver,
                volume = volume,
                sourcePath = "/docs",
                destTree = saf,
                destParent = saf.root,
                operation = op,
                conflict = { error("no") },
            )
        }
        assertTrue(op.mutationAttempted)
        assertEquals(OperationState.Partial, op.state)
        assertEquals(1, saf.listChildren(saf.child(saf.root, "docs")!!).size)
    }

    @Test
    fun uploadTreePreservesStructureAndReusesEmptyUsbDir() {
        val volume = MemoryVolume()
        volume.mkdir("/dest")
        volume.mkdir("/dest/docs") // empty → reuse
        val saf = MemorySafFolder()
        val docs = saf.createExclusiveSubfolder(saf.root, "docs")
        saf.createExclusiveSubfolder(docs, "empty")
        val nested = saf.createExclusiveSubfolder(docs, "nested")
        val fileUri = saf.createExclusiveFile(nested, "a.bin", "application/octet-stream")
        val resolver = RecordingResolver.inputsOnly(mapOf(fileUri to byteArrayOf(7, 7)))
        FilesOperations.uploadTree(
            resolver = resolver.resolver,
            sources = listOf(SafDocument(docs, "docs", true, null)),
            sourceTree = saf,
            destDir = "/dest",
            volume = volume,
            operation = DiskOperation("up-tree"),
            chunkBytes = 16,
            conflict = { error("unexpected $it") },
        )
        assertEquals(byteArrayOf(7, 7).toList(), volume.read("/dest/docs/nested/a.bin", 0, 2).toList())
        assertTrue(volume.stat("/dest/docs/empty").isDirectory)
        assertEquals(0, volume.list("/dest/docs/empty").size)
    }

    @Test
    fun uploadNonemptyUsbDirectoryRefusesReplace() {
        val volume = MemoryVolume()
        volume.mkdir("/docs")
        volume.write("/docs/keep.bin", byteArrayOf(1), create = true)
        val saf = MemorySafFolder()
        val docs = saf.createExclusiveSubfolder(saf.root, "docs")
        val fileUri = saf.createExclusiveFile(docs, "a.bin", "application/octet-stream")
        val thrown = assertThrows(IOException::class.java) {
            FilesOperations.uploadTree(
                resolver = RecordingResolver.inputsOnly(mapOf(fileUri to byteArrayOf(2))).resolver,
                sources = listOf(SafDocument(docs, "docs", true, null)),
                sourceTree = saf,
                destDir = "/",
                volume = volume,
                operation = DiskOperation("up-nonempty"),
                chunkBytes = 8,
                conflict = { FilesConflictDecision.ReplaceFile },
            )
        }
        assertTrue(thrown.message!!.contains("nonempty") || thrown.message!!.contains("Skip or Cancel"))
        assertEquals(byteArrayOf(1).toList(), volume.read("/docs/keep.bin", 0, 1).toList())
    }

    @Test
    fun uploadDocumentsFlatConflictReplaceAndSkip() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(1), create = true)
        volume.write("/b.bin", byteArrayOf(2), create = true)
        val a = Uri.parse("content://usb.batch.test.doc/a")
        val b = Uri.parse("content://usb.batch.test.doc/b")
        val resolver = RecordingResolver.inputsOnly(
            contents = mapOf(a to byteArrayOf(9), b to byteArrayOf(8)),
            names = mapOf(a to "a.bin", b to "b.bin"),
        )
        var seen = 0
        FilesOperations.uploadDocuments(
            resolver = resolver.resolver,
            uris = listOf(a, b),
            destDir = "/",
            volume = volume,
            operation = DiskOperation("up-docs"),
            chunkBytes = 8,
            conflict = {
                seen++
                if (it.name == "a.bin") FilesConflictDecision.ReplaceFile else FilesConflictDecision.Skip
            },
        )
        assertEquals(2, seen)
        assertEquals(byteArrayOf(9).toList(), volume.read("/a.bin", 0, 1).toList())
        assertEquals(byteArrayOf(2).toList(), volume.read("/b.bin", 0, 1).toList())
    }

    @Test
    fun pathEscapeRejectedByPolicyHelpers() {
        assertThrows(IllegalArgumentException::class.java) {
            FilesOperationPolicy.resolveInsideTree("/docs", "..")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FilesOperationPolicy.resolveInsideTree("/docs", "a/b")
        }
        assertFalse(FilesOperationPolicy.isSafeRelativePath("../x"))
        assertFalse(FilesOperationPolicy.isSafeRelativePath("a/../b"))
        assertTrue(FilesOperationPolicy.isSafeRelativePath("a/b"))
    }

    @Test
    fun parkAfterPriorMutationIsPartialAndKeepsRemainingRoots() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(1), create = true)
        volume.write("/b.bin", byteArrayOf(2), create = true)
        val (saf, resolver) = RecordingResolver.paired()
        // Conflicting second root so first write mutates then park.
        saf.createExclusiveFile(saf.root, "b.bin", "application/octet-stream")
        var cursor: FilesOperations.BatchDownloadCursor? = null
        val op = DiskOperation("dl-partial-park")
        val parked = assertThrows(RuntimeException::class.java) {
            FilesOperations.downloadChildren(
                resolver = resolver.resolver,
                volume = volume,
                parentPath = "/",
                names = listOf("a.bin", "b.bin"),
                destTree = saf,
                destParent = saf.root,
                operation = op,
                conflict = { throw RuntimeException("park:${it.pathLabel}") },
                onCursor = { cursor = it },
            )
        }
        assertTrue(parked.message!!.contains("/b.bin"))
        assertEquals(OperationState.Partial, op.state)
        assertTrue(op.mutationAttempted)
        assertNotNull(cursor)
        assertEquals(listOf("b.bin"), cursor!!.remainingRoots)
        assertNull(cursor!!.activeRoot)
    }

    @Test
    fun nestedResumeSkipsNonemptyRootAndMatchesPathLabel() {
        val volume = MemoryVolume()
        volume.mkdir("/folder")
        volume.write("/folder/a.bin", byteArrayOf(1), create = true)
        volume.write("/folder/b.bin", byteArrayOf(2), create = true)
        val (saf, resolver) = RecordingResolver.paired()
        val existingRoot = saf.createExclusiveSubfolder(saf.root, "folder")
        // Nonempty dest: naive root restart would refuse. Resume must continue inside.
        saf.createExclusiveFile(existingRoot, "keep.bin", "application/octet-stream")
        val existingA = saf.createExclusiveFile(existingRoot, "a.bin", "application/octet-stream")
        resolver.written[existingA] = byteArrayOf(7, 7)
        val resume = FilesOperations.BatchDownloadCursor(
            remainingRoots = listOf("folder"),
            activeRoot = "folder",
            remainingRelative = listOf("a.bin", "b.bin"),
        )
        val op = DiskOperation("dl-resume-nested")
        var decisions = 0
        val thrown = assertThrows(IOException::class.java) {
            FilesOperations.downloadChildren(
                resolver = resolver.resolver,
                volume = volume,
                parentPath = "/",
                names = resume.remainingRoots,
                destTree = saf,
                destParent = saf.root,
                operation = op,
                resume = resume,
                conflict = {
                    decisions++
                    assertFalse("must not re-ask nonempty root dir", it.isDirectory)
                    assertEquals("/folder/a.bin", it.pathLabel)
                    FilesConflictDecision.ReplaceFile
                },
            )
        }
        assertEquals(1, decisions)
        assertTrue(thrown.message!!.contains("atomic rename") || thrown.message!!.contains("Cannot safely replace"))
        // a.bin original retained; b.bin never reached.
        assertEquals(existingA, saf.child(existingRoot, "a.bin"))
        assertEquals(byteArrayOf(7, 7).toList(), resolver.written[existingA]!!.toList())
        assertNull(saf.child(existingRoot, "b.bin"))
        assertNotNull(saf.child(existingRoot, "keep.bin"))
        assertFalse(op.mutationAttempted)
    }


    @Test
    fun parkInsideNestedKeepsAncestorSiblingInCursor() {
        val volume = MemoryVolume()
        volume.mkdir("/folder")
        volume.mkdir("/folder/a")
        volume.write("/folder/a/x.bin", byteArrayOf(1), create = true)
        volume.write("/folder/b.bin", byteArrayOf(2), create = true)
        // Plant conflicting x.bin only after empty a/ is created so folder/a stay reusable
        // containers; a nonempty folder pre-seed would refuse at /folder first.
                lateinit var resolver: RecordingResolver
        val saf = object : MemorySafFolder(onFileCreated = { uri -> resolver.trackOutput(uri) }) {
            override fun createExclusiveSubfolder(parent: Uri, name: String): Uri {
                val created = super.createExclusiveSubfolder(parent, name)
                if (name == "a") {
                    createExclusiveFile(created, "x.bin", "application/octet-stream")
                }
                return created
            }
        }
        resolver = RecordingResolver.inputsOnly()
        var cursor: FilesOperations.BatchDownloadCursor? = null
        val op = DiskOperation("dl-ancestor-tail")
        val parked = assertThrows(RuntimeException::class.java) {
            FilesOperations.downloadChildren(
                resolver = resolver.resolver,
                volume = volume,
                parentPath = "/",
                names = listOf("folder"),
                destTree = saf,
                destParent = saf.root,
                operation = op,
                onCursor = { cursor = it },
                conflict = {
                    if (it.pathLabel == "/folder/a/x.bin") throw RuntimeException("park-nested")
                    error("unexpected ${it.pathLabel}")
                },
            )
        }
        assertEquals("park-nested", parked.message)
        assertNotNull(cursor)
        assertEquals(listOf("folder"), cursor!!.remainingRoots)
        assertEquals("folder", cursor!!.activeRoot)
        assertEquals(listOf("a/x.bin", "b.bin"), cursor!!.remainingRelative)
    }

    @Test
    fun parkInsideSameNamedNestedDirKeepsRelativePath() {
        val volume = MemoryVolume()
        volume.mkdir("/foo")
        volume.mkdir("/foo/foo")
        volume.write("/foo/foo/file.bin", byteArrayOf(1), create = true)
        volume.write("/foo/sib.bin", byteArrayOf(2), create = true)
                lateinit var resolver: RecordingResolver
        val saf = object : MemorySafFolder(onFileCreated = { uri -> resolver.trackOutput(uri) }) {
            override fun createExclusiveSubfolder(parent: Uri, name: String): Uri {
                val created = super.createExclusiveSubfolder(parent, name)
                // Conflict only under nested foo/foo, after empty containers exist.
                if (parent != root && name == "foo") {
                    createExclusiveFile(created, "file.bin", "application/octet-stream")
                }
                return created
            }
        }
        resolver = RecordingResolver.inputsOnly()
        var cursor: FilesOperations.BatchDownloadCursor? = null
        val op = DiskOperation("dl-same-name-nested")
        val parked = assertThrows(RuntimeException::class.java) {
            FilesOperations.downloadChildren(
                resolver = resolver.resolver,
                volume = volume,
                parentPath = "/",
                names = listOf("foo"),
                destTree = saf,
                destParent = saf.root,
                operation = op,
                onCursor = { cursor = it },
                conflict = {
                    if (it.pathLabel == "/foo/foo/file.bin") throw RuntimeException("park-same-name")
                    error("unexpected ${it.pathLabel}")
                },
            )
        }
        assertEquals("park-same-name", parked.message)
        assertNotNull(cursor)
        assertEquals(listOf("foo"), cursor!!.remainingRoots)
        assertEquals("foo", cursor!!.activeRoot)
        // Must keep foo/file.bin — not bare file.bin — so resume does not hit /foo/file.bin.
        assertEquals(listOf("foo/file.bin", "sib.bin"), cursor!!.remainingRelative)
    }

    @Test
    fun downloadChildrenResetsProgressPerFile() {
        val volume = MemoryVolume()
        volume.write("/big.bin", ByteArray(8) { 1 }, create = true)
        volume.write("/tiny.bin", ByteArray(1) { 2 }, create = true)
        val op = DiskOperation("dl-progress")
        val seenTotals = mutableListOf<Long>()
        val seenDoneAtOpen = mutableListOf<Long>()
        // openOutputStream supplier runs inside download() after beginCurrentFile — not onProgress
        // (onProgress fires before download, so it still sees the previous file's counters).
        val (saf, resolver) =
            RecordingResolver.paired(
                onWrite = {
                    seenDoneAtOpen += op.bytesDone
                    seenTotals += op.bytesTotal
                },
            )
        FilesOperations.downloadChildren(
            resolver = resolver.resolver,
            volume = volume,
            parentPath = "/",
            names = listOf("big.bin", "tiny.bin"),
            destTree = saf,
            destParent = saf.root,
            operation = op,
            conflict = { error("unexpected $it") },
        )
        assertEquals(listOf(0L, 0L), seenDoneAtOpen)
        assertEquals(listOf(8L, 1L), seenTotals)
        assertEquals(1L, op.bytesDone)
        assertEquals(1L, op.bytesTotal)
    }





    @Test
    fun uploadReplaceOriginalSurvivesOpenFailure() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(1, 1, 1), create = true)
        val a = Uri.parse("content://usb.batch.test.doc/a-missing")
        val resolver = RecordingResolver.inputsOnly(
            names = mapOf(a to "a.bin"),
            openFailures = mapOf(a to IOException("cannot open source")),
        )
        val op = DiskOperation("up-replace-open-fail")
        val thrown = assertThrows(IOException::class.java) {
            FilesOperations.uploadDocuments(
                resolver = resolver.resolver,
                uris = listOf(a),
                destDir = "/",
                volume = volume,
                operation = op,
                chunkBytes = 8,
                conflict = { FilesConflictDecision.ReplaceFile },
            )
        }
        assertTrue(thrown.message!!.contains("cannot open"))
        assertEquals(byteArrayOf(1, 1, 1).toList(), volume.read("/a.bin", 0, 3).toList())
        assertTrue(volume.list("/").none { it.name.startsWith(".up-") })
        assertFalse(op.mutationAttempted)
    }

    @Test
    fun uploadReplaceOriginalSurvivesMidstreamReadFailure() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(4, 5), create = true)
        val uri = Uri.parse("content://usb.batch.test.doc/read-failure")
        val resolver = RecordingResolver.inputsOnly(names = mapOf(uri to "a.bin"))
        resolver.failAfterFirstRead(uri)
        val op = DiskOperation("up-replace-read-failure")
        assertThrows(IOException::class.java) {
            FilesOperations.uploadDocuments(
                resolver = resolver.resolver, uris = listOf(uri), destDir = "/",
                volume = volume, operation = op, chunkBytes = 8,
                conflict = { FilesConflictDecision.ReplaceFile },
            )
        }
        assertEquals(byteArrayOf(4, 5).toList(), volume.read("/a.bin", 0, 2).toList())
        assertTrue(volume.list("/").none { it.name.startsWith(".up-") })
        assertEquals(OperationState.Partial, op.state)
    }



    @Test
    fun uploadReplaceOriginalSurvivesCancelDuringStaging() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(4, 5), create = true)
        val a = Uri.parse("content://usb.batch.test.doc/a-cancel")
        val payload = ByteArray(40) { 9 }
        val resolver = RecordingResolver.inputsOnly(
            contents = mapOf(a to payload),
            names = mapOf(a to "a.bin"),
        )
        // Cancel after first staged chunk on temp (not the original).
        val op = DiskOperation("up-replace-cancel", active = {
            // allow exclusive create empty write + first payload write, then cancel
            val tempWrites = volume.events.count { it.startsWith("write:/.up-t") }
            tempWrites < 2
        })
        assertThrows(CancellationException::class.java) {
            FilesOperations.uploadDocuments(
                resolver = resolver.resolver,
                uris = listOf(a),
                destDir = "/",
                volume = volume,
                operation = op,
                chunkBytes = 16,
                conflict = { FilesConflictDecision.ReplaceFile },
            )
        }
        assertEquals(byteArrayOf(4, 5).toList(), volume.read("/a.bin", 0, 2).toList())
        assertTrue(volume.list("/").none { it.name.startsWith(".up-") })
        // Never renamed original away.
        assertTrue(volume.events.none { it.startsWith("rename:/a.bin->") })
    }

    @Test
    fun uploadReplaceOriginalSurvivesNoSpaceBeforeStaging() {
        val volume = object : MemoryVolume(), usb.fs.UsbFsUsageVolume {
            override fun space() = usb.fs.FilesystemSpace(totalBytes = 10uL, freeBytes = 2uL)
        }
        volume.write("/a.bin", byteArrayOf(1), create = true)
        val a = Uri.parse("content://usb.batch.test.doc/a-space")
        val resolver = RecordingResolver.inputsOnly(
            contents = mapOf(a to byteArrayOf(1, 2, 3, 4)),
            names = mapOf(a to "a.bin"),
        )
        val op = DiskOperation("up-replace-space")
        val thrown = assertThrows(IOException::class.java) {
            FilesOperations.uploadDocuments(
                resolver = resolver.resolver,
                uris = listOf(a),
                destDir = "/",
                volume = volume,
                operation = op,
                chunkBytes = 8,
                conflict = { FilesConflictDecision.ReplaceFile },
            )
        }
        assertEquals(
            FilesOperationPolicy.insufficientSpaceMessage(2uL, 4L),
            thrown.message,
        )
        assertEquals(byteArrayOf(1).toList(), volume.read("/a.bin", 0, 1).toList())
        assertTrue(volume.list("/").none { it.name.startsWith(".up-") })
        assertFalse(op.mutationAttempted)
    }

    @Test
    fun uploadReplaceSucceedsAndRemovesTemps() {
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(1), create = true)
        val a = Uri.parse("content://usb.batch.test.doc/a-ok")
        val resolver = RecordingResolver.inputsOnly(
            contents = mapOf(a to byteArrayOf(9, 8, 7)),
            names = mapOf(a to "a.bin"),
        )
        FilesOperations.uploadDocuments(
            resolver = resolver.resolver,
            uris = listOf(a),
            destDir = "/",
            volume = volume,
            operation = DiskOperation("up-replace-ok"),
            chunkBytes = 8,
            conflict = { FilesConflictDecision.ReplaceFile },
        )
        assertEquals(byteArrayOf(9, 8, 7).toList(), volume.read("/a.bin", 0, 3).toList())
        assertTrue(volume.list("/").none { it.name.startsWith(".up-") })
    }

    @Test
    fun uploadReplaceDoesNotDeleteUnownedTempOnExclusiveRace() {
        val volume = object : MemoryVolume() {
            var racedPath: String? = null
            override fun write(
                path: String,
                data: ByteArray,
                offset: Long,
                create: Boolean,
                plannedTotalSize: Long,
                exclusiveCreate: Boolean,
            ) {
                if (racedPath == null && exclusiveCreate && path.contains(".up-t")) {
                    racedPath = path
                    super.write(path, byteArrayOf(42), 0, true, 1, false)
                    error("exclusive create failed: $path already exists")
                }
                super.write(path, data, offset, create, plannedTotalSize, exclusiveCreate)
            }
        }
        volume.write("/a.bin", byteArrayOf(3), create = true)
        val a = Uri.parse("content://usb.batch.test.doc/a-race")
        val resolver = RecordingResolver.inputsOnly(
            contents = mapOf(a to byteArrayOf(1)),
            names = mapOf(a to "a.bin"),
        )
        val op = DiskOperation("up-replace-race")
        assertThrows(Throwable::class.java) {
            FilesOperations.uploadDocuments(
                resolver = resolver.resolver,
                uris = listOf(a),
                destDir = "/",
                volume = volume,
                operation = op,
                chunkBytes = 8,
                conflict = { FilesConflictDecision.ReplaceFile },
            )
        }
        assertEquals(byteArrayOf(3).toList(), volume.read("/a.bin", 0, 1).toList())
        val peer = volume.racedPath!!
        assertEquals(byteArrayOf(42).toList(), volume.read(peer, 0, 1).toList())
        assertTrue(volume.events.none { it == "delete:$peer" })
    }

    @Test
    fun uploadDocumentsSettledDropsCompletedBeforePark() {
        val volume = MemoryVolume()
        volume.write("/b.bin", byteArrayOf(9), create = true)
        val a = Uri.parse("content://usb.batch.test.doc/a")
        val b = Uri.parse("content://usb.batch.test.doc/b")
        val resolver = RecordingResolver.inputsOnly(
            contents = mapOf(a to byteArrayOf(1), b to byteArrayOf(2)),
            names = mapOf(a to "a.bin", b to "b.bin"),
        )
        val remaining = mutableListOf(a.toString(), b.toString())
        val op = DiskOperation("up-settled")
        val thrown = assertThrows(RuntimeException::class.java) {
            FilesOperations.uploadDocuments(
                resolver = resolver.resolver,
                uris = remaining.map(Uri::parse),
                destDir = "/",
                volume = volume,
                operation = op,
                chunkBytes = 8,
                conflict = {
                    if (it.name == "b.bin") throw RuntimeException("park-b")
                    error("unexpected ${it.name}")
                },
                onDocumentSettled = { uri -> remaining.remove(uri.toString()) },
            )
        }
        assertEquals("park-b", thrown.message)
        assertEquals(listOf(b.toString()), remaining)
        assertEquals(byteArrayOf(1).toList(), volume.read("/a.bin", 0, 1).toList())
        assertEquals(OperationState.Partial, op.state)
    }

    @Test
    fun deleteBatchCommitFenceOnlyAfterAllRoots() {
        // Mirrors MainActivity.deleteBatch: mutationAttempted on first delete,
        // commitReached only after the full list succeeds.
        val volume = MemoryVolume()
        volume.write("/a.bin", byteArrayOf(1), create = true)
        volume.write("/b.bin", byteArrayOf(2), create = true)
        val op = DiskOperation("delete-batch")
        FilesOperations.deleteRecursively(volume, "/a.bin", op)
        assertTrue(op.mutationAttempted)
        assertFalse(op.commitReached)
        val inactive = DiskOperation("delete-batch-2", active = { false })
        inactive.mutationAttempted = true // prior root already mutated the batch
        assertThrows(CancellationException::class.java) {
            FilesOperations.deleteRecursively(volume, "/b.bin", inactive)
        }
        // Mid-cancel must not look Completed: commitReached stays false → Partial.
        assertFalse(inactive.commitReached)
        assertEquals(OperationState.Partial, inactive.state)
        assertNotNull(volume.statOrNull("/b.bin"))
    }

    private open class MemoryVolume : UsbFsVolume {
        private data class Node(var isDirectory: Boolean, var bytes: ByteArray = ByteArray(0))

        private val nodes = linkedMapOf<String, Node>("/" to Node(true))
        val events = mutableListOf<String>()

        private fun key(path: String): String = FilesOperationPolicy.normalizeVolumePath(path)

        override fun list(path: String): List<UsbFsDirEntry> {
            val parent = key(path)
            require(nodes[parent]?.isDirectory == true) { "not a directory: $parent" }
            val prefix = if (parent == "/") "/" else "$parent/"
            return nodes.keys
                .filter { it != parent && it.startsWith(prefix) && !it.substring(prefix.length).contains('/') }
                .map { child ->
                    val node = nodes.getValue(child)
                    UsbFsDirEntry(child.substringAfterLast('/'), node.isDirectory, node.bytes.size.toLong())
                }
        }

        override fun stat(path: String): UsbFsDirEntry =
            statOrNull(path) ?: error("missing $path")

        override fun statOrNull(path: String): UsbFsDirEntry? {
            val node = nodes[key(path)] ?: return null
            val name = if (path == "/") "/" else path.trim('/').substringAfterLast('/')
            return UsbFsDirEntry(name, node.isDirectory, node.bytes.size.toLong())
        }

        override fun mkdir(path: String) {
            val p = key(path)
            require(nodes[p] == null) { "exists $p" }
            require(nodes[parentKey(p)]?.isDirectory == true) { "missing parent ${parentKey(p)}" }
            nodes[p] = Node(true)
            events += "mkdir:$p"
        }

        override fun delete(path: String) {
            val p = key(path)
            val node = nodes[p] ?: error("missing $p")
            if (node.isDirectory) {
                require(list(p).isEmpty()) { "Directory not empty" }
            }
            nodes.remove(p)
            events += "delete:$p"
        }

        override fun rename(from: String, to: String) {
            val src = key(from)
            val dst = key(to)
            require(nodes[src] != null) { "missing $src" }
            require(nodes[dst] == null) { "exists $dst" }
            require(nodes[parentKey(dst)]?.isDirectory == true) { "missing parent ${parentKey(dst)}" }
            // Move whole subtree when renaming a directory.
            val moving = nodes.keys.filter { it == src || it.startsWith("$src/") }.sortedByDescending { it.length }
            for (old in moving) {
                val node = nodes.remove(old)!!
                val neu = if (old == src) dst else dst + old.removePrefix(src)
                nodes[neu] = node
            }
            events += "rename:$src->$dst"
        }

        override fun read(path: String, offset: Long, length: Int): ByteArray {
            val bytes = nodes.getValue(key(path)).bytes
            val end = minOf(bytes.size, (offset + length).toInt())
            if (offset >= bytes.size) return ByteArray(0)
            return bytes.copyOfRange(offset.toInt(), end)
        }

        override fun write(
            path: String,
            data: ByteArray,
            offset: Long,
            create: Boolean,
            plannedTotalSize: Long,
            exclusiveCreate: Boolean,
        ) {
            val p = key(path)
            val existing = nodes[p]
            if (existing == null) {
                require(create) { "missing $p" }
                require(nodes[parentKey(p)]?.isDirectory == true) { "missing parent ${parentKey(p)}" }
                nodes[p] = Node(false, ByteArray(maxOf(plannedTotalSize.toInt().coerceAtLeast(0), data.size)))
            } else {
                if (exclusiveCreate && create) error("exclusive create failed: $p already exists")
                require(!existing.isDirectory) { "is directory $p" }
            }
            val node = nodes.getValue(p)
            val need = offset.toInt() + data.size
            if (node.bytes.size < need) {
                node.bytes = node.bytes.copyOf(need)
            }
            System.arraycopy(data, 0, node.bytes, offset.toInt(), data.size)
            events += "write:$p"
        }


        private fun parentKey(path: String): String {
            if (path == "/") return "/"
            val trimmed = path.trim('/')
            val cut = trimmed.lastIndexOf('/')
            return if (cut < 0) "/" else "/" + trimmed.substring(0, cut)
        }

        override fun sync() {
            events += "sync"
        }

        override fun close() = Unit
    }

    private open class MemorySafFolder(
        private val onFileCreated: ((Uri) -> Unit)? = null,
    ) : BatchSafFolder {
        private data class Node(val name: String, val isDirectory: Boolean, val parent: Uri?)

        val root: Uri = Uri.parse("content://memory/root-${nextId()}")
        private val nodes = linkedMapOf(root to Node("root", true, null))
        private val children = linkedMapOf<Uri, MutableList<Uri>>(root to mutableListOf())

        fun child(parent: Uri, name: String): Uri? =
            listChildren(parent).singleOrNull { it.name == name }?.uri

        fun isDirectory(parent: Uri, name: String): Boolean =
            listChildren(parent).single { it.name == name }.isDirectory

        override fun listChildren(parent: Uri): List<SafDocument> =
            children.getValue(parent).map { uri ->
                val node = nodes.getValue(uri)
                SafDocument(uri, node.name, node.isDirectory, if (node.isDirectory) null else 0L)
            }

        override fun createExclusiveFile(parent: Uri, name: String, mimeType: String): Uri {
            require(listChildren(parent).none { it.name == name }) { "exists $name" }
            val uri = Uri.parse("content://memory/file-${nextId()}")
            nodes[uri] = Node(name, false, parent)
            children.getValue(parent) += uri
            children[uri] = mutableListOf()
            onFileCreated?.invoke(uri)
            return uri
        }

        open override fun createExclusiveSubfolder(parent: Uri, name: String): Uri {
            require(listChildren(parent).none { it.name == name }) { "exists $name" }
            val uri = Uri.parse("content://memory/dir-${nextId()}")
            nodes[uri] = Node(name, true, parent)
            children.getValue(parent) += uri
            children[uri] = mutableListOf()
            return uri
        }

        override fun deleteDocument(document: Uri): Boolean {
            val node = nodes[document] ?: return false
            val parent = node.parent ?: return false
            children[parent]?.remove(document)
            children.remove(document)
            nodes.remove(document)
            return true
        }

        companion object {
            private val ids = AtomicInteger()
            fun nextId(): Int = ids.incrementAndGet()
        }
    }

    /**
     * Real [ContentResolver] via Robolectric shadows — ContentResolver.open* / query are final.
     * Output streams register when [MemorySafFolder] creates file URIs; inputs/OpenableColumns
     * come from [BatchOpenableProvider] + stream suppliers.
     */
    private class RecordingResolver(
        contents: Map<Uri, ByteArray> = emptyMap(),
        names: Map<Uri, String> = emptyMap(),
        openFailures: Map<Uri, Throwable> = emptyMap(),
        private val onWrite: (() -> Unit)? = null,
    ) {
        val written = linkedMapOf<Uri, ByteArray>()
        val resolver: ContentResolver =
            RuntimeEnvironment.getApplication().contentResolver
        private val shadow: ShadowContentResolver = Shadow.extract(resolver)
        private val provider: BatchOpenableProvider =
            Robolectric.setupContentProvider(BatchOpenableProvider::class.java, DOC_AUTHORITY)

        init {
            for ((uri, bytes) in contents) {
                seedInput(uri, bytes, names[uri])
            }
            for ((uri, name) in names) {
                if (uri !in contents && uri !in openFailures) {
                    provider.meta[uri] = BatchOpenableProvider.Meta(name, null)
                }
            }
            for ((uri, failure) in openFailures) {
                provider.meta[uri] = BatchOpenableProvider.Meta(names[uri], null)
                shadow.registerInputStreamSupplier(uri) { throw failure }
            }
        }


        fun trackOutput(uri: Uri) {
            shadow.registerOutputStreamSupplier(uri) {
                onWrite?.invoke()
                object : OutputStream() {
                    private val buffer = ByteArrayOutputStream()
                    override fun write(b: Int) {
                        buffer.write(b)
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        buffer.write(b, off, len)
                    }

                    override fun flush() {
                        written[uri] = buffer.toByteArray()
                    }

                    override fun close() {
                        flush()
                    }
                }
            }
        }

        private fun seedInput(uri: Uri, bytes: ByteArray, name: String?) {
            provider.meta[uri] = BatchOpenableProvider.Meta(name, bytes.size.toLong())
            shadow.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        }

        fun failAfterFirstRead(uri: Uri) {
            shadow.registerInputStreamSupplier(uri) {
                object : java.io.InputStream() {
                    private var readOnce = false
                    override fun read(): Int {
                        if (readOnce) throw IOException("source read failure")
                        readOnce = true
                        return 9
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (readOnce) throw IOException("source read failure")
                        readOnce = true
                        b[off] = 9
                        return 1
                    }
                }
            }
        }


        companion object {
            const val DOC_AUTHORITY = "usb.batch.test.doc"

            fun paired(
                contents: Map<Uri, ByteArray> = emptyMap(),
                names: Map<Uri, String> = emptyMap(),
                onWrite: (() -> Unit)? = null,
            ): Pair<MemorySafFolder, RecordingResolver> {
                lateinit var recording: RecordingResolver
                val folder = MemorySafFolder(onFileCreated = { uri -> recording.trackOutput(uri) })
                recording = RecordingResolver(contents = contents, names = names, onWrite = onWrite)
                return folder to recording
            }

            fun inputsOnly(
                contents: Map<Uri, ByteArray> = emptyMap(),
                names: Map<Uri, String> = emptyMap(),
                openFailures: Map<Uri, Throwable> = emptyMap(),
                onWrite: (() -> Unit)? = null,
            ): RecordingResolver = RecordingResolver(contents, names, openFailures, onWrite)
        }
    }

    /** OpenableColumns provider for upload document URIs. */
    class BatchOpenableProvider : ContentProvider() {
        data class Meta(val displayName: String?, val size: Long?)

        val meta = mutableMapOf<Uri, Meta>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val entry = meta[uri] ?: return null
            if (entry.displayName == null && entry.size == null) return null
            val cols =
                projection?.toList()
                    ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val cursor = MatrixCursor(cols.toTypedArray())
            cursor.addRow(
                cols.map { col ->
                    when (col) {
                        OpenableColumns.DISPLAY_NAME -> entry.displayName
                        OpenableColumns.SIZE -> entry.size
                        else -> null
                    }
                }.toTypedArray(),
            )
            return cursor
        }

        override fun getType(uri: Uri): String = "application/octet-stream"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
