package usb.device

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exclusive BOT/SCSI disk session.
 * Owns one [BotSession]; all reads/writes are serial at the caller (OperationExecutor).
 */
open class UsbDisk(
    private val session: BotSession,
    geometry: BlockGeometry? = null,
) : BlockDevice {
    private var closed = false

    final override val sectorSize: Int
    final override val sectorCount: ULong

    val geometry: BlockGeometry

    init {
        val resolved = geometry ?: probeCapacity(session)
        this.geometry = resolved
        this.sectorSize = resolved.sectorSize
        this.sectorCount = resolved.sectorCount
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

        val parts = TransferPlan.plan(bytes.toLong(), sectorSize)
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

        val parts = TransferPlan.plan(bytes.toLong(), sectorSize)
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
        val result = execute(ScsiCommand.synchronizeCache10(), null)
        when (result.status) {
            CswStatus.PASSED -> return
            CswStatus.FAILED -> {
                val sense = requestSense()
                if (sense.isIllegalRequest) {
                    throw DiskException.UnsupportedCommand(
                        "SYNCHRONIZE CACHE(10) unsupported: $sense",
                    )
                }
                throw sense
            }
            CswStatus.PHASE_ERROR -> {
                session.bulkOnlyReset()
                closeSession()
                throw DiskException.IoFailure("phase error on SYNCHRONIZE CACHE(10)")
            }
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    val isClosed: Boolean get() = closed

    private fun transfer(cdb: ScsiCdb, buffer: ByteBuffer, length: Int) {
        val mark = buffer.position()
        val oldLimit = buffer.limit()
        val sliceLimit = mark + length
        var attempt = 0
        while (true) {
            buffer.position(mark)
            buffer.limit(sliceLimit)
            ensureConnected()
            val result = try {
                val res = session.transfer(cdb, buffer)
                buffer.limit(oldLimit)
                res
            } catch (e: DiskException.IoFailure) {
                // STALL / short-pipe: Mass Storage Reset once, then retry once.
                if (attempt >= 1) {
                    closeSession()
                    throw e
                }
                session.bulkOnlyReset()
                attempt++
                continue
            } catch (e: DiskException.Disconnected) {
                closeSession()
                throw e
            } finally {
                buffer.limit(oldLimit)
            }

            if (result.status == CswStatus.PHASE_ERROR) {
                session.bulkOnlyReset()
                closeSession()
                throw DiskException.IoFailure("BOT phase error")
            }
            if (result.residue !in 0..cdb.transferLength) {
                throw DiskException.IoFailure("phase error: invalid residue")
            }
            when (result.status) {
                CswStatus.PASSED -> {
                    if (result.residue > 0) {
                        throw DiskException.IoFailure("short transfer residue=${result.residue}")
                    }
                    return
                }
                CswStatus.FAILED -> {
                    // CSW status 1: REQUEST SENSE, then surface without closing session.
                    val sense = requestSense()
                    throw sense
                }
                CswStatus.PHASE_ERROR -> error("unreachable")
            }
        }
    }

    private fun execute(cdb: ScsiCdb, data: ByteBuffer?): CswResult {
        ensureConnected()
        return try {
            session.transfer(cdb, data)
        } catch (e: DiskException.Disconnected) {
            closeSession()
            throw e
        }
    }

    private fun requestSense(): DiskException.SenseError {
        val buf = ByteBuffer.allocate(18)
        val result = session.transfer(ScsiCommand.requestSense(18), buf)
        if (result.status != CswStatus.PASSED) {
            throw DiskException.IoFailure("REQUEST SENSE failed with status ${result.status}")
        }
        buf.flip()
        return parseSense(buf)
    }

    private fun ensureOpen() {
        if (closed) throw DiskException.Closed()
    }

    private fun ensureConnected() {
        if (!session.isConnected()) {
            closeSession()
            throw DiskException.Disconnected()
        }
    }

    private fun closeSession() {
        closed = true
        runCatching { session.close() }
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
         * READ CAPACITY(10); if last LBA is 0xFFFFFFFF, fall back to READ CAPACITY(16).
         */
        fun probeCapacity(session: BotSession): BlockGeometry {
            val buf10 = ByteBuffer.allocate(8)
            val status10 = session.transfer(ScsiCommand.readCapacity10(), buf10)
            if (status10.status == CswStatus.PHASE_ERROR) {
                session.bulkOnlyReset()
                throw DiskException.IoFailure("phase error on READ CAPACITY(10)")
            }
            if (status10.status == CswStatus.FAILED) {
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

        private fun readCapacity16(session: BotSession): BlockGeometry {
            val buf = ByteBuffer.allocate(32)
            val status = session.transfer(ScsiCommand.readCapacity16(32), buf)
            when (status.status) {
                CswStatus.PASSED -> Unit
                CswStatus.FAILED -> throw DiskException.IoFailure("READ CAPACITY(16) failed")
                CswStatus.PHASE_ERROR -> {
                    session.bulkOnlyReset()
                    throw DiskException.IoFailure("phase error on READ CAPACITY(16)")
                }
            }
            buf.flip()
            buf.order(ByteOrder.BIG_ENDIAN)
            val lastLba = buf.long.toULong()
            val blockLength = buf.int
            require(blockLength in 512..4096 && blockLength.countOneBits() == 1) {
                "illegal block length $blockLength"
            }
            return BlockGeometry(blockLength, lastLba + 1UL)
        }

        fun parseSense(buffer: ByteBuffer): DiskException.SenseError {
            require(buffer.remaining() >= 14) { "sense buffer too short" }
            val responseCode = buffer.get(0).toInt() and 0x7F
            val senseKey = buffer.get(2).toInt() and 0x0F
            val asc = buffer.get(12).toInt() and 0xFF
            val ascq = buffer.get(13).toInt() and 0xFF
            return DiskException.SenseError(responseCode, senseKey, asc, ascq)
        }
    }
}
