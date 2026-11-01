package usb.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScsiCommandTest {
    @Test
    fun largeLbaUsesSixteenByteCommand() {
        val width = ScsiCommand.select(lba = 1UL shl 32, blocks = 1)
        assertEquals(ScsiWidth.CDB16, width)
    }

    @Test
    fun largeBlockCountUsesSixteenByteCommand() {
        val width = ScsiCommand.select(lba = 0UL, blocks = 0x10000)
        assertEquals(ScsiWidth.CDB16, width)
    }

    @Test
    fun smallTransferUsesTenByteCommand() {
        val width = ScsiCommand.select(lba = 100UL, blocks = 8)
        assertEquals(ScsiWidth.CDB10, width)
    }

    @Test
    fun transferPlanStartsAt64KiBAndAlignsRemainder() {
        val parts = TransferPlan.plan(bytes = 70L * 1024L, sectorSize = 2048)
        assertEquals(listOf(64 * 1024, 6 * 1024), parts)
    }

    @Test
    fun transferPlanRejectsMisalignedByteCount() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferPlan.plan(bytes = 100L, sectorSize = 512)
        }
    }

    @Test
    fun readCapacity16ServiceActionEncoded() {
        val cdb = ScsiCommand.readCapacity16(32)
        assertEquals(ScsiCommand.OP_READ_CAPACITY16, cdb.bytes[0].toInt() and 0xFF)
        assertEquals(ScsiCommand.SERVICE_ACTION_READ_CAPACITY16, cdb.bytes[1].toInt() and 0xFF)
        assertEquals(ScsiDirection.IN, cdb.direction)
        assertEquals(32, cdb.transferLength)
    }

    @Test
    fun synchronizeCache10HasNoDataPhase() {
        val cdb = ScsiCommand.synchronizeCache10()
        assertEquals(ScsiCommand.OP_SYNCHRONIZE_CACHE10, cdb.bytes[0].toInt() and 0xFF)
        assertEquals(ScsiDirection.NONE, cdb.direction)
        assertEquals(0, cdb.transferLength)
    }
}
