package usb.diagnostics

object RescueRangePolicy {
    fun requireSignedBytes(sectorCount: ULong, sectorSize: Int): Long {
        require(sectorSize > 0)
        require(sectorCount <= Long.MAX_VALUE.toULong() / sectorSize.toULong()) {
            "rescue image size exceeds signed file offset range"
        }
        return (sectorCount * sectorSize.toULong()).toLong()
    }
}
