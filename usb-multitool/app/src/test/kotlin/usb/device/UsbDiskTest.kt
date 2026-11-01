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
    fun cswPassedWithResidueThrowsShortTransfer() {
        val session = FakeBotSession(residue = 128)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        val ex = assertThrows(DiskException.IoFailure::class.java) {
            disk.read(0UL, out)
        }
        assertEquals("short transfer residue=128", ex.message)
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
    fun synchronizeCacheTimeoutIs30000Ms() {
        assertEquals(30_000, BotConstants.SYNCHRONIZE_CACHE_TIMEOUT_MS)
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
