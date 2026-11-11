package usb.multitool

import java.nio.ByteBuffer
import java.util.zip.CRC32
import usb.device.BlockDevice
import usb.operation.DiskOperation

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
    fun blobSize(disk: BlockDevice, primarySectors: Int, backupSectors: Int): Int =
        (primarySectors + backupSectors) * disk.sectorSize

    private fun requireBlobSize(
        blob: ByteArray,
        sectorSize: Int,
        primarySectors: Int,
        backupSectors: Int,
    ) {
        val expectedBytes = (primarySectors + backupSectors) * sectorSize
        require(blob.size == expectedBytes) {
            "GPT critical backup is ${blob.size} bytes; this device needs $expectedBytes " +
                "bytes ($primarySectors + $backupSectors sectors of $sectorSize bytes)"
        }
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

    /**
     * Validate a captured primary + backup blob without a live disk. The recorded disk
     * size comes from the primary header, so a selected file can be inspected before the
     * destructive confirmation. The restore path still calls [inspect] against the live
     * disk; this function never admits a write by itself.
     */
    fun inspectBlob(
        blob: ByteArray,
        sectorSize: Int,
        primarySectors: Int,
        backupSectors: Int,
    ): RestoreReport {
        require(sectorSize > 0) { "invalid sector size $sectorSize" }
        requireBlobSize(blob, sectorSize, primarySectors, backupSectors)
        val primary = readHeader(blob, sectorSize, sectorSize)
        require(primary.currentLba == 1UL) {
            "backup primary header claims current LBA ${primary.currentLba}, expected 1"
        }
        require(primary.backupLba != ULong.MAX_VALUE) {
            "backup primary header claims an impossible disk end ${primary.backupLba}"
        }
        val sectorCount = primary.backupLba + 1UL
        require(sectorCount > (primarySectors + backupSectors).toULong()) {
            "backup describes a $sectorCount-sector disk, too small for $primarySectors + " +
                "$backupSectors critical GPT sectors"
        }
        val tailStart = sectorCount - backupSectors.toULong()
        val primaryEntries =
            entryArray(blob, primary, sectorSize, primarySectors, backupSectors, tailStart)
        require(crc32(primaryEntries, 0, primaryEntries.size) == primary.entriesCrc) {
            "primary GPT entry CRC mismatch inside the backup"
        }

        val backup = readHeader(blob, sectorSize, (primarySectors + backupSectors - 1) * sectorSize)
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
            primarySectors = primarySectors,
            backupSectors = backupSectors,
            tailStart = tailStart,
            primary = primary,
            backup = backup,
        )
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
        requireBlobSize(blob, sectorSize, primarySectors, backupSectors)
        val backup =
            readHeader(blob, sectorSize, (primarySectors + backupSectors - 1) * sectorSize)
        require(backup.currentLba + 1UL == disk.sectorCount) {
            "captured backup header claims LBA ${backup.currentLba} but the disk reports " +
                "${disk.sectorCount} sectors; relocate the backup GPT first"
        }
        return validate(blob, sectorSize, disk.sectorCount, primarySectors, backupSectors)
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
            ((primary.entryCount * primary.entrySize + sectorSize - 1) / sectorSize).toULong()
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
            sectorsRestored = primarySectors + backupSectors,
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
        val headBytes = primarySectors * sectorSize
        val tailBytes = backupSectors * sectorSize
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
        val bytes = header.entryCount * header.entrySize
        val sectors = ((bytes + sectorSize - 1) / sectorSize).toULong()
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
