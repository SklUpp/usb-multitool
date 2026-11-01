package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationTest {
    private val device =
        DeviceIdentity(
            label = "SanDisk Ultra USB 3.0 (USB\u00A01234:5678)",
            capacityBytes = 31_457_280_000uL,
        )

    @Test
    fun destructiveActionIncludesIdentityAndCapacity() {
        val request = DestructiveConfirmation.request(device, DestructiveAction.WIPE)
        assertTrue(request.message.contains(device.identity))
        assertTrue(request.message.contains(device.capacityLabel))
        assertEquals("wipe", request.action)
        assertEquals(device, request.identity)
    }

    @Test
    fun everyDestructiveActionEmbedsDeviceFacts() {
        DestructiveAction.entries.forEach { action ->
            val request = DestructiveConfirmation.request(device, action)
            assertTrue(action.name, request.message.contains(device.identity))
            assertTrue(action.name, request.message.contains(device.capacityLabel))
            assertTrue(action.name, request.message.contains(action.displayName()))
        }
    }
}

class UiStateMachineTest {
    private val device =
        DeviceIdentity(label = "Kingston DataTraveler", capacityBytes = 15_728_640_000uL)

    private val partitions =
        listOf(
            PartitionTab("1", "VENTOY", readOnly = false),
            PartitionTab("2", "VTOYEFI", readOnly = true, isVtoyEfi = true),
        )

    private fun ready(): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = partitions,
            selectedPartitionId = "1",
            files =
                listOf(
                    FileEntry("ubuntu.iso", isDirectory = false, sizeBytes = 2_000_000_000uL),
                    FileEntry("docs", isDirectory = true, sizeBytes = 0uL),
                ),
        )

    @Test
    fun absentDeviceBlocksDestructiveConfirm() {
        val result =
            UiReducer.reduce(
                AppUiState(presence = DevicePresence.Absent),
                UiIntent.RequestDestructive(DestructiveAction.WIPE),
            )
        assertNull(result.state.pendingConfirmation)
        assertEquals("No USB mass-storage device", result.state.statusMessage)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun permissionRequiredBlocksActions() {
        val state =
            AppUiState(
                presence = DevicePresence.PermissionRequired,
                device = device,
            )
        assertFalse(state.actionsEnabled)
        val result =
            UiReducer.reduce(state, UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE))
        assertNull(result.state.pendingConfirmation)
        assertEquals("USB permission required", result.state.statusMessage)
    }

    @Test
    fun busyAndRunningDisableActions() {
        val running =
            ready().copy(
                presence = DevicePresence.Busy,
                operation =
                    OperationProgress(
                        state = OperationState.Running,
                        label = "raw write",
                        bytesDone = 1000,
                        bytesTotal = 5000,
                        bytesPerSecond = 100,
                    ),
            )
        assertFalse(running.actionsEnabled)
        assertTrue(running.isRunning)

        val blocked =
            UiReducer.reduce(running, UiIntent.RequestDestructive(DestructiveAction.WIPE))
        assertNull(blocked.state.pendingConfirmation)
        assertEquals("Busy: wait for the running operation", blocked.state.statusMessage)
    }

    @Test
    fun successCancellationFailureAndPartialSurfaceTruthfully() {
        val base = ready()
        val completed =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(OperationState.Completed, "wipe"),
                ),
            )
        assertEquals("Success", completed.state.statusMessage)

        val cancelled =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(OperationState.Cancelled, "wipe"),
                ),
            )
        assertEquals("Cancelled", cancelled.state.statusMessage)

        val failed =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        OperationState.Failed,
                        "wipe",
                        errorMessage = "I/O error",
                    ),
                ),
            )
        assertEquals("I/O error", failed.state.statusMessage)

        val partial =
            UiReducer.reduce(
                base,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        OperationState.Partial,
                        "backup",
                        errorMessage = "Partial rescue map retained",
                    ),
                ),
            )
        assertEquals("Partial rescue map retained", partial.state.statusMessage)
    }

    @Test
    fun disconnectClearsPendingConfirmation() {
        val pending =
            ready().copy(
                pendingConfirmation =
                    DestructiveConfirmation.request(device, DestructiveAction.WIPE),
            )
        val result =
            UiReducer.reduce(
                pending,
                UiIntent.DeviceChanged(presence = DevicePresence.Disconnected),
            )
        assertEquals(DevicePresence.Disconnected, result.state.presence)
        assertNull(result.state.pendingConfirmation)
        assertEquals("Device disconnected", result.state.statusMessage)
    }

    @Test
    fun confirmEmitsSubmitEffectWithIdentityBoundAction() {
        val withConfirm =
            UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.WIPE))
        assertNotNull(withConfirm.state.pendingConfirmation)
        assertTrue(
            withConfirm.state.pendingConfirmation!!.message.contains(device.capacityLabel),
        )

        val confirmed = UiReducer.reduce(withConfirm.state, UiIntent.ConfirmDestructive)
        assertNull(confirmed.state.pendingConfirmation)
        assertEquals(1, confirmed.effects.size)
        assertEquals(UiEffect.Kind.SubmitDestructive, confirmed.effects.single().kind)
        assertEquals(DestructiveAction.WIPE, confirmed.effects.single().action)
    }

    @Test
    fun isoActionsExposeLinkXmlAndSendToRawWriter() {
        val link =
            UiReducer.reduce(
                ready(),
                UiIntent.IsoActionRequested("ubuntu.iso", IsoAction.LinkXml),
            )
        assertEquals(UiEffect.Kind.LinkXml, link.effects.single().kind)
        assertEquals("ubuntu.iso", link.effects.single().isoName)

        val raw =
            UiReducer.reduce(
                ready(),
                UiIntent.IsoActionRequested("ubuntu.iso", IsoAction.SendToRawWriter),
            )
        assertEquals(UiEffect.Kind.SendToRawWriter, raw.effects.single().kind)
        assertEquals(ShellTab.Partitions, raw.state.tab)
        assertTrue(raw.state.statusMessage!!.contains("ubuntu.iso"))
    }

    @Test
    fun isoActionsBlockedWhenDisconnected() {
        val state = ready().copy(presence = DevicePresence.Disconnected)
        val result =
            UiReducer.reduce(
                state,
                UiIntent.IsoActionRequested("ubuntu.iso", IsoAction.LinkXml),
            )
        assertTrue(result.effects.isEmpty())
        assertEquals("Device disconnected", result.state.statusMessage)
    }
}
