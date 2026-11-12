package usb.device

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exclusive BOT/SCSI disk session.
 * Owns one [BotSession]; all reads/writes are serial at the caller (OperationExecutor).
 * Holds one [UsbExclusiveClaim.Lease] until session close is confirmed. A close failure latches
 * the disk closed, retains the lease, and is rethrown on later [close] calls.
 */
open class UsbDisk(
    private val session: BotSession,
    geometry: BlockGeometry? = null,
    private var lease: UsbExclusiveClaim.Lease? = null
) : BlockDevice {
    private var closed = false
    private var closeFailure: Throwable? = null

    final override val sectorSize: Int
    final override val sectorCount: ULong

    val geometry: BlockGeometry

    /** Read once per disk session so a pref change cannot alter an in-flight transfer. */
    private val scsiChunkBytes = TransferTuning.scsiChunkBytes

    init {
        val resolved = geometry ?: probeCapacity(session)
        this.geometry = resolved
        this.sectorSize = resolved.sectorSize
        this.sectorCount = resolved.sectorCount
        lease?.bindSession()
    }

    @Synchronized
    override fun read(lba: ULong, destination: ByteBuffer) {
        ensureOpen()
        if (destination.remaining() == 0) return
        val bytes = destination.remaining()
        requireAligned(bytes)
        val blocks = bytes / sectorSize
        require(blocks > 0)
        requireNoOverflow(lba, blocks)

        val parts = TransferPlan.plan(bytes.toLong(), sectorSize, scsiChunkBytes)
        var offsetBlocks = 0
        for (part in parts) {
            val partBlocks = part / sectorSize
            transfer(ScsiCommand.read(lba + offsetBlocks.toULong(), partBlocks, sectorSize), destination, part)
            offsetBlocks += partBlocks
        }
    }

    @Synchronized
    override fun write(lba: ULong, source: ByteBuffer) {
        ensureOpen()
        if (source.remaining() == 0) return
        val bytes = source.remaining()
        requireAligned(bytes)
        val blocks = bytes / sectorSize
        require(blocks > 0)
        requireNoOverflow(lba, blocks)

        val parts = TransferPlan.plan(bytes.toLong(), sectorSize, scsiChunkBytes)
        var offsetBlocks = 0
        for (part in parts) {
            val partBlocks = part / sectorSize
            transfer(ScsiCommand.write(lba + offsetBlocks.toULong(), partBlocks, sectorSize), source, part)
            offsetBlocks += partBlocks
        }
    }

    /**
     * SCSI SYNCHRONIZE CACHE(10). Unsupported sense is surfaced, never swallowed.
     */
    @Synchronized
    override fun sync() {
        ensureOpen()
        var uaRetried = false
        while (true) {
            val result = execute(
                ScsiCommand.synchronizeCache10(),
                null,
                timeoutMs = BotConstants.SYNCHRONIZE_CACHE_TIMEOUT_MS,
            )
            when (result.status) {
                CswStatus.PASSED -> return
                CswStatus.FAILED -> {
                    val sense = requestSense()
                    if (sense.isIllegalRequest) {
                        throw DiskException.UnsupportedCommand(
                            "SYNCHRONIZE CACHE(10) unsupported: $sense",
                        )
                    }
                    if (sense.isUnitAttention && !uaRetried) {
                        uaRetried = true
                        continue
                    }
                    throw sense
                }
                CswStatus.PHASE_ERROR -> {
                    resetOrClose()
                    val failure = DiskException.IoFailure("phase error on SYNCHRONIZE CACHE(10)")
                    closeSessionPreserving(failure)
                    throw failure
                }
            }
        }
    }

    @Synchronized
    fun close() {
        closeSession()
    }

    val isClosed: Boolean get() = closed

    private fun transfer(cdb: ScsiCdb, buffer: ByteBuffer, length: Int) {
        val mark = buffer.position()
        val oldLimit = buffer.limit()
        val sliceLimit = mark + length
        var attempt = 0
        var uaRetried = false
        while (true) {
            buffer.position(mark)
            buffer.limit(sliceLimit)
            ensureConnected()
            val result = try {
                val res = session.transfer(cdb, buffer)
                buffer.limit(oldLimit)
                res
            } catch (e: DiskException.IoFailure) {
                // An OUT command may have reached the medium before transport failure, so its
                // completion is unknown. Recover the BOT pipe, then close without replaying it.
                if (cdb.direction == ScsiDirection.OUT) {
                    resetOrClose()
                    closeSessionPreserving(e)
                    throw e
                }
                // IN commands are safe to replay after one BOT reset recovery.
                if (attempt >= 1) {
                    closeSessionPreserving(e)
                    throw e
                }
                resetOrClose()
                attempt++
                continue
            } catch (e: DiskException.Disconnected) {
                closeSessionPreserving(e)
                throw e
            } finally {
                buffer.limit(oldLimit)
            }

            if (result.status == CswStatus.PHASE_ERROR) {
                val failure = DiskException.IoFailure("BOT phase error")
                resetOrClose()
                closeSessionPreserving(failure)
                throw failure
            }
            if (result.residue !in 0..cdb.transferLength) {
                throw DiskException.IoFailure("phase error: invalid residue")
            }
            when (result.status) {
                CswStatus.PASSED -> {
                    // Block reads need a full sector. Short IN (honest residue or lying
                    // residue=0) must not look like success — UsbDisk would otherwise
                    // advance LBA by the full part while the buffer only holds delivered.
                    val delivered = buffer.position() - mark
                    if (delivered != length || result.residue != 0) {
                        throw DiskException.IoFailure(
                            "data phase mismatch: $delivered of $length bytes, " +
                                "residue=${result.residue}",
                        )
                    }
                    return
                }
                CswStatus.FAILED -> {
                    // CSW status 1: REQUEST SENSE, then surface without closing session.
                    val sense = requestSense()
                    if (sense.isUnitAttention && !uaRetried) {
                        // Reset Recovery / media change leaves a one-shot UNIT ATTENTION pending;
                        // REQUEST SENSE cleared it, so the next identical attempt is expected to pass.
                        uaRetried = true
                        continue
                    }
                    throw sense
                }
                CswStatus.PHASE_ERROR -> error("unreachable")
            }
        }
    }

    private fun execute(
        cdb: ScsiCdb,
        data: ByteBuffer?,
        timeoutMs: Int = BotConstants.TRANSFER_TIMEOUT_MS,
    ): CswResult {
        ensureConnected()
        return try {
            session.transfer(cdb, data, timeoutMs)
        } catch (e: DiskException.Disconnected) {
            closeSessionPreserving(e)
            throw e
        }
    }

    private fun requestSense(): DiskException.SenseError = senseOf(session)

    private fun ensureOpen() {
        if (closed) throw DiskException.Closed()
    }

    private fun ensureConnected() {
        if (!session.isConnected()) {
            val failure = DiskException.Disconnected()
            closeSessionPreserving(failure)
            throw failure
        }
    }

    private fun closeSession() {
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        try {
            session.close()
        } catch (t: Throwable) {
            closeFailure = t
            throw t
        }
        // Release ownership only after session close is confirmed.
        lease?.releaseAfterSessionClose()
    }
    internal fun bindLease(boundLease: UsbExclusiveClaim.Lease) {
        if (lease === boundLease) return
        check(lease == null) { "USB lease already bound" }
        lease = boundLease
        boundLease.bindSession()
    }
    internal fun beginHigherLevelClose() {
        lease?.holdForHigherLevelClose()
    }

    internal fun confirmHigherLevelClose() {
        lease?.confirmHigherLevelClose()
    }

    private fun closeSessionPreserving(primary: Throwable) {
        try {
            closeSession()
        } catch (closeError: Throwable) {
            if (closeError !== primary) primary.addSuppressed(closeError)
        }
    }

    /**
     * BOT reset recovery. If the reset itself fails the pipe is untrustworthy:
     * close the session, then rethrow the reset failure unaltered.
     */
    private fun resetOrClose() {
        try {
            session.bulkOnlyReset()
        } catch (e: Exception) {
            closeSessionPreserving(e)
            throw e
        }
    }

    private fun requireAligned(bytes: Int) {
        require(bytes % sectorSize == 0) {
            "length $bytes not multiple of sectorSize $sectorSize"
        }
    }

    private fun requireNoOverflow(lba: ULong, blocks: Int) {
        val blocksU = blocks.toULong()
        require(blocksU <= sectorCount && lba <= sectorCount - blocksU) {
            "transfer past end: lba=$lba blocks=$blocks capacity=$sectorCount"
        }
    }

    companion object {
        /**
         * BOT reset recovery before a probe retry. A failed reset means the pipe
         * cannot be trusted: close the session and rethrow the reset failure.
         */
        private fun resetOrClose(session: BotSession) {
            try {
                session.bulkOnlyReset()
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }

        /**
         * READ CAPACITY(10); if last LBA is 0xFFFFFFFF, fall back to READ CAPACITY(16).
         * A CSW FAILED always gets a REQUEST SENSE (BOT requirement); a pending
         * UNIT ATTENTION is cleared and the same command retried once.
         */
        fun probeCapacity(session: BotSession): BlockGeometry {
            val buf10 = ByteBuffer.allocate(8)
            var uaRetried = false
            while (true) {
                val status10 = try {
                    session.transfer(ScsiCommand.readCapacity10(), buf10)
                } catch (e: DiskException.IoFailure) {
                    resetOrClose(session)
                    return readCapacity16(session)
                }
                if (status10.status == CswStatus.PHASE_ERROR) {
                    resetOrClose(session)
                    throw DiskException.IoFailure("phase error on READ CAPACITY(10)")
                }
                if (status10.status == CswStatus.FAILED) {
                    val sense = senseOf(session)
                    if (sense.isUnitAttention && !uaRetried) {
                        // Stale UNIT ATTENTION from an earlier reset recovery.
                        uaRetried = true
                        continue
                    }
                    // Some devices only speak CAPACITY(16).
                    return readCapacity16(session)
                }
                buf10.flip()
                buf10.order(ByteOrder.BIG_ENDIAN)
                val lastLba = buf10.int.toUInt().toULong()
                val blockLength = buf10.int
                if (lastLba == 0xFFFF_FFFFUL) {
                    return readCapacity16(session)
                }
                require(blockLength in 512..4096 && blockLength.countOneBits() == 1) {
                    "illegal block length $blockLength"
                }
                return BlockGeometry(blockLength, lastLba + 1UL)
            }
        }

        private fun readCapacity16(session: BotSession): BlockGeometry {
            var uaRetried = false
            while (true) {
                val buf = ByteBuffer.allocate(32)
                val status = session.transfer(ScsiCommand.readCapacity16(32), buf)
                when (status.status) {
                    CswStatus.PASSED -> {
                        buf.flip()
                        buf.order(ByteOrder.BIG_ENDIAN)
                        val lastLba = buf.long.toULong()
                        require(lastLba != ULong.MAX_VALUE) {
                            "READ CAPACITY(16) last LBA overflows sector count"
                        }
                        val blockLength = buf.int
                        require(blockLength in 512..4096 && blockLength.countOneBits() == 1) {
                            "illegal block length $blockLength"
                        }
                        return BlockGeometry(blockLength, lastLba + 1UL)
                    }
                    CswStatus.FAILED -> {
                        val sense = senseOf(session)
                        if (sense.isUnitAttention && !uaRetried) {
                            uaRetried = true
                            continue
                        }
                        throw DiskException.IoFailure("READ CAPACITY(16) failed")
                    }
                    CswStatus.PHASE_ERROR -> {
                        resetOrClose(session)
                        throw DiskException.IoFailure("phase error on READ CAPACITY(16)")
                    }
                }
            }
        }

        private fun senseOf(session: BotSession): DiskException.SenseError {
            val buf = ByteBuffer.allocate(18)
            val result = session.transfer(ScsiCommand.requestSense(18), buf)
            if (result.status != CswStatus.PASSED) {
                throw DiskException.IoFailure("REQUEST SENSE failed with status ${result.status}")
            }
            buf.flip()
            return parseSense(buf)
        }

        fun parseSense(buffer: ByteBuffer): DiskException.SenseError {
            require(buffer.remaining() >= 8) { "sense buffer too short" }
            val responseCode = buffer.get(0).toInt() and 0x7F
            val descriptor = responseCode == 0x72 || responseCode == 0x73
            val senseKey = buffer.get(if (descriptor) 1 else 2).toInt() and 0x0F
            val ascOffset = if (descriptor) 2 else 12
            require(buffer.remaining() > ascOffset + 1) { "sense buffer missing ASC/ASCQ" }
            val asc = buffer.get(ascOffset).toInt() and 0xFF
            val ascq = buffer.get(ascOffset + 1).toInt() and 0xFF
            return DiskException.SenseError(responseCode, senseKey, asc, ascq)
        }
    }
}
