package usb.ventoy

import java.security.MessageDigest

/**
 * Test-only synthetic Ventoy assets: self-consistent digests so layout code can be
 * exercised without the official release binaries. Production code must use
 * [VentoyDownloader] / [VentoyAssets.loadFromDirectory] instead.
 */
fun syntheticVentoyAssets(
    release: String = VentoyAssets.PINNED_RELEASE,
    commit: String = VentoyAssets.PINNED_COMMIT,
    efiPartSectors: Int = 65536,
    supports4Kn: Boolean = false,
    coreBytes: ByteArray? = null,
): VentoyAssets {
    val boot = ByteArray(512) { 0x90.toByte() }
    boot[510] = 0x55.toByte()
    boot[511] = 0xAA.toByte()

    val core = if (coreBytes != null) {
        coreBytes
    } else {
        ByteArray(2048) { 0xCC.toByte() }.also { it[500] = 1 }
    }

    // Minimal EFI image structure with a FAT boot sector
    val efi = ByteArray(efiPartSectors * 512)
    efi[0] = 0xEB.toByte()
    efi[1] = 0x3C.toByte()
    efi[2] = 0x90.toByte()
    "MSDOS5.0".toByteArray().copyInto(efi, 3)
    efi[510] = 0x55.toByte()
    efi[511] = 0xAA.toByte()
    "VENTOY_VERSION=\"$release\"".toByteArray().copyInto(efi, 1024)

    return VentoyAssets(
        release = release,
        commit = commit,
        bootImg = boot,
        coreImg = core,
        efiPartImg = efi,
        supports4Kn = supports4Kn,
        bootImgSha256 = boot.sha256Hex(),
        coreImgSha256 = core.sha256Hex(),
        diskImgSha256 = efi.sha256Hex(),
    )
}

/** Writes [syntheticVentoyAssets] into [dir] as the on-disk release layout. */
fun writeSyntheticRelease(
    dir: java.io.File,
    assets: VentoyAssets = syntheticVentoyAssets(release = VentoyAssets.RELEASE_1_1_05),
    withSha256File: Boolean = true,
) {
    dir.mkdirs()
    java.io.File(dir, "boot.img").writeBytes(assets.bootImg)
    java.io.File(dir, "core.img.xz").writeBytes(assets.coreImg)
    java.io.File(dir, "ventoy.disk.img.xz").writeBytes(assets.efiPartImg)
    java.io.File(dir, "version").writeText(assets.release)
    if (withSha256File) {
        java.io.File(dir, "sha256.txt").writeText(
            listOf(
                "${assets.bootImgSha256}  boot.img",
                "${assets.coreImgSha256}  core.img.xz",
                "${assets.diskImgSha256}  ventoy.disk.img.xz",
            ).joinToString("\n") + "\n",
        )
    }
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
