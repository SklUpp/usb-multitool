package usb.files

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.OutputStream
import usb.saf.SafCleanupPolicy
import usb.saf.SafOpenPolicy
import usb.saf.SafOpenResult
import usb.operation.SizeMismatchException


class SafWriteException(
    val bytesWritten: Long,
    val cleanupNote: String,
    cause: Throwable,
) : Exception(cause)

/**
 * One-shot SAF destination writes for download operations:
 * [SafOpenPolicy.openDownload] (`wt`/`w`/stream), synced writes,
 * size validation, and empty-destination cleanup via [SafCleanupPolicy].
 * Held-handle writers stay on [usb.saf.SafSyncedOutput].
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
                throw SizeMismatchException("download size mismatch: got $offset, expected $expectedSize")
            }
            reportedSize = OpenableSafHelpers.openableSizeBytes(resolver, destination)
            if (expectedSize > 0L && reportedSize == 0L) {
                throw SizeMismatchException("provider reports empty destination after $expectedSize bytes")
            }
            return offset
        } catch (failure: Throwable) {
            val emptyDestination = offset == 0L || SafCleanupPolicy.isEmptyDestination(
                offset = offset,
                reportedSize = reportedSize,
                hasData = destinationHasData(resolver, destination),
            )
            val deleted = emptyDestination && deleteDocument(resolver, destination, failure)
            throw SafWriteException(offset, SafCleanupPolicy.emptyDestinationNote(deleted), failure)
        }
    }

    /**
     * Opens [destination] via [SafOpenPolicy.openDownload].
     * Descriptor path flushes then `fd.sync`; stream fallback flushes only.
     * Returns the byte count reported by [write].
     */
    fun writeSynced(
        destination: Uri,
        write: (OutputStream) -> Long,
    ): Long {
        when (val opened = SafOpenPolicy.openDownload(resolver, destination)) {
            is SafOpenResult.Stream -> {
                try {
                    val written = write(opened.stream)
                    opened.stream.flush()
                    return written
                } finally {
                    opened.stream.close()
                }
            }
            is SafOpenResult.Descriptor -> {
                val descriptor = opened.descriptor
                val stream = try {
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                } catch (failure: Throwable) {
                    try {
                        descriptor.close()
                    } catch (closeFailure: Throwable) {
                        failure.addSuppressed(closeFailure)
                    }
                    throw failure
                }
                val written: Long
                try {
                    written = write(stream)
                    stream.flush()
                    descriptor.fileDescriptor.sync()
                } catch (failure: Throwable) {
                    try {
                        stream.close()
                    } catch (closeFailure: Throwable) {
                        failure.addSuppressed(closeFailure)
                    }
                    throw failure
                }
                stream.close()
                return written
            }
        }
    }
    companion object {
        /** Probe if provider destination can be opened and contains data. */
        fun destinationHasData(resolver: ContentResolver, destination: Uri): Boolean? =
            runCatching {
                resolver.openInputStream(destination)?.use { it.read() != -1 }
            }.getOrNull()

        /** Remove a destination document. Trust-boundary: provider may reject. */
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
    }
}
