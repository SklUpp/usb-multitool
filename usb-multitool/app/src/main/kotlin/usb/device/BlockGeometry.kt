package usb.device

data class BlockGeometry(
    val sectorSize: Int,
    val sectorCount: ULong,
) {
    init {
        require(sectorSize in 512..4096) {
            "sectorSize must be in 512..4096, was $sectorSize"
        }
        require(sectorSize > 0 && sectorSize and (sectorSize - 1) == 0) {
            "sectorSize must be a power of two, was $sectorSize"
        }
    }
}
