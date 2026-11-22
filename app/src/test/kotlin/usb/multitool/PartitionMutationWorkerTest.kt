package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.partition.Partition

class PartitionMutationWorkerTest {
    private val live = listOf(Partition(2048UL, 4096UL, name = "DATA"))

    @Test
    fun deleteStaleGeometryRejectsBeforeWriter() {
        var writes = 0
        val error = runCatching {
            mutateConfirmedPartition(live, 2048UL, 4097UL) { writes++ }
        }.exceptionOrNull()
        assertTrue(error is usb.diagnostics.ConfirmationMismatchException)
        assertEquals(0, writes)
    }

    @Test
    fun formatChangedStartRejectsBeforeFormatter() {
        var formats = 0
        val error = runCatching {
            mutateConfirmedPartition(live, 2049UL, 4096UL) { formats++ }
        }.exceptionOrNull()
        assertTrue(error is usb.diagnostics.ConfirmationMismatchException)
        assertEquals(0, formats)
    }
}
