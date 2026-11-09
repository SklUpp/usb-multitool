package usb.ventoy

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Acquires official Ventoy release files. Every path either yields assets whose
 * SHA-256 digests match the pinned release table or throws — synthetic assets are
 * never substituted for missing prerequisites.
 */
object VentoyDownloader {
    const val DEFAULT_BASE_URL = "https://github.com/ventoy/Ventoy/releases/download"
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5

    /** A pinned release: where to fetch it, and the digests that make it trustworthy. */
    data class ReleaseSource(
        val release: String,
        val commit: String,
        val urls: List<String>,
        val archiveSha256: String,
        val memberHashes: Map<String, String>,
    )

    data class VentoyReleaseDownload(
        val assets: VentoyAssets,
        val archiveSha256: String,
        val sourceUrl: String,
    )

    val RELEASES: List<ReleaseSource> =
        listOf(
            ReleaseSource(
                release = VentoyAssets.RELEASE_1_1_05,
                commit = "",
                urls =
                    listOf(
                        "$DEFAULT_BASE_URL/v1.1.05/ventoy-1.1.05-linux.tar.gz",
                        "https://sourceforge.net/projects/ventoy/files/v1.1.05/ventoy-1.1.05-linux.tar.gz/download",
                    ),
                archiveSha256 = RELEASE_1_1_05_ARCHIVE_SHA256,
                memberHashes = VentoyAssets.RELEASE_1_1_05_HASHES,
            ),
            ReleaseSource(
                release = VentoyAssets.PINNED_RELEASE,
                commit = VentoyAssets.PINNED_COMMIT,
                urls = listOf("$DEFAULT_BASE_URL/v1.1.18/ventoy-1.1.18-linux.tar.gz"),
                archiveSha256 = RELEASE_1_1_18_ARCHIVE_SHA256,
                memberHashes = VentoyAssets.RELEASE_1_1_18_HASHES,
            ),
        )

    fun sourceFor(release: String): ReleaseSource =
        RELEASES.firstOrNull { it.release == release }
            ?: throw IllegalArgumentException(
                "Release $release has no pinned digests; supported: ${RELEASES.joinToString(", ") { it.release }}",
            )

    /** Loads Ventoy assets from a directory on external storage. */
    fun loadFromExternalStorage(dir: File, strict: Boolean = true): VentoyAssets {
        require(dir.exists() && dir.isDirectory) { "External directory not found: ${dir.path}" }
        return VentoyAssets.loadFromDirectory(dir, strict = strict)
    }

