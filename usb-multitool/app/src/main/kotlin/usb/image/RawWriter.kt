package usb.image

import java.io.EOFException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import usb.device.DiskException
import usb.device.BlockDevice
import usb.device.TransferPlan
import usb.device.capacityBytes
import usb.diagnostics.DestructiveConfirmation
import usb.diagnostics.closeMountedVolumes
import usb.fs.MountedVolume
import usb.service.DiskOperation
import usb.service.OperationState

class ImageTooLargeException(message: String) : Exception(message)
class ImageCancelledException(message: String = "image write cancelled") : CancellationException(message)
class OverwriteRequiredException(message: String = "raw write requires overwrite=true") : Exception(message)
class ImageVerifyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object RawWriter {
    /**
     * Stream [source] onto [disk] from LBA 0 in [TransferPlan.MAX_CHUNK]-sized blocks.
     * Size rejection happens before any sector write.
     * Mounted volumes are synced and closed first.
     * [confirmation] must match [identity] and disk capacity.
     * [overwrite] must be true; silent overwrite is refused.
     * After sync, re-opens [source] and compares every written byte (padding included).
     * Cancellation is checked between chunks on both write and verify.
     */
    fun write(
        source: ImageSource,
        disk: BlockDevice,
        identity: String,
        confirmation: DestructiveConfirmation,
        overwrite: Boolean,
        openVolumes: List<MountedVolume> = emptyList(),
        operation: DiskOperation = DiskOperation("raw-write"),
    ) {
        confirmation.requireMatch(identity, disk.capacityBytes)
        if (!overwrite) throw OverwriteRequiredException()

        val capacity = disk.capacityBytes
        val sectorSize = disk.sectorSize
        val known = source.declaredDecodedSize
        if (known != null && known.toULong() > capacity) {
            throw ImageTooLargeException("image $known exceeds disk capacity $capacity")
        }

        closeMountedVolumes(openVolumes)

        val chunkBytes = TransferPlan.MAX_CHUNK
        require(chunkBytes % sectorSize == 0) {
            "TransferPlan.MAX_CHUNK must be a multiple of sectorSize"
        }

        source.open().use { input ->
            val hint = input.decodedSizeHint ?: known
            if (hint != null && hint.toULong() > capacity) {
                throw ImageTooLargeException("image $hint exceeds disk capacity $capacity")
            }

            val chunk = ByteArray(chunkBytes)
            var lba = 0UL
            var total = 0L
            while (true) {
                if (!operation.isActive()) throw ImageCancelledException()
                val remainingCap = (capacity - total.toULong()).toLong()
                if (remainingCap <= 0L) {
                    // Disk full: any further source byte means overflow after commits.
                    val peek = ByteArray(1)
                    val extra = input.read(peek)
                    if (extra > 0) {
                        operation.bytesTotal = total
                        if (total > 0L) {
                            operation.state = OperationState.Partial
                        }
                        throw ImageTooLargeException(
                            "decoded stream exceeds disk capacity at byte ${total + extra}",
                        )
                    }
                    break
                }
                // Never read past remaining capacity — preserves Partial for prior commits.
                val readLimit = minOf(chunkBytes.toLong(), remainingCap).toInt()
                val n = readFully(input, chunk, readLimit)
                if (n <= 0) break
                val writeBytes =
                    if (n % sectorSize == 0) {
                        n
                    } else {
                        // Pad final partial sector with zeros; still sector-aligned commit.
                        val padded = ((n + sectorSize - 1) / sectorSize) * sectorSize
                        require(padded.toLong() <= remainingCap) {
                            "padded final sector exceeds remaining capacity"
                        }
                        chunk.fill(0, n, padded)
                        padded
                    }
                val blocks = writeBytes / sectorSize
                disk.write(lba, ByteBuffer.wrap(chunk, 0, writeBytes))
                lba += blocks.toULong()
                total += n
                operation.report(total)
                if (n < readLimit) break
            }
            operation.bytesTotal = total
            if (total == 0L) {
                val ex = IllegalArgumentException("image is empty")
                operation.state = OperationState.Failed(ex)
                throw ex
            }
            val expectedSize = input.decodedSizeHint ?: known
            if (expectedSize != null && total < expectedSize) {
                val ex = EOFException("Premature EOF: wrote $total of $expectedSize bytes")
                operation.state = OperationState.Failed(ex)
                throw ex
            }
            disk.sync()
            try {
                verify(source, disk, total, sectorSize, operation)
            } catch (e: ImageCancelledException) {
                // Bytes already synced; cancel mid-verify is Partial, not Cancelled.
                val wrapped =
                    ImageCancelledException(
                        "image written and synced; verification cancelled: ${e.message ?: "stopped"}",
                    )
                operation.bytesTotal = total
                operation.failureCause = wrapped
                operation.state = OperationState.Partial
                throw wrapped
            } catch (e: ImageVerifyException) {
                operation.bytesTotal = total
                operation.failureCause = e
                operation.state = OperationState.Partial
                throw e
            } catch (e: DiskException) {
                // Production UsbDisk.read throws DiskException, not IOException.
                operation.bytesTotal = total
                val wrapped =
                    ImageVerifyException(
                        "image written and synced; verification incomplete: ${e.message}",
                        e,
                    )
                operation.failureCause = wrapped
                operation.state = OperationState.Partial
                throw wrapped
            } catch (e: Exception) {
                // IOException (tests), SecurityException from a second SAF open,
                // or unexpected RuntimeException — write already synced; never Failed.
                // Keep class name so invariant bugs (IllegalArgumentException) stay visible.
                operation.bytesTotal = total
                val wrapped =
                    ImageVerifyException(
                        "image written and synced; verification unavailable: ${e.javaClass.simpleName}: ${e.message}",
                        e,
                    )
                operation.failureCause = wrapped
                operation.state = OperationState.Partial
                throw wrapped
            }
            if (operation.state == OperationState.Queued || operation.state == OperationState.Running) {
                operation.state = OperationState.Completed
            }
        }
    }

