package usb.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafBackupFolderOwnershipTest {
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @Test
    fun existingReturnedFileIsRejectedAndRetained() {
        val provider = provider()
        val returned = Uri.parse("content://$AUTHORITY/document/existing-file")
        provider.created = returned
        provider.rows[scoped("existing-file")] = Row("old.bin", false, 7L)

        val failure = expectFailure {
            SafBackupFolder(resolver, tree()).createExclusiveFile(tree(), "new.bin", MIME)
        }

        assertTrue(failure.message!!.contains("retained returned document $returned"))
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    fun existingReturnedDirectoryIsRejectedAndRetained() {
        val provider = provider()
        val returned = Uri.parse("content://$AUTHORITY/document/existing-dir")
        provider.created = returned
        provider.rows[scoped("existing-dir")] = Row("old-dir", true, null)

        val failure = expectFailure {
            SafBackupFolder(resolver, tree()).createExclusiveFile(tree(), "new-file", MIME)
        }

        assertTrue(failure.message!!.contains("retained returned document $returned"))
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    fun foreignReturnedUriIsRejectedAndNeverDeleted() {
        val provider = provider()
        val returned = Uri.parse("content://foreign.authority/document/foreign")
        provider.created = returned

        val failure = expectFailure {
            SafBackupFolder(resolver, tree()).createExclusiveFile(tree(), "new.bin", MIME)
        }

        assertTrue(failure.message!!.contains("retained returned document $returned"))
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    fun nullQueryIsRejectedAndReturnedUriIsRetained() {
        val provider = provider()
        val returned = Uri.parse("content://$AUTHORITY/document/query-null")
        provider.created = returned
        provider.nullQueries += scoped("query-null")

        val failure = expectFailure {
            SafBackupFolder(resolver, tree()).createExclusiveFile(tree(), "new.bin", MIME)
        }

        assertTrue(failure.message!!.contains("retained returned document $returned"))
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    fun queryFailureIsRejectedWithRetainedDiagnosticsAndNoDelete() {
        val provider = provider()
        val returned = Uri.parse("content://$AUTHORITY/document/query-failure")
        provider.created = returned
        provider.queryFailures += scoped("query-failure")

        val failure = expectFailure {
            SafBackupFolder(resolver, tree()).createExclusiveFile(tree(), "new.bin", MIME)
        }

        assertTrue(failure.message!!.contains("query failed"))
        assertTrue(failure.message!!.contains("retained returned document $returned"))
        assertEquals("query failure", failure.cause?.message)
        assertTrue(provider.deleted.isEmpty())
    }

    private fun scoped(id: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree(), id)

    private fun provider(): OwnershipProvider =
        Robolectric.setupContentProvider(OwnershipProvider::class.java, AUTHORITY)

    private fun tree(): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    private fun expectFailure(block: () -> Unit): Throwable {
        try {
            block()
        } catch (failure: IllegalArgumentException) {
            return failure
        } catch (failure: IllegalStateException) {
            return failure
        }
        throw AssertionError("expected SAF rejection")
    }

    data class Row(val name: String, val directory: Boolean, val size: Long?)

    class OwnershipProvider : ContentProvider() {
        var created: Uri? = null
        val rows = mutableMapOf<Uri, Row>()
        val nullQueries = mutableSetOf<Uri>()
        val queryFailures = mutableSetOf<Uri>()
        val deleted = mutableListOf<Uri>()

        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") {
                extras?.getParcelable<Uri>("uri")?.let { deleted += it }
                return Bundle()
            }
            if (method != "android:createDocument") return null
            return Bundle().apply { putParcelable("uri", created) }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            if (uri in queryFailures) throw IllegalStateException("query failure")
            if (uri in nullQueries) return null
            val row = rows[uri] ?: return null
            val columns = projection?.toList()
                ?: listOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                )
            return MatrixCursor(columns.toTypedArray()).apply {
                addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            if (row.directory) DocumentsContract.Document.MIME_TYPE_DIR else MIME
                        DocumentsContract.Document.COLUMN_SIZE -> row.size
                        else -> null
                    }
                }.toTypedArray())
            }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted += uri
            return 1
        }

        override fun getType(uri: Uri): String = MIME
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    companion object {
        private const val AUTHORITY = "usb.saf.ownership"
        private const val MIME = "application/octet-stream"
    }
}
