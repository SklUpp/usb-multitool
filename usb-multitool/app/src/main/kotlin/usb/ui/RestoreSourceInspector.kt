package usb.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import usb.diagnostics.RescueMap
import usb.multitool.GptCriticalBackup
import usb.partition.GptBackup

/**
 * Bounded inspection of restore sources before the destructive confirmation.
 *
 * Rescue images are inspected through SAF metadata only: the image itself is never
 * opened or read. Rescue maps are read with the same character cap as restore and
 * decoded with the same decoder. GPT backups are read with a hard byte cap and
 * validated by [GptCriticalBackup.inspectBlob] (headers, CRCs, geometry, entries).
 * Nothing here writes to the selected file or to the disk.
 */
object RestoreSourceInspector {
    /** Hard cap before any GPT blob restore; a real backup is < 1 MiB even at 4Kn. */
    const val MAX_BLOB_BYTES: Int = 2 * 1024 * 1024

    /** Candidate sector sizes when the live device does not report one. */
    private val SECTOR_SIZE_CANDIDATES = intArrayOf(512, 1024, 2048, 4096)

    fun inspectImage(
        uri: String,
        name: String?,
        sizeBytes: Long?,
    ): RescueImageInspection = RescueImageInspection(uri = uri, name = name, sizeBytes = sizeBytes)

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
     * the live device; when null, each sector-size candidate that matches the blob size
     * is validated by its own headers, and the summary names the detected size.
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
        val matching = candidates.filter { expectedBlobBytes(it) == blob.size }
        if (matching.isEmpty()) {
            val expected = candidates.joinToString(", ") { "$it-byte sectors: ${expectedBlobBytes(it)} bytes" }
            return GptBackupInspection(
                uri = uri,
                name = name,
                sizeBytes = sizeBytes,
                error = "selected file has ${blob.size} bytes; a GPT critical backup needs $expected",
            )
        }
        var lastFailure: Exception? = null
        for (sectorSize in matching) {
            val primarySectors = GptBackup.primarySectors(sectorSize)
            val backupSectors = GptBackup.backupSectors(sectorSize)
            try {
                val report =
                    GptCriticalBackup.inspectBlob(blob, sectorSize, primarySectors, backupSectors)
                return GptBackupInspection(
                    uri = uri,
                    name = name,
                    sizeBytes = sizeBytes,
                    summary =
                        GptBackupSummary(
                            sectorSize = sectorSize,
                            primarySectors = primarySectors,
                            backupSectors = backupSectors,
                            blobBytes = blob.size,
                            partitions = report.partitions,
                            firstUsable = report.primary.firstUsable,
                            lastUsable = report.primary.lastUsable,
                            originalSectorCount = report.primary.backupLba + 1UL,
                        ),
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

    fun expectedBlobBytes(sectorSize: Int): Int =
        (GptBackup.primarySectors(sectorSize) + GptBackup.backupSectors(sectorSize)) * sectorSize

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
