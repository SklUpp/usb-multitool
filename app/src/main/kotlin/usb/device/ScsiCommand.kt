package usb.device

enum class ScsiWidth {
    CDB10,
    CDB16,
}

enum class ScsiDirection {
    NONE,
    IN,
    OUT,
}

data class ScsiCdb(
    val bytes: ByteArray,
    val direction: ScsiDirection,
    val transferLength: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScsiCdb) return false
        return direction == other.direction &&
            transferLength == other.transferLength &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + transferLength
        return result
    }
}

object ScsiCommand {
    private const val CDB10_MAX_LBA = 0xFFFF_FFFFUL
    private const val CDB10_MAX_BLOCKS = 0xFFFF

    const val OP_TEST_UNIT_READY = 0x00
    const val OP_REQUEST_SENSE = 0x03
    const val OP_INQUIRY = 0x12
    const val OP_READ_CAPACITY10 = 0x25
    const val OP_READ10 = 0x28
    const val OP_WRITE10 = 0x2A
    const val OP_SYNCHRONIZE_CACHE10 = 0x35
    const val OP_READ_CAPACITY16 = 0x9E
    const val OP_READ16 = 0x88
    const val OP_WRITE16 = 0x8A
    const val SERVICE_ACTION_READ_CAPACITY16 = 0x10
    /** SAT ATA PASS-THROUGH (16). Whitelisted read-only CDBs only. */
    const val OP_ATA_PASS_THROUGH_16 = 0x85
    private const val ATA_PROTOCOL_PIO_DATA_IN = 4
    private const val ATA_T_LENGTH_SECTOR_COUNT = 2
    private const val ATA_CMD_IDENTIFY_DEVICE = 0xEC
    private const val ATA_CMD_SMART = 0xB0
    private const val ATA_SMART_READ_DATA = 0xD0
    private const val ATA_SMART_LBA_MID = 0x4F
    private const val ATA_SMART_LBA_HIGH = 0xC2
    /** 512-byte IDENTIFY / SMART payload. */
    const val ATA_SECTOR_BYTES = 512


    fun select(lba: ULong, blocks: Int): ScsiWidth {
        require(blocks > 0) { "blocks must be positive, was $blocks" }
        val fits10 = lba <= CDB10_MAX_LBA &&
            blocks <= CDB10_MAX_BLOCKS &&
            blocks.toULong() <= CDB10_MAX_LBA - lba + 1UL
        return if (fits10) ScsiWidth.CDB10 else ScsiWidth.CDB16
    }

    fun testUnitReady(): ScsiCdb =
        ScsiCdb(ByteArray(6), ScsiDirection.NONE, 0)

    fun requestSense(allocationLength: Int = 18): ScsiCdb {
        require(allocationLength in 1..255)
        val cdb = ByteArray(6)
        cdb[0] = OP_REQUEST_SENSE.toByte()
        cdb[4] = allocationLength.toByte()
        return ScsiCdb(cdb, ScsiDirection.IN, allocationLength)
    }

    fun inquiry(allocationLength: Int = 36): ScsiCdb {
        require(allocationLength in 1..255)
        val cdb = ByteArray(6)
        cdb[0] = OP_INQUIRY.toByte()
        cdb[4] = allocationLength.toByte()
        return ScsiCdb(cdb, ScsiDirection.IN, allocationLength)
    }

    fun readCapacity10(): ScsiCdb {
        val cdb = ByteArray(10)
        cdb[0] = OP_READ_CAPACITY10.toByte()
        return ScsiCdb(cdb, ScsiDirection.IN, 8)
    }

    fun readCapacity16(allocationLength: Int = 32): ScsiCdb {
        require(allocationLength >= 32)
        val cdb = ByteArray(16)
        cdb[0] = OP_READ_CAPACITY16.toByte()
        cdb[1] = SERVICE_ACTION_READ_CAPACITY16.toByte()
        for (i in 0 until 4) {
            cdb[10 + i] = ((allocationLength shr (24 - 8 * i)) and 0xFF).toByte()
        }
        return ScsiCdb(cdb, ScsiDirection.IN, allocationLength)
    }

    fun read(lba: ULong, blocks: Int, sectorSize: Int): ScsiCdb {
        val bytes = checkedTransferBytes(blocks, sectorSize)
        return when (select(lba, blocks)) {
            ScsiWidth.CDB10 -> ScsiCdb(buildCdb10(OP_READ10, lba, blocks), ScsiDirection.IN, bytes)
            ScsiWidth.CDB16 -> ScsiCdb(buildCdb16(OP_READ16, lba, blocks), ScsiDirection.IN, bytes)
        }
    }

    fun write(lba: ULong, blocks: Int, sectorSize: Int): ScsiCdb {
        val bytes = checkedTransferBytes(blocks, sectorSize)
        return when (select(lba, blocks)) {
            ScsiWidth.CDB10 -> ScsiCdb(buildCdb10(OP_WRITE10, lba, blocks), ScsiDirection.OUT, bytes)
            ScsiWidth.CDB16 -> ScsiCdb(buildCdb16(OP_WRITE16, lba, blocks), ScsiDirection.OUT, bytes)
        }
    }

