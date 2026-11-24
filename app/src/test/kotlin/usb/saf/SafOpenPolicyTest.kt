package usb.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafOpenPolicyTest {
    @Test
    fun openDownloadPrefersWtThenWThenStream() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val shadow: ShadowContentResolver = Shadows.shadowOf(resolver)
        val provider = Robolectric.setupContentProvider(OpenOrderProvider::class.java, AUTHORITY)
        val uri = Uri.parse("content://$AUTHORITY/doc/order")
        val file = File.createTempFile("saf-open-", ".bin").also { it.deleteOnExit() }
        provider.file = file
        provider.failModes += "wt"
        provider.failModes += "w"
        shadow.registerOutputStream(uri, java.io.ByteArrayOutputStream())

        when (val opened = SafOpenPolicy.openDownload(resolver, uri)) {
            is SafOpenResult.Stream -> opened.stream.close()
            is SafOpenResult.Descriptor -> {
                opened.descriptor.close()
                fail("expected stream fallback after wt/w rejection")
            }
        }
        assertEquals(listOf("wt", "w"), provider.modes)
    }

    @Test
    fun openDownloadReturnsDescriptorWhenWtAccepted() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val provider = Robolectric.setupContentProvider(OpenOrderProvider::class.java, AUTHORITY)
        val uri = Uri.parse("content://$AUTHORITY/doc/wt-ok")
        val file = File.createTempFile("saf-open-wt-", ".bin").also { it.deleteOnExit() }
        provider.file = file

        when (val opened = SafOpenPolicy.openDownload(resolver, uri)) {
            is SafOpenResult.Descriptor -> {
                assertEquals(listOf("wt"), provider.modes)
                opened.descriptor.close()
            }
            is SafOpenResult.Stream -> fail("expected descriptor from wt")
        }
    }

    @Test
    fun openDownloadFallsBackToWWhenWtRejected() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val provider = Robolectric.setupContentProvider(OpenOrderProvider::class.java, AUTHORITY)
        val uri = Uri.parse("content://$AUTHORITY/doc/w-ok")
        val file = File.createTempFile("saf-open-w-", ".bin").also { it.deleteOnExit() }
        provider.file = file
        provider.failModes += "wt"

        when (val opened = SafOpenPolicy.openDownload(resolver, uri)) {
            is SafOpenResult.Descriptor -> {
                assertEquals(listOf("wt", "w"), provider.modes)
                opened.descriptor.close()
            }
            is SafOpenResult.Stream -> fail("expected descriptor from w")
        }
    }

    @Test
    fun openHeldWriteUsesPlainWAndRejectsMissingDocument() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val provider = Robolectric.setupContentProvider(OpenOrderProvider::class.java, AUTHORITY)
        val uri = Uri.parse("content://$AUTHORITY/doc/held")
        val file = File.createTempFile("saf-held-", ".bin").also { it.deleteOnExit() }
        provider.file = file

        SafOpenPolicy.openHeldWrite(resolver, uri).close()
        assertEquals(listOf("w"), provider.modes)

        provider.file = null
        try {
            SafOpenPolicy.openHeldWrite(resolver, uri)
            fail("expected FileNotFoundException for missing held document")
        } catch (failure: FileNotFoundException) {
            assertTrue(failure.message!!.contains("missing $uri"))
        }

        provider.returnNull = true
        try {
            SafOpenPolicy.openHeldWrite(resolver, uri)
            fail("expected IOException when openFile returns null")
        } catch (failure: IOException) {
            assertTrue(failure.message!!.contains("cannot open"))
        }
    }

    class OpenOrderProvider : ContentProvider() {
        var file: File? = null
        var returnNull = false
        val failModes = mutableSetOf<String>()
        val modes = mutableListOf<String>()

        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            modes += mode
            if (returnNull) return null
            if (mode in failModes) throw FileNotFoundException("reject $mode")
            val target = file ?: throw FileNotFoundException("missing $uri")
            if (mode.contains('t') || (mode.contains('w') && !mode.contains('a'))) {
                java.io.FileOutputStream(target).close()
            }
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    companion object {
        const val AUTHORITY = "usb.saf.open.policy.test"
    }
}
