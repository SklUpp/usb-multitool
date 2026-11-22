package usb.image

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver

/**
 * The real SAF picker boundary: `ImageSource.fromContentUri` reads OpenableColumns from
 * the provider and streams the chosen document. Opaque document ids must never be used
 * as file names, a compressed payload must win over a mislabeled name, and SIZE must
 * only be published once the live magic proves the stream is uncompressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContentUriImageSourceTest {
    private val resolver: android.content.ContentResolver
        get() = RuntimeEnvironment.getApplication().contentResolver

    @Test
    fun gzipPayloadWinsOverIsoDisplayName() {
        val plain = "raw-image-payload".toByteArray()
        val gzip = gzipOf(plain)
        installProvider(displayName = "ubuntu.iso", size = gzip.size.toLong(), payload = gzip)

        val source = ImageSource.fromContentUri(URI, resolver)
        assertEquals(ImageFormat.ISO, source.format)
        val opened = source.open()
        try {
            assertEquals(ImageFormat.GZIP, source.format)
            assertArrayEquals(plain, opened.readBytes())
            assertNull(opened.decodedSizeHint)
        } finally {
            opened.close()
        }
    }

    @Test
    fun missingDisplayNameFallsBackToMagic() {
        val plain = "raw-image-payload".toByteArray()
        installProvider(displayName = null, size = null, payload = gzipOf(plain))

        val source = ImageSource.fromContentUri(URI, resolver)
        assertEquals(ImageFormat.IMG, source.format)
        val opened = source.open()
        try {
            assertEquals(ImageFormat.GZIP, source.format)
            assertArrayEquals(plain, opened.readBytes())
        } finally {
            opened.close()
        }
    }

    @Test
    fun providerSizeIsPublishedOnlyForAnUncompressedStream() {
        val payload = ByteArray(4096) { it.toByte() }
        installProvider(displayName = "stick.iso", size = payload.size.toLong(), payload = payload)

        val source = ImageSource.fromContentUri(URI, resolver)
        val opened = source.open()
        try {
            assertEquals(ImageFormat.ISO, source.format)
            assertEquals(payload.size.toLong(), opened.decodedSizeHint)
            assertArrayEquals(payload, opened.readBytes())
        } finally {
            opened.close()
        }
    }

    private fun installProvider(
        displayName: String?,
        size: Long?,
        payload: ByteArray,
    ) {
        val provider =
            Robolectric.setupContentProvider(FakeOpenableProvider::class.java, AUTHORITY)
        provider.displayName = displayName
        provider.size = size
        Shadow.extract<ShadowContentResolver>(resolver)
            .registerInputStreamSupplier(URI) { ByteArrayInputStream(payload) }
    }

    private fun gzipOf(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Minimal OpenableColumns provider: opaque document id, metadata only. */
    class FakeOpenableProvider : ContentProvider() {
        var displayName: String? = null
        var size: Long? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            if (displayName == null && size == null) return null
            val column = projection?.firstOrNull() ?: OpenableColumns.DISPLAY_NAME
            val value: Any? = if (column == OpenableColumns.SIZE) size else displayName
            return MatrixCursor(arrayOf(column)).apply { addRow(arrayOf(value)) }
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

    private companion object {
        const val AUTHORITY = "usb.test"
        val URI: Uri = Uri.parse("content://usb.test/document/msf%3A42")
    }
}
