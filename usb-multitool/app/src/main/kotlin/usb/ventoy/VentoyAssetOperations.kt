package usb.ventoy

import java.io.File
import usb.service.DiskOperation

object VentoyAssetOperations {
    fun download(
        cacheDir: File,
        operation: DiskOperation,
        isActive: () -> Boolean,
    ): VentoyDownloader.VentoyReleaseDownload = VentoyDownloader.downloadRelease(
        cacheDir = cacheDir,
        release = VentoyAssets.PINNED_RELEASE,
        onProgress = { done, total ->
            if (total > 0) operation.bytesTotal = total
            operation.report(done)
        },
        isActive = isActive,
    )
}
