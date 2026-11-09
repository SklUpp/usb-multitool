package usb.partition

import java.nio.charset.StandardCharsets

/** Minimal GPT disk image with real primary + backup structures. */
object SyntheticGpt {
    private val EFI_TYPE = guidBytes("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")
    private val DATA_TYPE = guidBytes("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
    private val DISK_GUID = guidBytes("12345678-1234-1234-1234-123456789ABC")

    /** One GPT record; [slot] is the raw entry index, so sparse layouts are expressible. */
    data class Layout(
        val slot: Int,
        val start: ULong,
        val end: ULong,
        val name: String,
        val efi: Boolean = false,
        val attributes: ULong = 0UL,
    )

    fun build(
        sectorCount: ULong = 2048UL,
        sectorSize: Int = 512,
        entryCount: Int = 128,
        layout: List<Layout>? = null,
    ): ImageBlockDevice {
        require(sectorCount >= 128UL)
        val entrySize = 128
        val entryBytes = entryCount * entrySize
        val entrySectors = ((entryBytes + sectorSize - 1) / sectorSize).toULong()
        val primaryEntryLba = 2UL
        val firstUsable = primaryEntryLba + entrySectors
        val backupHeaderLba = sectorCount - 1UL
        val backupEntriesLba = backupHeaderLba - entrySectors
        val lastUsable = backupEntriesLba - 1UL
        require(lastUsable > firstUsable + 10UL)

        val image = ByteArray((sectorCount * sectorSize.toULong()).toInt())

        // Protective MBR
        image[510] = 0x55.toByte()
        image[511] = 0xAA.toByte()
        image[446 + 4] = 0xEE.toByte()
        putLeInt(image, 446 + 8, 1)
        putLeInt(image, 446 + 12, (sectorCount - 1UL).toInt())

        val entries = ByteArray(entryBytes)
        val entriesLayout = layout ?: listOf(
            Layout(0, firstUsable, firstUsable + 9UL, "EFI", efi = true),
            Layout(1, firstUsable + 10UL, firstUsable + 29UL, "DATA"),
        )
        for (spec in entriesLayout) {
            require(spec.slot in 0 until entryCount) { "slot ${spec.slot} out of range" }
            require(spec.end >= spec.start) { "entry end < start at slot ${spec.slot}" }
            require(spec.start >= firstUsable && spec.end <= lastUsable) {
                "entry slot ${spec.slot} outside usable range"
            }
            writeEntry(entries, spec)
        }
        val entriesCrc = GptTable.crc32(entries)

        fun writeHeader(lba: ULong, current: ULong, backup: ULong, entryLba: ULong) {
            val sector = ByteArray(sectorSize)
            "EFI PART".toByteArray(StandardCharsets.US_ASCII).copyInto(sector)
            putLeInt(sector, 8, 0x00010000)
            putLeInt(sector, 12, 92)
            putLeInt(sector, 16, 0)
            putLeU64(sector, 24, current)
            putLeU64(sector, 32, backup)
            putLeU64(sector, 40, firstUsable)
            putLeU64(sector, 48, lastUsable)
            DISK_GUID.copyInto(sector, 56)
            putLeU64(sector, 72, entryLba)
            putLeInt(sector, 80, entryCount)
            putLeInt(sector, 84, entrySize)
            putLeInt(sector, 88, entriesCrc)
            putLeInt(sector, 16, GptTable.crc32(sector, 0, 92))
            sector.copyInto(image, (lba * sectorSize.toULong()).toInt())
        }

        writeHeader(1UL, 1UL, backupHeaderLba, primaryEntryLba)
        writeHeader(backupHeaderLba, backupHeaderLba, 1UL, backupEntriesLba)
        entries.copyInto(image, (primaryEntryLba * sectorSize.toULong()).toInt())
        entries.copyInto(image, (backupEntriesLba * sectorSize.toULong()).toInt())

        return ImageBlockDevice(image, sectorSize, sectorCount)
    }

    private fun writeEntry(entries: ByteArray, spec: Layout) {
        val off = spec.slot * 128
        (if (spec.efi) EFI_TYPE else DATA_TYPE).copyInto(entries, off)
        // unique GUID: vary by slot
        guidBytes("%08X-0000-0000-0000-000000000000".format(spec.slot + 1))
            .copyInto(entries, off + 16)
        putLeU64(entries, off + 32, spec.start)
        putLeU64(entries, off + 40, spec.end)
        putLeU64(entries, off + 48, spec.attributes)
        val nameBytes = spec.name.toByteArray(StandardCharsets.UTF_16LE)
        nameBytes.copyInto(entries, off + 56, 0, minOf(nameBytes.size, 72))
    }

    private fun guidBytes(text: String): ByteArray {
        val hex = text.replace("-", "")
        require(hex.length == 32)
        val raw = ByteArray(16)
        for (i in 0 until 16) {
            raw[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        // Microsoft mixed-endian for first 3 fields
        fun rev(start: Int, len: Int) {
            raw.copyOfRange(start, start + len).reversedArray().copyInto(raw, start)
        }
        rev(0, 4)
        rev(4, 2)
        rev(6, 2)
        return raw
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putLeU64(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFFu).toByte()
        }
    }
}
