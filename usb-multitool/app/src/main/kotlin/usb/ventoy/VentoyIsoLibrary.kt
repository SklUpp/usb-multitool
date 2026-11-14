package usb.ventoy

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation

class InvalidImageNameException(message: String) : IOException(message)

class ImageAlreadyExistsException(message: String) : IOException(message)

class ImageTooLargeException(message: String) : IOException(message)

class ImageEmptyException(message: String) : IOException(message)

class ImageSizeMismatchException(message: String) : IOException(message)

/**
 * Chosen import folder is gone (or is not a directory). Raised before any write is
 * attempted: the library never creates the destination itself, so a stale picker choice
 * cannot resurrect a folder that was deleted on another host.
 */
class ImportDirectoryMissingException(message: String) : IOException(message)

data class ImageImportOutcome(
    val targetPath: String,
    val bytes: Long,
    val replaced: Boolean,
)

/**
 * Safe copy of a phone-selected file onto the Ventoy data partition.
 *
 * The file is written beside its target under a hidden temporary name and renamed
 * only after a durable sync. If a file already exists, it is renamed aside first and
 * restored on every failure (including cancellation); an interrupted import never
 * truncates or corrupts the file that was already there. No extension or directory
 * filter is implied: [importDirectory] is the caller's chosen destination and
 * [requireImportName] only rejects names that cannot be a plain child of it.
 */
object VentoyIsoLibrary {
    /** Default destination: the conventional Ventoy image folder. */
    const val ISO_DIR = "/ISO"

