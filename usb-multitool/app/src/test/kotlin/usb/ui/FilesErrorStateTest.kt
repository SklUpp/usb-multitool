package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilesErrorStateTest {
    private val ready =
        AppUiState(
            presence = DevicePresence.Ready,
            device = DeviceIdentity("USB", 100uL),
            partitions = listOf(PartitionTab("1", "DATA", readOnly = false)),
            selectedPartitionId = "1",
            currentPath = "/docs",
        )

    @Test
    fun failureSetsStructuredErrorIndependentOfLanguage() {
        val failed = UiReducer.reduce(
            ready,
            UiIntent.FilesListFailed("/docs", "Не удалось прочитать папку"),
        ).state
        assertEquals("Не удалось прочитать папку", failed.filesError)

        val failedEnglish = UiReducer.reduce(
            ready,
            UiIntent.FilesListFailed("/docs", "Read failed"),
        ).state
        assertEquals("Read failed", failedEnglish.filesError)
    }

    @Test
    fun successAndNavigationClearTheError() {
        val failed = ready.copy(filesError = "boom")
        assertNull(
            UiReducer.reduce(failed, UiIntent.FilesListed("/docs", emptyList()))
                .state
                .filesError,
        )
        assertNull(
            UiReducer.reduce(failed, UiIntent.OpenDirectory("/other"))
                .state
                .filesError,
        )
        assertNull(
            UiReducer.reduce(failed, UiIntent.NavigateUp)
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
            UiReducer.reduce(failed, UiIntent.FilesListed("/elsewhere", emptyList()))
                .state
                .filesError,
        )
        assertEquals(
            "boom",
            UiReducer.reduce(failed, UiIntent.FilesListFailed("/elsewhere", "late"))
                .state
                .filesError,
        )
    }
}
