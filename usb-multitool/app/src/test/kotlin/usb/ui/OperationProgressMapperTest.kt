package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import usb.service.OperationState as ServiceOperationState

class OperationProgressMapperTest {
    @Test fun partialPreservesProgressAndMessages() {
        val progress = readOnlyOperationProgress(
            ServiceOperationState.Partial, "scan", 4L, 10L, "saved", IllegalStateException("incomplete"), false,
        )
        assertEquals(OperationState.Partial, progress.state)
        assertEquals(4L, progress.bytesDone)
        assertEquals("saved", progress.resultMessage)
        assertEquals("incomplete", progress.errorMessage)
        assertEquals(false, progress.cancelable)
    }

    @Test fun failedUsesOutcomeCauseMessage() {
        val progress = readOnlyOperationProgress(
            ServiceOperationState.Failed(IllegalArgumentException("bad disk")), "scan", 4L, 10L, null, null, true,
        )
        assertEquals(OperationState.Failed, progress.state)
        assertEquals("bad disk", progress.errorMessage)
    }

    @Test fun completedPreservesResultAndProgress() {
        val progress = readOnlyOperationProgress(
            ServiceOperationState.Completed, "scan", 10L, 10L, "done", null, true,
        )
        assertEquals(OperationState.Completed, progress.state)
        assertEquals(10L, progress.bytesDone)
        assertEquals("done", progress.resultMessage)
    }
}
