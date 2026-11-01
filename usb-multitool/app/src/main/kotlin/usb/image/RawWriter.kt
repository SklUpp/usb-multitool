package usb.image

import java.nio.ByteBuffer
import usb.device.UsbDisk
import usb.diagnostics.DestructiveConfirmation
import usb.diagnostics.closeMountedVolumes
import usb.fs.MountedVolume
import usb.service.DiskOperation
import usb.service.OperationState

class ImageTooLargeException(message: String) : Exception(message)
class ImageCancelledException(message: String = "image write cancelled") : Exception(message)
class OverwriteRequiredException(message: String = "raw write requires overwrite=true") : Exception(message)

object RawWriter {
    /**
     * Stream [source] onto [disk] from LBA 0.
     * Size rejection happens before any sector write.
     * Mounted volumes are synced and closed first.
     * [confirmation] must match [identity] and disk capacity.
     * [overwrite] must be true; silent overwrite is refused.
     */
    fun write(
        source: ImageSource,
        disk: UsbDisk,
        identity: String,
        confirmation: DestructiveConfirmation,
        overwrite: Boolean,
        openVolumes: List<MountedVolume> = emptyList(),
        operation: DiskOperation = DiskOperation("raw-write"),
    ) {
        confirmation.requireMatch(identity, disk)
        if (!overwrite) throw OverwriteRequiredException()

        val capacity = disk.geometry.capacityBytes
        val sectorSize = disk.geometry.sectorSize
        val known = source.declaredDecodedSize
        if (known != null && known.toULong() > capacity) {
            throw ImageTooLargeException("image $known exceeds disk capacity $capacity")
        }

        closeMountedVolumes(openVolumes)

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
                    // Do not commit overflowing chunk. Prior sectors may already
                    // be on disk; report PARTIAL instead of claiming zero writes.
                    operation.bytesTotal = total
                    if (total > 0L) {
                        operation.state = OperationState.Partial
                    }
                    throw ImageTooLargeException(
                        "decoded stream exceeds disk capacity at byte $endBytes",
                    )
                }
                if (n < sectorSize) {
                    // Pad final partial sector with zeros; still one sector commit.
                    sector.fill(0, n, sectorSize)
                }
                if (lba >= disk.geometry.sectorCount) {
                    operation.bytesTotal = total
                    if (total > 0L) {
                        operation.state = OperationState.Partial
                    }
                    throw ImageTooLargeException("no sectors remain")
                }
                disk.write(lba, ByteBuffer.wrap(sector))
                lba++
                total += n
                operation.report(total)
                if (n < sectorSize) break
            }
            operation.bytesTotal = total
            if (operation.state == OperationState.Queued || operation.state == OperationState.Running) {
                operation.state = OperationState.Completed
            }
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
