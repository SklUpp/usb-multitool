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

        val chunk = scsiChunkBytes - scsiChunkBytes % sectorSize
        require(chunk > 0) { "SCSI chunk $scsiChunkBytes is smaller than sector $sectorSize" }
        var remaining = bytes
        var offsetBlocks = 0
        while (remaining > 0) {
            val part = if (remaining > chunk) chunk else remaining
            val partBlocks = part / sectorSize
            transfer(ScsiCommand.read(lba + offsetBlocks.toULong(), partBlocks, sectorSize), destination, part)
            offsetBlocks += partBlocks
            remaining -= part
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

        val chunk = scsiChunkBytes - scsiChunkBytes % sectorSize
        require(chunk > 0) { "SCSI chunk $scsiChunkBytes is smaller than sector $sectorSize" }
        var remaining = bytes
        var offsetBlocks = 0
        while (remaining > 0) {
            val part = if (remaining > chunk) chunk else remaining
            val partBlocks = part / sectorSize
            transfer(ScsiCommand.write(lba + offsetBlocks.toULong(), partBlocks, sectorSize), source, part)
            offsetBlocks += partBlocks
            remaining -= part
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
                    val failure = DiskException.IoFailure("phase error on SYNCHRONIZE CACHE(10)")
                    resetOrClose(failure)
                    closeSessionPreserving(failure)
                    throw failure
                }
            }
        }
    }

    /**
     * Read-only SAT SMART probe: IDENTIFY DEVICE then SMART READ DATA.
     * Never writes. Unsupported bridges become [SmartAvailability.Unsupported],
     * not a session-killing hard failure, unless BOT itself is untrustworthy.
     */
    @Synchronized
    fun readSmartTelemetry(isActive: () -> Boolean = { true }): SmartTelemetry {
        ensureOpen()
        if (!isActive()) throw java.util.concurrent.CancellationException("SMART read cancelled")
        val identifyBuf = ByteBuffer.allocate(ScsiCommand.ATA_SECTOR_BYTES)
        when (val identifyOutcome = satPioDataIn(ScsiCommand.ataIdentifyDevice(), identifyBuf)) {
            is SatCommandOutcome.Unsupported ->
                return SmartTelemetry.unsupported(identifyOutcome.detail)
            is SatCommandOutcome.Failed ->
                return SmartTelemetry.unavailable(identifyOutcome.detail)
            is SatCommandOutcome.Ok -> {
                identifyBuf.flip()
                if (identifyBuf.remaining() < ScsiCommand.ATA_SECTOR_BYTES) {
                    return SmartTelemetry.unavailable(
                        "IDENTIFY delivered ${identifyBuf.remaining()} bytes",
                    )
                }
                val identify = SmartParser.parseIdentify(identifyBuf)
                if (identify.smartSupported == false) {
                    return SmartTelemetry(
                        availability = SmartAvailability.Unsupported,
                        identify = identify,
                        detail = "IDENTIFY reports SMART feature set unsupported",
                        ataStatus = identifyOutcome.ataStatus,
                        ataError = identifyOutcome.ataError,
                    )
                }
                if (!isActive()) throw java.util.concurrent.CancellationException("SMART read cancelled")

                val smartBuf = ByteBuffer.allocate(ScsiCommand.ATA_SECTOR_BYTES)
                when (val smartOutcome = satPioDataIn(ScsiCommand.ataSmartReadData(), smartBuf)) {
                    is SatCommandOutcome.Unsupported ->
                        return SmartTelemetry(
                            availability = SmartAvailability.Unsupported,
                            identify = identify,
                            detail = smartOutcome.detail,
                            ataStatus = smartOutcome.ataStatus,
                            ataError = smartOutcome.ataError,
                        )
                    is SatCommandOutcome.Failed ->
                        return SmartTelemetry(
                            availability = SmartAvailability.Unavailable,
                            identify = identify,
                            detail = smartOutcome.detail,
                            ataStatus = smartOutcome.ataStatus,
                            ataError = smartOutcome.ataError,
                        )
                    is SatCommandOutcome.Ok -> {
                        smartBuf.flip()
                        if (smartBuf.remaining() < ScsiCommand.ATA_SECTOR_BYTES) {
                            return SmartTelemetry(
                                availability = SmartAvailability.Unavailable,
                                identify = identify,
                                detail = "SMART READ DATA delivered ${smartBuf.remaining()} bytes",
                                ataStatus = smartOutcome.ataStatus,
                                ataError = smartOutcome.ataError,
                            )
                        }
                        val (attributes, checksumOk) = SmartParser.parseSmartAttributes(smartBuf)
                        return SmartTelemetry(
                            availability = SmartAvailability.Available,
                            identify = identify,
                            attributes = attributes,
                            checksumOk = checksumOk,
                            ataStatus = smartOutcome.ataStatus ?: identifyOutcome.ataStatus,
                            ataError = smartOutcome.ataError ?: identifyOutcome.ataError,
                            detail = if (checksumOk) {
                                null
                            } else {
                                "SMART attribute page checksum mismatch"
                            },
                        )
                    }
                }
            }
        }
    }


    /**
     * SAT PIO Data-In with CK_COND=1. Successful ATA completion may arrive as
     * CSW FAILED plus ATA Return descriptor; data phase can still fill [buffer].
     */
    private fun satPioDataIn(cdb: ScsiCdb, buffer: ByteBuffer): SatCommandOutcome {
        require(cdb.direction == ScsiDirection.IN)
        require(cdb.transferLength == ScsiCommand.ATA_SECTOR_BYTES)
        var uaRetried = false
        while (true) {
            buffer.clear()
            buffer.limit(ScsiCommand.ATA_SECTOR_BYTES)
            val mark = buffer.position()
            val result = try {
                execute(cdb, buffer)
            } catch (e: DiskException.Disconnected) {
                throw e
            } catch (e: DiskException.Closed) {
                throw e
            } catch (e: DiskException) {
                // BOT reset/close already applied by execute helpers for untrusted replies.
                return SatCommandOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            val delivered = buffer.position() - mark
            when (result.status) {
                CswStatus.PASSED -> {
                    if (delivered != ScsiCommand.ATA_SECTOR_BYTES || result.residue != 0) {
                        closeAfterUntrustedReply(
                            DiskException.IoFailure(
                                "SAT data mismatch: delivered=$delivered residue=${result.residue}",
                            ),
                        )
                    }
                    // Some SATLs pass CSW and still expose ATA return via sense.
                    val senseBytes = requestSenseBytes(64)
                    val ata = senseBytes?.let { SmartParser.findAtaReturn(it) }
                    if (ata != null && ata.hasError) {
                        return SatCommandOutcome.Failed(
                            detail = "ATA status=0x${ata.status.toString(16)} error=0x${ata.error.toString(16)}",
                            ataStatus = ata.status,
                            ataError = ata.error,
                        )
                    }
                    return SatCommandOutcome.Ok(ata?.status, ata?.error)
                }
                CswStatus.FAILED -> {
                    val senseBytes = requestSenseBytes(64)
                        ?: return SatCommandOutcome.Failed("REQUEST SENSE failed after SAT CSW FAILED")
                    val parsed = try {
                        parseSenseBuffer(senseBytes)
                    } catch (e: IllegalArgumentException) {
                        return SatCommandOutcome.Failed(e.message ?: "invalid sense")
                    }
                    if (parsed.isUnitAttention && !uaRetried) {
                        uaRetried = true
                        continue
                    }
                    if (parsed.isIllegalRequest) {
                        return SatCommandOutcome.Unsupported(
                            detail = parsed.message ?: "ILLEGAL REQUEST",
                        )
                    }
                    val ata = SmartParser.findAtaReturn(senseBytes)
                    if (ata != null) {
                        if (ata.hasError) {
                            return SatCommandOutcome.Failed(
                                detail = "ATA status=0x${ata.status.toString(16)} error=0x${ata.error.toString(16)}",
                                ataStatus = ata.status,
                                ataError = ata.error,
                            )
                        }
                        if (delivered == ScsiCommand.ATA_SECTOR_BYTES && result.residue == 0) {
                            return SatCommandOutcome.Ok(ata.status, ata.error)
                        }
                        closeAfterUntrustedReply(
                            DiskException.IoFailure(
                                "ATA OK but delivered $delivered of " +
                                    "${ScsiCommand.ATA_SECTOR_BYTES} residue=${result.residue}",
                            ),
                        )
                    }
                    return SatCommandOutcome.Failed(parsed.message ?: "SAT CSW FAILED")
                }
                CswStatus.PHASE_ERROR -> {
                    val failure = DiskException.IoFailure("BOT phase error on SAT command")
                    resetOrClose(failure)
                    closeSessionPreserving(failure)
                    throw failure
                }
            }
        }
    }

    private fun requestSenseBytes(allocationLength: Int): ByteArray? {
        val buf = ByteBuffer.allocate(allocationLength)
        val result = execute(ScsiCommand.requestSense(allocationLength), buf)
        if (result.status == CswStatus.PHASE_ERROR) {
            closeAfterUntrustedReply(
                DiskException.IoFailure("BOT phase error on REQUEST SENSE"),
            )
        }
        if (result.status != CswStatus.PASSED) return null
        val delivered = buf.position()
        if (delivered <= 0) return null
        val out = ByteArray(delivered)
        buf.flip()
        buf.get(out)
        return out
    }

    private fun parseSenseBuffer(bytes: ByteArray): DiskException.SenseError {
        val buffer = ByteBuffer.wrap(bytes)
        return parseSense(buffer)
    }

    private sealed class SatCommandOutcome {
        data class Ok(val ataStatus: Int?, val ataError: Int?) : SatCommandOutcome()
        data class Unsupported(
            val detail: String?,
            val ataStatus: Int? = null,
            val ataError: Int? = null,
        ) : SatCommandOutcome()
        data class Failed(
            val detail: String?,
            val ataStatus: Int? = null,
            val ataError: Int? = null,
        ) : SatCommandOutcome()
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
            } catch (e: DiskException.CswReadFailure) {
                closeAfterUntrustedReply(e)
            } catch (e: DiskException.InvalidCsw) {
                closeAfterUntrustedReply(e)
            } catch (e: DiskException.NonMeaningfulCsw) {
                closeAfterUntrustedReply(e)
            } catch (e: DiskException.Disconnected) {
                closeSessionPreserving(e)
                throw e
            } catch (e: DiskException.IoFailure) {
                if (cdb.direction == ScsiDirection.OUT) {
                    resetOrClose(e)
                    closeSessionPreserving(e)
                    throw e
                }
                if (attempt >= 1) {
                    closeSessionPreserving(e)
                    throw e
                }
                resetOrClose(e)
                attempt++
                continue
            } finally {
                buffer.limit(oldLimit)
            }

            if (result.status == CswStatus.PHASE_ERROR) {
                val failure = DiskException.IoFailure("BOT phase error")
                resetOrClose(failure)
                closeSessionPreserving(failure)
                throw failure
            }
            if (result.residue !in 0..cdb.transferLength) {
                val failure = DiskException.NonMeaningfulCsw(
                    "CSW residue ${result.residue} outside transfer length ${cdb.transferLength}",
                )
                closeAfterUntrustedReply(failure)
            }
            when (result.status) {
                CswStatus.PASSED -> {
                    // Block reads need a full sector. Short IN (honest residue or lying
                    // residue=0) must not look like success — UsbDisk would otherwise
                    // advance LBA by the full part while the buffer only holds delivered.
                    val delivered = buffer.position() - mark
                    if (delivered != length || result.residue != 0) {
                        val failure = DiskException.NonMeaningfulCsw(
                            "data phase mismatch: $delivered of $length bytes, " +
                                "residue=${result.residue}",
                        )
                        closeAfterUntrustedReply(failure)
                    }
                    return
                }
                CswStatus.FAILED -> {
                    // REQUEST SENSE is required before surfacing command failure.
                    val sense = requestSense()
                    if (sense.isUnitAttention && cdb.direction == ScsiDirection.OUT) {
                        // Target identity/media may have changed. Do not replay unknown OUT;
                        // close so caller must re-probe and revalidate before any write.
                        closeSessionPreserving(sense)
                        throw sense
                    }
                    if (sense.isUnitAttention && !uaRetried) {
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
        } catch (e: DiskException.CswReadFailure) {
            closeAfterUntrustedReply(e)
        } catch (e: DiskException.InvalidCsw) {
            closeAfterUntrustedReply(e)
        } catch (e: DiskException.NonMeaningfulCsw) {
            closeAfterUntrustedReply(e)
        } catch (e: DiskException.Disconnected) {
            closeSessionPreserving(e)
            throw e
        } catch (e: DiskException.IoFailure) {
            closeAfterUntrustedReply(e)
        }
    }

    private fun requestSense(): DiskException.SenseError = try {
        senseOf(session)
    } catch (e: DiskException.CswReadFailure) {
        closeAfterUntrustedReply(e)
    } catch (e: DiskException.InvalidCsw) {
        closeAfterUntrustedReply(e)
    } catch (e: DiskException.NonMeaningfulCsw) {
        closeAfterUntrustedReply(e)
    } catch (e: DiskException.Disconnected) {
        closeSessionPreserving(e)
        throw e
    } catch (e: DiskException.IoFailure) {
        closeAfterUntrustedReply(e)
    }
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

    private fun closeAfterUntrustedReply(primary: Throwable): Nothing {
        if (session.isConnected()) {
            try {
                session.bulkOnlyReset()
            } catch (recovery: Throwable) {
                if (recovery !== primary) primary.addSuppressed(recovery)
            }
        }
        closeSessionPreserving(primary)
        throw primary
    }

    /**
     * BOT reset recovery. If the reset itself fails the pipe is untrustworthy:
     * close the session, then rethrow the reset failure unaltered.
     */
    private fun resetOrClose(primary: Throwable? = null) {
        try {
            session.bulkOnlyReset()
        } catch (recovery: Throwable) {
            if (primary != null) {
                primary.addSuppressed(recovery)
                closeSessionPreserving(primary)
                throw primary
            }
            closeSessionPreserving(recovery)
            throw recovery
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
                buf10.clear()
                val status10 = try {
                    session.transfer(ScsiCommand.readCapacity10(), buf10)
                } catch (e: DiskException.CswReadFailure) {
                    recoverAfterInvalidCsw(session, e)
                } catch (e: DiskException.InvalidCsw) {
                    recoverAfterInvalidCsw(session, e)
                } catch (e: DiskException.NonMeaningfulCsw) {
                    recoverAfterInvalidCsw(session, e)
                } catch (e: DiskException.IoFailure) {
                    resetOrClose(session)
                    return readCapacity16(session)
                }
                if (status10.status == CswStatus.PHASE_ERROR) {
                    resetOrClose(session)
                    throw DiskException.IoFailure("phase error on READ CAPACITY(10)")
                }
                if (status10.status == CswStatus.FAILED) {
                    val sense = probeSenseOf(session)
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
                val status = try {
                    session.transfer(ScsiCommand.readCapacity16(32), buf)
                } catch (e: DiskException.CswReadFailure) {
                    recoverAfterInvalidCsw(session, e)
                } catch (e: DiskException.InvalidCsw) {
                    recoverAfterInvalidCsw(session, e)
                } catch (e: DiskException.NonMeaningfulCsw) {
                    recoverAfterInvalidCsw(session, e)
                } catch (e: DiskException.IoFailure) {
                    resetOrClose(session)
                    throw e
                } catch (e: DiskException.Disconnected) {
                    throw e
                }
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
                        val sense = probeSenseOf(session)
                        if (sense.isUnitAttention && !uaRetried) {
                            uaRetried = true
                            continue
                        }
                        throw sense
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

        private fun probeSenseOf(session: BotSession): DiskException.SenseError = try {
            senseOf(session)
        } catch (e: DiskException.IoFailure) {
            recoverAfterInvalidCsw(session, e)
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
