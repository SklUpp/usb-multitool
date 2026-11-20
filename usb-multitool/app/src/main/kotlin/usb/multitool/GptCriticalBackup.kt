package usb.multitool

import java.nio.ByteBuffer
import java.util.zip.CRC32
import usb.device.BlockDevice
import usb.operation.DiskOperation
import usb.partition.GptBackup

/**
 * Restore half of [usb.partition.GptBackup.backupCriticalSectors]: validate a captured
 * critical-sector blob against the attached disk, write it back, and verify the read-back.
 *
 * Every check fails loudly; a blob captured from a differently sized disk, a corrupted
 * file, or a device that drops writes never reports success.
 */
object GptCriticalBackup {
    private val SIGNATURE = "EFI PART".toByteArray(Charsets.US_ASCII)
    private const val HEADER_FIELDS_BYTES = 92

    /** Hard cap shared with [usb.ui.RestoreSourceInspector]; refuse oversized blobs early. */
    const val MAX_BLOB_BYTES: Int = 4 * 1024 * 1024

    data class Header(
        val currentLba: ULong,
        val backupLba: ULong,
        val firstUsable: ULong,
        val lastUsable: ULong,
        val entryLba: ULong,
        val entryCount: Int,
        val entrySize: Int,
        val entriesCrc: Int,
    )

    data class RestoreReport(
        val sectorsRestored: Int,
        val primary: Header,
        val backup: Header,
        val partitions: Int,
    )

    /** Expected blob size for [disk]; used by the capture side before writing the file. */
    fun blobSize(disk: BlockDevice, primarySectors: Int, backupSectors: Int): Int {
        require(primarySectors > 0 && backupSectors > 0) {
            "invalid GPT critical span $primarySectors+$backupSectors"
        }
        val total =
            (primarySectors.toLong() + backupSectors.toLong()) * disk.sectorSize.toLong()
        require(total in 1L..MAX_BLOB_BYTES.toLong()) {
            "GPT critical backup size $total exceeds cap $MAX_BLOB_BYTES"
        }
        return total.toInt()
    }

    private fun requireBlobSize(
        blob: ByteArray,
        sectorSize: Int,
        primarySectors: Int,
        backupSectors: Int,
    ) {
        val expectedBytes =
            (primarySectors.toLong() + backupSectors.toLong()) * sectorSize.toLong()
        require(expectedBytes in 1L..MAX_BLOB_BYTES.toLong()) {
            "GPT critical backup expected size $expectedBytes exceeds cap $MAX_BLOB_BYTES"
        }
        require(blob.size.toLong() == expectedBytes) {
            "GPT critical backup is ${blob.size} bytes; this device needs $expectedBytes " +
                "bytes ($primarySectors + $backupSectors sectors of $sectorSize bytes)"
        }
    }


    /**
     * Head/tail sector counts stored in the GPT header's entryCount×entrySize. Caller-supplied
     * [primarySectors]/[backupSectors] must match; defaults are never assumed from sector size alone.
     */
    private fun requireHeaderGeometry(
        primary: Header,
        sectorSize: Int,
        sectorCount: ULong,
        primarySectors: Int,
        backupSectors: Int,
    ): GptBackup.CriticalGeometry {
        require(primary.entryLba == 2UL) {
            "primary GPT entries at LBA ${primary.entryLba} cannot be represented in a " +
                "contiguous critical-sector blob (expected LBA 2)"
        }
        val derived = GptBackup.criticalGeometry(sectorSize, primary.entryCount, primary.entrySize)
        require(
            primarySectors == derived.primarySectors &&
                backupSectors == derived.backupSectors,
        ) {
            "backup geometry $primarySectors+$backupSectors does not match GPT header entry " +
                "array ${primary.entryCount}x${primary.entrySize} " +
                "(need ${derived.primarySectors}+${derived.backupSectors})"
        }
        require(sectorCount > (derived.primarySectors + derived.backupSectors).toULong()) {
            "backup describes a $sectorCount-sector disk, too small for " +
                "${derived.primarySectors} + ${derived.backupSectors} critical GPT sectors"
        }
        val entrySectors =
            ((primary.entryCount.toLong() * primary.entrySize.toLong() + sectorSize - 1) /
                sectorSize).toULong()
        require(primary.firstUsable >= 2UL + entrySectors) {
            "primary first usable LBA ${primary.firstUsable} overlaps the entry array"
        }
        require(primary.backupLba >= entrySectors && primary.lastUsable < primary.backupLba - entrySectors) {
            "primary last usable LBA ${primary.lastUsable} overlaps backup entries"
        }
        return derived
    }

