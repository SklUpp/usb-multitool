package usb.image

import java.io.EOFException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import usb.device.BlockDevice
import usb.device.TransferPlan
import usb.device.capacityBytes
import usb.diagnostics.DestructiveConfirmation
import usb.partition.RawRangePlan
import usb.partition.RawWriteRange
import usb.operation.DiskOperation
import usb.operation.OperationState

class ImageTooLargeException(message: String) : Exception(message)
class ImageCancelledException(message: String = "image write cancelled") : CancellationException(message)
class OverwriteRequiredException(message: String = "raw write requires overwrite=true") : Exception(message)
class ImageVerifyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private class ReadbackOutcome(
    val verdict: ReadbackVerdict,
)

object RawWriter {
    /**
     * Stream [source] into [range] on [disk] in [TransferPlan.MAX_CHUNK]-sized blocks.
     * The range is absolute; every write and the readback use `range.startLba + offset`.
     * Size/bounds rejection happens before any sector write.
     * Caller must release any files volume on the disk first (fail-closed).
     * [confirmation] must match [identity] and the whole-disk capacity.
     * [overwrite] must be true; silent overwrite is refused.
     * After the write the device is synced and the written bytes are read back; a
     * Partial result is always synced and read back as far as the medium allows.
     * A failed write request is never reported as Verified: its whole attempted range
     * becomes [ReadbackVerdict.Unverified] because USB can fail after transferring data.
     * A source-stream close failure never discards the structured result; it downgrades
     * a Completed outcome to Partial and is suppressed onto the primary failure.
     */
    fun write(
        source: ImageSource,
        disk: BlockDevice,
        identity: String,
        confirmation: DestructiveConfirmation,
        overwrite: Boolean,
        range: RawWriteRange,
        operation: DiskOperation = DiskOperation("raw-write"),
    ): RawWriteResult {
        confirmation.requireMatch(identity, disk.capacityBytes)
        if (!overwrite) throw OverwriteRequiredException()

        val sectorSize = disk.sectorSize
        RawRangePlan.requireWritable(range, disk.sectorCount, sectorSize)
        val capacity = range.byteLength(sectorSize)
        val known = source.declaredDecodedSize
        if (known != null && (known < 0L || known.toULong() > capacity)) {
            throw ImageTooLargeException("image $known exceeds selected range $capacity bytes")
        }

        val chunkBytes = TransferPlan.MAX_CHUNK
        require(chunkBytes % sectorSize == 0) {
            "TransferPlan.MAX_CHUNK must be a multiple of sectorSize"
        }

        val input = source.open()
        val result =
            try {
                streamToRange(
                    source = source,
                    input = input,
                    disk = disk,
                    range = range,
                    capacity = capacity,
                    sectorSize = sectorSize,
                    chunkBytes = chunkBytes,
                    known = known,
                    operation = operation,
                )
            } catch (primary: Throwable) {
                // No committed result exists; keep the original failure and only note close.
                try {
                    input.close()
                } catch (closeFailure: Throwable) {
                    primary.addSuppressed(closeFailure)
                }
                throw primary
            }

        val closeFailure =
            try {
                input.close()
                null
            } catch (e: Throwable) {
                e
            }
        if (closeFailure == null) return result

        val primaryFailure = result.failure
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(closeFailure)
        }
        val failure = primaryFailure ?: closeFailure
        operation.state = OperationState.Partial
        operation.failureCause = failure
        return result.copy(state = RawWriteStatus.Partial, failure = failure)
    }

    private fun streamToRange(
        source: ImageSource,
        input: CountedInputStream,
        disk: BlockDevice,
        range: RawWriteRange,
        capacity: ULong,
        sectorSize: Int,
        chunkBytes: Int,
        known: Long?,
        operation: DiskOperation,
    ): RawWriteResult {
        val expected = input.decodedSizeHint ?: known
        if (expected != null && (expected < 0L || expected.toULong() > capacity)) {
            throw ImageTooLargeException("image $expected exceeds selected range $capacity bytes")
        }
        // Progress is only advertised when the decoded size is actually known.
        if (expected != null) operation.bytesTotal = expected

        val chunk = ByteArray(chunkBytes)
        var local = 0UL
        var total = 0L
        var stop: Throwable? = null
        var attemptedLocal = 0UL
        var attemptedBlocks = 0UL
        var attemptedDecoded = 0L
        var attemptedWriteFailed = false
        while (true) {
            if (!operation.isActive()) {
                stop = ImageCancelledException("raw write cancelled after $total bytes")
                break
            }
            val remaining = capacity - total.toULong()
            if (remaining == 0UL) {
                // The range is full. Any further source byte is refused, never written.
                val extra = try {
                    input.read(ByteArray(1))
                } catch (e: Exception) {
                    stop = e
                    break
                }
                if (extra > 0) {
                    stop = ImageTooLargeException(
                        "source exceeds selected range at byte ${total + extra}",
                    )
                }
                break
            }
            // Never read past the range end: the tail is exactly what was asked for.
            val readLimit = minOf(chunkBytes.toULong(), remaining).toInt()
            val n = try {
                readFully(input, chunk, readLimit)
            } catch (e: Exception) {
                stop = e
                break
            }
            if (n <= 0) break
            val padded =
                if (n % sectorSize == 0) {
                    n
                } else {
                    // Pad the final partial sector with zeros, inside the range only.
                    val pad = ((n + sectorSize - 1) / sectorSize) * sectorSize
                    check(pad.toULong() <= remaining) {
                        "padded final sector exceeds the selected range"
                    }
                    chunk.fill(0, n, pad)
                    pad
                }
            operation.mutationAttempted = true
            try {
                disk.write(range.startLba + local, ByteBuffer.wrap(chunk, 0, padded))
            } catch (e: Exception) {
                // The transport may have transferred part or all of this request before
                // failing; the whole attempted range is potentially modified.
                stop = e
                attemptedLocal = local
                attemptedBlocks = (padded / sectorSize).toULong()
                attemptedDecoded = n.toLong()
                attemptedWriteFailed = true
                break
            }
            local += (padded / sectorSize).toULong()
            total += n
            operation.report(total)
            if (n < readLimit) break
        }

        if (expected != null && stop == null && total in 1L until expected) {
            stop = EOFException("Premature EOF: wrote $total of $expected bytes")
        }
        if (!operation.mutationAttempted && total == 0L) {
            if (stop != null) {
                if (stop is CancellationException) throw stop
                operation.state = OperationState.Failed(stop)
                throw stop
            }
            val ex = IllegalArgumentException("image is empty")
            operation.state = OperationState.Failed(ex)
            throw ex
        }

        // From here at least one write was attempted: sync what may be on the medium,
        // then read back the committed prefix plus the failed attempt's full range.
        if (stop == null) {
            try {
                disk.sync()
            } catch (e: Exception) {
                stop = e
            }
        } else {
            // A failed transport may also refuse sync; that must not hide the original.
            try {
                disk.sync()
            } catch (_: Exception) {
            }
        }

        // The failed request's decoded bytes were already consumed from the stream, so a
        // re-opened source yields prefix followed by exactly those attempted bytes.
        val readbackDecoded = total + attemptedDecoded
        val outcome =
            try {
                verify(
                    source = source,
                    disk = disk,
                    startLba = range.startLba,
                    totalDecoded = readbackDecoded,
                    sectorSize = sectorSize,
                    operation = operation,
                    requireSourceExhausted = stop == null,
                    checkActive = stop == null,
                )
            } catch (e: ImageCancelledException) {
                ReadbackOutcome(
                    ReadbackVerdict.Incomplete(ReadbackProblem.Cancelled, e.message),
                )
            } catch (e: ImageVerifyException) {
                ReadbackOutcome(
                    ReadbackVerdict.Incomplete(ReadbackProblem.SourceChanged, e.message),
                )
            } catch (e: CancellationException) {
                ReadbackOutcome(
                    ReadbackVerdict.Incomplete(ReadbackProblem.Cancelled, e.message),
                )
            } catch (e: Exception) {
                ReadbackOutcome(
                    ReadbackVerdict.Incomplete(
                        ReadbackProblem.IoError,
                        "${e.javaClass.simpleName}: ${e.message}",
                    ),
                )
            }
        val writtenPrefixMatches = outcome.verdict is ReadbackVerdict.Verified
        val verdict: ReadbackVerdict =
            if (attemptedWriteFailed && writtenPrefixMatches) {
                // Content happens to match, but the failed request is not proof enough:
                // the transport can throw after a partial transfer, so do not claim it.
                ReadbackVerdict.Unverified(
                    range = RawWriteRange(
                        range.startLba + attemptedLocal,
                        attemptedBlocks,
                    ),
                    detail = "write request failed; the attempted range was not proven",
                )
            } else {
                outcome.verdict
            }

        val failure: Throwable? = when {
            stop != null -> stop
            verdict is ReadbackVerdict.Mismatch ->
                ImageVerifyException(
                    "readback mismatch in ${verdict.mismatchedSectors} sector(s); " +
                        "first at LBA ${verdict.firstMismatchLba}",
                )
            verdict is ReadbackVerdict.Incomplete ->
                ImageVerifyException("readback incomplete: ${verdict.reason}")
            verdict is ReadbackVerdict.Unverified ->
                ImageVerifyException("readback unverified: ${verdict.detail ?: "failed attempt"}")
            else -> null
        }
        if (failure != null) {
            operation.state = OperationState.Partial
            operation.failureCause = failure
            return RawWriteResult(
                range = range,
                writtenBytes = total,
                readback = verdict,
                state = RawWriteStatus.Partial,
                failure = failure,
            )
        }
        if (operation.state == OperationState.Queued ||
            operation.state == OperationState.Running
        ) {
            operation.state = OperationState.Completed
        }
        return RawWriteResult(
            range = range,
            writtenBytes = total,
            readback = verdict,
            state = RawWriteStatus.Completed,
        )
    }

    /**
     * Re-open [source] and compare against the disk for every decoded byte, starting at
     * absolute [startLba]. The zero pad of a final partial sector is compared too.
     * Cancellation aborts between chunks. Mismatches are counted, not thrown, so the
     * caller can report how many sectors differ.
     */
    private fun verify(
        source: ImageSource,
        disk: BlockDevice,
        startLba: ULong,
        totalDecoded: Long,
        sectorSize: Int,
        operation: DiskOperation,
        requireSourceExhausted: Boolean,
        checkActive: Boolean,
    ): ReadbackOutcome {
        if (totalDecoded <= 0L) {
            return ReadbackOutcome(ReadbackVerdict.Verified)
        }
        val chunkBytes = TransferPlan.MAX_CHUNK
        val expectedDiskBytes =
            ((totalDecoded - 1) / sectorSize + 1) * sectorSize.toLong()
        val srcChunk = ByteArray(chunkBytes)
        val diskChunk = ByteArray(chunkBytes)
        var mismatched = 0UL
        var firstMismatchLba: ULong? = null
        source.open().use { input ->
            var offset = 0L
            while (offset < expectedDiskBytes) {
                if (checkActive && !operation.isActive()) {
                    throw ImageCancelledException("readback cancelled at byte $offset")
                }
                val want = minOf(chunkBytes.toLong(), expectedDiskBytes - offset).toInt()
                check(want % sectorSize == 0) { "readback chunk is not sector aligned" }
                val fromSource =
                    if (offset < totalDecoded) {
                        val need = minOf(want.toLong(), totalDecoded - offset).toInt()
                        val n = readFully(input, srcChunk, need)
                        if (n < need) {
                            throw ImageVerifyException(
                                "readback source short at byte ${offset + n}: got $n of $need",
                            )
                        }
                        if (need < want) srcChunk.fill(0, need, want)
                        want
                    } else {
                        // Past the decoded payload the disk must still hold the zero pad.
                        srcChunk.fill(0, 0, want)
                        want
                    }
                val lba = startLba + (offset / sectorSize).toULong()
                disk.read(lba, ByteBuffer.wrap(diskChunk, 0, fromSource))
                var inChunk = 0
                while (inChunk < fromSource) {
                    val sectorLen = minOf(sectorSize, fromSource - inChunk)
                    var differs = false
                    for (i in 0 until sectorLen) {
                        if (srcChunk[inChunk + i] != diskChunk[inChunk + i]) {
                            differs = true
                            break
                        }
                    }
                    if (differs) {
                        mismatched++
                        if (firstMismatchLba == null) {
                            firstMismatchLba = lba + (inChunk / sectorSize).toULong()
                        }
                    }
                    inChunk += sectorLen
                }
                offset += fromSource
                operation.report(totalDecoded)
            }
            if (requireSourceExhausted) {
                val extra = input.read(ByteArray(1))
                if (extra > 0) {
                    throw ImageVerifyException(
                        "source contains bytes beyond the written range",
                    )
                }
            }
        }
        if (mismatched == 0UL) return ReadbackOutcome(ReadbackVerdict.Verified)
        return ReadbackOutcome(
            ReadbackVerdict.Mismatch(mismatched, firstMismatchLba ?: startLba),
        )
    }

    private fun readFully(input: CountedInputStream, buffer: ByteArray): Int =
        readFully(input, buffer, buffer.size)

    private fun readFully(input: CountedInputStream, buffer: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n < 0) break
            if (n == 0) continue
            offset += n
        }
        return offset
    }
}
