package usb.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbDiskSessionTest {
    @Test
    fun transportFailurePropagatesBeforeCapacityProbe() {
        val session = FakeBotSession(stallsBeforeSuccess = 1)
        assertThrows(DiskException.IoFailure::class.java) {
            UsbDiskSession.initialize(session)
        }
        assertEquals(listOf(ScsiCommand.OP_TEST_UNIT_READY), session.commands)
    }

    @Test
    fun failedRequestSensePropagates() {
        val session = FakeBotSession(
            failOnOps = setOf(ScsiCommand.OP_TEST_UNIT_READY, ScsiCommand.OP_REQUEST_SENSE),
        )
        assertThrows(DiskException.IoFailure::class.java) {
            UsbDiskSession.initialize(session)
        }
        assertEquals(
            listOf(ScsiCommand.OP_TEST_UNIT_READY, ScsiCommand.OP_REQUEST_SENSE),
            session.commands,
        )
    }

    @Test
    fun unitAttentionSenseAllowsInitialization() {
        val session = FakeBotSession(unitAttentionBeforeSuccess = 1)
        UsbDiskSession.initialize(session)
        assertEquals(
            listOf(ScsiCommand.OP_TEST_UNIT_READY, ScsiCommand.OP_REQUEST_SENSE),
            session.commands,
        )
    }

    @Test
    fun descriptorUnitAttentionSenseAllowsInitialization() {
        val session = FakeBotSession(
            failOnOps = setOf(ScsiCommand.OP_TEST_UNIT_READY),
            requestSenseError = DiskException.SenseError(0x72, 0x06, 0x29, 0x00),
        )
        UsbDiskSession.initialize(session)
        assertEquals(listOf(ScsiCommand.OP_TEST_UNIT_READY, ScsiCommand.OP_REQUEST_SENSE), session.commands)
    }

    @Test
    fun becomingReadySenseClassification() {
        val sense = DiskException.SenseError(0x70, 0x02, 0x04, 0x01)
        assertEquals(false, sense.isUnitAttention)
        assertEquals(0x04, sense.asc)
    }

    @Test
    fun becomingReadySenseAllowsInitialization() {
        val session = FakeBotSession(
            failOnOps = setOf(ScsiCommand.OP_TEST_UNIT_READY),
            requestSenseError = DiskException.SenseError(0x70, 0x02, 0x04, 0x01),
        )
        UsbDiskSession.initialize(session)
        assertEquals(listOf(ScsiCommand.OP_TEST_UNIT_READY, ScsiCommand.OP_REQUEST_SENSE), session.commands)
    }

}
