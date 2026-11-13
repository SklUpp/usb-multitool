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
        /** True only when archive and members match an independent app pin. */
        val independentlyPinned: Boolean = true,
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

    fun pinnedSourceOrNull(release: String): ReleaseSource? =
        RELEASES.firstOrNull { it.release == release }

    /** Newest release this build can independently verify; never the bare upstream latest. */
    fun newestPinnedSource(): ReleaseSource =
        RELEASES.maxWithOrNull { left, right ->
            compareVersions(left.release, right.release)
        } ?: throw VentoyAssetsMissingException("No independently pinned Ventoy release is configured")

    /**
     * Picks the upstream release only when this build pins it; otherwise falls back to the
     * newest pinned release so the Download action still has a real, honestly-labelled
     * payload. The UI keeps showing the unverified upstream version separately.
     */
    fun pinnedDownloadSource(upstream: String?): ReleaseSource =
        upstream?.let(::pinnedSourceOrNull) ?: newestPinnedSource()

    private fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): List<Long> =
            value.split('.').map { part -> part.toLongOrNull() ?: 0L }
        val a = parts(left)
        val b = parts(right)
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val cmp = a.getOrElse(index) { 0L }.compareTo(b.getOrElse(index) { 0L })
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun sourceFor(release: String): ReleaseSource =
        pinnedSourceOrNull(release)
            ?: throw IllegalArgumentException(
                "Release $release has no pinned digests; supported: ${RELEASES.joinToString(", ") { it.release }}",
            )

    /**
     * Recomputes every member digest and requires it to match this app's independent pin.
     * `sha256.txt` shipped beside the bytes is never a trust anchor: a directory or store
     * whose file and manifest agree with each other still fails here.
     */
    fun requirePinned(assets: VentoyAssets): ReleaseSource {
        val source = pinnedSourceOrNull(assets.release)
            ?: throw VentoyAssetsMissingException(
                "Ventoy ${assets.release} has no independently pinned SHA-256 in this app; " +
                    "refusing to use it for install/update. Available pinned releases: " +
                    RELEASES.joinToString(", ") { it.release },
            )
        val actual =
            mapOf(
                "boot.img" to sha256Hex(assets.bootImg),
                "core.img.xz" to sha256Hex(assets.coreImg),
                "ventoy.disk.img.xz" to sha256Hex(assets.efiPartImg),
            )
        for ((name, got) in actual) {
            val expected = source.memberHashes[name]
                ?: throw VentoyAssetsMissingException(
                    "Ventoy ${assets.release} has no independent pin for $name; refusing to use it",
                )
            if (!got.equals(expected, ignoreCase = true)) {
                throw VentoyAssetsMissingException(
                    "Ventoy ${assets.release} member $name SHA-256 $got does not match the " +
                        "independently pinned $expected; refusing to use it",
                )
            }
        }
        requirePayloadStructure(assets)
        return source
    }
    /** Loads pre-staged Ventoy assets from an app-private directory. */
    fun loadFromExternalStorage(dir: File, strict: Boolean = true): VentoyAssets {
        require(dir.exists() && dir.isDirectory) { "Ventoy assets directory not found: ${dir.path}" }
        return VentoyAssets.loadFromDirectory(dir, strict = strict)
    }

    /**
     * Streams the pinned release archive into [cacheDir], verifies the archive and
     * every member digest, and returns validated assets. The temporary archive is
     * always deleted. [onProgress] receives (bytesWritten, contentLength or -1).
     */
    fun downloadRelease(
        cacheDir: File,
        release: String? = null,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ): VentoyReleaseDownload {
        val source = if (release == null) newestPinnedSource() else sourceFor(release)
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

            val names = setOf("boot.img", "core.img.xz", "ventoy.disk.img.xz")
            val members = archive.inputStream().buffered().use { VentoyReleaseArchive.extract(it, names) }
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
                    commit = source.commit,
                    bootImg = members.getValue("boot.img"),
                    coreImg = members.getValue("core.img.xz"),
                    efiPartImg = members.getValue("ventoy.disk.img.xz"),
                    bootImgSha256 = source.memberHashes["boot.img"] ?: sha256Hex(members.getValue("boot.img")),
                    coreImgSha256 = source.memberHashes["core.img.xz"] ?: sha256Hex(members.getValue("core.img.xz")),
                    diskImgSha256 = source.memberHashes["ventoy.disk.img.xz"] ?: sha256Hex(members.getValue("ventoy.disk.img.xz")),
                )
            assets.validateChecksums(strict = true)
            return VentoyReleaseDownload(assets, archiveSha, sourceUrl)
        } finally {
            archive.delete()
        }
    }
    /**
     * Downloads an upstream release without an independent SHA pin. Caller must have shown
     * explicit risk confirmation. HTTPS host identity, archive bounds, required member names,
     * and member checksums still apply; provenance remains explicitly unpinned.
     */
    fun downloadUnpinnedRelease(
        cacheDir: File,
        release: String,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ): VentoyReleaseDownload {
        require(Regex("[0-9]+(?:\\.[0-9]+)+").matches(release)) {
            "Invalid Ventoy release $release"
        }
        require(pinnedSourceOrNull(release) == null) {
            "Release $release has an independent pin; use the pinned download path"
        }
        cacheDir.mkdirs()
        if (!cacheDir.isDirectory) {
            throw VentoyAssetsMissingException("Cache directory ${cacheDir.path} is not writable")
        }
        val sourceUrl = "$DEFAULT_BASE_URL/v$release/ventoy-$release-linux.tar.gz"
        val archive = File.createTempFile("ventoy-$release-", ".tar.gz", cacheDir)
        try {
            val archiveSha = fetch(
                url = sourceUrl,
                destination = archive,
                onProgress = onProgress,
                isActive = isActive,
                allowedHosts = setOf(
                    "github.com",
                    "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com",
                ),
            )
            val members = archive.inputStream().buffered().use {
                VentoyReleaseArchive.extract(it, setOf("boot.img", "core.img.xz", "ventoy.disk.img.xz"))
            }
            val assets = VentoyAssets(
                release = release,
                commit = "",
                bootImg = members.getValue("boot.img"),
                coreImg = members.getValue("core.img.xz"),
                efiPartImg = members.getValue("ventoy.disk.img.xz"),
                bootImgSha256 = sha256Hex(members.getValue("boot.img")),
                coreImgSha256 = sha256Hex(members.getValue("core.img.xz")),
                diskImgSha256 = sha256Hex(members.getValue("ventoy.disk.img.xz")),
            )
            requireValidated(assets)
            return VentoyReleaseDownload(
                assets = assets,
                archiveSha256 = archiveSha,
                sourceUrl = sourceUrl,
                independentlyPinned = false,
            )
        } finally {
            archive.delete()
        }
    }

    /** Validates payload structure and self-consistent member digests, without claiming trust. */
    fun requireValidated(assets: VentoyAssets) {
        require(Regex("[0-9]+(?:\\.[0-9]+)+").matches(assets.release)) {
            "Invalid Ventoy release ${assets.release}"
        }
        requirePayloadStructure(assets)
        assets.validateChecksums(strict = true)
    }

    private fun requirePayloadStructure(assets: VentoyAssets) {
        require(assets.bootImg.size >= 512) { "boot.img is shorter than one sector" }
        require(assets.bootImg[0] == 0xEB.toByte() && assets.bootImg[2] == 0x90.toByte()) {
            "boot.img lacks Ventoy x86 boot entry signature"
        }
        require(assets.bootImg[510] == 0x55.toByte() && assets.bootImg[511] == 0xAA.toByte()) {
            "boot.img lacks the 0x55AA boot signature"
        }
        require(VentoyAssets.isXz(assets.coreImg) && VentoyAssets.isXz(assets.efiPartImg)) {
            "Ventoy core and EFI payloads must be strict XZ streams"
        }
        val core = assets.decompressCoreImg()
        require(core.size >= 512 && core.size % 512 == 0) {
            "core.img is not a sector-aligned image"
        }
        require(core[0] == 0x52.toByte() && core[1] == 0x56.toByte()) {
            "core.img lacks Ventoy GRUB image signature"
        }
        val efi = assets.decompressEfiPartImg()
        require(efi.size >= 512 && efi.size % 512 == 0) {
            "ventoy.disk.img is not a sector-aligned image"
        }
        requireFatFilesystem(efi, assets.release)
    }

    /** Parse FAT directory chains instead of accepting boot-sector magic alone. */
    private fun requireFatFilesystem(image: ByteArray, release: String) {
        fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
        fun u32(bytes: ByteArray, offset: Int): Long =
            u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
        require(image.size >= 512 && image[510] == 0x55.toByte() && image[511] == 0xAA.toByte()) {
            "ventoy.disk.img lacks a FAT boot signature"
        }
        val bytesPerSector = u16(image, 11)
        val sectorsPerCluster = image[13].toInt() and 0xff
        val reserved = u16(image, 14)
        val fatCount = image[16].toInt() and 0xff
        val rootEntries = u16(image, 17)
        val total16 = u16(image, 19)
        val sectorsPerFat16 = u16(image, 22)
        val totalSectors = if (total16 != 0) total16.toLong() else u32(image, 32)
        require(
            bytesPerSector == 512 &&
                sectorsPerCluster > 0 && (sectorsPerCluster and (sectorsPerCluster - 1)) == 0 &&
                sectorsPerCluster <= 128 && reserved > 0 && fatCount in 1..2 && totalSectors > 0,
        ) {
            "invalid FAT BPB"
        }
        val sectorsPerFat = if (sectorsPerFat16 != 0) sectorsPerFat16 else u32(image, 36).toInt()
        require(sectorsPerFat > 0) { "FAT has no allocation table" }
        val rootDirSectors = ((rootEntries * 32) + bytesPerSector - 1) / bytesPerSector
        val dataStart = reserved + fatCount * sectorsPerFat + rootDirSectors
        require(dataStart < totalSectors && totalSectors * bytesPerSector <= image.size) {
            "FAT geometry exceeds EFI image"
        }
        val clusterCount = ((totalSectors - dataStart) / sectorsPerCluster).toInt()
        require(clusterCount in 4085..65524) {
            "unsupported FAT type: expected FAT16, got $clusterCount data clusters"
        }
        require(sectorsPerFat.toLong() * bytesPerSector >= (clusterCount + 2L) * 2L) {
            "FAT16 allocation table is too small"
        }
        val clusterBytes = sectorsPerCluster * bytesPerSector
        val fatOffset = reserved * bytesPerSector
        val fatBytes = sectorsPerFat * bytesPerSector
        require(fatOffset + fatBytes <= image.size) { "FAT table exceeds EFI image" }
        val maxCluster = ((totalSectors - dataStart) / sectorsPerCluster).toInt() + 1
        fun next(cluster: Int): Int {
            val at = fatOffset + cluster * 2
            require(at + 2 <= fatOffset + fatBytes) { "FAT cluster pointer out of bounds" }
            return u16(image, at)
        }
        fun clusterBytes(cluster: Int): ByteArray {
            require(cluster >= 2 && cluster <= maxCluster) { "FAT cluster out of bounds" }
            val offset = (dataStart + (cluster - 2L) * sectorsPerCluster) * bytesPerSector
            require(offset >= 0 && offset + clusterBytes <= image.size) { "FAT data out of bounds" }
            return image.copyOfRange(offset.toInt(), offset.toInt() + clusterBytes)
        }
        fun readChain(first: Int, length: Int?, maxBytes: Int): ByteArray {
            if (length != null) require(length > 0 && length <= maxBytes) { "FAT file length invalid" }
            val out = java.io.ByteArrayOutputStream()
            val seen = HashSet<Int>()
            var cluster = first
            while (cluster >= 2 && cluster < 0xfff8) {
                require(seen.add(cluster)) { "FAT cluster loop" }
                out.write(clusterBytes(cluster))
                require(out.size() <= maxBytes) { "FAT file exceeds validation bound" }
                cluster = next(cluster)
            }
            require(cluster >= 0xfff8) { "FAT file chain is truncated" }
            if (length == null) return out.toByteArray()
            require(out.size() >= length) { "FAT file chain is shorter than directory size" }
            return out.toByteArray().copyOf(length)
        }
        data class Entry(val name: String, val directory: Boolean, val first: Int, val size: Int)
        fun entries(bytes: ByteArray): List<Entry> = buildList {
            var offset = 0
            while (offset + 32 <= bytes.size) {
                val entryOffset = offset
                val first = bytes[entryOffset].toInt() and 0xff
                if (first == 0) break
                offset += 32
                val attr = bytes[entryOffset + 11].toInt() and 0xff
                if (first == 0xe5 || attr == 0x0f) continue
                val base = String(bytes, entryOffset, 8, Charsets.US_ASCII).trimEnd(' ', '\u0000')
                val ext = String(bytes, entryOffset + 8, 3, Charsets.US_ASCII).trimEnd(' ', '\u0000')
                val name = if (ext.isEmpty()) base else "$base.$ext"
                val cluster = u16(bytes, entryOffset + 26)
                val size = u32(bytes, entryOffset + 28).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                add(Entry(name.uppercase(java.util.Locale.ROOT), attr and 0x10 != 0, cluster, size))
            }
        }
        fun dirAt(entry: Entry): List<Entry> = entries(
            readChain(entry.first, null, 2 * 1024 * 1024),
        )
        val rootOffset = (reserved + fatCount * sectorsPerFat) * bytesPerSector
        require(rootOffset >= 0 && rootOffset + rootDirSectors * bytesPerSector <= image.size) {
            "FAT root directory exceeds EFI image"
        }
        val root = entries(image.copyOfRange(rootOffset, rootOffset + rootDirSectors * bytesPerSector))
        fun find(list: List<Entry>, name: String): Entry = list.firstOrNull { it.name == name } ?:
            error("required FAT path component $name missing")
        val grub = find(root, "GRUB")
        require(grub.directory && grub.first >= 2) { "GRUB is not a directory" }
        val grubEntries = dirAt(grub)
        val cfg = find(grubEntries, "GRUB.CFG")
        require(!cfg.directory && cfg.first >= 2 && cfg.size > 0) { "GRUB.CFG is not a file" }
        val cfgText = String(readChain(cfg.first, cfg.size, 1024 * 1024), Charsets.US_ASCII)
        require(Regex("(?m)(?:set\\s+)?VENTOY_VERSION\\s*=\\s*\\\"?${Regex.escape(release)}\\\"?(?:\\s|$)").containsMatchIn(cfgText)) {
            "GRUB.CFG has no VENTOY_VERSION for $release"
        }
        val efi = find(root, "EFI")
        require(efi.directory && efi.first >= 2) { "EFI is not a directory" }
        val boot = find(dirAt(efi), "BOOT")
        require(boot.directory && boot.first >= 2) { "EFI/BOOT is not a directory" }
        val bootEntries = dirAt(boot)
        val bootx = find(bootEntries, "BOOTX64.EFI")
        require(!bootx.directory && bootx.first >= 2 && bootx.size > 0) {
            "EFI/BOOT/BOOTX64.EFI is missing"
        }
        val bootxBytes = readChain(bootx.first, bootx.size, 16 * 1024 * 1024)
        require(isPeCoffEfiExecutable(bootxBytes)) {
            "EFI/BOOT/BOOTX64.EFI is not a valid PE/COFF EFI executable"
        }
    }

    private fun isPeCoffEfiExecutable(bytes: ByteArray): Boolean {
        if (bytes.size < 0x100 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) return false
        val peOffset = (bytes[0x3c].toInt() and 0xff) or
            ((bytes[0x3d].toInt() and 0xff) shl 8) or
            ((bytes[0x3e].toInt() and 0xff) shl 16) or
            ((bytes[0x3f].toInt() and 0xff) shl 24)
        if (peOffset < 0 || peOffset > bytes.size - 24) return false
        if (bytes[peOffset] != 'P'.code.toByte() || bytes[peOffset + 1] != 'E'.code.toByte() ||
            bytes[peOffset + 2] != 0.toByte() || bytes[peOffset + 3] != 0.toByte()
        ) return false
        fun u16(at: Int): Int = (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8)
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val machine = u16(peOffset + 4)
        val sections = u16(peOffset + 6)
        val optionalSize = u16(peOffset + 20)
        val optional = peOffset + 24
        if (machine != 0x8664 || sections !in 1..96 || optionalSize < 112 || optional + optionalSize > bytes.size) return false
        val magic = u16(optional)
        val entryRva = u32(optional + 16)
        val imageSize = u32(optional + 56)
        val headersSize = u32(optional + 60)
        val subsystem = u16(optional + 68)
        if (magic != 0x20b || subsystem != 10 || entryRva == 0L || imageSize == 0L ||
            headersSize == 0L || headersSize > bytes.size || headersSize > imageSize
        ) return false
        val sectionTable = optional + optionalSize
        val sectionBytes = sections.toLong() * 40L
        if (sectionTable < 0 || sectionBytes > Int.MAX_VALUE || sectionTable + sectionBytes > bytes.size) return false
        var entryInExecutable = false
        for (index in 0 until sections) {
            val section = sectionTable + index * 40
            val virtualSize = u32(section + 8)
            val virtualAddress = u32(section + 12)
            val rawSize = u32(section + 16)
            val rawPointer = u32(section + 20)
            val characteristics = u32(section + 36)
            if (virtualSize == 0L || virtualAddress >= imageSize ||
                virtualSize > imageSize - virtualAddress || rawPointer > bytes.size ||
                rawSize > bytes.size - rawPointer
            ) return false
            if (rawSize > 0L && rawPointer < headersSize) return false
            if (entryRva >= virtualAddress && entryRva < virtualAddress + virtualSize) {
                val entryDelta = entryRva - virtualAddress
                val entryOffset = rawPointer + entryDelta
                val entryFileBacked = rawSize > 0L && entryDelta < rawSize &&
                    entryOffset >= rawPointer && entryOffset < bytes.size
                if (entryFileBacked) {
                    entryInExecutable = characteristics and 0x20000000L != 0L
                }
            }
        }
        return entryInExecutable
    }
    /**
     * Non-destructive loader for inspection/tests: app-private storage first, then APK
     * assets. It is not install-safe because pre-staged bytes are only self-attested;
     * install/update must go through [VentoyInstallAssets.resolve], which requires the
     * independent pin and the exact confirmed release.
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
                "ventoy.disk.img.xz in ${externalDir?.path ?: "<app-private>/ventoy-assets"} or APK assets/ventoy, " +
                "or download a pinned release from the server. " + failures.joinToString("; "),
        )
    }
    private fun java.io.Reader.readTextLimited(limit: Int): String {
        val text = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(text.length + count <= limit) { "Ventoy release metadata too large" }
            text.append(buffer, 0, count)
        }
        return text.toString()
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
            val body = conn.inputStream.bufferedReader().use { it.readTextLimited(1024 * 1024) }
            val tag = org.json.JSONObject(body).getString("tag_name")
            require(Regex("v[0-9]+(?:\\.[0-9]+)+").matches(tag)) { "Invalid Ventoy release tag" }
            tag.substring(1)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun fetch(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit,
        isActive: () -> Boolean,
        allowedHosts: Set<String> = setOf(
            "github.com",
            "sourceforge.net",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
        ),
    ): String {
        val connection = openConnection(url, allowedHosts)
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

    /** Opens [url] over HTTPS, following redirects only to approved release hosts. */
    private fun openConnection(url: String, allowedHosts: Set<String>): HttpURLConnection {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val host = current.host.lowercase(java.util.Locale.ROOT)
            val hostAllowed = host in allowedHosts ||
                ("sourceforge.net" in allowedHosts && host.endsWith(".sourceforge.net"))
            if (!hostAllowed) {
                throw VentoyAssetsMissingException("Refusing untrusted Ventoy release host: $host")
            }
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