    fun synchronizeCache10(): ScsiCdb {
        val cdb = ByteArray(10)
        cdb[0] = OP_SYNCHRONIZE_CACHE10.toByte()
        return ScsiCdb(cdb, ScsiDirection.NONE, 0)
    }

    /**
     * ATA PASS-THROUGH (16) IDENTIFY DEVICE. Protocol PIO Data-In, CK_COND=1,
     * T_DIR=device→host, BYTE_BLOCK=512, T_LENGTH=sector_count. No UI-supplied ATA fields.
     */
    fun ataIdentifyDevice(): ScsiCdb =
        ataPassThrough16(
            feature = 0,
            sectorCount = 1,
            lbaLow = 0,
            lbaMid = 0,
            lbaHigh = 0,
            command = ATA_CMD_IDENTIFY_DEVICE,
        )

    /**
     * ATA PASS-THROUGH (16) SMART READ DATA (feature 0xD0). Same transport flags as IDENTIFY.
     */
    fun ataSmartReadData(): ScsiCdb =
        ataPassThrough16(
            feature = ATA_SMART_READ_DATA,
            sectorCount = 1,
            lbaLow = 0,
            lbaMid = ATA_SMART_LBA_MID,
            lbaHigh = ATA_SMART_LBA_HIGH,
            command = ATA_CMD_SMART,
        )

    private fun ataPassThrough16(
        feature: Int,
        sectorCount: Int,
        lbaLow: Int,
        lbaMid: Int,
        lbaHigh: Int,
        command: Int,
    ): ScsiCdb {
        require(feature in 0..0xFF)
        require(sectorCount in 0..0xFF)
        require(lbaLow in 0..0xFF)
        require(lbaMid in 0..0xFF)
        require(lbaHigh in 0..0xFF)
        require(command in 0..0xFF)
        val cdb = ByteArray(16)
        cdb[0] = OP_ATA_PASS_THROUGH_16.toByte()
        // MULTIPLE_COUNT=0 | PROTOCOL | EXTEND=0
        cdb[1] = ((ATA_PROTOCOL_PIO_DATA_IN and 0x0F) shl 1).toByte()
        // OFF_LINE=0 | CK_COND=1 | T_DIR=1 | BYTE_BLOCK=1 | T_LENGTH=2
        cdb[2] = (
            (1 shl 5) or
                (1 shl 3) or
                (1 shl 2) or
                ATA_T_LENGTH_SECTOR_COUNT
            ).toByte()
        cdb[4] = feature.toByte()
        cdb[6] = sectorCount.toByte()
        cdb[8] = lbaLow.toByte()
        cdb[10] = lbaMid.toByte()
        cdb[12] = lbaHigh.toByte()
        cdb[14] = command.toByte()
        return ScsiCdb(cdb, ScsiDirection.IN, ATA_SECTOR_BYTES)
    }


    private fun checkedTransferBytes(blocks: Int, sectorSize: Int): Int {
        require(blocks > 0)
        require(sectorSize > 0)
        val bytes = blocks.toLong() * sectorSize.toLong()
        require(bytes <= Int.MAX_VALUE) { "transfer too large: $bytes" }
        return bytes.toInt()
    }

    private fun buildCdb10(op: Int, lba: ULong, blocks: Int): ByteArray =
        byteArrayOf(
            op.toByte(),
            0,
            ((lba shr 24) and 0xFFu).toByte(),
            ((lba shr 16) and 0xFFu).toByte(),
            ((lba shr 8) and 0xFFu).toByte(),
            (lba and 0xFFu).toByte(),
            0,
            ((blocks shr 8) and 0xFF).toByte(),
            (blocks and 0xFF).toByte(),
            0,
        )

    private fun buildCdb16(op: Int, lba: ULong, blocks: Int): ByteArray =
        ByteArray(16).also { cdb ->
            cdb[0] = op.toByte()
            for (i in 0 until 8) {
                cdb[2 + i] = ((lba shr (56 - 8 * i)) and 0xFFu).toByte()
            }
            for (i in 0 until 4) {
                cdb[10 + i] = ((blocks shr (24 - 8 * i)) and 0xFF).toByte()
            }
        }
}

object TransferPlan {
    const val MAX_CHUNK = 64 * 1024

    fun plan(
        bytes: Long,
        sectorSize: Int,
        maxChunk: Int = MAX_CHUNK,
    ): List<Int> {
        require(bytes >= 0) { "bytes must be non-negative, was $bytes" }
        require(sectorSize in 512..4096) {
            "sectorSize must be in 512..4096, was $sectorSize"
        }
        require(sectorSize.countOneBits() == 1) {
            "sectorSize must be a power of two, was $sectorSize"
        }
        if (bytes == 0L) return emptyList()
        require(bytes % sectorSize == 0L) {
            "bytes $bytes not multiple of sectorSize $sectorSize"
        }
        val chunk = maxChunk - maxChunk % sectorSize
        require(chunk > 0) { "maxChunk $maxChunk is smaller than one sector" }

        val parts = ArrayList<Int>()
        var remaining = bytes
        while (remaining > chunk) {
            parts.add(chunk)
            remaining -= chunk
        }
        if (remaining > 0) {
            parts.add(remaining.toInt())
        }
        return parts
    }
}
