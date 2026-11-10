package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbiguousLayoutTest {
    private val device =
        DeviceIdentity(label = "Kingston DataTraveler", capacityBytes = 15_728_640_000uL)

    /** Write-protected ESP: the EFI override is exactly what could unlock it. */
    private val esp =
        PartitionTab(
            "1",
            "VTOYEFI",
            readOnly = true,
            isEfiType = true,
            startLba = 2048uL,
            sectorCount = 4096uL,
        )

    private fun ambiguousState(): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = listOf(esp),
            selectedPartitionId = "1",
            partitionTableAmbiguous = true,
            // Override deliberately bound to the exact partition under test.
            vtoyEfiWritePartitionId = "1",
            vtoyEfiWriteStartLba = esp.startLba,
            vtoyEfiWriteSectorCount = esp.sectorCount,
        )

    @Test
    fun efiOverrideCannotUnlockAnAmbiguousLayout() {
        val state = ambiguousState()
        assertTrue("the EFI override alone would allow writes", state.isEfiWriteAllowed(esp))

        assertEquals(
            UiMessage.Key(UiMessageKey.PartitionTableAmbiguous),
            UiReducer.writeBlockMessage(state),
        )
    }

    @Test
    fun destructiveRequestsStayBlockedDespiteEfiOverride() {
        val layoutActions =
            listOf(
                DestructiveAction.FORMAT_PARTITION,
                DestructiveAction.DELETE_PARTITION,
                DestructiveAction.CREATE_PARTITION,
                DestructiveAction.SET_PARTITION_ROLE,
                DestructiveAction.SET_PARTITION_BOOTABLE,
                DestructiveAction.GPT_REPAIR,
            )
        for (action in layoutActions) {
            val result = UiReducer.reduce(ambiguousState(), UiIntent.RequestDestructive(action))
            assertNull("$action must not reach confirmation", result.state.pendingConfirmation)
            assertEquals(
                UiMessage.Key(UiMessageKey.PartitionTableAmbiguous),
                result.state.statusMessage,
            )
        }
    }

    @Test
    fun confirmedLayoutMutationIsDropped() {
        val forced =
            ambiguousState().copy(
                pendingConfirmation =
                    DestructiveConfirmation.request(
                        device,
                        DestructiveAction.FORMAT_PARTITION,
                        target = esp,
                    ),
            )

        val result = UiReducer.reduce(forced, UiIntent.ConfirmDestructive)

        assertNull(result.state.pendingConfirmation)
    }

    @Test
    fun createRequestStaysBlocked() {
        val result =
            UiReducer.reduce(
                ambiguousState().copy(freeRegions = listOf(FreeRegionUi(8192uL, 4096uL))),
                UiIntent.RequestCreatePartition(8192uL, 2048uL),
            )

        assertNull(result.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.PartitionTableAmbiguous),
            result.state.statusMessage,
        )
    }
}
