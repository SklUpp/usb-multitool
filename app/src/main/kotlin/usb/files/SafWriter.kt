package usb.files

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.OutputStream

class SafWriteException(
    val bytesWritten: Long,
    val cleanupNote: String,
    cause: Throwable,
) : Exception(cause)

/**
 * Consolidates SAF destination writes for download operations:
 * opening descriptor (wt/w) with stream fallback, synced writes,
 * size validation, and empty-destination cleanup.
 */
class SafWriter(
    private val resolver: ContentResolver,
) {
    /** Streams into [destination], flushes/syncs, validates size, and removes an empty failure. */
    fun write(
        destination: Uri,
        expectedSize: Long,
        writer: (OutputStream) -> Long,
    ): Long {
        var offset = 0L
        var reportedSize: Long? = null
        try {
            offset = writeSynced(destination) { stream -> writer(stream).also { offset = it } }
            if (offset != expectedSize) {
                error("download size mismatch: got $offset, expected $expectedSize")
            }
            reportedSize = FilesOperations.openableSizeBytes(resolver, destination)
            if (expectedSize > 0L && reportedSize == 0L) {
                error("provider reports empty destination after $expectedSize bytes")
            }
            return offset
        } catch (failure: Throwable) {
            val emptyDestination = offset == 0L || isEmptyDestination(
                offset = offset,
                reportedSize = reportedSize,
                hasData = destinationHasData(resolver, destination),
            )
            val deleted = emptyDestination && deleteDocument(resolver, destination, failure)
            val cleanupNote = if (deleted) "empty destination deleted" else "destination retained"
            throw SafWriteException(offset, cleanupNote, failure)
        }
    }

    /**
     * Opens [destination] using descriptor if supported (with fd.sync),
     * or falls back to resolver.openOutputStream.
     * Returns the byte count reported by [write].
     */
    fun writeSynced(
        destination: Uri,
        write: (OutputStream) -> Long,
    ): Long {
        val descriptor = openDownloadDescriptor(resolver, destination)
        if (descriptor == null) {
            val stream = resolver.openOutputStream(destination, "w")
                ?: error("cannot open $destination for writing")
            try {
                val written = write(stream)
                stream.flush()
                return written
            } finally {
                stream.close()
            }
        }
        val stream = try {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        } catch (failure: Throwable) {
            runCatching { descriptor.close() }.onFailure(failure::addSuppressed)
            throw failure
        }
        val written: Long
        try {
            written = write(stream)
            stream.flush()
            descriptor.fileDescriptor.sync()
        } catch (failure: Throwable) {
            runCatching { stream.close() }.onFailure(failure::addSuppressed)
            throw failure
        }
        stream.close()
        return written
    }

    companion object {
        /** Pure decision helper for whether a download destination has no data. */
        fun isEmptyDestination(
            offset: Long,
            reportedSize: Long?,
            hasData: Boolean?,
        ): Boolean = offset == 0L || (reportedSize == 0L && hasData == false)

        /** Probe if provider destination can be opened and contains data. */
        fun destinationHasData(resolver: ContentResolver, destination: Uri): Boolean? =
            runCatching {
                resolver.openInputStream(destination)?.use { it.read() != -1 }
            }.getOrNull()

        /** Remove a destination document. */
        fun deleteDocument(
            resolver: ContentResolver,
            destination: Uri,
            failure: Throwable? = null,
        ): Boolean {
            val byDocuments = runCatching {
                DocumentsContract.deleteDocument(resolver, destination)
            }.also { res ->
                failure?.let { f -> res.exceptionOrNull()?.let(f::addSuppressed) }
            }.getOrDefault(false)
            if (byDocuments) return true
            return runCatching { resolver.delete(destination, null, null) }
                .also { res ->
                    failure?.let { f -> res.exceptionOrNull()?.let(f::addSuppressed) }
                }
                .getOrDefault(0) > 0
        }

        /** Open descriptor with wt then w fallback. */
        fun openDownloadDescriptor(
            resolver: ContentResolver,
            destination: Uri,
        ): ParcelFileDescriptor? {
            val truncating = try {
                resolver.openFileDescriptor(destination, "wt")
            } catch (_: FileNotFoundException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: UnsupportedOperationException) {
                null
            }
            if (truncating != null) return truncating
            return try {
                resolver.openFileDescriptor(destination, "w")
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
    }
}
