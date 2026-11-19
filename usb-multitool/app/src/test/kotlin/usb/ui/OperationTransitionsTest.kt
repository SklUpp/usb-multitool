package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.service.OperationTokenGenerator

class OperationTransitionsTest {
    private val device = DeviceIdentity("Test stick", 8_000_000_000uL, serialNumber = "SN-1", attachGeneration = 4L)
    private val partition = PartitionTab("1", "DATA", readOnly = false, startLba = 2048uL, sectorCount = 100_000uL)

    private fun ready() = AppUiState(
        presence = DevicePresence.Ready,
        device = device,
        partitions = listOf(partition),
        selectedPartitionId = partition.id,
        tab = ShellTab.Files,
        files = listOf(FileEntry("old.bin", isDirectory = false, sizeBytes = 1uL)),
        filesListingState = FilesListingState.Loaded,
    )

    @Test
    fun completedWritePublishesSuccessAndReloadsFiles() {
        val token = OperationTokenGenerator.next()
        val running = UiReducer.reduce(
            ready(),
            UiIntent.OperationBegan(OperationProgress(OperationState.Running, OperationTitle.Upload, token)),
        )
        val completedUpdate = UiReducer.reduce(
            running.state,
            UiIntent.OperationUpdated(OperationProgress(OperationState.Completed, OperationTitle.Upload, token)),
        )
        val completed = UiReducer.reduce(
            completedUpdate.state,
            UiIntent.OperationCompletionEffects(
                token = token,
                attachmentGeneration = device.attachGeneration,
                title = OperationTitle.Upload,
                state = OperationState.Completed,
            ),
        )

        assertEquals(UiMessage.Key(UiMessageKey.Success), completedUpdate.state.statusMessage)
        assertEquals(FilesListingState.Loading, completed.state.filesListingState)
        assertTrue(completed.state.files.isEmpty())
        assertEquals(listOf(UiEffect.LoadFiles("/")), completed.effects)
    }

    @Test
    fun staleUpdateAndCompletionDoNotChangeStateOrEffects() {
        val currentToken = OperationTokenGenerator.next()
        val staleToken = OperationTokenGenerator.next()
        val running = UiReducer.reduce(
            ready(),
            UiIntent.OperationBegan(OperationProgress(OperationState.Running, OperationTitle.Upload, currentToken)),
        )
        val failed = UiReducer.reduce(
            running.state,
            UiIntent.OperationUpdated(
                OperationProgress(
                    OperationState.Failed,
                    OperationTitle.Upload,
                    currentToken,
                    errorMessage = "disk read failed",
                ),
            ),
        )
        assertEquals(UiMessage.Raw("disk read failed"), failed.state.statusMessage)
        val stale = UiReducer.reduce(
            running.state,
            UiIntent.OperationUpdated(OperationProgress(OperationState.Failed, OperationTitle.Upload, staleToken, errorMessage = "old")),
        )
        val staleCompletion = UiReducer.reduce(
            running.state,
            UiIntent.OperationCompletionEffects(
                token = currentToken,
                attachmentGeneration = device.attachGeneration - 1L,
                title = OperationTitle.Upload,
                state = OperationState.Completed,
            ),
        )

        assertEquals(running.state, stale.state)
        assertTrue(stale.effects.isEmpty())
        assertEquals(running.state, staleCompletion.state)
        assertTrue(staleCompletion.effects.isEmpty())
    }

    @Test
    fun closeDiagnosticAfterNewOperationIsAppendOnly() {
        val newToken = OperationTokenGenerator.next()
        val current = UiReducer.reduce(
            ready(),
            UiIntent.OperationBegan(OperationProgress(OperationState.Queued, OperationTitle.ReadScan, newToken)),
        )
        val diagnostic = UiReducer.reduce(
            current.state,
            UiIntent.AppendLog("Volume close/sync unconfirmed: old close failure"),
        )

        assertEquals(current.state.operation, diagnostic.state.operation)
        assertEquals(current.state.statusMessage, diagnostic.state.statusMessage)
        assertEquals(listOf("Volume close/sync unconfirmed: old close failure"), diagnostic.state.logLines)
        assertTrue(diagnostic.effects.isEmpty())
    }

    @Test
    fun cancelUsesCurrentCancelableTokenOnly() {
        val token = OperationTokenGenerator.next()
        val running = UiReducer.reduce(
            ready(),
            UiIntent.OperationBegan(
                OperationProgress(OperationState.Running, OperationTitle.Upload, token, cancelable = true),
            ),
        )
        val cancelled = UiReducer.reduce(running.state, UiIntent.CancelOperation(token))
        val stale = UiReducer.reduce(running.state, UiIntent.CancelOperation(OperationTokenGenerator.next()))

        assertEquals(listOf(UiEffect.CancelOperation(token)), cancelled.effects)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun operationScopedLogResetsOnNewOperationAndKeepsJournalHistory() {
        val first = UiReducer.reduce(ready(), UiIntent.AppendLog("device ready")).state
        val oldOp = UiReducer.reduce(first, UiIntent.AppendLog("old operation line")).state
        val token = OperationTokenGenerator.next()
        val begun = UiReducer.reduce(
            oldOp,
            UiIntent.OperationBegan(OperationProgress(OperationState.Running, OperationTitle.Upload, token)),
        ).state
        val running = UiReducer.reduce(begun, UiIntent.AppendLog("new operation line")).state

        assertEquals(listOf("device ready", "old operation line"), begun.logLines)
        assertEquals(emptyList<String>(), begun.operationLogLines)
        assertEquals(listOf("device ready", "old operation line", "new operation line"), running.logLines)
        assertEquals(listOf("new operation line"), running.operationLogLines)
    }
}