    /**
     * Streams the pinned release archive into [cacheDir], verifies the archive and
     * every member digest, and returns validated assets. The temporary archive is
     * always deleted. [onProgress] receives (bytesWritten, contentLength or -1).
     */
    fun downloadRelease(
        cacheDir: File,
        release: String = VentoyAssets.PINNED_RELEASE,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ): VentoyReleaseDownload {
        val source = sourceFor(release)
        cacheDir.mkdirs()
        if (!cacheDir.isDirectory) {
            throw VentoyAssetsMissingException("Cache directory ${cacheDir.path} is not writable")
        }
        val archive = File.createTempFile("ventoy-${source.release}-", ".tar.gz", cacheDir)
        try {
            var archiveSha: String? = null
            var sourceUrl: String? = null
            val failures = mutableListOf<String>()
            for (url in source.urls) {
                try {
                    archiveSha = fetch(url, archive, onProgress, isActive)
                    sourceUrl = url
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures += "$url: ${e.message}"
                    archive.delete()
                }
            }
            if (archiveSha == null || sourceUrl == null) {
                throw VentoyAssetsMissingException(
                    "Download of Ventoy ${source.release} failed from every mirror. " + failures.joinToString("; "),
                )
            }
            if (!archiveSha.equals(source.archiveSha256, ignoreCase = true)) {
                throw VentoyAssetsMissingException(
                    "Downloaded Ventoy ${source.release} archive SHA-256 $archiveSha does not match the " +
                        "pinned ${source.archiveSha256}; refusing to install it",
                )
            }

            val members =
                VentoyReleaseArchive.extract(archive.inputStream().buffered(), source.memberHashes.keys)
            for ((name, expected) in source.memberHashes) {
                val actual = sha256Hex(members.getValue(name))
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw VentoyAssetsMissingException(
                        "Release member $name SHA-256 $actual does not match the pinned $expected",
                    )
                }
            }

            val assets =
                VentoyAssets(
                    release = source.release,
                    commit = source.commit.ifEmpty { VentoyAssets.PINNED_COMMIT },
                    bootImg = members.getValue("boot.img"),
                    coreImg = members.getValue("core.img.xz"),
                    efiPartImg = members.getValue("ventoy.disk.img.xz"),
                    bootImgSha256 = source.memberHashes.getValue("boot.img"),
                    coreImgSha256 = source.memberHashes.getValue("core.img.xz"),
                    diskImgSha256 = source.memberHashes.getValue("ventoy.disk.img.xz"),
                )
            assets.validateChecksums(strict = true)
            return VentoyReleaseDownload(assets, archiveSha, sourceUrl)
        } finally {
            archive.delete()
        }
    }

    /**
     * Loads the pinned release from external storage, then from the APK assets.
     * Never reaches the network implicitly; call [downloadRelease] for that.
     */
    fun loadWithFallback(
        externalDir: File? = null,
        assetManager: android.content.res.AssetManager? = null,
        strict: Boolean = true,
    ): VentoyAssets {
        val failures = mutableListOf<String>()
        if (externalDir != null && externalDir.exists() && externalDir.isDirectory) {
            runCatching { loadFromExternalStorage(externalDir, strict) }
                .onSuccess { return it }
                .onFailure { failures += "${externalDir.path}: ${it.message}" }
        }
        if (assetManager != null) {
            runCatching { VentoyAssets.loadFromAssets(assetManager, strict = strict) }
                .onSuccess { return it }
                .onFailure { failures += "APK assets/ventoy: ${it.message}" }
        }
        throw VentoyAssetsMissingException(
            "Ventoy release assets are not available locally. Expected boot.img, core.img.xz and " +
                "ventoy.disk.img.xz in ${externalDir?.path ?: "<external storage>/ventoy"} or APK assets/ventoy, " +
                "or download a pinned release from the server. " + failures.joinToString("; "),
        )
    }

    /**
     * Queries the GitHub Releases API for the latest Ventoy release tag.
     * Returns the version string (e.g. "1.1.19") without the "v" prefix, or
     * null if the check fails (network error, parse failure, timeout).
     * Never throws; failures are silently swallowed so callers can fall back
     * to [VentoyAssets.PINNED_RELEASE].
     */
    fun checkLatestRelease(timeoutMs: Int = 8_000): String? = runCatching {
        val url = URL("https://api.github.com/repos/ventoy/Ventoy/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "usb-multitool")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val body = conn.inputStream.bufferedReader().readText()
            // Parse "tag_name":"v1.1.19" without a JSON library.
            val match = Regex(""""tag_name"\s*:\s*"v?([^"]+)"""").find(body)
            match?.groupValues?.getOrNull(1)?.trim()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun fetch(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit,
        isActive: () -> Boolean,
    ): String {
        val connection = openConnection(url)
        try {
            val total = connection.contentLengthLong
            if (total > MAX_ARCHIVE_BYTES) {
                throw VentoyAssetsMissingException(
                    "Archive reports $total bytes; limit is $MAX_ARCHIVE_BYTES",
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (!isActive()) {
                            throw CancellationException("Ventoy download cancelled")
                        }
                        written += read
                        if (written > MAX_ARCHIVE_BYTES) {
                            throw VentoyAssetsMissingException(
                                "Download exceeded the $MAX_ARCHIVE_BYTES byte limit",
                            )
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        onProgress(written, total)
                    }
                }
            }
            if (written == 0L) throw VentoyAssetsMissingException("Empty download from $url")
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** Opens [url] over HTTPS, following up to [MAX_REDIRECTS] HTTPS redirects. */
    private fun openConnection(url: String): HttpURLConnection {
        var current = URL(url)
        var redirects = 0
        while (true) {
            if (!current.protocol.equals("https", ignoreCase = true)) {
                throw VentoyAssetsMissingException("Refusing non-HTTPS Ventoy release URL: $current")
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "usb-multitool")
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) {
                    throw VentoyAssetsMissingException("HTTP $status from $current without a Location header")
                }
                redirects++
                if (redirects > MAX_REDIRECTS) {
                    throw VentoyAssetsMissingException("Too many redirects fetching $url")
                }
                current = URL(current, location)
                continue
            }
            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw VentoyAssetsMissingException("HTTP $status from $current")
            }
            return connection
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Official `ventoy-1.1.05-linux.tar.gz` digest (20,048,535 bytes). */
    const val RELEASE_1_1_05_ARCHIVE_SHA256 =
        "3379c99890359dcff55aab7f7b3286f87c988d1da2fd616e6a9e305fb0a1de9e"

    /** Official `ventoy-1.1.18-linux.tar.gz` digest (20,320,548 bytes). */
    const val RELEASE_1_1_18_ARCHIVE_SHA256 =
        "d86ff9de63d94c8b8f1f6b14d23c55af4b88e8207bf2893890892a6a97b1ad54"
}

/** The official Ventoy release files the installer needs are absent or unverifiable. */
class VentoyAssetsMissingException(message: String) : Exception(message)
