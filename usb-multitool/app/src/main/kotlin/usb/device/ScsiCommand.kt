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

    fun plan(bytes: Long, sectorSize: Int): List<Int> {
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

        val parts = ArrayList<Int>()
        var remaining = bytes
        while (remaining > MAX_CHUNK) {
            parts.add(MAX_CHUNK)
            remaining -= MAX_CHUNK
        }
        if (remaining > 0) {
            parts.add(remaining.toInt())
        }
        return parts
    }
}
