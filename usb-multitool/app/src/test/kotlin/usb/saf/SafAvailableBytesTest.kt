package usb.saf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


import android.database.Cursor
import android.provider.DocumentsContract
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])

/**
 * Behaviour of SAF free-space lookup: only the unique root whose document ID equals the
 * tree's document ID is trusted; nested trees, ambiguous mappings and provider gaps are
 * unknown (null), never a guessed number.
 */
class SafAvailableBytesTest {
    private val documentIdColumn = DocumentsContract.Root.COLUMN_DOCUMENT_ID
    private val availableBytesColumn = DocumentsContract.Root.COLUMN_AVAILABLE_BYTES

    /** Proxied Cursor over fixed rows; values keyed by column index. */
    private fun cursor(columns: List<String>, rows: List<List<Any?>>): Cursor {
        var position = -1
        val handler = object : InvocationHandler {
            override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? =
                when (method.name) {
                    "moveToFirst" -> {
                        position = 0
                        rows.isNotEmpty()
                    }
                    "moveToNext" -> {
                        val next = position + 1
                        if (next < rows.size) {
                            position = next
                            true
                        } else {
                            position = rows.size
                            false
                        }
                    }
                    "getPosition" -> position
                    "getCount" -> rows.size
                    "getColumnIndex" -> columns.indexOf(args!![0] as String)
                    "getColumnCount" -> columns.size
                    "getColumnName" -> columns[args!![0] as Int]
                    "isNull" -> rows[position][args!![0] as Int] == null
                    "getString" -> rows[position][args!![0] as Int] as String?
                    "getLong" -> rows[position][args!![0] as Int] as Long
                    "close" -> Unit
                    "isClosed" -> false
                    "toString" -> "FakeCursor"
                    "hashCode" -> 1
                    "equals" -> false
                    else -> throw UnsupportedOperationException(method.name)
                }
        }
        return Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java),
            handler,
        ) as Cursor
    }

    private fun rootsCursor(vararg roots: Pair<String, Long?>): Cursor = cursor(
        listOf(documentIdColumn, availableBytesColumn),
        roots.map { (documentId, bytes) -> listOf<Any?>(documentId, bytes) },
    )

    @Test
    fun uniqueMatchingRootReportsBytes() {
        val cursor = rootsCursor("primary:" to 4096L, "sd:" to 1L)
        assertEquals(4096L, availableBytesForTree(cursor, "primary:"))
    }

    @Test
    fun zeroReportedBytesIsValid() {
        val cursor = rootsCursor("primary:" to 0L)
        assertEquals(0L, availableBytesForTree(cursor, "primary:"))
    }

    @Test
    fun mismatchRootWithoutBytesIsUnknown() {
        val cursor = rootsCursor("not-this-tree:" to 4096L)
        assertNull(availableBytesForTree(cursor, "primary:"))
    }

    @Test
    fun duplicateMatchingRootsAreAmbiguous() {
        val cursor = rootsCursor("primary:" to 4096L, "primary:" to 4096L)
        assertNull(availableBytesForTree(cursor, "primary:"))
    }

    @Test
    fun nestedTreeHasNoMatchingRoot() {
        val cursor = rootsCursor("primary:" to 4096L)
        assertNull(availableBytesForTree(cursor, "primary:Folder/Backups"))
    }

    @Test
    fun negativeValueIsUnknown() {
        val cursor = rootsCursor("primary:" to -1L)
        assertNull(availableBytesForTree(cursor, "primary:"))
    }

    @Test
    fun nullValueIsUnknown() {
        val cursor = rootsCursor("primary:" to null)
        assertNull(availableBytesForTree(cursor, "primary:"))
    }

    @Test
    fun missingAvailableBytesColumnIsCapabilityAbsent() {
        val cursor = cursor(listOf(documentIdColumn), listOf(listOf<Any?>("primary:")))
        assertNull(availableBytesForTree(cursor, "primary:"))
    }

    @Test
    fun missingDocumentIdColumnIsUnknown() {
        val cursor = cursor(listOf(availableBytesColumn), listOf(listOf<Any?>(4096L)))
        assertNull(availableBytesForTree(cursor, "primary:"))
    }

    @Test
    fun emptyRootsAreUnknown() {
        assertNull(availableBytesForTree(rootsCursor(), "primary:"))
    }

    @Test
    fun operationCleanupDeletesOnlyExactOwnedUrisInReverseOrder() {
        val deleted = mutableListOf<String>()
        val owned = SafOperationDocuments { deleted += it.toString(); true }
        val archive = android.net.Uri.parse("content://tree/run")
        val image = android.net.Uri.parse("content://tree/run/image.img")
        owned.own(archive)
        owned.own(image)
        owned.cleanup()
        assertEquals(listOf(image.toString(), archive.toString()), deleted)
        owned.cleanup()
        assertEquals(2, deleted.size)
    }

    @Test
    fun preflightFailureLeavesUnownedUserDocumentUntouched() {
        val deleted = mutableListOf<String>()
        val owned = SafOperationDocuments { deleted += it.toString(); true }
        owned.own(android.net.Uri.parse("content://tree/run"))
        owned.cleanup()
        assertEquals(listOf("content://tree/run"), deleted)
        assertFalse(deleted.contains("content://tree/existing"))
    }

    @Test
    fun providerDeleteFalseIsReturnedAsCleanupFailure() {
        val owned = SafOperationDocuments { false }
        owned.own(android.net.Uri.parse("content://tree/run"))
        val failure = owned.cleanup()
        assertEquals("SAF provider refused deletion of operation-owned document content://tree/run", failure?.message)
    }

    @Test
    fun exclusiveCreateCleanupFalsePreservesOriginalFailureAndAddsDiagnostic() {
        val original = IllegalStateException("metadata mismatch")
        val created = android.net.Uri.parse("content://tree/new-file")
        deleteCreatedDocument({ false }, created, original)
        assertEquals("metadata mismatch", original.message)
        assertEquals(1, original.suppressed.size)
        assertEquals(
            "SAF provider refused deletion of operation-owned document $created",
            original.suppressed.single().message,
        )
    }
}