    /**
     * Validate [blob] against [disk] without writing. Returns what a restore would write.
     * Throws [IllegalArgumentException] with the exact mismatch instead of guessing.
     */
    fun inspect(
        blob: ByteArray,
        disk: BlockDevice,
        primarySectors: Int,
        backupSectors: Int,
    ): RestoreReport =
        validate(blob, disk.sectorSize, disk.sectorCount, primarySectors, backupSectors)

    /** Derive geometry from the GPT header inside [blob], then validate against [disk]. */
    fun inspect(blob: ByteArray, disk: BlockDevice): RestoreReport {
        require(blob.size >= 2 * disk.sectorSize) {
            "GPT critical backup is ${blob.size} bytes; need at least ${2 * disk.sectorSize} " +
                "to read the primary header"
        }
        val primary = readHeader(blob, disk.sectorSize, disk.sectorSize)
        require(primary.backupLba != ULong.MAX_VALUE) {
            "backup primary header claims an impossible disk end ${primary.backupLba}"
        }
        val geo = GptBackup.criticalGeometry(disk.sectorSize, primary.entryCount, primary.entrySize)
        return inspect(blob, disk, geo.primarySectors, geo.backupSectors)
    }

    /**
     * Validate a captured primary + backup blob without a live disk. The recorded disk
     * size comes from the primary header, so a selected file can be inspected before the
     * destructive confirmation. The restore path still calls [inspect] against the live
     * disk; this function never admits a write by itself.
     *
     * [primarySectors]/[backupSectors] must equal the geometry derived from the GPT header
     * entry array inside [blob]; the 128×128 defaults are never assumed.
     */
    fun inspectBlob(
        blob: ByteArray,
        sectorSize: Int,
        primarySectors: Int,
        backupSectors: Int,
    ): RestoreReport {
        require(sectorSize > 0) { "invalid sector size $sectorSize" }
        require(blob.size <= MAX_BLOB_BYTES) {
            "GPT critical backup is ${blob.size} bytes; exceeds cap $MAX_BLOB_BYTES"
        }
        require(blob.size >= 2 * sectorSize) {
            "GPT critical backup is ${blob.size} bytes; need at least ${2 * sectorSize} " +
                "to read the primary header"
        }
        val primary = readHeader(blob, sectorSize, sectorSize)
        require(primary.currentLba == 1UL) {
            "backup primary header claims current LBA ${primary.currentLba}, expected 1"
        }
        require(primary.backupLba != ULong.MAX_VALUE) {
            "backup primary header claims an impossible disk end ${primary.backupLba}"
        }
        val sectorCount = primary.backupLba + 1UL
        val geo = requireHeaderGeometry(
            primary = primary,
            sectorSize = sectorSize,
            sectorCount = sectorCount,
            primarySectors = primarySectors,
            backupSectors = backupSectors,
        )
        requireBlobSize(blob, sectorSize, geo.primarySectors, geo.backupSectors)
        validateProtectiveMbr(blob, sectorSize, sectorCount)

        val tailStart = sectorCount - geo.backupSectors.toULong()
        val primaryEntries =
            entryArray(blob, primary, sectorSize, geo.primarySectors, geo.backupSectors, tailStart)
        require(crc32(primaryEntries, 0, primaryEntries.size) == primary.entriesCrc) {
            "primary GPT entry CRC mismatch inside the backup"
        }

        val backupHeaderOffset =
            (geo.primarySectors.toLong() + geo.backupSectors.toLong() - 1L) * sectorSize.toLong()
        require(backupHeaderOffset in 0L..Int.MAX_VALUE.toLong()) {
            "backup header offset $backupHeaderOffset exceeds Int range"
        }
        val backup = readHeader(blob, sectorSize, backupHeaderOffset.toInt())
        require(backup.currentLba == sectorCount - 1UL) {
            "backup header claims current LBA ${backup.currentLba}, expected ${sectorCount - 1UL}"
        }
        require(backup.backupLba == 1UL) {
            "backup header points at alternate LBA ${backup.backupLba}, expected 1"
        }
        return validatePair(
            blob = blob,
            sectorSize = sectorSize,
            sectorCount = sectorCount,
            primarySectors = geo.primarySectors,
            backupSectors = geo.backupSectors,
            tailStart = tailStart,
            primary = primary,
            backup = backup,
        )
    }

