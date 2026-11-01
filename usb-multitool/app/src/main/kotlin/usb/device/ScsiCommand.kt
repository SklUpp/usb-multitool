package usb.device

enum class ScsiWidth {
    CDB10,
    CDB16,
}

object ScsiCommand {
    private const val CDB10_MAX_LBA = 0xFFFF_FFFFUL
    private const val CDB10_MAX_BLOCKS = 0xFFFF

    fun select(lba: ULong, blocks: Int): ScsiWidth {
        require(blocks > 0) { "blocks must be positive, was $blocks" }
        return if (lba <= CDB10_MAX_LBA && blocks <= CDB10_MAX_BLOCKS) {
            ScsiWidth.CDB10
        } else {
            ScsiWidth.CDB16
        }
    }
}

object TransferPlan {
    private const val CHUNK = 64 * 1024

    fun plan(bytes: Long, sectorSize: Int): List<Int> {
        require(bytes >= 0) { "bytes must be non-negative, was $bytes" }
        require(sectorSize in 512..4096) {
            "sectorSize must be in 512..4096, was $sectorSize"
        }
        require(sectorSize > 0 && sectorSize and (sectorSize - 1) == 0) {
            "sectorSize must be a power of two, was $sectorSize"
        }
        require(bytes % sectorSize.toLong() == 0L) {
            "bytes $bytes not multiple of sectorSize $sectorSize"
        }
        if (bytes == 0L) return emptyList()

        val parts = ArrayList<Int>()
        var remaining = bytes
        while (remaining > CHUNK) {
            parts.add(CHUNK)
            remaining -= CHUNK
        }
        if (remaining > 0) {
            parts.add(remaining.toInt())
        }
        return parts
    }
}
