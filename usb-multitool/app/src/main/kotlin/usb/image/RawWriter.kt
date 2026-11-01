package usb.image

import java.nio.ByteBuffer
import usb.device.UsbDisk
import usb.service.DiskOperation

class ImageTooLargeException(message: String) : Exception(message)
class ImageCancelledException(message: String = "image write cancelled") : Exception(message)

object RawWriter {
    fun write(
        source: ImageSource,
        disk: UsbDisk,
        operation: DiskOperation = DiskOperation("raw-write"),
    ) {
        val capacity = disk.geometry.capacityBytes
        val sectorSize = disk.geometry.sectorSize
        val known = source.declaredDecodedSize
        if (known != null && known.toULong() > capacity) {
            throw ImageTooLargeException("image $known exceeds disk capacity $capacity")
        }

        source.open().use { input ->
            val hint = input.decodedSizeHint ?: known
            if (hint != null && hint.toULong() > capacity) {
                throw ImageTooLargeException("image $hint exceeds disk capacity $capacity")
            }

            val sector = ByteArray(sectorSize)
            var lba = 0UL
            var total = 0L
            while (true) {
                if (!operation.isActive()) throw ImageCancelledException()
                val n = readFully(input, sector)
                if (n <= 0) break
                val endBytes = total + n
                if (endBytes.toULong() > capacity) {
                    throw ImageTooLargeException(
                        "decoded stream exceeds disk capacity at byte $endBytes",
                    )
                }
                if (n < sectorSize) {
                    // Pad final partial sector with zeros; still one sector commit.
                    sector.fill(0, n, sectorSize)
                }
                if (lba >= disk.geometry.sectorCount) {
                    throw ImageTooLargeException("no sectors remain")
                }
                disk.write(lba, ByteBuffer.wrap(sector))
                lba++
                total += n
                operation.report(total)
                if (n < sectorSize) break
            }
            operation.bytesTotal = total
        }
    }

    private fun readFully(input: CountedInputStream, buffer: ByteArray): Int {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) return off
            off += n
        }
        return off
    }
}