    /**
     * Derive head/tail sector counts from the GPT header inside [blob], then validate.
     * Callers that do not already know the entry geometry must use this overload.
     */
    fun inspectBlob(blob: ByteArray, sectorSize: Int): RestoreReport {
        require(sectorSize > 0) { "invalid sector size $sectorSize" }
        require(blob.size <= MAX_BLOB_BYTES) {
            "GPT critical backup is ${blob.size} bytes; exceeds cap $MAX_BLOB_BYTES"
        }
        require(blob.size >= 2 * sectorSize) {
            "GPT critical backup is ${blob.size} bytes; need at least ${2 * sectorSize} " +
                "to read the primary header"
        }
        val primary = readHeader(blob, sectorSize, sectorSize)
        require(primary.backupLba != ULong.MAX_VALUE) {
            "backup primary header claims an impossible disk end ${primary.backupLba}"
        }
        val geo = GptBackup.criticalGeometry(sectorSize, primary.entryCount, primary.entrySize)
        return inspectBlob(blob, sectorSize, geo.primarySectors, geo.backupSectors)
    }

    /**
     * Capture-side validation: the blob must be a self-consistent primary + backup pair for
     * the disk it was read from. Run before the file is written, so a non-GPT disk or a torn
     * capture never lands on the phone as a "backup".
     */
    fun inspectCaptured(
        blob: ByteArray,
        disk: BlockDevice,
        primarySectors: Int,
        backupSectors: Int,
    ): RestoreReport {
        val sectorSize = disk.sectorSize
        require(blob.size <= MAX_BLOB_BYTES) {
            "GPT critical backup is ${blob.size} bytes; exceeds cap $MAX_BLOB_BYTES"
        }
        require(blob.size >= 2 * sectorSize) {
            "GPT critical backup is ${blob.size} bytes; need at least ${2 * sectorSize} " +
                "to read the primary header"
        }
        // Peek the backup header with caller geometry only after header-derived sizes match.
        val primary = readHeader(blob, sectorSize, sectorSize)
        require(primary.backupLba != ULong.MAX_VALUE) {
            "backup primary header claims an impossible disk end ${primary.backupLba}"
        }
        val geo = requireHeaderGeometry(
            primary = primary,
            sectorSize = sectorSize,
            sectorCount = primary.backupLba + 1UL,
            primarySectors = primarySectors,
            backupSectors = backupSectors,
        )
        requireBlobSize(blob, sectorSize, geo.primarySectors, geo.backupSectors)
        val backupHeaderOffset =
            (geo.primarySectors.toLong() + geo.backupSectors.toLong() - 1L) * sectorSize.toLong()
        require(backupHeaderOffset in 0L..Int.MAX_VALUE.toLong()) {
            "backup header offset $backupHeaderOffset exceeds Int range"
        }
        val backup = readHeader(blob, sectorSize, backupHeaderOffset.toInt())
        require(backup.currentLba + 1UL == disk.sectorCount) {
            "captured backup header claims LBA ${backup.currentLba} but the disk reports " +
                "${disk.sectorCount} sectors; relocate the backup GPT first"
        }
        return validate(blob, sectorSize, disk.sectorCount, geo.primarySectors, geo.backupSectors)
    }

    /** Derive geometry from the blob header, then validate against [disk]. */
    fun inspectCaptured(blob: ByteArray, disk: BlockDevice): RestoreReport {
        require(blob.size >= 2 * disk.sectorSize) {
            "GPT critical backup is ${blob.size} bytes; need at least ${2 * disk.sectorSize} " +
                "to read the primary header"
        }
        val primary = readHeader(blob, disk.sectorSize, disk.sectorSize)
        require(primary.backupLba != ULong.MAX_VALUE) {
            "backup primary header claims an impossible disk end ${primary.backupLba}"
        }
        val geo = GptBackup.criticalGeometry(disk.sectorSize, primary.entryCount, primary.entrySize)
        return inspectCaptured(blob, disk, geo.primarySectors, geo.backupSectors)
    }

