package usb.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomic mapfile publish: write+sync a temp file, then ATOMIC_MOVE over [file].
 * Never deletes or truncates the last good map — on failure only the temp is removed.
 */
object RescueCheckpoint {
    fun save(map: RescueMap, file: File) {
        val dir = file.parentFile ?: error("no parent dir for ${file.path}")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("cannot create rescue map directory ${dir.path}")
        }
        val tmp = File.createTempFile(file.name + ".", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(map.encode().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                tmp.delete()
                throw IOException(
                    "atomic map replace unsupported on this filesystem; last good map kept at ${file.path}",
                    e,
                )
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
