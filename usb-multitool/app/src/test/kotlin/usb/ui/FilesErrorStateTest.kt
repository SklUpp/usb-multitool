package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        message: String,
        generation: Long = filesListGeneration,
        identity: String? = device?.identity,
        partitionId: String = selectedPartitionId ?: "",
        attachGeneration: Long = device?.attachGeneration ?: 0L,
    ): UiIntent.FilesListFailed =
        UiIntent.FilesListFailed(path, message, generation, identity, partitionId, attachGeneration)

    @Test
    fun failureSetsStructuredErrorIndependentOfLanguage() {
        val failed = UiReducer.reduce(
            ready,
            ready.listFailed(message = "Не удалось прочитать папку"),
        ).state
        assertEquals("Не удалось прочитать папку", failed.filesError)

        val failedEnglish = UiReducer.reduce(
            ready,
            ready.listFailed(message = "Read failed"),
        ).state
        assertEquals("Read failed", failedEnglish.filesError)
    }

    @Test
    fun deepPathFailureFallsBackToRootReload() {
        val deep = ready.copy(currentPath = "/docs", filesPending = true)
        val fallback =
            UiReducer.reduce(deep, deep.listFailed(message = "Path not found: /docs"))
        assertEquals("/", fallback.state.currentPath)
        assertTrue(fallback.state.filesPending)
        assertNull(fallback.state.filesError)
        val load = fallback.effects.single() as UiEffect.LoadFiles
        assertEquals("/", load.path)
    }

    @Test
    fun deepPathIoErrorDoesNotFallback() {
        val deep = ready.copy(currentPath = "/docs", filesPending = true)
        val failed = UiReducer.reduce(deep, deep.listFailed(message = "Read failed"))
        assertEquals("/docs", failed.state.currentPath)
        assertEquals("Read failed", failed.state.filesError)
        assertFalse(failed.state.filesPending)
        assertTrue(failed.effects.isEmpty())
    }

    @Test
    fun successAndNavigationClearTheError() {
        val failed = ready.copy(filesError = "boom")
        assertNull(
            UiReducer.reduce(failed, failed.listed(files = emptyList()))
                .state
                .filesError,
        )
        assertNull(
            UiReducer.reduce(failed, UiIntent.OpenDirectory("/other"))
                .state
                .filesError,
        )
        assertNull(
            UiReducer.reduce(failed, UiIntent.DeviceChanged(DevicePresence.Disconnected))
                .state
                .filesError,
        )
    }

    @Test
    fun staleResultForAnotherPathIsIgnored() {
        val failed = ready.copy(filesError = "boom")
        assertEquals(
            "boom",
            UiReducer.reduce(failed, failed.listed(path = "/elsewhere", files = emptyList()))
                .state
                .filesError,
        )
        assertEquals(
            "boom",
            UiReducer.reduce(failed, failed.listFailed(path = "/elsewhere", message = "late"))
                .state
                .filesError,
        )
    }
}