    private fun validate(
        blob: ByteArray,
        sectorSize: Int,
        sectorCount: ULong,
        primarySectors: Int,
        backupSectors: Int,
    ): RestoreReport {
        require(sectorCount > (primarySectors + backupSectors).toULong()) {
            "device with $sectorCount sectors is too small for $primarySectors + " +
                "$backupSectors critical GPT sectors"
        }
        val report = inspectBlob(blob, sectorSize, primarySectors, backupSectors)
        require(report.primary.backupLba + 1UL == sectorCount) {
            "backup was captured on a ${report.primary.backupLba + 1UL}-sector disk; this device has " +
                "$sectorCount sectors — restore the matching backup and then relocate it"
        }
        return report
    }

    /** Shared tail of [inspectBlob] and [validate]; both paths run the same checks. */
    private fun validatePair(
        blob: ByteArray,
        sectorSize: Int,
        sectorCount: ULong,
        primarySectors: Int,
        backupSectors: Int,
        tailStart: ULong,
        primary: Header,
        backup: Header,
    ): RestoreReport {
        require(backup.entryCount == primary.entryCount && backup.entrySize == primary.entrySize) {
            "primary and backup GPT entry geometry disagree"
        }
        val entrySectors =
            ((primary.entryCount.toLong() * primary.entrySize.toLong() + sectorSize.toLong() - 1L) /
                sectorSize.toLong()).toULong()
        require(entrySectors <= primarySectors.toULong() &&
            primary.entryLba <= primarySectors.toULong() - entrySectors) {
            "primary entries at LBA ${primary.entryLba} do not fit the " +
                "$primarySectors captured head sectors"
        }
        require(entrySectors <= sectorCount - 1UL &&
            backup.entryLba == (sectorCount - 1UL) - entrySectors) {
            "backup entries at LBA ${backup.entryLba} do not end at the backup header " +
                "LBA ${sectorCount - 1UL}"
        }
        require(backup.entryLba >= tailStart) {
            "backup entries at LBA ${backup.entryLba} lie outside the $backupSectors captured " +
                "tail sectors"
        }
        require(backup.entriesCrc == primary.entriesCrc) {
            "primary and backup GPT entry CRCs disagree"
        }
        val backupEntries =
            entryArray(blob, backup, sectorSize, primarySectors, backupSectors, tailStart)
        require(crc32(backupEntries, 0, backupEntries.size) == backup.entriesCrc) {
            "backup GPT entry CRC mismatch inside the backup"
        }
        require(primary.firstUsable <= primary.lastUsable) {
            "primary first usable LBA ${primary.firstUsable} exceeds last usable ${primary.lastUsable}"
        }
        require(backup.firstUsable == primary.firstUsable && backup.lastUsable == primary.lastUsable) {
            "primary and backup GPT usable ranges disagree"
        }
        // lastUsable + 1 <= backup.entryLba without ULong wrap on lastUsable == MAX.
        require(primary.lastUsable < backup.entryLba) {
            "primary last usable LBA ${primary.lastUsable} does not end before the backup " +
                "entries at ${backup.entryLba}"
        }
        // Refuse restore of a GPT the live parser would reject: every used entry must
        // sit inside usable bounds without overflow or overlap.
        val partitions = ArrayList<usb.partition.Partition>()
        for (i in 0 until backup.entryCount) {
            val off = i * backup.entrySize
            if (backupEntries.copyOfRange(off, off + 16).all { it == 0.toByte() }) continue
            val start = leLong(backupEntries, off + 32)
            val end = leLong(backupEntries, off + 40)
            require(end >= start) { "GPT entry end < start at index $i" }
            require(start >= primary.firstUsable && end <= primary.lastUsable) {
                "GPT entry outside usable range: start=$start end=$end " +
                    "usable=${primary.firstUsable}..${primary.lastUsable}"
            }
            partitions.add(
                usb.partition.Partition(
                    startLba = start,
                    sectorCount = end - start + 1UL,
                ),
            )
        }
        usb.partition.rejectOverlaps(partitions)

        return RestoreReport(
            sectorsRestored = run {
                val total = primarySectors.toLong() + backupSectors.toLong()
                require(total in 1L..Int.MAX_VALUE.toLong()) {
                    "restored sector count $total exceeds Int range"
                }
                total.toInt()
            },
            primary = primary,
            backup = backup,
            partitions = partitions.size,
        )
    }

