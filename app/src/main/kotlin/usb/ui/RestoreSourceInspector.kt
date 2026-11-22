package usb.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import usb.diagnostics.RescueMap
import usb.multitool.GptCriticalBackup
import usb.partition.GptBackup

/**
 * Bounded inspection of restore sources before the destructive confirmation.
 *
 * Rescue image inspection starts with SAF metadata plus a bounded 6-byte magic probe:
 * the picker reads only that header through `openInputStream(...).use`, then this object
 * stores the detected format. The image payload is never read into memory and the
 * selected document is never written.
 * Rescue maps are read with the same character cap as restore and
 * decoded with the same decoder. GPT backups are read with a hard byte cap and
 * validated by [GptCriticalBackup.inspectBlob] (headers, CRCs, geometry, entries).
 * Nothing here writes to the selected file or to the disk.
 */
object RestoreSourceInspector {
    /** Hard cap before any GPT blob restore; same value as the backup validator. */
    const val MAX_BLOB_BYTES: Int = GptCriticalBackup.MAX_BLOB_BYTES

    fun sha256Text(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Candidate sector sizes when the live device does not report one. */
    private val SECTOR_SIZE_CANDIDATES = intArrayOf(512, 1024, 2048, 4096)

    /**
     * [format] is the result of the caller's bounded 6-byte header probe; null means the
     * header was unreadable, so no stored-size comparison is safe before the stream opens.
     */
    fun inspectImage(
        uri: String,
        name: String?,
        sizeBytes: Long?,
        format: usb.image.ImageFormat? = null,
        lastModified: Long? = null,
    ): RescueImageInspection =
        RescueImageInspection(
            uri = uri,
            name = name,
            sizeBytes = sizeBytes,
            format = format,
            lastModified = lastModified,
        )

    fun inspectMap(
        uri: String,
        name: String?,
        sizeBytes: Long?,
        text: String,
    ): RescueMapInspection =
        try {
            RescueMapInspection(
                uri = uri,
                name = name,
                sizeBytes = sizeBytes,
                summary = RescueMap.decode(text).summary(),
                sha256 = sha256(text.toByteArray(Charsets.UTF_8)),
            )
        } catch (failure: Exception) {
            RescueMapInspection(
                uri = uri,
                name = name,
                sizeBytes = sizeBytes,
                error = failure.message ?: failure.javaClass.simpleName,
            )
        }

    /**
     * Validate an already bounded blob. [diskSectorSize] narrows the accepted geometry to
     * the live device; when null, each sector-size candidate is tried against the blob's
     * own GPT header, and the summary names the detected size.
     */
    fun inspectGpt(
        uri: String,
        name: String?,
        sizeBytes: Long?,
        blob: ByteArray,
        diskSectorSize: Int?,
    ): GptBackupInspection {
        val candidates =
            if (diskSectorSize != null && diskSectorSize > 0) {
                listOf(diskSectorSize)
            } else {
                SECTOR_SIZE_CANDIDATES.toList()
            }
        var lastFailure: Exception? = null
        for (sectorSize in candidates) {
            try {
                val report = GptCriticalBackup.inspectBlob(blob, sectorSize)
                val geo =
                    GptBackup.criticalGeometry(
                        sectorSize,
                        report.primary.entryCount,
                        report.primary.entrySize,
                    )
                return GptBackupInspection(
                    uri = uri,
                    name = name,
                    sizeBytes = sizeBytes,
                    summary =
                        GptBackupSummary(
                            sectorSize = sectorSize,
                            primarySectors = geo.primarySectors,
                            backupSectors = geo.backupSectors,
                            blobBytes = blob.size,
                            partitions = report.partitions,
                            firstUsable = report.primary.firstUsable,
                            lastUsable = report.primary.lastUsable,
                            originalSectorCount = report.primary.backupLba + 1UL,
                        ),
                    sha256 = sha256(blob),
                )
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        return GptBackupInspection(
            uri = uri,
            name = name,
            sizeBytes = sizeBytes,
            error = lastFailure?.message ?: "not a GPT critical backup",
        )
    }

    /**
     * Default 128×128 entry geometry size for [sectorSize]. Non-default entry arrays
     * derive size from the GPT header via [GptCriticalBackup.inspectBlob].
     */
    fun expectedBlobBytes(sectorSize: Int): Int =
        GptBackup.criticalGeometry(sectorSize).blobBytes(sectorSize)

    /**
     * Read at most [limit] bytes; one extra byte is read only to prove the document is
     * larger. Callers never stream an unbounded image through this helper.
     */
    fun readBounded(input: InputStream, limit: Int = MAX_BLOB_BYTES): ByteArray {
        require(limit > 0) { "bound must be positive" }
        val out = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val room = limit + 1 - total
            if (room <= 0) break
            val read = input.read(buffer, 0, minOf(buffer.size, room))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        require(total <= limit) { "selected document is larger than $limit bytes" }
        return out.toByteArray()
    }
}
