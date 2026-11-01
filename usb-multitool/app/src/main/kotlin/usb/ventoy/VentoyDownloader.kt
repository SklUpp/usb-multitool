package usb.ventoy

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads official Ventoy release assets and provides fallback loading from external storage.
 */
object VentoyDownloader {
    const val DEFAULT_BASE_URL = "https://github.com/ventoy/Ventoy/releases/download"

    /**
     * Loads Ventoy assets from a directory on external storage.
     */
    fun loadFromExternalStorage(dir: File, strict: Boolean = true): VentoyAssets {
        require(dir.exists() && dir.isDirectory) { "External directory not found: ${dir.path}" }
        return VentoyAssets.loadFromDirectory(dir, strict = strict)
    }

    /**
     * Downloads official release assets into targetDir, then loads and validates them.
     */
    fun downloadRelease(
        release: String = VentoyAssets.RELEASE_1_1_05,
        targetDir: File,
        baseUrl: String = "$DEFAULT_BASE_URL/v$release",
        strict: Boolean = true,
    ): VentoyAssets {
        targetDir.mkdirs()
        val filenames = listOf("boot.img", "core.img.xz", "ventoy.disk.img.xz", "sha256.txt", "version")
        for (filename in filenames) {
            val url = URL("$baseUrl/$filename")
            val dest = File(targetDir, filename)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return VentoyAssets.loadFromDirectory(targetDir, strict = strict)
    }

    /**
     * Fallback loader: tries external storage, then asset manager, then fallback mock.
     */
    fun loadWithFallback(
        externalDir: File? = null,
        assetManager: android.content.res.AssetManager? = null,
        strict: Boolean = true,
    ): VentoyAssets {
        if (externalDir != null && externalDir.exists() && externalDir.isDirectory) {
            val fromExt = runCatching { loadFromExternalStorage(externalDir, strict) }.getOrNull()
            if (fromExt != null) return fromExt
        }
        if (assetManager != null) {
            val fromAssets = runCatching { VentoyAssets.loadFromAssets(assetManager, strict = strict) }.getOrNull()
            if (fromAssets != null) return fromAssets
        }
        if (strict) {
            throw usb.device.DiskException.UnsupportedCommand("Ventoy assets unavailable; refusing to install synthetic images")
        }
        return VentoyAssets.mockLoader(strict = false)
    }
}