    /**
     * Write [blob] back to the primary area at LBA 0 and the backup area at the disk end,
     * then read both regions and compare them byte for byte before reporting success.
     */
    fun restore(
        disk: BlockDevice,
        blob: ByteArray,
        primarySectors: Int,
        backupSectors: Int,
        operation: DiskOperation? = null,
    ): RestoreReport {
        val report = inspect(blob, disk, primarySectors, backupSectors)
        val sectorSize = disk.sectorSize
        val headBytesLong = primarySectors.toLong() * sectorSize.toLong()
        val tailBytesLong = backupSectors.toLong() * sectorSize.toLong()
        require(headBytesLong in 1L..Int.MAX_VALUE.toLong() && tailBytesLong in 1L..Int.MAX_VALUE.toLong()) {
            "restore region size exceeds Int range"
        }
        val headBytes = headBytesLong.toInt()
        val tailBytes = tailBytesLong.toInt()
        val tailStart = disk.sectorCount - backupSectors.toULong()

        operation?.let { it.state = usb.operation.OperationState.Running }
        try {
            operation?.let { it.failureCause = null }
            operation?.mutationAttempted = true
            disk.write(0UL, ByteBuffer.wrap(blob, 0, headBytes))
            operation?.mutationAttempted = true
            disk.write(tailStart, ByteBuffer.wrap(blob, headBytes, tailBytes))
        } catch (failure: Throwable) {
            operation?.let { it.state = usb.operation.OperationState.Partial; it.failureCause = failure }
            throw failure
        }

        try {
            disk.sync()
            val head = ByteArray(headBytes)
            disk.read(0UL, ByteBuffer.wrap(head))
            val mismatchHead = firstMismatch(blob, 0, head)
            if (mismatchHead >= 0) throw IllegalArgumentException("restore verification failed: LBA ${mismatchHead / sectorSize} does not match the backup")
            val tail = ByteArray(tailBytes)
            disk.read(tailStart, ByteBuffer.wrap(tail))
            val mismatchTail = firstMismatch(blob, headBytes, tail)
            if (mismatchTail >= 0) throw IllegalArgumentException("restore verification failed: LBA ${tailStart + (mismatchTail / sectorSize).toULong()} does not match the backup")
        } catch (failure: Throwable) {
            operation?.let { it.state = usb.operation.OperationState.Partial; it.failureCause = failure }
            throw failure
        }
        return report
    }

    /**
     * Derive geometry from the GPT header inside [blob], then restore. Prefer this overload
     * when the caller does not already know entryCount×entrySize.
     */
    fun restore(
        disk: BlockDevice,
        blob: ByteArray,
        operation: DiskOperation? = null,
    ): RestoreReport {
        require(blob.size >= 2 * disk.sectorSize) {
            "GPT critical backup is ${blob.size} bytes; need at least ${2 * disk.sectorSize} " +
                "to read the primary header"
        }
        val primary = readHeader(blob, disk.sectorSize, disk.sectorSize)
        require(primary.backupLba != ULong.MAX_VALUE) {
            "backup primary header claims an impossible disk end ${primary.backupLba}"
        }
        val geo = GptBackup.criticalGeometry(disk.sectorSize, primary.entryCount, primary.entrySize)
        return restore(disk, blob, geo.primarySectors, geo.backupSectors, operation)
    }

    /** Validate the LBA 0 Protective MBR required by UEFI 2.10A §5.2. */
    private fun validateProtectiveMbr(blob: ByteArray, sectorSize: Int, sectorCount: ULong) {
        require(sectorCount > 1UL) { "GPT disk must contain at least two sectors" }
        require(sectorSize >= 512 && blob.size >= sectorSize) {
            "sector is too small for a protective MBR"
        }
        require((blob[510].toInt() and 0xFF) == 0x55 &&
            (blob[511].toInt() and 0xFF) == 0xAA) {
            "missing protective MBR signature at LBA 0"
        }
        val entry = 446
        // UEFI §5.2 says implementations ignore PMBR BootIndicator; validate protective type/extent instead.
        require((blob[entry + 4].toInt() and 0xFF) == 0xEE) {
            "missing GPT protective MBR entry at LBA 0"
        }
        require(leInt(blob, entry + 8).toUInt().toULong() == 1UL) {
            "GPT protective MBR entry must start at LBA 1"
        }
        val expectedSize = minOf(sectorCount - 1UL, 0xFFFF_FFFFUL)
        require(leInt(blob, entry + 12).toUInt().toULong() == expectedSize) {
            "GPT protective MBR entry does not cover disk"
        }
        for (slot in 1 until 4) {
            val offset = entry + slot * 16
            val type = blob[offset + 4].toInt() and 0xFF
            val start = leInt(blob, offset + 8).toUInt()
            val size = leInt(blob, offset + 12).toUInt()
            require(type == 0 && start == 0u && size == 0u) {
                "hybrid GPT/MBR layout: protective MBR has extra partition entry $slot"
            }
        }
    }

