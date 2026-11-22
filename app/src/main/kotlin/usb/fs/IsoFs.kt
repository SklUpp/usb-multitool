package usb.fs

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import usb.device.BlockDevice

internal const val MAX_ISO_IO_BYTES: Int = 8 * 1024 * 1024
private const val ISO_DESCRIPTOR_SECTOR = 16
private const val ISO_DESCRIPTOR_SIZE = 2048
private const val ISO_DESCRIPTOR_ID = "CD001"
private const val ISO_MAX_DIRECTORY_BYTES = 32 * 1024 * 1024
private const val ISO_MAX_DESCRIPTORS = 64

/**
 * Read-only ISO9660/Joliet volume for Rufus-style hybrid images and whole-disk ISO writes.
 * Directory records and files are read directly from the block device; no native engine and
 * no mounting are involved. Joliet names are preferred when a supplementary descriptor is
 * present, with the primary descriptor as fallback.
 */
class IsoVolume(
    private val device: BlockDevice,
) : UsbFsVolume {
    private class Extent(val lba: ULong, val size: Long)

    private class Entry(
        val name: String,
        val version: Int,
        val directory: Boolean,
        val extents: MutableList<Extent>,
        var size: Long,
        val directorySize: Long,
    )

    private class Descriptor(
        val blockSize: Int,
        val root: Entry,
        val joliet: Boolean,
        val label: String,
    )

    private val descriptor: Descriptor = readDescriptor()
    private val blockSize: Int get() = descriptor.blockSize
    private val pathCache = HashMap<String, Entry>()
    private val directoryCache = HashMap<String, List<Entry>>()

    val volumeLabel: String get() = descriptor.label

    override fun list(path: String): List<UsbFsDirEntry> {
        val normalized = normalize(path)
        val entry = resolve(normalized)
        if (!entry.directory) throw IOException("not a directory: $path")
        return directoryEntries(entry, normalized).map {
            UsbFsDirEntry(
                name = it.name,
                isDirectory = it.directory,
                size = if (it.directory) 0L else it.size,
            )
        }
    }

    override fun stat(path: String): UsbFsDirEntry = resolve(path).let {
        UsbFsDirEntry(it.name, it.directory, if (it.directory) 0L else it.size)
    }

    override fun statOrNull(path: String): UsbFsDirEntry? =
        try {
            stat(path)
        } catch (_: FileNotFoundException) {
            null
        }

    override fun read(path: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0L) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        require(length <= MAX_ISO_IO_BYTES) { "request too large: $length > $MAX_ISO_IO_BYTES" }
        val entry = resolve(path)
        if (entry.directory) throw IOException("cannot read a directory: $path")
        if (length == 0 || offset >= entry.size) return ByteArray(0)
        val count = minOf(length.toLong(), entry.size - offset).toInt()
        val result = ByteArray(count)
        var sourceOffset = offset
        var target = 0
        var extentStart = 0L
        for (extent in entry.extents) {
            val extentEnd = extentStart + extent.size
            if (sourceOffset < extentEnd) {
                val within = sourceOffset - extentStart
                val take = minOf(count.toLong() - target, extentEnd - sourceOffset).toInt()
                val bytes = readBytes(
                    extent.lba * blockSize.toULong() + within.toULong(),
                    take,
                )
                bytes.copyInto(result, target)
                target += bytes.size
                sourceOffset += bytes.size
                if (target == count) break
            }
            extentStart = extentEnd
        }
        if (target != count) throw IOException("short ISO extent read: $target of $count")
        return result
    }

    override fun mkdir(path: String) = readOnly()
    override fun delete(path: String) = readOnly()
    override fun rename(from: String, to: String) = readOnly()
    override fun write(
        path: String,
        data: ByteArray,
        offset: Long,
        create: Boolean,
        plannedTotalSize: Long,
        exclusiveCreate: Boolean,
    ) = readOnly()

    override fun sync() = Unit

    override fun close() = Unit

    private fun readOnly(): Nothing = throw IOException("ISO9660 is read-only")

    private fun resolve(path: String): Entry {
        val normalized = normalize(path)
        if (normalized == "/") return descriptor.root
        pathCache[normalized]?.let { return it }
        var current = descriptor.root
        var currentPath = "/"
        for (part in normalized.trim('/').split('/')) {
            if (part.isEmpty()) continue
            if (!current.directory) throw FileNotFoundException(normalized)
            current = directoryEntries(current, currentPath)
                .firstOrNull { it.name.equals(part, ignoreCase = true) }
                ?: throw FileNotFoundException(normalized)
            currentPath = if (currentPath == "/") "/$part" else "$currentPath/$part"
        }
        pathCache[normalized] = current
        return current
    }

    private fun directoryEntries(entry: Entry, path: String): List<Entry> {
        directoryCache[path]?.let { return it }
        if (!entry.directory) throw FileNotFoundException(path)
        val entries = parseDirectory(readDirectoryBytes(entry))
        directoryCache[path] = entries
        return entries
    }

    private fun readDirectoryBytes(entry: Entry): ByteArray {
        val total = entry.extents.sumOf { it.size }
        if (total > ISO_MAX_DIRECTORY_BYTES) {
            throw IOException("ISO directory too large: $total bytes")
        }
        val result = ByteArray(total.toInt())
        var target = 0
        for (extent in entry.extents) {
            val bytes = readBytes(extent.lba * blockSize.toULong(), extent.size.toInt())
            bytes.copyInto(result, target)
            target += bytes.size
        }
        return result
    }

    private fun parseDirectory(bytes: ByteArray): List<Entry> {
        val result = LinkedHashMap<String, Entry>()
        var lastEntry: Entry? = null
        var lastWasMultiExtent = false
        var position = 0
        while (position < bytes.size) {
            val length = bytes[position].toInt() and 0xFF
            if (length == 0) {
                val next = ((position / blockSize) + 1) * blockSize
                if (next <= position) break
                position = next
                continue
            }
            if (length < 34 || position + length > bytes.size) {
                throw IOException("malformed ISO directory record at $position")
            }
            val extentLba = u32(bytes, position + 2)
            val dataLength = u32(bytes, position + 10).toLong()
            val flags = bytes[position + 25].toInt() and 0xFF
            val nameLength = bytes[position + 32].toInt() and 0xFF
            if (position + 33 + nameLength > position + length) {
                throw IOException("malformed ISO name at $position")
            }
            val rawName = bytes.copyOfRange(
                position + 33,
                position + 33 + nameLength,
            )
            // ISO self/parent records are one-byte identifiers 0x00 and 0x01, not dots.
            val special = nameLength == 1 &&
                (rawName[0].toInt() and 0xFF) in setOf(0x00, 0x01)
            val decoded = if (special) "" else decodeName(rawName)
            val version = decoded.substringAfterLast(';', "").toIntOrNull() ?: 1
            val name = decoded
                .trimEnd(' ', '.')
                .substringBefore(';')
                .takeIf { !special && it.isNotEmpty() && it != "." && it != ".." }
            if (name != null) {
                val key = name.lowercase(java.util.Locale.ROOT)
                val multiExtent = flags and 0x80 != 0
                val existing = result[key]
                // Concatenate only the immediately preceding multi-extent record of the same
                // version. Distinct versions (FILE;1 / FILE;2) keep the highest, never merge.
                val entry = when {
                    existing != null &&
                        existing === lastEntry &&
                        existing.version == version &&
                        existing.directory == (flags and 0x02 != 0) &&
                        lastWasMultiExtent -> existing
                    existing != null && existing.version > version -> null
                    else -> Entry(
                        name = name,
                        version = version,
                        directory = flags and 0x02 != 0,
                        extents = mutableListOf(),
                        size = 0L,
                        directorySize = if (flags and 0x02 != 0) dataLength else 0L,
                    ).also { result[key] = it }
                }
                if (entry != null) {
                    entry.extents += Extent(extentLba, dataLength)
                    entry.size += dataLength
                    lastEntry = entry
                    lastWasMultiExtent = multiExtent
                } else {
                    lastEntry = null
                    lastWasMultiExtent = false
                }
            }
            position += length
        }
        return result.values.toList()
    }

    private fun decodeName(raw: ByteArray): String =
        if (descriptor.joliet && raw.size % 2 == 0) {
            String(raw, Charsets.UTF_16BE)
        } else {
            String(raw, Charsets.ISO_8859_1)
        }

    private fun readDescriptor(): Descriptor {
        var primary: Descriptor? = null
        var joliet: Descriptor? = null
        for (index in 0 until ISO_MAX_DESCRIPTORS) {
            val offset = (ISO_DESCRIPTOR_SECTOR + index) * ISO_DESCRIPTOR_SIZE
            val bytes = readBytes(offset.toULong(), ISO_DESCRIPTOR_SIZE)
            if (String(bytes, 1, 5, Charsets.US_ASCII) != ISO_DESCRIPTOR_ID) {
                throw IOException("missing ISO volume descriptor at $offset")
            }
            val type = bytes[0].toInt() and 0xFF
            when (type) {
                1 -> primary = primary ?: parseDescriptor(bytes, joliet = false)
                2 -> if (isJoliet(bytes)) joliet = joliet ?: parseDescriptor(bytes, joliet = true)
                255 -> break
            }
            if (type == 255) break
        }
        return joliet ?: primary ?: throw IOException("ISO volume has no primary descriptor")
    }

    private fun parseDescriptor(bytes: ByteArray, joliet: Boolean): Descriptor {
        val blockSize = u16(bytes, 128)
        if (blockSize !in 512..4096 || blockSize and (blockSize - 1) != 0) {
            throw IOException("unsupported ISO logical block size: $blockSize")
        }
        val rootLength = bytes[156].toInt() and 0xFF
        if (rootLength < 34) throw IOException("malformed ISO root directory record")
        val root = parseRoot(bytes.copyOfRange(156, 156 + rootLength))
        val volumeId = String(bytes, 40, 32, Charsets.US_ASCII).trimEnd(' ', '\u0000')
        return Descriptor(blockSize, root, joliet, volumeId)
    }

    private fun isJoliet(bytes: ByteArray): Boolean {
        val escape = String(bytes, 88, 3, Charsets.US_ASCII)
        return escape == "%/@" || escape == "%/C" || escape == "%/E"
    }

    private fun parseRoot(record: ByteArray): Entry {
        if (record.size < 34) throw IOException("malformed ISO root record")
        val extent = u32(record, 2)
        val size = u32(record, 10).toLong()
        return Entry(
            name = "/",
            version = 1,
            directory = true,
            extents = mutableListOf(Extent(extent, size)),
            size = size,
            directorySize = size,
        )
    }

    private fun readBytes(offset: ULong, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val sectorSize = device.sectorSize
        val startSector = offset / sectorSize.toULong()
        val inSector = (offset % sectorSize.toULong()).toInt()
        val total = inSector + length
        val sectors = (total + sectorSize - 1) / sectorSize
        val raw = ByteArray(sectors * sectorSize)
        try {
            device.read(startSector, ByteBuffer.wrap(raw))
        } catch (failure: RuntimeException) {
            throw IOException("ISO read failed at $offset", failure)
        }
        return raw.copyOfRange(inSector, inSector + length)
    }

    private fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) throw FileNotFoundException(path)
        return "/" + parts.joinToString("/")
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): ULong =
        (bytes[offset].toULong() and 0xFFu) or
            ((bytes[offset + 1].toULong() and 0xFFu) shl 8) or
            ((bytes[offset + 2].toULong() and 0xFFu) shl 16) or
            ((bytes[offset + 3].toULong() and 0xFFu) shl 24)

    companion object {
        /** Signature-only probe, safe to run on any volume's sector 16. */
        fun probe(device: BlockDevice): Boolean =
            try {
                val offset = (ISO_DESCRIPTOR_SECTOR * ISO_DESCRIPTOR_SIZE).toULong()
                val sectorSize = device.sectorSize
                val start = offset / sectorSize.toULong()
                val inSector = (offset % sectorSize.toULong()).toInt()
                val raw = ByteArray(sectorSize * 2)
                device.read(start, ByteBuffer.wrap(raw))
                raw[inSector].toInt() == 1 &&
                    String(raw, inSector + 1, 5, Charsets.US_ASCII) == ISO_DESCRIPTOR_ID
            } catch (_: Throwable) {
                false
            }
    }
}
