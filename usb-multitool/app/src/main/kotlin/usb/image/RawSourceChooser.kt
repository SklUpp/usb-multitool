package usb.image

import kotlinx.coroutines.CancellationException
import usb.fs.UsbFsVolume

/**
 * One regular file on the mounted source volume. No extension or MIME filter:
 * a raw image may legitimately be called anything.
 */
data class RawSourceFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
)

/**
 * Bounded, read-only scan of a mounted volume for raw-write sources.
 *
 * The scan is deliberately finite: [maxDepth] and [maxEntries] cap how long an
 * unexpected volume can hold the USB lock. [truncated] says the cap was hit, so the
 * caller can tell the user to pick through the Files tab instead of silently hiding files.
 * Unreadable subdirectories do not abort the whole listing; their paths are returned in
 * [unreadable] so the caller can surface a real diagnostic. The root listing still throws.
 */
object RawSourceChooser {
    data class Listing(
        val candidates: List<RawSourceFile>,
        val truncated: Boolean,
        val unreadable: List<String>,
    )

    fun list(
        volume: UsbFsVolume,
        root: String = "/",
        maxEntries: Int = 1000,
        maxDepth: Int = 4,
        isActive: () -> Boolean = { true },
    ): Listing {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxDepth >= 0) { "maxDepth must not be negative" }
        val found = ArrayList<RawSourceFile>()
        val unreadable = ArrayList<String>()
        var truncated = false

        fun walk(dir: String, depth: Int) {
            if (truncated) return
            checkActive(isActive)
            val entries =
                if (depth == 0 || dir == root) {
                    volume.list(dir)
                } else {
                    try {
                        volume.list(dir)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        unreadable.add("$dir: ${failure.message ?: failure.javaClass.simpleName}")
                        return
                    }
                }
            // Deterministic traversal: directories first, then files, both by name.
            for (entry in entries.sortedWith(compareByDescending<usb.fs.UsbFsDirEntry> { it.isDirectory }.thenBy { it.name.lowercase() })) {
                checkActive(isActive)
                if (entry.name == "." || entry.name == "..") continue
                val path = join(dir, entry.name)
                if (entry.isDirectory) {
                    if (depth < maxDepth) walk(path, depth + 1)
                    continue
                }
                if (found.size >= maxEntries) {
                    truncated = true
                    return
                }
                found.add(RawSourceFile(path = path, name = entry.name, sizeBytes = entry.size.coerceAtLeast(0L)))
            }
        }

        walk(root, 0)
        return Listing(
            // Full-path order is case-sensitive so "README" sorts before "archive"
            // (ASCII 'R' < 'a'). Walk order still uses lowercase names for stability.
            candidates = found.sortedBy { it.path },
            truncated = truncated,
            unreadable = unreadable,
        )
    }

    private fun checkActive(isActive: () -> Boolean) {
        if (!isActive()) throw CancellationException("raw source listing cancelled")
    }

    private fun join(dir: String, name: String): String =
        if (dir == "/" || dir.isEmpty()) "/$name" else "$dir/$name"
}
