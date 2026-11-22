package usb.ventoy

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal ustar reader for the official Ventoy `-linux.tar.gz` release archives.
 *
 * Members are located by base name, so an upstream directory reshuffle
 * (`boot/boot.img` → `boot.img`) cannot silently break extraction. Only regular
 * files are returned; the stream is consumed once and never fully buffered.
 */
internal object VentoyReleaseArchive {
    private const val BLOCK = 512
    private const val MAX_MEMBER_BYTES = 64L * 1024 * 1024

    /** Whole-stream bound: the downloaded archive is already capped at 512 MiB. */
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    /**
     * Extracts the members whose base name is in [wantedBaseNames].
     * Throws [VentoyReleaseArchiveException] for a truncated archive, a member
     * larger than [maxMemberBytes], a gzip stream expanding past [maxTotalBytes],
     * or a wanted member that is missing.
     */
    fun extract(
        tarGz: InputStream,
        wantedBaseNames: Set<String>,
        maxMemberBytes: Long = MAX_MEMBER_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): Map<String, ByteArray> {
        val found = linkedMapOf<String, ByteArray>()
        var consumed = 0L
        fun consume(bytes: Long) {
            consumed += bytes
            if (consumed > maxTotalBytes) {
                throw VentoyReleaseArchiveException(
                    "archive expands past the $maxTotalBytes byte limit",
                )
            }
        }
        GZIPInputStream(tarGz.buffered()).use { gz ->
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readBlock(gz, header)) {
                    throw VentoyReleaseArchiveException("Truncated archive header")
                }
                consume(BLOCK.toLong())
                if (header.all { it == 0.toByte() }) break

                val name = cString(header, 0, 100)
                val prefix = cString(header, 345, 155)
                val typeFlag = header[156].toInt().toChar()
                val size = octal(header, 124, 12)
                val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
                val baseName = fullName.substringAfterLast('/')
                consume(size + padding(size))

                val wanted = (typeFlag == '0' || typeFlag == '\u0000') && baseName in wantedBaseNames
                if (wanted && size > maxMemberBytes) {
                    throw VentoyReleaseArchiveException(
                        "$fullName is $size bytes; the archive member limit is $maxMemberBytes",
                    )
                }
                if (wanted) {
                    if (found.containsKey(baseName)) {
                        throw VentoyReleaseArchiveException(
                            "Release archive contains duplicate member $baseName",
                        )
                    }
                    val data = ByteArray(size.toInt())
                    if (!readExactly(gz, data)) {
                        throw VentoyReleaseArchiveException("Truncated data for $fullName")
                    }
                    found[baseName] = data
                    skip(gz, padding(size))
                } else {
                    skip(gz, size + padding(size))
                }
            }
        }
        val missing = wantedBaseNames - found.keys
        if (missing.isNotEmpty()) {
            throw VentoyReleaseArchiveException(
                "Release archive is missing ${missing.joinToString(", ")}",
            )
        }
        return found
    }

    class VentoyReleaseArchiveException(message: String) : Exception(message)

    private fun padding(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK

    private fun cString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: offset + length
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in offset until offset + length) {
            val c = header[i].toInt()
            if (c == 0 || c == ' '.code) {
                if (value != 0L) break else continue
            }
            if (c < '0'.code || c > '7'.code) break
            value = value * 8 + (c - '0'.code)
        }
        return value
    }

    private fun readBlock(input: InputStream, buffer: ByteArray): Boolean = readExactly(input, buffer)

    private fun readExactly(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skip(input: InputStream, bytes: Long) {
        var remaining = bytes
        val sink = ByteArray(BLOCK)
        while (remaining > 0L) {
            val n = input.read(sink, 0, minOf(remaining, BLOCK.toLong()).toInt())
            if (n < 0) throw VentoyReleaseArchiveException("Truncated archive payload")
            remaining -= n
        }
    }
}