    private fun firstMismatch(expected: ByteArray, expectedOffset: Int, actual: ByteArray): Int {
        for (i in actual.indices) {
            if (expected[expectedOffset + i] != actual[i]) return i
        }
        return -1
    }

    private fun readHeader(blob: ByteArray, sectorSize: Int, offset: Int): Header {
        require(offset >= 0 && offset + HEADER_FIELDS_BYTES <= blob.size) {
            "GPT header at byte $offset lies outside the backup"
        }
        for (i in SIGNATURE.indices) {
            require(blob[offset + i] == SIGNATURE[i]) {
                "missing GPT signature at byte $offset"
            }
        }
        val headerSize = leInt(blob, offset + 12)
        require(headerSize in HEADER_FIELDS_BYTES..sectorSize) {
            "invalid GPT header size $headerSize"
        }
        val storedCrc = leInt(blob, offset + 16)
        val header = blob.copyOfRange(offset, offset + headerSize)
        header[16] = 0
        header[17] = 0
        header[18] = 0
        header[19] = 0
        require(crc32(header, 0, headerSize) == storedCrc) {
            "GPT header CRC mismatch at byte $offset"
        }
        return Header(
            currentLba = leLong(blob, offset + 24),
            backupLba = leLong(blob, offset + 32),
            firstUsable = leLong(blob, offset + 40),
            lastUsable = leLong(blob, offset + 48),
            entryLba = leLong(blob, offset + 72),
            entryCount = leInt(blob, offset + 80),
            entrySize = leInt(blob, offset + 84),
            entriesCrc = leInt(blob, offset + 88),
        )
    }

    /** Entries sector of [header], mapped into the head/tail layout of the blob. */
    private fun entryArray(
        blob: ByteArray,
        header: Header,
        sectorSize: Int,
        primarySectors: Int,
        backupSectors: Int,
        tailStart: ULong,
    ): ByteArray {
        require(header.entrySize in 128..512 && header.entryCount in 1..1024) {
            "invalid GPT entry geometry ${header.entryCount} x ${header.entrySize}"
        }
        val bytesLong = header.entryCount.toLong() * header.entrySize.toLong()
        require(bytesLong in 1L..Int.MAX_VALUE.toLong()) {
            "GPT entry array size $bytesLong exceeds Int range"
        }
        val bytes = bytesLong.toInt()
        val sectors =
            ((bytesLong + sectorSize.toLong() - 1L) / sectorSize.toLong()).toULong()
        val lba = header.entryLba
        require(lba >= 2UL) { "GPT entries must not start in the protective MBR or header" }
        val offset =
            if (lba < primarySectors.toULong()) {
                require(sectors <= primarySectors.toULong() &&
                    lba <= primarySectors.toULong() - sectors) {
                    "GPT entries at LBA $lba overrun the $primarySectors captured head sectors"
                }
                lba * sectorSize.toULong()
            } else {
                require(lba >= tailStart) {
                    "GPT entries at LBA $lba are inside the unusable gap"
                }
                val index = lba - tailStart
                require(sectors <= backupSectors.toULong() &&
                    index <= backupSectors.toULong() - sectors) {
                    "GPT entries at LBA $lba overrun the $backupSectors captured tail sectors"
                }
                primarySectors.toULong() * sectorSize.toULong() + index * sectorSize.toULong()
            }
        require(offset <= Int.MAX_VALUE.toULong()) {
            "GPT entry offset $offset exceeds Int range"
        }
        val start = offset.toInt()
        require(start + bytes <= blob.size) { "GPT entries at LBA $lba overrun the backup" }
        return blob.copyOfRange(start, start + bytes)
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 3 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return value
    }

    private fun leLong(bytes: ByteArray, offset: Int): ULong {
        var value = 0UL
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL).toULong()
        }
        return value
    }
}
