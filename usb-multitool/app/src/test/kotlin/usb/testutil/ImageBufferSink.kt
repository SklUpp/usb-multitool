package usb.testutil

import usb.diagnostics.RescueSink

/**
 * Seekable in-memory image for rescue-copy tests: writes land at their LBA, which
 * is what the SectorForge engine expects from a real image file.
 */
class ImageBufferSink(private val sectorSize: Int, sectorCount: ULong) {
    private val storage = ByteArray((sectorCount * sectorSize.toULong()).toInt())

    val sink: RescueSink =
        object : RescueSink {
            override val positional = true

            override fun writeSector(lba: ULong, sector: ByteArray) =
                writeBatch(lba, sector, sector.size, sectorSize)

            override fun writeBatch(lba: ULong, bytes: ByteArray, length: Int, sectorSize: Int) {
                require(length > 0 && length <= bytes.size && length % sectorSize == 0)
                require(lba + (length / sectorSize).toULong() <= sectorCount) {
                    "write past the image end at LBA $lba"
                }
                System.arraycopy(bytes, 0, storage, (lba * sectorSize.toULong()).toInt(), length)
            }
        }

    fun bytes(): ByteArray = storage.copyOf()
}