    /**
     * Re-open [source] and compare against the disk for every decoded byte, including the
     * zero pad of the final partial sector. Cancellation aborts between chunks.
     */
    private fun verify(
        source: ImageSource,
        disk: BlockDevice,
        totalDecoded: Long,
        sectorSize: Int,
        operation: DiskOperation,
    ) {
        val chunkBytes = TransferPlan.MAX_CHUNK
        val expectedDiskBytes = ((totalDecoded + sectorSize - 1) / sectorSize) * sectorSize
        val srcChunk = ByteArray(chunkBytes)
        val diskChunk = ByteArray(chunkBytes)
        source.open().use { input ->
            var offset = 0L
            var lba = 0UL
            while (offset < expectedDiskBytes) {
                if (!operation.isActive()) throw ImageCancelledException()
                val want = minOf(chunkBytes.toLong(), expectedDiskBytes - offset).toInt()
                require(want % sectorSize == 0)
                val fromSource =
                    if (offset < totalDecoded) {
                        val need = minOf(want.toLong(), totalDecoded - offset).toInt()
                        val n = readFully(input, srcChunk, need)
                        if (n < need) {
                            throw ImageVerifyException(
                                "verify source short at byte ${offset + n}: got $n of $need",
                            )
                        }
                        if (need < want) srcChunk.fill(0, need, want)
                        want
                    } else {
                        // Past decoded payload: disk must still hold the zero pad only.
                        srcChunk.fill(0, 0, want)
                        want
                    }
                disk.read(lba, ByteBuffer.wrap(diskChunk, 0, fromSource))
                for (i in 0 until fromSource) {
                    if (srcChunk[i] != diskChunk[i]) {
                        val abs = offset + i
                        throw ImageVerifyException(
                            "проверка выявила ошибку: mismatch at byte $abs " +
                                "(LBA ${abs / sectorSize})",
                        )
                    }
                }
                offset += fromSource
                lba += (fromSource / sectorSize).toULong()
                operation.report(totalDecoded)
            }
            // Source must be exhausted for the decoded length we claimed.
            if (totalDecoded > 0L) {
                val leftover = ByteArray(1)
                val extra = input.read(leftover)
                if (extra > 0) {
                    throw ImageVerifyException(
                        "verify source longer than written $totalDecoded bytes",
                    )
                }
            }
        }
    }

    private fun readFully(input: CountedInputStream, buffer: ByteArray): Int =
        readFully(input, buffer, buffer.size)

    private fun readFully(input: CountedInputStream, buffer: ByteArray, length: Int): Int {
        var off = 0
        while (off < length) {
            val n = input.read(buffer, off, length - off)
            if (n < 0) return off
            off += n
        }
        return off
    }
}
