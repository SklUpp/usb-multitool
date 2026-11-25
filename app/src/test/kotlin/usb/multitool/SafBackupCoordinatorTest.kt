package usb.multitool

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import usb.saf.SafDocument
import usb.saf.SafTree
import usb.ui.AppUiState
import usb.ui.UiIntent

/**
 * [SafBackupCoordinator] fences: grant usability, folder-pick clears, newest GPT name,
 * `.map` vs image classification, stale pick generation dropped on main hop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafBackupCoordinatorTest {
    private lateinit var resolver: ContentResolver
    private lateinit var shadow: ShadowContentResolver
    private lateinit var prefs: SharedPreferences
    private lateinit var scope: CoroutineScope
    private val intents = CopyOnWriteArrayList<UiIntent>()
    private val trees = mutableMapOf<String, FakeSafTree>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        resolver = app.contentResolver
        shadow = Shadow.extract(resolver)
        prefs = app.getSharedPreferences("saf-backup-coord-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        intents.clear()
        trees.clear()
        Robolectric.setupContentProvider(FakeOpenableProvider::class.java, "usb.saf.test")
        FakeOpenableProvider.rows.clear()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun noWriteGrantMeansUsableFalse() {
        val uri = "content://usb.saf.test/tree/no-grant"
        val coord = coordinator()
        val state = AppUiState(backupFolderUri = uri, backupFolderUsable = true)
        assertFalse(coord.hasUsableBackupFolder(state))
        assertFalse(
            coord.hasUsableBackupFolder(
                AppUiState(backupFolderUri = uri, backupFolderUsable = false),
            ),
        )
        assertFalse(coord.hasUsableBackupFolder(AppUiState()))
    }

    @Test
    fun folderPickClearsGptRescueAndMapSelections() {
        val folder = Uri.parse("content://usb.saf.test/tree/clear")
        trees[folder.toString()] = FakeSafTree(display = "ClearMe")
        val coord = coordinator()
        // Robolectric may auto-grant the tree; usability is covered by noWriteGrantMeansUsableFalse.
        // Here only the paired selection clears matter.
        coord.onFolderPicked(folder)

        assertTrue(intents.any { it is UiIntent.SetBackupFolder && it.uri == folder.toString() })
        val clearedBackup = intents.filterIsInstance<UiIntent.SetBackupFile>()
        assertTrue(clearedBackup.any { it.uri == null })
        assertTrue(intents.any { it is UiIntent.SetRescueArchiveTree && it.treeUri == null })
        val clearedImage = intents.filterIsInstance<UiIntent.SetRescueImage>()
        assertTrue(clearedImage.any { it.uri == null })
        val clearedMap = intents.filterIsInstance<UiIntent.SetRescueMap>()
        assertTrue(clearedMap.any { it.uri == null })
    }

    @Test
    fun folderPickPersistsWithoutArmingRestore() {
        val folder = Uri.parse("content://usb.saf.test/tree/gpt")
        val older = Uri.parse("content://usb.saf.test/doc/gpt-critical-2020.bin")
        val newer = Uri.parse("content://usb.saf.test/doc/gpt-critical-2024.bin")
        trees[folder.toString()] = FakeSafTree(
            display = "Backups",
            root = listOf(
                SafDocument(older, "gpt-critical-2020.bin", false, 3L),
                SafDocument(newer, "gpt-critical-2024.bin", false, 3L),
                SafDocument(
                    Uri.parse("content://usb.saf.test/doc/notes.txt"),
                    "notes.txt",
                    false,
                    1L,
                ),
            ),
        )

        val coord = coordinator()
        // Robolectric records takePersistableUriPermission as a write grant.
        assertTrue(coord.onFolderPicked(folder))
        assertEquals(folder.toString(), prefs.getString("backup_folder_uri", null))
        assertEquals("Backups", prefs.getString("backup_folder_name", null))

        assertTrue(
            "folder choice must not pre-arm GPT restore",
            intents.filterIsInstance<UiIntent.SetBackupFile>().all { it.uri == null },
        )
        assertTrue(
            "folder choice must not pre-arm rescue restore",
            intents.filterIsInstance<UiIntent.SetRescueImage>().all { it.uri == null },
        )
        assertTrue(
            intents.filterIsInstance<UiIntent.SetRescueMap>().all { it.uri == null },
        )
    }

    @Test
    fun rescueFileClassifiesMapVersusImage() {
        val mapUri = Uri.parse("content://usb.saf.test/doc/disk.map")
        val imgUri = Uri.parse("content://usb.saf.test/doc/disk.img")
        // Minimal invalid map text still produces a RescueMapInspection (error set).
        seedInput(mapUri, "not-a-map".toByteArray(), "disk.map")
        // Raw header → IMG classification via name.
        seedInput(imgUri, ByteArray(8) { 0 }, "disk.img")

        val coord = coordinator()
        coord.onRescueFilePicked(mapUri, AppUiState())
        awaitIntent { it is UiIntent.SetRescueMap && it.uri == mapUri.toString() }
        assertTrue(
            intents.none {
                it is UiIntent.SetRescueImage && it.uri == mapUri.toString()
            },
        )

        intents.clear()
        coord.onRescueFilePicked(imgUri, AppUiState())
        awaitIntent { it is UiIntent.SetRescueImage && it.uri == imgUri.toString() }
        assertTrue(
            intents.none {
                it is UiIntent.SetRescueMap && it.uri == imgUri.toString()
            },
        )
        assertTrue(intents.any { it is UiIntent.SetRescueArchiveTree && it.treeUri == null })
    }

    @Test
    fun staleGptPickGenerationDroppedOnMainHop() {
        val uriOld = Uri.parse("content://usb.saf.test/doc/gpt-old.bin")
        val uriNew = Uri.parse("content://usb.saf.test/doc/gpt-new.bin")
        val oldOpened = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldClosed = CountDownLatch(1)

        shadow.registerInputStreamSupplier(uriOld) {
            oldOpened.countDown()
            assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun close() {
                    super.close()
                    oldClosed.countDown()
                }
            }
        }
        seedInput(uriNew, byteArrayOf(4, 5, 6), "gpt-new.bin")

        val coord = coordinator()
        coord.onGptBackupPicked(uriOld, AppUiState())
        assertTrue(oldOpened.await(5, TimeUnit.SECONDS))

        coord.onGptBackupPicked(uriNew, AppUiState())
        awaitIntent { it is UiIntent.SetBackupFile && it.uri == uriNew.toString() }

        releaseOld.countDown()
        assertTrue(oldClosed.await(5, TimeUnit.SECONDS))
        // Drain the stale coroutine's main hop; it must not overwrite the newer pick.
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.yield()
        }
        val backups = intents.filterIsInstance<UiIntent.SetBackupFile>().filter { it.uri != null }
        assertEquals(uriNew.toString(), backups.last().uri)
        assertTrue(backups.none { it.uri == uriOld.toString() })
    }

    private fun coordinator(
        treeFactory: (Uri) -> SafTree = { uri ->
            trees[uri.toString()] ?: FakeSafTree(display = "empty")
        },
    ): SafBackupCoordinator =
        SafBackupCoordinator(
            scope = scope,
            resolver = resolver,
            tree = treeFactory,
            intents = { intents += it },
            log = {},
            prefs = prefs,
        )

    private fun seedInput(uri: Uri, bytes: ByteArray, name: String) {
        FakeOpenableProvider.rows[uri] = name to bytes.size.toLong()
        shadow.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
    }
    private fun awaitIntent(timeoutMs: Long = 5_000L, predicate: (UiIntent) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (intents.any(predicate)) return
            Thread.yield()
        }
        error("intent not observed within ${timeoutMs}ms; saw=$intents")
    }

    private class FakeSafTree(
        private val display: String? = null,
        private val root: List<SafDocument> = emptyList(),
        private val children: Map<Uri, List<SafDocument>> = emptyMap(),
        private val texts: Map<String, String> = emptyMap(),
    ) : SafTree {
        override fun displayName(): String? = display
        override fun listRootFiles(): List<SafDocument> = root
        override fun listChildren(parent: Uri): List<SafDocument> =
            children[parent] ?: emptyList()
        override fun readText(document: Uri, maxChars: Int): String =
            texts[document.toString()]
                ?: error("no text for $document")
        override fun createExclusiveFile(parent: Uri, name: String, mimeType: String): Uri =
            error("unused")
        override fun createExclusiveSubfolder(parent: Uri, name: String): Uri =
            error("unused")
        override fun deleteDocument(document: Uri): Boolean = false
    }

    class FakeOpenableProvider : ContentProvider() {
        companion object {
            val rows = mutableMapOf<Uri, Pair<String, Long>>()
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val (name, size) = rows[uri] ?: return null
            val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(cols).apply {
                addRow(
                    cols.map { col ->
                        when (col) {
                            OpenableColumns.DISPLAY_NAME -> name
                            OpenableColumns.SIZE -> size
                            else -> null
                        }
                    }.toTypedArray(),
                )
            }
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
