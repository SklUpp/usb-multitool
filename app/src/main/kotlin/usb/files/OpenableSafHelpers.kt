package usb.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import usb.fs.FilesystemSpace
import usb.fs.UsbFsUsageVolume
import usb.fs.UsbFsVolume

/** SAF openable metadata + free-space preflight shared by upload/download paths. */
internal object OpenableSafHelpers {
    /**
     * SAF/content size when the provider reports a non-negative value; null when
     * unknown so upload can skip free-space preflight instead of guessing.
     */
    fun openableSizeBytes(resolver: ContentResolver, uri: Uri): Long? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) return null
                cursor.getLong(index).takeIf { it >= 0L }
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun openableDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0 || cursor.isNull(index)) return null
                cursor.getString(index)
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * When [needBytes] is known and [volume] can report free space, refuse before the
     * first write. Missing usage support, failed space queries, or unknown size leave
     * the stream path unchanged (no invented reject).
     */
    fun requireFreeSpaceBeforeWrite(volume: UsbFsVolume, needBytes: Long?) {
        if (needBytes == null || needBytes < 0L) return
        if (needBytes == 0L) return
        val usage = volume as? UsbFsUsageVolume ?: return
        val space: FilesystemSpace =
            try {
                usage.space()
            } catch (_: Throwable) {
                return
            }
        if (space.freeBytes < needBytes.toULong()) {
            throw IOException(FilesOperationPolicy.insufficientSpaceMessage(space.freeBytes, needBytes))
        }
    }
}
