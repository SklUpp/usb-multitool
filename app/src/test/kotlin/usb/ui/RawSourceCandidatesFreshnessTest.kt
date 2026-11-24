package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.service.OperationTokenGenerator

class RawSourceCandidatesFreshnessTest {
    private val device =
        DeviceIdentity("SanDisk", 8_000_000_000uL, serialNumber = "SN-1", attachGeneration = 3L)
    private val part = PartitionTab("1", "DATA", readOnly = false)

    private fun ready(
        generation: Long = 5L,
        status: UiMessage = UiMessage.Raw("op-B-running"),
        operation: OperationProgress? = null,
    ) =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = listOf(part),
            selectedPartitionId = part.id,
            rawSourceCandidatesGeneration = generation,
            rawSourceCandidatesIdentity = device.identity,
            operation = operation,
        ).withStatus(status)

    @Test
    fun matchingFailureSetsBoundErrorAndStatusWhenIdle() {
        val base = ready(status = UiMessage.Raw("idle"))
        val failed =
            UiReducer.reduce(
                base,
                UiIntent.RawSourceCandidatesFailed(
                    generation = 5L,
                    identity = device.identity,
                    failure = OperationFailure.SafIo,
                    diagnostic = "chooser I/O",
                ),
            )
        assertEquals(OperationFailure.SafIo, failed.state.rawSourceCandidatesFailure)
        assertEquals(UiMessage.Failure(OperationFailure.SafIo), failed.state.statusMessage)
        assertEquals(emptyList<RawSourceCandidate>(), failed.state.rawSourceCandidates)
    }

    @Test
    fun matchingFailureWhileMutationBRunningKeepsBStatusLogsBound() {
        val bRunning =
            OperationProgress(
                state = OperationState.Running,
                token = OperationTokenGenerator.next(),
                title = OperationTitle.Destructive(DestructiveAction.WIPE),
            )
        val base =
            ready(
                status = UiMessage.Raw("op-B-running"),
                operation = bRunning,
            )
        val failed =
            UiReducer.reduce(
                base,
                UiIntent.RawSourceCandidatesFailed(
                    generation = 5L,
                    identity = device.identity,
                    failure = OperationFailure.SafIo,
                    diagnostic = "late chooser A",
                ),
            )
        // Bound chooser still gets the error.
        assertEquals(OperationFailure.SafIo, failed.state.rawSourceCandidatesFailure)
        assertEquals(emptyList<RawSourceCandidate>(), failed.state.rawSourceCandidates)
        // Global status stays with mutation B.
        assertEquals(UiMessage.Raw("op-B-running"), failed.state.statusMessage)
        assertTrue(failed.state.logLines.any { it.contains("late chooser A") })
    }

    @Test
    fun matchingFailureWhileQueuedKeepsStatus() {
        val queued =
            OperationProgress(
                state = OperationState.Queued,
                token = OperationTokenGenerator.next(),
                title = OperationTitle.FormatTarget("1"),
            )
        val base = ready(status = UiMessage.Raw("queued-B"), operation = queued)
        val failed =
            UiReducer.reduce(
                base,
                UiIntent.RawSourceCandidatesFailed(
                    generation = 5L,
                    identity = device.identity,
                    failure = OperationFailure.Cancelled,
                    diagnostic = "chooser cancel",
                ),
            )
        assertEquals(OperationFailure.Cancelled, failed.state.rawSourceCandidatesFailure)
        assertEquals(UiMessage.Raw("queued-B"), failed.state.statusMessage)
        assertTrue(failed.state.logLines.any { "chooser cancel" in it })
    }

    @Test
    fun staleFailureDoesNotClobberStatusOfBLogsOnly() {
        val base = ready(generation = 6L, status = UiMessage.Raw("op-B-running"))
        val stale =
            UiReducer.reduce(
                base,
                UiIntent.RawSourceCandidatesFailed(
                    generation = 5L,
                    identity = device.identity,
                    failure = OperationFailure.SafIo,
                    diagnostic = "late chooser A",
                ),
            )
        assertNull(stale.state.rawSourceCandidatesFailure)
        assertEquals(UiMessage.Raw("op-B-running"), stale.state.statusMessage)
        assertTrue(stale.state.logLines.any { it.contains("stale") && it.contains("late chooser A") })
    }

    @Test
    fun staleFailureWrongIdentityIgnoredLogs() {
        val base = ready()
        val stale =
            UiReducer.reduce(
                base,
                UiIntent.RawSourceCandidatesFailed(
                    generation = 5L,
                    identity = "other#serial",
                    failure = OperationFailure.SafIo,
                    diagnostic = "wrong stick",
                ),
            )
        assertNull(stale.state.rawSourceCandidatesFailure)
        assertEquals(UiMessage.Raw("op-B-running"), stale.state.statusMessage)
        assertTrue(stale.state.logLines.any { it.contains("wrong stick") })
    }

    @Test
    fun loadClearsPriorError() {
        val dirty = ready().copy(rawSourceCandidatesFailure = OperationFailure.SafIo)
        val loaded = UiReducer.reduce(dirty, UiIntent.LoadRawSourceCandidates)
        assertNull(loaded.state.rawSourceCandidatesFailure)
        assertTrue(loaded.effects.any { it is UiEffect.LoadRawSources })
    }
}
