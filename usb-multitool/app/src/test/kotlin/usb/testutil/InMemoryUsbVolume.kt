package usb.testutil

import java.io.IOException
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume

/**
 * Small in-memory volume for chooser/import tests. Paths are absolute; directories are
 * implicit when they contain children. Failure hooks let a test prove rollback paths.
 */
class InMemoryUsbVolume(
    initial: Map<String, ByteArray> = emptyMap(),
) : UsbFsVolume {
    private val files = linkedMapOf<String, ByteArray>()
    private val directories = linkedSetOf<String>("/")
    private val implicitDirectories = linkedSetOf<String>()

    var failWriteAtPath: String? = null
    var failRenameFrom: String? = null
    var failDeletePath: String? = null
    var unreadableDirectories: Set<String> = emptySet()
    var syncCount = 0
        private set

    init {
        for ((path, bytes) in initial) putFile(path, bytes)
    }

    fun putFile(path: String, bytes: ByteArray) {
        addParents(path)
        files[path] = bytes.copyOf()
    }

    fun fileBytes(path: String): ByteArray? = files[path]?.copyOf()

    fun paths(): Set<String> = files.keys.toSet()

    override fun list(path: String): List<UsbFsDirEntry> {
        val dir = normalize(path)
        if (dir in unreadableDirectories) throw IOException("unreadable $dir")
        require(directories.contains(dir) || implicitDirectories.contains(dir)) { "no dir $dir" }
        val prefix = if (dir == "/") "/" else "$dir/"
        val filesHere =
            files.keys
                .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map { full ->
                    val name = full.removePrefix(prefix)
                    UsbFsDirEntry(name, isDirectory = false, size = files.getValue(full).size.toLong())
                }
        // putFile only records parents in implicitDirectories; expose them as dir entries
        // so chooser/walk can recurse the same way a real volume would.
        val dirsHere =
            (directories + implicitDirectories)
                .filter { it != dir && it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map { it.removePrefix(prefix) }
                .distinct()
                .map { UsbFsDirEntry(it, isDirectory = true, size = 0L) }
        return filesHere + dirsHere
    }

    override fun stat(path: String): UsbFsDirEntry {
        val normalized = normalize(path)
        files[normalized]?.let { return UsbFsDirEntry(normalized.substringAfterLast('/'), false, it.size.toLong()) }
        if (directories.contains(normalized) || implicitDirectories.contains(normalized)) {
            return UsbFsDirEntry(normalized.substringAfterLast('/').ifEmpty { "/" }, true, 0L)
        }
        throw IOException("no file $normalized")
    }

    override fun statOrNull(path: String): UsbFsDirEntry? =
        try {
            stat(path)
        } catch (_: IOException) {
            null
        }

    override fun mkdir(path: String) {
        val normalized = normalize(path)
        addParents(normalized)
        directories.add(normalized)
    }

    override fun delete(path: String) {
        val normalized = normalize(path)
        if (failDeletePath == normalized) throw IOException("delete failed")
        if (files.remove(normalized) == null) throw IOException("no file $normalized")
    }

    override fun rename(from: String, to: String) {
        val source = normalize(from)
        val target = normalize(to)
        if (failRenameFrom == source) throw IOException("rename failed")
        val bytes = files[source] ?: throw IOException("no file $source")
        if (files.containsKey(target)) throw IOException("target exists $target")
        files.remove(source)
        putFile(target, bytes)
    }

    override fun read(path: String, offset: Long, length: Int): ByteArray {
        val bytes = files[normalize(path)] ?: throw IOException("no file $path")
        if (offset >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }

    override fun write(
        path: String,
        data: ByteArray,
        offset: Long,
        create: Boolean,
        plannedTotalSize: Long,
    ) {
        val normalized = normalize(path)
        if (failWriteAtPath == normalized) throw IOException("write failed")
        val existing = files[normalized]
        if (existing == null && !create) throw IOException("missing $normalized")
        val base =
            if (existing == null) {
                addParents(normalized)
                ByteArray(offset.toInt())
            } else {
                existing.copyOf()
            }
        val end = offset.toInt() + data.size
        val grown = if (end <= base.size) base else base.copyOf(end)
        data.copyInto(grown, offset.toInt())
        files[normalized] = grown
    }

    override fun sync() {
        syncCount++
    }

    override fun close() = Unit

    private fun addParents(path: String) {
        var parent = normalize(path).substringBeforeLast('/', "/")
        while (parent != "/") {
            implicitDirectories.add(parent)
            parent = parent.substringBeforeLast('/', "/")
        }
    }

    private fun normalize(path: String): String {
        val absolute = if (path.startsWith("/")) path else "/$path"
        return if (absolute.length > 1) absolute.trimEnd('/') else absolute
    }
}
