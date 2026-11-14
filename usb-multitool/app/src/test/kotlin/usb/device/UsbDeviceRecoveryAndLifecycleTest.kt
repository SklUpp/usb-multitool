package usb.device

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceRecoveryAndLifecycleTest {

    private class ScriptedChannel(
        private var connected: Boolean = true,
        private val resetControlResult: Int = 0,
    ) : UsbBulkChannel {
        override val interfaceId: Int = 3
        val events = mutableListOf<String>()
        private val inActions = ArrayDeque<(ByteBuffer, Int) -> Int>()

        fun queueInBytes(bytes: ByteArray) {
            inActions.addLast { dest, length ->
                val n = minOf(length, bytes.size)
                dest.put(bytes, 0, n)
                n
            }
        }

        fun queueRawCsw(
            tag: Int,
            residue: Int = 0,
            statusCode: Int = CswStatus.PASSED.code,
            signature: Int = BotConstants.CSW_SIGNATURE,
        ) {
            val buf = ByteBuffer.allocate(BotConstants.CSW_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(signature)
            buf.putInt(tag)
            buf.putInt(residue)
            buf.put(statusCode.toByte())
            queueInBytes(buf.array())
        }

        fun queueInReturn(returnCode: Int) {
            inActions.addLast { _, _ -> returnCode }
        }

        override fun bulkIn(destination: ByteBuffer, length: Int, timeoutMs: Int): Int {
            if (!connected) throw DiskException.Disconnected()
            events += "bulkIn:$length"
            val action = inActions.removeFirstOrNull()
                ?: error("unexpected bulkIn($length)")
            return action(destination, length)
        }

        override fun bulkOut(source: ByteBuffer, length: Int, timeoutMs: Int): Int {
            if (!connected) throw DiskException.Disconnected()
            events += "bulkOut:$length"
            source.position(source.position() + length)
            return length
        }

        override fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray?,
            length: Int,
        ): Int {
            if (!connected) throw DiskException.Disconnected()
            if (requestType == BotConstants.REQUEST_TYPE_CLASS_INTERFACE_OUT &&
                request == BotConstants.REQUEST_BULK_ONLY_RESET
            ) {
                events += "massStorageReset:$index"
                return resetControlResult
            }
            events += "control:$requestType/$request"
            return 0
        }

        override fun clearHaltIn() {
            events += "clearHaltIn"
        }

        override fun clearHaltOut() {
            events += "clearHaltOut"
        }

        override fun isConnected(): Boolean = connected

        override fun close() {
            connected = false
            events += "close"
        }
    }

    private fun decodeCdb10(cdb: ScsiCdb): Pair<ULong, Int> {
        val b = cdb.bytes
        val lba = ((b[2].toULong() and 0xFFu) shl 24) or
            ((b[3].toULong() and 0xFFu) shl 16) or
            ((b[4].toULong() and 0xFFu) shl 8) or
            (b[5].toULong() and 0xFFu)
        val blocks = ((b[7].toInt() and 0xFF) shl 8) or (b[8].toInt() and 0xFF)
        return lba to blocks
    }

    private fun filledBufferWithWindow(
        prefix: Int,
        payloadSize: Int,
        suffix: Int,
        sentinel: Byte,
        payloadByte: (Int) -> Byte = { 0 },
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(prefix + payloadSize + suffix)
        for (i in 0 until prefix) buf.put(i, sentinel)
        for (i in 0 until payloadSize) buf.put(prefix + i, payloadByte(i))
        for (i in (prefix + payloadSize) until buf.capacity()) buf.put(i, sentinel)
        buf.position(prefix)
        buf.limit(prefix + payloadSize)
        return buf
    }

    @Test
    fun multiChunkReadStallOnSecondChunkResetsAndRetriesAtOffsetLbaWithPreservedWindow() {
        val chunkBytes = TransferTuning.scsiChunkBytes
        val sectorSize = 512
        assertTrue(chunkBytes >= sectorSize && chunkBytes % sectorSize == 0)
        val chunkBlocks = chunkBytes / sectorSize
        val totalBlocks = chunkBlocks + 2
        val startLba = 10UL

        val recordedCdbs = mutableListOf<ScsiCdb>()
        var readAttempts = 0
        val session = object : FakeBotSession() {
            override fun transfer(cdb: ScsiCdb, data: ByteBuffer?, timeoutMs: Int): CswResult {
                val op = cdb.bytes[0].toInt() and 0xFF
                if (op == ScsiCommand.OP_READ10) {
                    recordedCdbs += cdb
                    readAttempts++
                    if (readAttempts == 2) {
                        // Advance position before failing to prove retry rewinds to chunk mark.
                        data!!.put(ByteArray(256) { 0xEE.toByte() })
                        throw DiskException.IoFailure("chunk 2 STALL")
                    }
                }
                return super.transfer(cdb, data, timeoutMs)
            }
        }

        val expectedPayload = ByteArray(totalBlocks * sectorSize)
        for (block in 0 until totalBlocks) {
            val sector = ByteArray(sectorSize) { idx ->
                (((block + 1) * 17 + idx) and 0xFF).toByte()
            }
            session.putSector(startLba + block.toULong(), sector)
            System.arraycopy(sector, 0, expectedPayload, block * sectorSize, sectorSize)
        }

        val claim = UsbExclusiveClaim()
        val disk = UsbDisk(
            session,
            BlockGeometry(sectorSize, (startLba + totalBlocks.toULong() + 16UL)),
            claim.tryAcquire(),
        )

        val prefix = 512
        val payloadSize = totalBlocks * sectorSize
        val suffix = 512
        val sentinel = 0x5A.toByte()
        val buffer = filledBufferWithWindow(prefix, payloadSize, suffix, sentinel)
        val oldLimit = buffer.limit()

        disk.read(startLba, buffer)

        assertEquals(1, session.resetCount)
        assertEquals(3, recordedCdbs.size)
        assertEquals(startLba to chunkBlocks, decodeCdb10(recordedCdbs[0]))
        assertEquals((startLba + chunkBlocks.toULong()) to 2, decodeCdb10(recordedCdbs[1]))
        assertEquals((startLba + chunkBlocks.toULong()) to 2, decodeCdb10(recordedCdbs[2]))
        assertEquals(oldLimit, buffer.position())
        assertEquals(oldLimit, buffer.limit())
        assertFalse(disk.isClosed)
        assertTrue(claim.isHeld())

        val raw = buffer.array()
        for (i in 0 until prefix) assertEquals(sentinel, raw[i])
        for (i in oldLimit until raw.size) assertEquals(sentinel, raw[i])
        assertArrayEquals(expectedPayload, raw.copyOfRange(prefix, oldLimit))
    }

    @Test
    fun multiChunkWriteAndReadRoundTripWithOffsetBufferWindow() {
        val chunkBytes = TransferTuning.scsiChunkBytes
        val sectorSize = 512
        val chunkBlocks = chunkBytes / sectorSize
        val extraBlocks = 3
        val totalBlocks = chunkBlocks + extraBlocks
        val payloadSize = totalBlocks * sectorSize
        val startLba = 12UL

        val recordedWrites = mutableListOf<Pair<ULong, Int>>()
        val session = object : FakeBotSession() {
            override fun transfer(cdb: ScsiCdb, data: ByteBuffer?, timeoutMs: Int): CswResult {
                if ((cdb.bytes[0].toInt() and 0xFF) == ScsiCommand.OP_WRITE10) {
                    recordedWrites += decodeCdb10(cdb)
                }
                return super.transfer(cdb, data, timeoutMs)
            }
        }
        val disk = UsbDisk(session, BlockGeometry(sectorSize, startLba + totalBlocks.toULong() + 8UL))

        val src = filledBufferWithWindow(
            prefix = 512,
            payloadSize = payloadSize,
            suffix = 512,
            sentinel = 0x3C.toByte(),
            payloadByte = { idx -> ((idx * 31 + 7) and 0xFF).toByte() },
        )
        val expected = src.array().copyOfRange(512, 512 + payloadSize)
        val srcOldLimit = src.limit()

        disk.write(startLba, src)

        assertEquals(srcOldLimit, src.position())
        assertEquals(srcOldLimit, src.limit())
        assertEquals(
            listOf(startLba to chunkBlocks, (startLba + chunkBlocks.toULong()) to extraBlocks),
            recordedWrites,
        )

        val dst = filledBufferWithWindow(
            prefix = 256,
            payloadSize = payloadSize,
            suffix = 256,
            sentinel = 0x7E.toByte(),
        )
        val dstOldLimit = dst.limit()
        disk.read(startLba, dst)

        assertEquals(dstOldLimit, dst.position())
        assertEquals(dstOldLimit, dst.limit())
        val dstRaw = dst.array()
        for (i in 0 until 256) assertEquals(0x7E.toByte(), dstRaw[i])
        for (i in dstOldLimit until dstRaw.size) assertEquals(0x7E.toByte(), dstRaw[i])
        assertArrayEquals(expected, dstRaw.copyOfRange(256, dstOldLimit))
    }

    @Test
    fun multiChunkWriteIoFailureOnSecondChunkDoesNotRetryAndReleasesLease() {
        val chunkBytes = TransferTuning.scsiChunkBytes
        val sectorSize = 512
        val chunkBlocks = chunkBytes / sectorSize
        val totalBlocks = chunkBlocks + 2
        val payloadSize = totalBlocks * sectorSize

        var writeCalls = 0
        val session = object : FakeBotSession() {
            override fun transfer(cdb: ScsiCdb, data: ByteBuffer?, timeoutMs: Int): CswResult {
                if ((cdb.bytes[0].toInt() and 0xFF) == ScsiCommand.OP_WRITE10) {
                    writeCalls++
                    if (writeCalls == 2) {
                        throw DiskException.IoFailure("chunk 2 write stall")
                    }
                }
                return super.transfer(cdb, data, timeoutMs)
            }
        }
        val claim = UsbExclusiveClaim()
        val disk = UsbDisk(
            session,
            BlockGeometry(sectorSize, (totalBlocks + 32).toULong()),
            claim.tryAcquire(),
        )
        val src = filledBufferWithWindow(512, payloadSize, 512, 0x11.toByte()) { 0x42.toByte() }
        val oldLimit = src.limit()

        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.write(20UL, src)
        }

        assertEquals("chunk 2 write stall", ex.message)
        assertEquals(1, session.resetCount)
        assertEquals(2, writeCalls)
        assertEquals(oldLimit, src.limit())
        assertTrue(disk.isClosed)
        assertFalse(session.isConnected())
        assertFalse(claim.isHeld())
    }

    @Test
    fun multiChunkWriteUnitAttentionOnSecondChunkClosesWithoutReplay() {
        val chunkBytes = TransferTuning.scsiChunkBytes
        val sectorSize = 512
        val chunkBlocks = chunkBytes / sectorSize
        val totalBlocks = chunkBlocks + 2
        val payloadSize = totalBlocks * sectorSize

        val recordedOps = mutableListOf<Int>()
        var writeCalls = 0
        val session = object : FakeBotSession(
            requestSenseError = DiskException.SenseError(0x70, 0x06, 0x29, 0x00),
        ) {
            override fun transfer(cdb: ScsiCdb, data: ByteBuffer?, timeoutMs: Int): CswResult {
                val op = cdb.bytes[0].toInt() and 0xFF
                recordedOps += op
                if (op == ScsiCommand.OP_WRITE10) {
                    writeCalls++
                    if (writeCalls == 2) {
                        return CswResult(CswStatus.FAILED, 0, 0)
                    }
                }
                return super.transfer(cdb, data, timeoutMs)
            }
        }
        val claim = UsbExclusiveClaim()
        val disk = UsbDisk(
            session,
            BlockGeometry(sectorSize, (totalBlocks + 32).toULong()),
            claim.tryAcquire(),
        )
        val src = filledBufferWithWindow(256, payloadSize, 256, 0x00) { 0x77.toByte() }
        val oldLimit = src.limit()

        val ex = assertThrows(DiskException.SenseError::class.java) {
            disk.write(4UL, src)
        }

        assertTrue(ex.isUnitAttention)
        assertEquals(
            listOf(
                ScsiCommand.OP_WRITE10,
                ScsiCommand.OP_WRITE10,
                ScsiCommand.OP_REQUEST_SENSE,
            ),
            recordedOps,
        )
        assertEquals(oldLimit, src.limit())
        assertTrue(disk.isClosed)
        assertFalse(claim.isHeld())
    }

    @Test
    fun syncPhaseErrorRunsResetClosesSessionAndReleasesLease() {
        val claim = UsbExclusiveClaim()
        val session = FakeBotSession(phaseErrorOnOps = setOf(ScsiCommand.OP_SYNCHRONIZE_CACHE10))
        val disk = UsbDisk(session, BlockGeometry(512, 16UL), claim.tryAcquire())

        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.sync()
        }

        assertEquals("phase error on SYNCHRONIZE CACHE(10)", ex.message)
        assertEquals(1, session.resetCount)
        assertEquals(
            listOf("massStorageReset", "clearHaltIn", "clearHaltOut"),
            session.resetOrders.single(),
        )
        assertTrue(disk.isClosed)
        assertFalse(session.isConnected())
        assertFalse(claim.isHeld())
    }

    @Test
    fun syncPhaseErrorPreservesResetAndCloseFailuresAndKeepsLeaseHeld() {
        val claim = UsbExclusiveClaim()
        val session = object : FakeBotSession(
            phaseErrorOnOps = setOf(ScsiCommand.OP_SYNCHRONIZE_CACHE10),
        ) {
            override fun bulkOnlyReset() {
                throw DiskException.IoFailure("reset failed")
            }

            override fun close() {
                throw IOException("close failed")
            }
        }
        val disk = UsbDisk(session, BlockGeometry(512, 16UL), claim.tryAcquire())

        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.sync()
        }

        assertEquals("phase error on SYNCHRONIZE CACHE(10)", ex.message)
        assertEquals(listOf("reset failed", "close failed"), ex.suppressed.map { it.message })
        assertTrue(disk.isClosed)
        assertTrue(claim.isHeld())
        val repeatedClose = assertThrows(IOException::class.java) { disk.close() }
        assertEquals("close failed", repeatedClose.message)
    }

    @Test
    fun syncNonIllegalNonUnitAttentionSensePropagatesWithoutRetryOrClose() {
        val claim = UsbExclusiveClaim()
        val notReady = DiskException.SenseError(0x70, 0x02, 0x04, 0x01)
        val session = FakeBotSession(syncSense = notReady)
        val disk = UsbDisk(session, BlockGeometry(512, 16UL), claim.tryAcquire())

        val ex = assertThrows(DiskException.SenseError::class.java) {
            disk.sync()
        }

        assertEquals(notReady, ex)
        assertEquals(
            listOf(ScsiCommand.OP_SYNCHRONIZE_CACHE10, ScsiCommand.OP_REQUEST_SENSE),
            session.commands,
        )
        assertEquals(0, session.resetCount)
        assertFalse(disk.isClosed)
        assertTrue(claim.isHeld())
    }

    @Test
    fun syncOnClosedDiskThrowsClosedWithoutCommands() {
        val session = FakeBotSession()
        val disk = UsbDisk(session, BlockGeometry(512, 16UL))
        disk.close()

        assertThrows(DiskException.Closed::class.java) {
            disk.sync()
        }
        assertTrue(session.commands.isEmpty())
    }

    @Test
    fun botSessionCswReadMinusOneReservedStatusAndPhaseErrorResidueRules() {
        val channel = ScriptedChannel()
        val session = BotSessionImpl(channel)

        // 1) bulkIn returning -1 during CSW read throws CswReadFailure without closing channel.
        channel.queueInReturn(-1)
        assertThrows(DiskException.CswReadFailure::class.java) {
            session.transfer(ScsiCommand.testUnitReady(), null)
        }
        assertTrue(session.isConnected())

        // 2) Reserved CSW status (0x03) throws NonMeaningfulCsw without closing channel.
        channel.queueRawCsw(tag = 2, residue = 0, statusCode = 0x03)
        val reserved = assertThrows(DiskException.NonMeaningfulCsw::class.java) {
            session.transfer(ScsiCommand.testUnitReady(), null)
        }
        assertEquals("reserved CSW status 3", reserved.message)
        assertTrue(session.isConnected())

        // 3) PHASE_ERROR bypasses residue bounds check in BotSessionImpl.
        channel.queueRawCsw(tag = 3, residue = -1, statusCode = CswStatus.PHASE_ERROR.code)
        val phase = session.transfer(ScsiCommand.testUnitReady(), null)
        assertEquals(CswResult(CswStatus.PHASE_ERROR, tag = 3, residue = -1), phase)
    }

    @Test
    fun closeAfterUntrustedReplyOnCswFailureAttachesFailedResetAsSuppressed() {
        val readChannel = ScriptedChannel(resetControlResult = -1)
        readChannel.queueInBytes(ByteArray(512))
        readChannel.queueInReturn(-1)
        val readClaim = UsbExclusiveClaim()
        val readDisk = UsbDisk(
            BotSessionImpl(readChannel),
            BlockGeometry(512, 16UL),
            readClaim.tryAcquire(),
        )

        val readEx = assertThrows(DiskException.CswReadFailure::class.java) {
            readDisk.read(0UL, ByteBuffer.allocate(512))
        }
        assertEquals(
            "Bulk-Only Mass Storage Reset failed",
            readEx.suppressed.single().message,
        )
        assertTrue(readDisk.isClosed)
        assertFalse(readChannel.isConnected())
        assertFalse(readClaim.isHeld())

        val syncChannel = ScriptedChannel(resetControlResult = -1)
        syncChannel.queueRawCsw(tag = 1, residue = 0, statusCode = 0x03)
        val syncClaim = UsbExclusiveClaim()
        val syncDisk = UsbDisk(
            BotSessionImpl(syncChannel),
            BlockGeometry(512, 16UL),
            syncClaim.tryAcquire(),
        )

        val syncEx = assertThrows(DiskException.NonMeaningfulCsw::class.java) {
            syncDisk.sync()
        }
        assertEquals(
            "Bulk-Only Mass Storage Reset failed",
            syncEx.suppressed.single().message,
        )
        assertTrue(syncDisk.isClosed)
        assertFalse(syncChannel.isConnected())
        assertFalse(syncClaim.isHeld())
    }

    @Test
    fun probeCapacityPhaseErrorInvalidBlockLengthAndCap16Failure() {
        val cap10Phase = FakeBotSession(phaseErrorOnOps = setOf(ScsiCommand.OP_READ_CAPACITY10))
        val cap10Ex = assertThrows(DiskException.IoFailure::class.java) {
            UsbDisk.probeCapacity(cap10Phase)
        }
        assertEquals("phase error on READ CAPACITY(10)", cap10Ex.message)
        assertEquals(1, cap10Phase.resetCount)

        val cap16Phase = FakeBotSession(
            capacityLastLba10 = 0xFFFF_FFFFUL,
            capacity16LastLba = 2047UL,
            phaseErrorOnOps = setOf(ScsiCommand.OP_READ_CAPACITY16),
        )
        val cap16Ex = assertThrows(DiskException.IoFailure::class.java) {
            UsbDisk.probeCapacity(cap16Phase)
        }
        assertEquals("phase error on READ CAPACITY(16)", cap16Ex.message)
        assertEquals(1, cap16Phase.resetCount)

        val cap16Fail = FakeBotSession(
            capacityLastLba10 = 0xFFFF_FFFFUL,
            capacity16LastLba = 2047UL,
            failOnOps = setOf(ScsiCommand.OP_READ_CAPACITY16),
            requestSenseError = DiskException.SenseError(0x70, 0x02, 0x3A, 0x00),
        )
        val cap16FailEx = assertThrows(DiskException.IoFailure::class.java) {
            UsbDisk.probeCapacity(cap16Fail)
        }
        assertEquals("READ CAPACITY(16) failed", cap16FailEx.message)

        assertThrows(IllegalArgumentException::class.java) {
            UsbDisk.probeCapacity(FakeBotSession(capacityBlockLength = 511))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbDisk.probeCapacity(
                FakeBotSession(
                    capacityLastLba10 = 0xFFFF_FFFFUL,
                    capacity16LastLba = 2047UL,
                    capacityBlockLength = 8192,
                ),
            )
        }
    }

    @Test
    fun parseSenseSupportsFixedAndDescriptorFormatsAndRejectsTruncatedBuffers() {
        val fixed = ByteArray(14)
        fixed[0] = 0x70
        fixed[2] = 0x01
        fixed[12] = 0x11
        fixed[13] = 0x00
        val parsedFixed = UsbDisk.parseSense(ByteBuffer.wrap(fixed))
        assertEquals(DiskException.SenseError(0x70, 0x01, 0x11, 0x00), parsedFixed)
        assertTrue(parsedFixed.isMediumError)

        val desc72 = ByteArray(8)
        desc72[0] = 0x72
        desc72[1] = 0x06
        desc72[2] = 0x29
        desc72[3] = 0x00
        assertEquals(
            DiskException.SenseError(0x72, 0x06, 0x29, 0x00),
            UsbDisk.parseSense(ByteBuffer.wrap(desc72)),
        )

        val desc73 = ByteArray(8)
        desc73[0] = 0x73
        desc73[1] = 0x05
        desc73[2] = 0x24
        desc73[3] = 0x01
        assertEquals(
            DiskException.SenseError(0x73, 0x05, 0x24, 0x01),
            UsbDisk.parseSense(ByteBuffer.wrap(desc73)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            UsbDisk.parseSense(ByteBuffer.allocate(7))
        }
        val shortFixed = ByteArray(13)
        shortFixed[0] = 0x70
        assertThrows(IllegalArgumentException::class.java) {
            UsbDisk.parseSense(ByteBuffer.wrap(shortFixed))
        }
    }

    @Test
    fun higherLevelCloseHoldsClaimAcrossDiskCloseUntilConfirmed() {
        val claim = UsbExclusiveClaim()
        val session = FakeBotSession()
        val disk = UsbDisk(session, BlockGeometry(512, 16UL), claim.tryAcquire())

        disk.beginHigherLevelClose()
        disk.close()

        assertTrue(disk.isClosed)
        assertFalse(session.isConnected())
        assertTrue(claim.isHeld())
        assertThrows(UsbExclusiveClaim.BusyException::class.java) { claim.tryAcquire() }

        disk.confirmHigherLevelClose()

        assertFalse(claim.isHeld())
        claim.tryAcquire().close()
    }

    @Test
    fun abortHigherLevelCloseKeepsLiveSessionClaimUntilSessionCloses() {
        val claim = UsbExclusiveClaim()
        val lease = claim.tryAcquire()
        val disk = UsbDisk(FakeBotSession(), BlockGeometry(512, 16UL), lease)

        disk.beginHigherLevelClose()
        lease.abortHigherLevelClose()
        assertTrue(claim.isHeld())

        disk.close()
        assertTrue(claim.isHeld())

        lease.abortHigherLevelClose()
        assertFalse(claim.isHeld())
    }

    @Test
    fun probeCapacityFailureRollbackAllowsLeaseCloseAndBindLeaseRejectsSecondLease() {
        val claim = UsbExclusiveClaim()
        val failedOpenLease = claim.tryAcquire()
        val badSession = FakeBotSession(phaseErrorOnOps = setOf(ScsiCommand.OP_READ_CAPACITY10))

        assertThrows(DiskException.IoFailure::class.java) {
            UsbDisk(badSession, lease = failedOpenLease)
        }
        // Probe failed before bindSession(), so caller close() rolls back ownership.
        failedOpenLease.close()
        assertFalse(claim.isHeld())

        val activeLease = claim.tryAcquire()
        val disk = UsbDisk(FakeBotSession(), BlockGeometry(512, 16UL))
        disk.bindLease(activeLease)
        disk.bindLease(activeLease)
        assertTrue(claim.isHeld())

        val secondClaim = UsbExclusiveClaim()
        val distinctLease = secondClaim.tryAcquire()
        try {
            assertThrows(IllegalStateException::class.java) {
                disk.bindLease(distinctLease)
            }
        } finally {
            distinctLease.close()
            disk.close()
        }
        assertFalse(claim.isHeld())
    }

    @Test
    fun usbDiskSessionInitializeRecoversUntrustedCswAndPhaseError() {
        val invalidCswChannel = ScriptedChannel(resetControlResult = -1)
        invalidCswChannel.queueRawCsw(tag = 1, signature = 0x12345678)
        val invalidEx = assertThrows(DiskException.InvalidCsw::class.java) {
            UsbDiskSession.initialize(BotSessionImpl(invalidCswChannel))
        }
        assertEquals(
            "Bulk-Only Mass Storage Reset failed",
            invalidEx.suppressed.single().message,
        )
        assertTrue(invalidCswChannel.events.contains("massStorageReset:3"))

        val phaseChannel = ScriptedChannel()
        phaseChannel.queueRawCsw(tag = 1, statusCode = CswStatus.PHASE_ERROR.code)
        val phaseEx = assertThrows(DiskException.IoFailure::class.java) {
            UsbDiskSession.initialize(BotSessionImpl(phaseChannel))
        }
        assertEquals("phase error on TEST UNIT READY", phaseEx.message)
        assertEquals(
            listOf("bulkOut:31", "bulkIn:13", "massStorageReset:3", "clearHaltIn", "clearHaltOut"),
            phaseChannel.events,
        )
    }
}
