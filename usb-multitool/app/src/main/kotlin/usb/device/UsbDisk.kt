package usb.device

import java.nio.ByteBuffer

open class UsbDisk(
    private val transport: BlockTransport,
    val geometry: BlockGeometry,
) {
    @Volatile
    var isClosed: Boolean = false
        private set

    open fun read(lba: ULong, destination: ByteBuffer) {
        ensureOpen()
        val bytes = destination.remaining()
        require(bytes % geometry.sectorSize == 0) {
            "destination length $bytes not multiple of sectorSize ${geometry.sectorSize}"
        }
        val blocks = bytes / geometry.sectorSize
        require(blocks > 0)
        require(blocks.toULong() <= geometry.sectorCount &&
            lba <= geometry.sectorCount - blocks.toULong()) {
            "read past end: lba=$lba blocks=$blocks capacity=${geometry.sectorCount}"
        }

        val parts = TransferPlan.plan(bytes.toLong(), geometry.sectorSize)
        var offsetBlocks = 0
        for (part in parts) {
            val partBlocks = part / geometry.sectorSize
            transferIn(lba + offsetBlocks.toULong(), partBlocks, destination)
            offsetBlocks += partBlocks
        }
    }

    open fun write(lba: ULong, source: ByteBuffer) {
        ensureOpen()
        val bytes = source.remaining()
        require(bytes % geometry.sectorSize == 0) {
            "source length $bytes not multiple of sectorSize ${geometry.sectorSize}"
        }
        val blocks = bytes / geometry.sectorSize
        require(blocks > 0)
        require(blocks.toULong() <= geometry.sectorCount &&
            lba <= geometry.sectorCount - blocks.toULong()) {
            "write past end: lba=$lba blocks=$blocks capacity=${geometry.sectorCount}"
        }

        val parts = TransferPlan.plan(bytes.toLong(), geometry.sectorSize)
        var offsetBlocks = 0
        for (part in parts) {
            val partBlocks = part / geometry.sectorSize
            transferOut(lba + offsetBlocks.toULong(), partBlocks, source)
            offsetBlocks += partBlocks
        }
    }

    private fun transferIn(lba: ULong, blocks: Int, destination: ByteBuffer) {
        val length = blocks * geometry.sectorSize
        val mark = destination.position()
        var attempt = 0
        while (true) {
            destination.position(mark)
            ensureConnected()
            try {
                val cdb = buildCdb(read = true, lba = lba, blocks = blocks)
                transport.command(cdb)
                var remaining = length
                while (remaining > 0) {
                    val n = transport.dataIn(destination, remaining)
                    if (n <= 0) throw DiskException.IoFailure("short read returned $n")
                    remaining -= n
                }
                when (val status = transport.status()) {
                    BotStatus.OK -> return
                    BotStatus.STALL -> throw StallException()
                    BotStatus.FAIL -> {
                        closeSession()
                        throw DiskException.IoFailure("BOT status FAIL")
                    }
                }
            } catch (e: StallException) {
                if (attempt >= 1) {
                    closeSession()
                    throw DiskException.IoFailure("STALL after recovery")
                }
                Recovery.bulkOnlyReset(transport)
                attempt++
            } catch (e: DiskException.Disconnected) {
                closeSession()
                throw e
            }
        }
    }

    private fun transferOut(lba: ULong, blocks: Int, source: ByteBuffer) {
        val length = blocks * geometry.sectorSize
        val mark = source.position()
        var attempt = 0
        while (true) {
            source.position(mark)
            ensureConnected()
            try {
                val cdb = buildCdb(read = false, lba = lba, blocks = blocks)
                transport.command(cdb)
                var remaining = length
                while (remaining > 0) {
                    val n = transport.dataOut(source, remaining)
                    if (n <= 0) throw DiskException.IoFailure("short write returned $n")
                    remaining -= n
                }
                when (val status = transport.status()) {
                    BotStatus.OK -> return
                    BotStatus.STALL -> throw StallException()
                    BotStatus.FAIL -> {
                        closeSession()
                        throw DiskException.IoFailure("BOT status FAIL")
                    }
                }
            } catch (e: StallException) {
                if (attempt >= 1) {
                    closeSession()
                    throw DiskException.IoFailure("STALL after recovery")
                }
                Recovery.bulkOnlyReset(transport)
                attempt++
            } catch (e: DiskException.Disconnected) {
                closeSession()
                throw e
            }
        }
    }

    private fun buildCdb(read: Boolean, lba: ULong, blocks: Int): ByteArray {
        val width = ScsiCommand.select(lba, blocks)
        return when (width) {
            ScsiWidth.CDB10 -> {
                val op = if (read) 0x28 else 0x2A
                byteArrayOf(
                    op.toByte(),
                    0,
                    ((lba shr 24) and 0xFFu).toByte(),
                    ((lba shr 16) and 0xFFu).toByte(),
                    ((lba shr 8) and 0xFFu).toByte(),
                    (lba and 0xFFu).toByte(),
                    0,
                    ((blocks shr 8) and 0xFF).toByte(),
                    (blocks and 0xFF).toByte(),
                    0,
                )
            }
            ScsiWidth.CDB16 -> {
                val op = if (read) 0x88 else 0x8A
                ByteArray(16).also { cdb ->
                    cdb[0] = op.toByte()
                    for (i in 0 until 8) {
                        cdb[2 + i] = ((lba shr (56 - 8 * i)) and 0xFFu).toByte()
                    }
                    for (i in 0 until 4) {
                        cdb[10 + i] = ((blocks shr (24 - 8 * i)) and 0xFF).toByte()
                    }
                }
            }
        }
    }

    private fun ensureOpen() {
        if (isClosed) throw DiskException.Closed()
    }

    private fun ensureConnected() {
        if (!transport.isConnected()) {
            closeSession()
            throw DiskException.Disconnected()
        }
    }

    private fun closeSession() {
        isClosed = true
    }

    private class StallException : Exception()
}
