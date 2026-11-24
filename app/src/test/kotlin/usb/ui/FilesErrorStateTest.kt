package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesErrorStateTest {
    private val ready =
        AppUiState(
            presence = DevicePresence.Ready,
            device = DeviceIdentity("USB", 100uL, attachGeneration = 1L),
            partitions = listOf(PartitionTab("1", "DATA", readOnly = false)),
            selectedPartitionId = "1",
            currentPath = "/",
            filesListGeneration = 3L,
            filesListingState = FilesListingState.Loading,
        )

    private fun AppUiState.listed(
        path: String = currentPath,
        files: List<FileEntry> = emptyList(),
        generation: Long = filesListGeneration,
        identity: String? = device?.identity,
        partitionId: String = selectedPartitionId ?: "",
        attachGeneration: Long = device?.attachGeneration ?: 0L,
    ): UiIntent.FilesListed =
        UiIntent.FilesListed(path, files, generation, identity, partitionId, attachGeneration)

    private fun AppUiState.listFailed(
        path: String = currentPath,
        failure: OperationFailure? = null,
        diagnostic: String? = null,
        generation: Long = filesListGeneration,
        identity: String? = device?.identity,
        partitionId: String = selectedPartitionId ?: "",
        attachGeneration: Long = device?.attachGeneration ?: 0L,
    ): UiIntent.FilesListFailed =
        UiIntent.FilesListFailed(
            path = path,
            failure = failure,
            diagnostic = diagnostic,
            generation = generation,
            identity = identity,
            partitionId = partitionId,
            attachGeneration = attachGeneration,
        )

    @Test
    fun failureSetsTypedFailureIndependentOfLanguage() {
        val failed = UiReducer.reduce(
            ready,
            ready.listFailed(failure = OperationFailure.SafIo),
        ).state
        assertEquals(OperationFailure.SafIo, failed.filesFailure)
        assertEquals(UiMessage.Failure(OperationFailure.SafIo), failed.statusMessage)

        val failedAgain = UiReducer.reduce(
            ready,
            ready.listFailed(failure = OperationFailure.SafIo, diagnostic = "Read failed"),
        ).state
        assertEquals(OperationFailure.SafIo, failedAgain.filesFailure)
    }

    @Test
    fun unclassifiedNullFailureStillMarksFailed() {
        val failed = UiReducer.reduce(
            ready,
            ready.listFailed(failure = null, diagnostic = "engine boom"),
        ).state
        assertNull(failed.filesFailure)
        assertEquals(FilesListingState.Failed, failed.filesListingState)
        assertEquals(UiMessage.Key(UiMessageKey.FailedStatus), failed.statusMessage)
    }

    @Test
    fun deepPathFailureFallsBackToRootReload() {
        val deep = ready.copy(currentPath = "/docs", filesListingState = FilesListingState.Loading)
        val fallback =
            UiReducer.reduce(
                deep,
                deep.listFailed(
                    failure = OperationFailure.SafIo,
                    diagnostic = "Path not found: /docs",
                ),
            )
        assertEquals("/", fallback.state.currentPath)
        assertEquals(FilesListingState.Loading, fallback.state.filesListingState)
        assertNull(fallback.state.filesFailure)
        val load = fallback.effects.single() as UiEffect.LoadFiles
        assertEquals("/", load.path)
    }

    @Test
    fun deepPathIoErrorDoesNotFallback() {
        val deep = ready.copy(currentPath = "/docs", filesListingState = FilesListingState.Loading)
        val failed = UiReducer.reduce(
            deep,
            deep.listFailed(failure = OperationFailure.SafIo, diagnostic = "Read failed"),
        )
        assertEquals("/docs", failed.state.currentPath)
        assertEquals(OperationFailure.SafIo, failed.state.filesFailure)
        assertEquals(FilesListingState.Failed, failed.state.filesListingState)
        assertTrue(failed.effects.isEmpty())
    }

    @Test
    fun successAndNavigationClearTheError() {
        val failed = ready.copy(
            filesListingState = FilesListingState.Failed,
            filesFailure = OperationFailure.SafIo,
        )
        val listedState = UiReducer.reduce(failed, UiIntent.RefreshFiles).state
        assertNull(
            UiReducer.reduce(listedState, listedState.listed(files = emptyList())).state.filesFailure,
        )
        assertNull(
            UiReducer.reduce(failed, UiIntent.OpenDirectory("/other")).state.filesFailure,
        )
        assertNull(
            UiReducer.reduce(failed, UiIntent.DeviceChanged(DevicePresence.Disconnected)).state.filesFailure,
        )
    }

    @Test
    fun staleResultForAnotherPathIsIgnored() {
        val failed = ready.copy(filesFailure = OperationFailure.SafIo)
        assertEquals(
            OperationFailure.SafIo,
            UiReducer.reduce(failed, failed.listed(path = "/elsewhere", files = emptyList()))
                .state
                .filesFailure,
        )
        assertEquals(
            OperationFailure.SafIo,
            UiReducer.reduce(
                failed,
                failed.listFailed(path = "/elsewhere", failure = OperationFailure.ProviderRejected),
            )
                .state
                .filesFailure,
        )
    }
}
