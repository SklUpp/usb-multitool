package usb.device

data class BlockGeometry(
    val sectorSize: Int,
    val sectorCount: ULong,
) {
    init {
        require(sectorSize in 512..4096) {
            "sectorSize must be in 512..4096, was $sectorSize"
        }
        require(sectorSize.countOneBits() == 1) {
            "sectorSize must be a power of two, was $sectorSize"
        }
        require(sectorCount <= ULong.MAX_VALUE / sectorSize.toULong()) {
            "capacityBytes overflows ULong: sectorSize=$sectorSize sectorCount=$sectorCount"
        }
    }

    val capacityBytes: ULong
        get() = sectorSize.toULong() * sectorCount
}