    /**
     * A picked DISPLAY_NAME is provider-controlled; accept only a plain child name.
     * Ventoy's auto_install image path supports any name and directory, so no
     * extension check is applied here.
     */
    fun requireImportName(rawName: String): String {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isEmpty() || name == "." || name == "..") {
            throw InvalidImageNameException("invalid file name \"$rawName\"")
        }
        if (name.contains('\u0000')) {
            throw InvalidImageNameException("file name contains NUL")
        }
        return name
    }

    /**
     * Copy [input] into [importDirectory]/<rawName>.
     *
     * @param expectedSize provider-reported bytes; enforced exactly when positive.
     * @param maxBytes hard ceiling (data-partition capacity); checked before mutation.
     * @param overwrite must be true to replace an existing file; the caller owns that
     *        explicit confirmation.
     */
    fun import(
        volume: UsbFsVolume,
        input: InputStream,
        rawName: String,
        operation: DiskOperation,
        overwrite: Boolean,
        expectedSize: Long? = null,
        maxBytes: Long? = null,
        importDirectory: String = ISO_DIR,
        chunkBytes: Int = usb.device.TransferTuning.jniCapBytes,
        onCleanupFailure: (Throwable) -> Unit = {},
    ): ImageImportOutcome {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        val directory = normalizeDirectory(importDirectory)
        val name = requireImportName(rawName)
        val expected = expectedSize?.takeIf { it > 0L }
        if (expected != null && maxBytes != null && expected > maxBytes) {
            throw ImageTooLargeException(
                "File is $expected bytes; target partition holds at most $maxBytes",
            )
        }

        val target = if (directory == "/") "/$name" else "$directory/$name"
        require(!target.equals(VentoySetup.CONFIG_PATH, ignoreCase = true)) {
            "refusing to import over the Ventoy config"
        }
        val existing = volume.statOrNull(target)
        if (existing != null && existing.isDirectory) {
            throw IOException("$target is a directory")
        }
        if (existing != null && !overwrite) {
            throw ImageAlreadyExistsException("$target already exists; overwrite was not confirmed")
        }

        // Fail closed before mutation: the destination folder must already exist (root
        // always does). It is never created here — a concurrent delete must not be undone.
        val directoryEntry = volume.statOrNull(directory)
        if (directory != "/" && (directoryEntry == null || !directoryEntry.isDirectory)) {
            throw ImportDirectoryMissingException(
                "import folder \"$directory\" does not exist on the Ventoy partition; pick it again",
            )
        }

        operation.mutationAttempted = true

        // Fixed-length temps: never embed [name] so near-255 LFN stems stay valid.
        val temp = uniqueShortChild(volume, directory, "i")
        val backup = uniqueShortChild(volume, directory, "b")
        val cleanupErrors = ArrayList<Throwable>()
        fun cleanupFailure(error: Throwable) {
            cleanupErrors.add(error)
            runCatching { onCleanupFailure(error) }
        }

        var tempOwned = false
        var backupMoved = false
        var targetMoved = false
        var committed = false
        try {
            input.use { stream ->
                val buffer = ByteArray(chunkBytes)
                var written = 0L
                var created = false
                while (true) {
                    if (!operation.isActive()) {
                        throw CancellationException("Import cancelled")
                    }
                    val want =
                        if (maxBytes == null) {
                            buffer.size
                        } else {
                            val remaining = maxBytes - written
                            if (remaining < buffer.size.toLong()) {
                                (remaining + 1L).coerceAtMost(buffer.size.toLong()).toInt()
                            } else {
                                buffer.size
                            }
                        }
                    var read = stream.read(buffer, 0, want)
                    if (read == 0) {
                        val single = stream.read()
                        if (single < 0) break
                        buffer[0] = single.toByte()
                        read = 1
                    }
                    if (read < 0) break
                    if (maxBytes != null && written + read > maxBytes) {
                        throw ImageTooLargeException(
                            "Stream exceeds target partition capacity $maxBytes at byte ${written + read}",
                        )
                    }
                    if (expected != null && written + read > expected) {
                        throw ImageSizeMismatchException(
                            "Stream is larger than the provider-reported $expected bytes",
                        )
                    }
                    if (!created) {
                        // Phase 1: exclusive empty create — ownership only after success.
                        volume.write(
                            path = temp,
                            data = ByteArray(0),
                            create = true,
                            exclusiveCreate = true,
                        )
                        tempOwned = true
                        created = true
                    }
                    val data = if (read == buffer.size) buffer else buffer.copyOf(read)
                    volume.write(
                        path = temp,
                        data = data,
                        offset = written,
                        create = false,
                        plannedTotalSize = expected ?: (written + read),
                    )
                    written += read
                    operation.report(written)
                }
                if (!created) {
                    volume.write(
                        path = temp,
                        data = ByteArray(0),
                        create = true,
                        exclusiveCreate = true,
                    )
                    tempOwned = true
                    created = true
                }
                if (written == 0L) throw ImageEmptyException("Selected file is empty")
                if (expected != null && written != expected) {
                    throw ImageSizeMismatchException(
                        "File is $written bytes but the provider reported $expected; nothing was replaced",
                    )
                }
                if (!operation.isActive()) {
                    throw CancellationException("Import cancelled before commit")
                }
                volume.sync()
                // Commit boundary: after this point cancellation must not replace/rollback
                // a fully written file; the host treats the result as completed.
                if (!operation.isActive()) {
                    throw CancellationException("Import cancelled before commit")
                }

                // Install target under cancel-disabled window. commitReached only after
                // target is in place and synced — premature marker made cancel look Completed
                // while rollback was still possible.
                if (existing != null) {
                    volume.rename(target, backup)
                    backupMoved = true
                }
                volume.rename(temp, target)
                targetMoved = true
                volume.sync()
                committed = true
                operation.commitReached = true

                if (backupMoved) {
                    try {
                        volume.delete(backup)
                        backupMoved = false
                        volume.sync()
                    } catch (cleanup: Throwable) {
                        // Target is already complete and synced; a leftover backup is noise.
                        cleanupFailure(cleanup)
                    }
                }
            }
            return ImageImportOutcome(
                targetPath = target,
                bytes = expected ?: volume.stat(target).size,
                replaced = existing != null,
            )
        } catch (failure: Throwable) {
            if (!committed) {
                if (targetMoved) safe(cleanupErrors, onCleanupFailure) { removeIfPresent(volume, target) }
                if (backupMoved) safe(cleanupErrors, onCleanupFailure) { volume.rename(backup, target) }
                if (tempOwned) safe(cleanupErrors, onCleanupFailure) { removeIfPresent(volume, temp) }
                safe(cleanupErrors, onCleanupFailure) { volume.sync() }
            }
            cleanupErrors.forEach(failure::addSuppressed)
            throw failure
        }
    }

    internal fun normalizeDirectory(directory: String): String {
        val trimmed = directory.trim()
        require(trimmed.isNotEmpty()) { "import directory is empty" }
        val absolute = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val segments = absolute.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) {
            "import directory must not contain . or .. segments: $directory"
        }
        return "/" + segments.joinToString("/")
    }

    private fun uniqueChild(volume: UsbFsVolume, dir: String, name: String): String {
        fun child(suffix: String): String =
            if (dir == "/") "/$name$suffix" else "$dir/$name$suffix"
        var candidate = child("")
        var suffix = 0
        while (volume.statOrNull(candidate) != null) {
            suffix++
            if (suffix > 16) throw IOException("cannot allocate a temporary name under $dir")
            candidate = child("-$suffix")
        }
        return candidate
    }

    /** Fixed-length unique sibling; never embeds the destination basename. */
    private fun uniqueShortChild(volume: UsbFsVolume, dir: String, kind: String): String {
        val nonce = "${System.currentTimeMillis().toString(36)}-${System.nanoTime().toString(36)}"
        val base = ".x$kind-$nonce"
        return uniqueChild(volume, dir, base)
    }

    private fun removeIfPresent(volume: UsbFsVolume, path: String) {
        if (volume.statOrNull(path) != null) volume.delete(path)
    }

    private inline fun safe(
        errors: MutableList<Throwable>,
        onCleanupFailure: (Throwable) -> Unit,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: Throwable) {
            errors.add(failure)
            runCatching { onCleanupFailure(failure) }
        }
    }
}
