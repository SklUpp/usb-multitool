package usb.image

import java.io.File
import kotlinx.coroutines.CancellationException
import usb.fs.UsbFsVolume
import usb.service.DiskOperation

/** Copies one USB file into owned staging storage. Returns only after durable close and size check. */
fun stageRawImageChunks(
    filesDir: File,
    volume: UsbFsVolume,
    sourcePath: String,
    sourceSize: Long,
    sourceName: String,
    operation: DiskOperation,
    chunkBytes: Int = 64 * 1024,
): File {
    require(sourceSize >= 0L) { "negative source size: $sourceSize" }
    require(chunkBytes > 0) { "chunkBytes must be positive" }
    val staged = ImageStaging.createUnique(filesDir, sourceName)
    try {
        staged.outputStream().use { out ->
            var offset = 0L
            while (offset < sourceSize) {
                if (!operation.isActive()) throw CancellationException("Staging cancelled")
                val want = minOf(chunkBytes.toLong(), sourceSize - offset).toInt()
                val data = volume.read(sourcePath, offset, want)
                if (data.isEmpty()) throw IllegalStateException("short read at $offset of $sourceSize bytes")
                out.write(data)
                offset += data.size
                operation.report(offset)
            }
            out.flush()
            out.fd.sync()
        }
        if (!operation.isActive()) throw CancellationException("Staging cancelled")
        if (staged.length() != sourceSize) {
            throw IllegalStateException("staged size mismatch: got ${staged.length()}, expected $sourceSize")
        }
        return staged
    } catch (t: Throwable) {
        try {
            ImageStaging.deleteIfManaged(filesDir, staged.absolutePath)
        } catch (cleanup: Throwable) {
            // Keep the primary failure visible; cleanup failure is diagnostic only.
            t.addSuppressed(cleanup)
        }
        throw t
    }
}
