package usb.device

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun botFailClosesSessionAfterSense() {
        val session = FakeBotSession(failAlways = true)
        val disk = UsbDisk(session, BlockGeometry(512, 100UL))
        val out = ByteBuffer.allocate(512)
        try {
            disk.read(0UL, out)
            fail("expected sense/io failure")
        } catch (e: DiskException) {
            assertTrue(disk.isClosed)
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
