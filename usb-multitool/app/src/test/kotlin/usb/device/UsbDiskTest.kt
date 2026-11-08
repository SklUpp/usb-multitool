package usb.device

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UsbDiskTest {
    @Test
    fun stallRetriesOnceThenReads() {
        val session = FakeBotSession(stallsBeforeSuccess = 1)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        disk.read(4UL, out)
        assertEquals(1, session.resetCount)
        assertEquals(512, out.position())
        assertEquals(
            listOf("massStorageReset", "clearHaltIn", "clearHaltOut"),
            session.resetOrders.single(),
        )
    }

    @Test
    fun secondStallDoesNotResetAgain() {
        val session = FakeBotSession(stallsBeforeSuccess = 2)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        try {
            disk.read(0UL, out)
            fail("expected DiskException")
        } catch (e: DiskException) {
            assertEquals(1, session.resetCount)
            assertTrue(disk.isClosed)
        }
    }

    @Test
    fun disconnectThrowsAndClosesSession() {
        val session = FakeBotSession(disconnectOnCommand = true)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        try {
            disk.read(0UL, out)
            fail("expected DiskException.Disconnected")
        } catch (e: DiskException.Disconnected) {
            assertTrue(disk.isClosed)
        }
    }

    @Test
    fun shortReadFillsRequestedBytesAcrossChunks() {
        val session = FakeBotSession(maxChunk = 256)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        disk.read(1UL, out)
        assertEquals(512, out.position())
        assertEquals(0, session.resetCount)
    }

    @Test
    fun writeFillsDataAndRecoversStall() {
        val session = FakeBotSession(stallsBeforeSuccess = 1)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val src = ByteBuffer.allocate(512)
        for (i in 0 until 512) src.put(0x55.toByte())
        src.flip()
        disk.write(2UL, src)
        assertEquals(1, session.resetCount)
        assertEquals(512, src.position())
    }

    @Test
    fun botFailPreservesSessionAfterSense() {
        val sense = DiskException.SenseError(0x70, 0x03, 0x11, 0x00)
        val session = FakeBotSession(failAlways = true, syncSense = sense)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        try {
            disk.read(0UL, out)
            fail("expected sense error")
        } catch (e: DiskException.SenseError) {
            assertEquals(sense, e)
            assertFalse(disk.isClosed)
            assertTrue(session.isConnected())
        }
    }

    @Test
    fun lbaAdditionOverflowRejected() {
        val session = FakeBotSession()
        val disk = UsbDisk(session, BlockGeometry(512, ULong.MAX_VALUE))
        val out = ByteBuffer.allocate(512)
        assertThrows(IllegalArgumentException::class.java) {
            disk.read(ULong.MAX_VALUE, out)
        }
    }

    @Test
    fun misalignedLengthRejected() {
        val session = FakeBotSession()
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(100)
        assertThrows(IllegalArgumentException::class.java) {
            disk.read(0UL, out)
        }
    }

    @Test
    fun capacity10MaxLbaFallsBackToCapacity16() {
        val session = FakeBotSession(
            capacityLastLba10 = 0xFFFF_FFFFUL,
            capacity16LastLba = (1UL shl 33) - 1UL,
        )
        val disk = UsbDisk(session)
        assertEquals(512, disk.sectorSize)
        assertEquals(1UL shl 33, disk.sectorCount)
        assertTrue(session.commands.contains(ScsiCommand.OP_READ_CAPACITY10))
        assertTrue(session.commands.contains(ScsiCommand.OP_READ_CAPACITY16))
    }

    @Test
    fun largeLbaUsesRead16() {
        val session = FakeBotSession(capacity16LastLba = (1UL shl 33) - 1UL)
        val disk = UsbDisk(session, BlockGeometry(512, 1UL shl 33))
        val out = ByteBuffer.allocate(512)
        disk.read(1UL shl 32, out)
        assertTrue(session.commands.contains(ScsiCommand.OP_READ16))
    }

    @Test
    fun syncSurfacesUnsupportedSense() {
        val sense = DiskException.SenseError(0x70, 0x05, 0x20, 0x00)
        val session = FakeBotSession(syncSense = sense)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        assertThrows(DiskException.UnsupportedCommand::class.java) {
            disk.sync()
        }
    }

    @Test
    fun phaseErrorRunsResetThenCloses() {
        val session = FakeBotSession(phaseErrorOnOps = setOf(ScsiCommand.OP_READ10))
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        try {
            disk.read(0UL, out)
            fail("expected phase error")
        } catch (e: DiskException.IoFailure) {
            assertEquals(1, session.resetCount)
            assertEquals(
                listOf("massStorageReset", "clearHaltIn", "clearHaltOut"),
                session.resetOrders.single(),
            )
            assertTrue(disk.isClosed)
        }
    }

    @Test
    fun roundTripWriteThenRead() {
        val session = FakeBotSession()
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val pattern = ByteArray(512) { it.toByte() }
        disk.write(7UL, ByteBuffer.wrap(pattern))
        val out = ByteBuffer.allocate(512)
        disk.read(7UL, out)
        assertArrayEquals(pattern, out.array())
    }

    @Test
    fun cswPassedWithResidueAndFullDeliveryThrowsMismatch() {
        val session = FakeBotSession(residue = 128)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.read(0UL, out)
        }
        assertEquals("data phase mismatch: 512 of 512 bytes, residue=128", ex.message)
    }

    @Test
    fun shortDataInWithLyingResidueZeroThrowsMismatch() {
        // Device ends data phase after 256 bytes but CSW claims residue=0.
        val session = FakeBotSession(shortReadBytes = 256, residue = 0)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.read(0UL, out)
        }
        assertEquals("data phase mismatch: 256 of 512 bytes, residue=0", ex.message)
        assertEquals(256, out.position())
    }

    @Test
    fun honestShortInWithMatchingResidueStillRejected() {
        // Device short-INs 256 of 512 and reports residue=256 — BOT-honest, still
        // unusable for a full-sector block read.
        val session = FakeBotSession(shortReadBytes = 256, residue = 256)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.read(0UL, out)
        }
        assertEquals("data phase mismatch: 256 of 512 bytes, residue=256", ex.message)
        assertEquals(256, out.position())
    }

    @Test
    fun cswResidueGreaterThanTransferLengthThrowsPhaseError() {
        val session = FakeBotSession(residue = 1024)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.read(0UL, out)
        }
        assertEquals("phase error: invalid residue", ex.message)
    }

    @Test
    fun cswNegativeResidueThrowsPhaseError() {
        val session = FakeBotSession(residue = -1)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.read(0UL, out)
        }
        assertEquals("phase error: invalid residue", ex.message)
    }

    @Test
    fun boundaryLbaWrapAroundAvoidsCdb10() {
        val session = FakeBotSession(
            capacityLastLba10 = 0xFFFF_FFFFUL,
            capacity16LastLba = 0x1_0000_0005UL,
        )
        val disk = UsbDisk(session, BlockGeometry(512, 0x1_0000_0006UL))
        val out = ByteBuffer.allocate(1024)
        disk.read(0xFFFF_FFFFUL, out)
        assertTrue(session.commands.contains(ScsiCommand.OP_READ16))
        assertFalse(session.commands.contains(ScsiCommand.OP_READ10))
    }

    @Test
    fun zeroRemainingBufferReturnsImmediately() {
        val session = FakeBotSession()
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val emptyBuf = ByteBuffer.allocate(0)
        disk.read(0UL, emptyBuf)
        disk.write(0UL, emptyBuf)
        assertEquals(0, session.commands.size)
    }

    @Test
    fun stalledCapacity10FallsBackToCapacity16() {
        val session = FakeBotSession(
            capacity16LastLba = 2047UL,
            stallOnOps = setOf(ScsiCommand.OP_READ_CAPACITY10),
        )
        val geometry = UsbDisk.probeCapacity(session)
        assertEquals(2048UL, geometry.sectorCount)
        assertEquals(1, session.resetCount)
    }

    @Test
    fun syncUsesTheLongFlushTimeoutWhileReadsKeepTheShortOne() {
        val session = FakeBotSession()
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        disk.read(0UL, ByteBuffer.allocate(512))
        disk.sync()

        assertEquals(BotConstants.TRANSFER_TIMEOUT_MS, session.timeouts.first())
        assertEquals(BotConstants.SYNCHRONIZE_CACHE_TIMEOUT_MS, session.timeouts.last())
    }

    @Test
    fun syncRetriesOnUnitAttentionAndSucceeds() {
        val session = FakeBotSession(
            unitAttentionBeforeSuccess = 1,
            unitAttentionOnOps = setOf(ScsiCommand.OP_SYNCHRONIZE_CACHE10),
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        disk.sync()

        assertEquals(
            listOf(
                ScsiCommand.OP_SYNCHRONIZE_CACHE10,
                ScsiCommand.OP_REQUEST_SENSE,
                ScsiCommand.OP_SYNCHRONIZE_CACHE10,
            ),
            session.commands,
        )
    }

    @Test
    fun syncFailsWhenUnitAttentionPersists() {
        val session = FakeBotSession(
            unitAttentionBeforeSuccess = 2,
            stickyUnitAttention = true,
            unitAttentionOnOps = setOf(ScsiCommand.OP_SYNCHRONIZE_CACHE10),
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        assertThrows(DiskException.SenseError::class.java) {
            disk.sync()
        }
    }

    @Test
    fun stallThenUnitAttentionRetriesSameCommandOnceAndReads() {
        // Real path: STALL -> reset recovery -> retried command fails with a
        // pending UNIT ATTENTION -> REQUEST SENSE clears it -> retry succeeds.
        val session = FakeBotSession(
            stallsBeforeSuccess = 1,
            unitAttentionBeforeSuccess = 1,
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)

        disk.read(4UL, out)

        assertEquals(1, session.resetCount)
        assertEquals(512, out.position())
        assertEquals(
            listOf(
                ScsiCommand.OP_READ10,
                ScsiCommand.OP_READ10,
                ScsiCommand.OP_REQUEST_SENSE,
                ScsiCommand.OP_READ10,
            ),
            session.commands,
        )
    }

    @Test
    fun unitAttentionBeyondOneRetryIsSurfaced() {
        val session = FakeBotSession(
            unitAttentionBeforeSuccess = 1,
            stickyUnitAttention = true,
        )
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)

        val ex = assertThrows(DiskException.SenseError::class.java) {
            disk.read(0UL, out)
        }

        assertEquals(0x06, ex.senseKey)
        assertEquals(
            listOf(
                ScsiCommand.OP_READ10,
                ScsiCommand.OP_REQUEST_SENSE,
                ScsiCommand.OP_READ10,
                ScsiCommand.OP_REQUEST_SENSE,
            ),
            session.commands,
        )
        assertEquals(0, session.resetCount)
        assertFalse(disk.isClosed)
    }

    @Test
    fun capacityProbeSurvivesPendingUnitAttention() {
        // CAPACITY(16) is deliberately unconfigured: falling back fails the test.
        val session = FakeBotSession(unitAttentionBeforeSuccess = 1)

        val geometry = UsbDisk.probeCapacity(session)

        assertEquals(512, geometry.sectorSize)
        assertEquals(100UL, geometry.sectorCount)
        assertEquals(0, session.resetCount)
        assertEquals(
            listOf(
                ScsiCommand.OP_READ_CAPACITY10,
                ScsiCommand.OP_REQUEST_SENSE,
                ScsiCommand.OP_READ_CAPACITY10,
            ),
            session.commands,
        )
    }

    @Test
    fun capacity10FailureFetchesSenseBeforeFallingBack() {
        val session = FakeBotSession(
            failOnOps = setOf(ScsiCommand.OP_READ_CAPACITY10),
            capacity16LastLba = 2047UL,
        )

        val geometry = UsbDisk.probeCapacity(session)

        assertEquals(2048UL, geometry.sectorCount)
        assertEquals(
            listOf(
                ScsiCommand.OP_READ_CAPACITY10,
                ScsiCommand.OP_REQUEST_SENSE,
                ScsiCommand.OP_READ_CAPACITY16,
            ),
            session.commands,
        )
    }

    @Test
    fun capacity16RetriesOnceAfterUnitAttention() {
        val session = FakeBotSession(
            capacityLastLba10 = 0xFFFF_FFFFUL,
            capacity16LastLba = 2047UL,
            unitAttentionBeforeSuccess = 1,
            unitAttentionOnOps = setOf(ScsiCommand.OP_READ_CAPACITY16),
        )

        val geometry = UsbDisk.probeCapacity(session)

        assertEquals(2048UL, geometry.sectorCount)
        assertEquals(
            listOf(
                ScsiCommand.OP_READ_CAPACITY10,
                ScsiCommand.OP_READ_CAPACITY16,
                ScsiCommand.OP_REQUEST_SENSE,
                ScsiCommand.OP_READ_CAPACITY16,
            ),
            session.commands,
        )
    }
}

class RecoveryOrderTest {
    @Test
    fun bulkOnlyResetOrderMatchesSpec() {
        val channel = RecordingChannel()
        Recovery.bulkOnlyReset(channel)
        assertEquals(
            listOf("massStorageReset", "clearHaltIn", "clearHaltOut"),
            channel.events,
        )
    }
}
