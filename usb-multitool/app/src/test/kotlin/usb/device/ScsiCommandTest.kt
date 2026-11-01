package usb.device

import org.junit.Assert.assertEquals
import org.junit.Test

class ScsiCommandTest {
    @Test fun largeLbaUsesSixteenByteCommand() {
        val width = ScsiCommand.select(lba = 1UL shl 32, blocks = 1)
        assertEquals(ScsiWidth.CDB16, width)
    }

    @Test fun transferPlanStartsAt64KiBAndAlignsRemainder() {
        val parts = TransferPlan.plan(bytes = 70L * 1024L, sectorSize = 4096)
        assertEquals(listOf(64 * 1024, 6 * 1024), parts)
    }
}
