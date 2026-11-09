package usb.image

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * App-private staging for "Send to raw writer".
 *
 * Ownership is **session-scoped**: only paths returned by a successful [createUnique]
 * in this process (recorded in an in-memory registry) are [isManaged] / deletable.
 * Name patterns prove nothing — leftovers after process death are left in place.
 */
object ImageStaging {
    const val DIR_NAME = "staging"

    /** Canonical paths created by [createUnique] in this process only. */
    private val sessionOwned = ConcurrentHashMap.newKeySet<String>()

    fun stagingDir(filesDir: File): File {
        val dir = File(filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("cannot create staging directory ${dir.path}")
        }
        return dir
    }

    /**
     * Atomically create a new empty file under [stagingDir] and register it.
     * Never overwrites. Retries on [File.createNewFile] collision.
     */
    fun createUnique(filesDir: File, sourceName: String): File {
        val dir = stagingDir(filesDir)
        val safe = sanitize(sourceName)
        var lastCollision: String? = null
        repeat(16) {
            val stamp = "${System.currentTimeMillis()}-${System.nanoTime()}"
            val file = File(dir, "raw-$stamp-$safe")
            if (file.createNewFile()) {
                val key = file.canonicalFile.path
                sessionOwned.add(key)
                return file
            }
            lastCollision = file.name
        }
        throw IOException(
            "staging createNewFile exhausted retries" +
                (lastCollision?.let { " (last collision $it)" } ?: ""),
        )
    }

    /** Estimate-only gate. Real ENOSPC still surfaces during the stream. */
    fun requireUsableSpaceEstimate(filesDir: File, needBytes: Long) {
        if (needBytes <= 0L) return
        val usable = stagingDir(filesDir).usableSpace
        if (usable in 1L until needBytes) {
            throw IOException(
                "not enough free space for staging under ${stagingDir(filesDir).path}: " +
                    "usable=$usable, need≈$needBytes (estimate)",
            )
        }
    }

    /**
     * True only if [path] is under staging/ **and** was returned by [createUnique]
     * in this process. Unknown / post-restart paths → false (fail closed).
     */
    fun isManaged(filesDir: File, path: String): Boolean {
        if (path.isBlank()) return false
        val stagedRoot = stagingDir(filesDir).canonicalFile
        val candidate = File(path).canonicalFile
        if (!candidate.path.startsWith(stagedRoot.path + File.separator)) return false
        if (candidate.path == stagedRoot.path) return false
        return sessionOwned.contains(candidate.path)
    }

    /** Snapshot of session-owned canonical paths (tests / diagnostics). */
    fun sessionOwnedPaths(): Set<String> = sessionOwned.toSet()

    /** Test helper: drop registry without touching disk. */
    internal fun clearSessionRegistryForTest() {
        sessionOwned.clear()
    }

    /**
     * List regular files currently under staging/ (may include foreign / prior-run leftovers).
     * Does not delete anything.
     */
    fun listStagingFiles(filesDir: File): List<File> {
        val dir = File(filesDir, DIR_NAME)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.toList().orEmpty()
    }

    /**
     * Delete only a path registered by [createUnique] in this process.
     * Missing registered path = success (unregisters). Unmanaged = false (no throw, no delete).
     * Registered path that still exists after failed [File.delete] → [IOException] (stays registered).
     */
    fun deleteIfManaged(filesDir: File, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (!isManaged(filesDir, path)) return false
        val file = File(path).canonicalFile
        if (!file.exists()) {
            sessionOwned.remove(file.path)
            return true
        }
        if (file.delete()) {
            sessionOwned.remove(file.path)
            return true
        }
        throw IOException("failed to delete owned staging file: ${file.path}")
    }

    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString(base.length.coerceAtMost(120)) {
            for (c in base.take(120)) {
                append(if (c.isLetterOrDigit() || c in "._-+") c else '_')
            }
        }
        return cleaned.ifBlank { "image.bin" }
    }
}
