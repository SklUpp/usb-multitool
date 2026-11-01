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
        require(release == PINNED_RELEASE) {
            "Unsupported Ventoy release: $release; expected $PINNED_RELEASE"
        }
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

        const val OFFICIAL_BOOT_IMG_SHA256 =
            "85b4b080765738ca96620c32d8182edddbaf6a36149162946d12aa1f9802bc44"
        const val OFFICIAL_CORE_IMG_SHA256 =
            "97d70bf738a6e8d72fae6eb1a8993ceb4ac8bdc92570f2ff87bcd9aef3723431"
        const val OFFICIAL_DISK_IMG_SHA256 =
            "19174f133c4df8acda4423b1bc40ae69961773ef54e286ffd8c42fefaf6f174f"

        fun fake(
            release: String = PINNED_RELEASE,
            commit: String = PINNED_COMMIT,
            efiPartSectors: Int = 65536,
            supports4Kn: Boolean = false,
        ): VentoyAssets {
            val boot = ByteArray(512) { 0x90.toByte() }
            boot[510] = 0x55.toByte()
            boot[511] = 0xAA.toByte()

            val core = ByteArray(2048) { 0xCC.toByte() }
            core[500] = 1

            // Minimal valid EFI image structure with FAT header
            val efi = ByteArray(efiPartSectors * 512)
            efi[0] = 0xEB.toByte()
            efi[1] = 0x3C.toByte()
            efi[2] = 0x90.toByte()
            "MSDOS5.0".toByteArray().copyInto(efi, 3)
            efi[510] = 0x55.toByte()
            efi[511] = 0xAA.toByte()
            val verBytes = "VENTOY_VERSION=\"$release\"".toByteArray()
            verBytes.copyInto(efi, 1024)

            val digest = MessageDigest.getInstance("SHA-256")
            val bootSha = digest.digest(boot).toHex()
            digest.reset()
            val coreSha = digest.digest(core).toHex()
            digest.reset()
            val efiSha = digest.digest(efi).toHex()

            return VentoyAssets(
                release = release,
                commit = commit,
                bootImg = boot,
                coreImg = core,
                efiPartImg = efi,
                supports4Kn = supports4Kn,
                bootImgSha256 = bootSha,
                coreImgSha256 = coreSha,
                diskImgSha256 = efiSha,
            )
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

        fun decompressXz(data: ByteArray): ByteArray {
            if (!isXz(data)) return data
            try {
                val bais = java.io.ByteArrayInputStream(data)
                val xzIn = org.apache.commons.compress.compressors.xz.XZCompressorInputStream(bais)
                val baos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                var n: Int
                while (xzIn.read(buf).also { n = it } != -1) {
                    baos.write(buf, 0, n)
                }
                xzIn.close()
                return baos.toByteArray()
            } catch (_: Throwable) {}

            try {
                val proc = ProcessBuilder("xz", "-dc").start()
                proc.outputStream.use { it.write(data) }
                val out = proc.inputStream.readBytes()
                if (proc.waitFor() == 0 && out.isNotEmpty()) {
                    return out
                }
            } catch (_: Throwable) {}

            throw IllegalStateException("Failed to decompress XZ asset: no XZ decompressor available")
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
