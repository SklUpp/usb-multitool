package usb.ventoy

import java.security.MessageDigest

/**
 * Pinned official release assets and checksum contracts for Ventoy v1.1.18.
 */
data class VentoyAssets(
    val release: String = PINNED_RELEASE,
    val commit: String = PINNED_COMMIT,
    val bootImg: ByteArray,
    val coreImg: ByteArray,
    val efiPartImg: ByteArray,
    val supports4Kn: Boolean = false,
    val bootImgSha256: String = OFFICIAL_BOOT_IMG_SHA256,
    val coreImgSha256: String = OFFICIAL_CORE_IMG_SHA256,
    val diskImgSha256: String = OFFICIAL_DISK_IMG_SHA256,
) {
    init {
        require(bootImg.size >= 446) { "boot.img must be at least 446 bytes" }
        require(coreImg.isNotEmpty()) { "core.img must not be empty" }
        require(efiPartImg.isNotEmpty()) { "efi_part.img must not be empty" }
    }

    fun validateChecksums(strict: Boolean = true) {
        if (!strict) return
        val digest = MessageDigest.getInstance("SHA-256")

        val actualBootSha = digest.digest(bootImg).toHex()
        check(actualBootSha.equals(bootImgSha256, ignoreCase = true)) {
            "boot.img SHA-256 mismatch: got $actualBootSha, expected $bootImgSha256"
        }

        digest.reset()
        val actualCoreSha = digest.digest(coreImg).toHex()
        check(actualCoreSha.equals(coreImgSha256, ignoreCase = true)) {
            "core.img SHA-256 mismatch: got $actualCoreSha, expected $coreImgSha256"
        }

        digest.reset()
        val actualDiskSha = digest.digest(efiPartImg).toHex()
        check(actualDiskSha.equals(diskImgSha256, ignoreCase = true)) {
            "efiPartImg SHA-256 mismatch: got $actualDiskSha, expected $diskImgSha256"
        }
    }

    fun decompressCoreImg(): ByteArray = decompressXz(coreImg)

    fun decompressEfiPartImg(): ByteArray = decompressXz(efiPartImg)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VentoyAssets) return false
        return release == other.release &&
            commit == other.commit &&
            bootImg.contentEquals(other.bootImg) &&
            coreImg.contentEquals(other.coreImg) &&
            efiPartImg.contentEquals(other.efiPartImg) &&
            supports4Kn == other.supports4Kn
    }

    override fun hashCode(): Int {
        var result = release.hashCode()
        result = 31 * result + commit.hashCode()
        result = 31 * result + bootImg.contentHashCode()
        result = 31 * result + coreImg.contentHashCode()
        result = 31 * result + efiPartImg.contentHashCode()
        result = 31 * result + supports4Kn.hashCode()
        return result
    }

    companion object {
        const val PINNED_RELEASE = "1.1.18"
        const val PINNED_COMMIT = "6116894ab8abdf3ef8d43411a6732d34ee19214e"
        const val RELEASE_1_1_05 = "1.1.05"

        /**
         * Official `ventoy-1.1.05-linux.tar.gz` member digests (boot/boot.img,
         * boot/core.img.xz, ventoy/ventoy.disk.img.xz), measured from the release asset.
         */
        val RELEASE_1_1_05_HASHES: Map<String, String> = mapOf(
            "boot.img" to "c2d400a821134e051764eacace530636e1e76596ca04308dbd122e4708c4455a",
            "core.img.xz" to "0eeb0e0af219722714faf2011f62f89f1829edc4b8e005e2d15707a89df11f96",
            "ventoy.disk.img.xz" to "00c75919bd4686ba3315d25403f1d1f38cd170438ca8c4a78ec1ade93260edd8",
        )

        val RELEASE_1_1_18_HASHES: Map<String, String> = mapOf(
            "boot.img" to OFFICIAL_BOOT_IMG_SHA256,
            "core.img.xz" to OFFICIAL_CORE_IMG_SHA256,
            "ventoy.disk.img.xz" to OFFICIAL_DISK_IMG_SHA256,
        )


        const val OFFICIAL_BOOT_IMG_SHA256 =
            "85b4b080765738ca96620c32d8182edddbaf6a36149162946d12aa1f9802bc44"
        const val OFFICIAL_CORE_IMG_SHA256 =
            "97d70bf738a6e8d72fae6eb1a8993ceb4ac8bdc92570f2ff87bcd9aef3723431"
        const val OFFICIAL_DISK_IMG_SHA256 =
            "19174f133c4df8acda4423b1bc40ae69961773ef54e286ffd8c42fefaf6f174f"

        /**
         * Version actually bundled with this APK: assets/ventoy/version when shipped,
         * otherwise the pinned release that names the same bundled bytes. No download and
         * no external-storage lookup can change this value.
         */
        fun bundledRelease(
            assetManager: android.content.res.AssetManager,
            basePath: String = "ventoy",
        ): String =
            runCatching {
                assetManager.open("$basePath/version").bufferedReader().use { reader ->
                    reader.readLine()?.trim()
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: PINNED_RELEASE

        fun loadFromAssets(
            assetManager: android.content.res.AssetManager,
            basePath: String = "ventoy",
            strict: Boolean = true,
        ): VentoyAssets {
            val boot = assetManager.open("$basePath/boot.img").use { it.readBytes() }
            val core = runCatching {
                assetManager.open("$basePath/core.img.xz").use { it.readBytes() }
            }.getOrElse {
                assetManager.open("$basePath/core.img").use { it.readBytes() }
            }
            val disk = runCatching {
                assetManager.open("$basePath/ventoy.disk.img.xz").use { it.readBytes() }
            }.getOrElse {
                runCatching {
                    assetManager.open("$basePath/ventoy.disk.img").use { it.readBytes() }
                }.getOrElse {
                    assetManager.open("$basePath/efi_part.img").use { it.readBytes() }
                }
            }

            var rel = PINNED_RELEASE
            var com = PINNED_COMMIT
            runCatching {
                assetManager.open("$basePath/version").bufferedReader().use {
                    val line = it.readLine()?.trim()
                    if (!line.isNullOrEmpty()) rel = line
                }
            }

            var bootSha: String? = null
            var coreSha: String? = null
            var diskSha: String? = null

            runCatching {
                assetManager.open("$basePath/sha256.txt").bufferedReader().use { reader ->
                    reader.lineSequence().forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isNotEmpty() && !line.startsWith("#")) {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 2) {
                                val hash = parts[0].trim()
                                val name = parts[1].trim().removePrefix("*")
                                when {
                                    name.endsWith("boot.img") -> bootSha = hash
                                    name.endsWith("core.img.xz") || name.endsWith("core.img") -> coreSha = hash
                                    name.endsWith("ventoy.disk.img.xz") || name.endsWith("ventoy.disk.img") || name.endsWith("efi_part.img") -> diskSha = hash
                                }
                            }
                        }
                    }
                }
            }

            val defaultBootSha = if (rel == RELEASE_1_1_05) RELEASE_1_1_05_HASHES.getValue("boot.img") else OFFICIAL_BOOT_IMG_SHA256
            val defaultCoreSha = if (rel == RELEASE_1_1_05) RELEASE_1_1_05_HASHES.getValue("core.img.xz") else OFFICIAL_CORE_IMG_SHA256
            val defaultDiskSha = if (rel == RELEASE_1_1_05) RELEASE_1_1_05_HASHES.getValue("ventoy.disk.img.xz") else OFFICIAL_DISK_IMG_SHA256

            val finalBootSha = bootSha ?: defaultBootSha
            val finalCoreSha = coreSha ?: defaultCoreSha
            val finalDiskSha = diskSha ?: defaultDiskSha

            val assets = VentoyAssets(
                release = rel,
                commit = com,
                bootImg = boot,
                coreImg = core,
                efiPartImg = disk,
                bootImgSha256 = finalBootSha,
                coreImgSha256 = finalCoreSha,
                diskImgSha256 = finalDiskSha,
            )
            assets.validateChecksums(strict = strict)
            return assets
        }

        fun fromAssetManager(
            assetManager: android.content.res.AssetManager,
            basePath: String = "ventoy",
            strict: Boolean = true,
        ): VentoyAssets = loadFromAssets(assetManager, basePath, strict)

        fun loadFromDirectory(
            dir: java.io.File,
            strict: Boolean = true,
        ): VentoyAssets {
            val boot = java.io.File(dir, "boot.img").readBytes()
            val coreFile = java.io.File(dir, "core.img.xz").takeIf { it.exists() }
                ?: java.io.File(dir, "core.img")
            val core = coreFile.readBytes()

            val diskFile = java.io.File(dir, "ventoy.disk.img.xz").takeIf { it.exists() }
                ?: java.io.File(dir, "ventoy.disk.img").takeIf { it.exists() }
                ?: java.io.File(dir, "efi_part.img")
            val disk = diskFile.readBytes()

            var rel = PINNED_RELEASE
            val verFile = java.io.File(dir, "version")
            if (verFile.exists()) {
                val line = verFile.readText().trim()
                if (line.isNotEmpty()) rel = line
            }

            var bootSha: String? = null
            var coreSha: String? = null
            var diskSha: String? = null
            val shaFile = java.io.File(dir, "sha256.txt")
            if (shaFile.exists()) {
                shaFile.readLines().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isNotEmpty() && !line.startsWith("#")) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val hash = parts[0].trim()
                            val name = parts[1].trim().removePrefix("*")
                            when {
                                name.endsWith("boot.img") -> bootSha = hash
                                name.endsWith("core.img.xz") || name.endsWith("core.img") -> coreSha = hash
                                name.endsWith("ventoy.disk.img.xz") || name.endsWith("ventoy.disk.img") || name.endsWith("efi_part.img") -> diskSha = hash
                            }
                        }
                    }
                }
            }

            val defaultBootSha = if (rel == RELEASE_1_1_05) RELEASE_1_1_05_HASHES.getValue("boot.img") else OFFICIAL_BOOT_IMG_SHA256
            val defaultCoreSha = if (rel == RELEASE_1_1_05) RELEASE_1_1_05_HASHES.getValue("core.img.xz") else OFFICIAL_CORE_IMG_SHA256
            val defaultDiskSha = if (rel == RELEASE_1_1_05) RELEASE_1_1_05_HASHES.getValue("ventoy.disk.img.xz") else OFFICIAL_DISK_IMG_SHA256

            val finalBootSha = bootSha ?: defaultBootSha
            val finalCoreSha = coreSha ?: defaultCoreSha
            val finalDiskSha = diskSha ?: defaultDiskSha

            val assets = VentoyAssets(
                release = rel,
                commit = PINNED_COMMIT,
                bootImg = boot,
                coreImg = core,
                efiPartImg = disk,
                bootImgSha256 = finalBootSha,
                coreImgSha256 = finalCoreSha,
                diskImgSha256 = finalDiskSha,
            )
            assets.validateChecksums(strict = strict)
            return assets
        }

        fun isXz(data: ByteArray): Boolean {
            if (data.size < 6) return false
            return data[0] == 0xFD.toByte() &&
                data[1] == '7'.code.toByte() &&
                data[2] == 'z'.code.toByte() &&
                data[3] == 'X'.code.toByte() &&
                data[4] == 'Z'.code.toByte() &&
                data[5] == 0x00.toByte()
        }

        /**
         * Decompresses one pinned XZ asset. The output is capped at [maxBytes] so a
         * malformed or substituted stream cannot allocate unbounded memory before the
         * result would reach the USB medium.
         */
        fun decompressXz(data: ByteArray, maxBytes: Long = MAX_DECOMPRESSED_ASSET_BYTES): ByteArray {
            if (!isXz(data)) return data
            require(maxBytes >= 0) { "maxBytes must be non-negative, was $maxBytes" }
            try {
                val bais = java.io.ByteArrayInputStream(data)
                val xzIn = org.apache.commons.compress.compressors.xz.XZCompressorInputStream(bais)
                val baos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                var total = 0L
                var n: Int
                while (xzIn.read(buf).also { n = it } != -1) {
                    total += n
                    if (total > maxBytes) {
                        throw IllegalStateException(
                            "decompressed XZ asset exceeds the $maxBytes byte limit",
                        )
                    }
                    baos.write(buf, 0, n)
                }
                xzIn.close()
                return baos.toByteArray()
            } catch (e: Throwable) {
                throw IllegalStateException("Failed to decompress XZ asset: ${e.message}", e)
            }
        }

        /** Upper bound for one decompressed Ventoy asset (64 MiB). */
        const val MAX_DECOMPRESSED_ASSET_BYTES: Long = 64L * 1024 * 1024

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
