package usb.saf

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Explicit open result for download writers: a sync-capable descriptor, or a
 * stream fallback when the provider rejects file descriptors.
 */
sealed class SafOpenResult {
    class Descriptor(val descriptor: ParcelFileDescriptor) : SafOpenResult()
    class Stream(val stream: OutputStream) : SafOpenResult()
}

/**
 * Shared SAF open order. Download probes truncating `wt`, then `w`, then
 * `openOutputStream("w")`. Held-handle writers open plain `w` only — no stream
 * fallback — so capability failure stays at construction time.
 */
object SafOpenPolicy {
    /** Download open order: `wt` → `w` → `openOutputStream("w")`. */
    fun openDownload(resolver: ContentResolver, destination: Uri): SafOpenResult {
        openDescriptor(resolver, destination, "wt")?.let { return SafOpenResult.Descriptor(it) }
        openDescriptor(resolver, destination, "w")?.let { return SafOpenResult.Descriptor(it) }
        val stream = resolver.openOutputStream(destination, "w")
            ?: error("cannot open $destination for writing")
        return SafOpenResult.Stream(stream)
    }

    /** Held-handle open used by [SafSyncedOutput]: plain `w`, no stream fallback. */
    fun openHeldWrite(resolver: ContentResolver, document: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(document, "w")
            ?: throw IOException("cannot open $document for writing")

    private fun openDescriptor(
        resolver: ContentResolver,
        destination: Uri,
        mode: String,
    ): ParcelFileDescriptor? =
        try {
            resolver.openFileDescriptor(destination, mode)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
}
