package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings

/** Reducer + confirmation contract for staged Ventoy global settings. */
class VentoySettingsUiTest {
    private fun ready(
        pending: List<VentoyControlSetting> = emptyList(),
    ) = AppUiState(
        presence = DevicePresence.Ready,
        device = DeviceIdentity(
            label = "Test Stick",
            capacityBytes = 8_000_000_000uL,
            attachGeneration = 1L,
        ),
        partitions = listOf(
            PartitionTab(
                id = "1",
                name = "Ventoy",
                readOnly = false,
                startLba = 2048uL,
                sectorCount = 4096uL,
                filesystemId = FilesystemId.FAT,
            ),
        ),
        selectedPartitionId = "1",
        diskSectorSize = 512,
        pendingVentoyControls = pending,
    )

    private val controls =
        listOf(
            VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"),
            VentoyControlSetting("CUSTOM_KEY", "kept"),
        )

    @Test
    fun installConfirmationSnapshotsStagedControls() {
        val requested =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL),
            ).state
        assertEquals(controls, requested.pendingConfirmation?.ventoyControls)

        val confirmed = UiReducer.reduce(requested, UiIntent.ConfirmDestructive)
        val effect = confirmed.effects.filterIsInstance<UiEffect.SubmitDestructive>().single()
        assertEquals(controls, effect.confirmation.ventoyControls)
        assertNull(confirmed.state.pendingConfirmation)
    }

    @Test
    fun updateWithoutStagedControlsKeepsEmptySnapshot() {
        val requested =
            UiReducer.reduce(
                ready(),
                UiIntent.RequestDestructive(DestructiveAction.VENTOY_UPDATE),
            ).state
        assertEquals(emptyList<VentoyControlSetting>(), requested.pendingConfirmation?.ventoyControls)
    }

    @Test
    fun partialInstallKeepsControlsForRetryAndCompletedClearsThem() {
        val partial =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Partial,
                        title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                        errorMessage = "settings write failed",
                    ),
                ),
            ).state
        assertEquals(controls, partial.pendingVentoyControls)

        val completed =
            UiReducer.reduce(
                partial,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Completed,
                        title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                    ),
                ),
            ).state
        assertEquals(emptyList<VentoyControlSetting>(), completed.pendingVentoyControls)
    }

    @Test
    fun applyEffectCarriesTheStagedSnapshot() {
        val reduced =
            UiReducer.reduce(ready(pending = controls), UiIntent.RequestApplyVentoyControls)
        val effect = reduced.effects.filterIsInstance<UiEffect.ApplyVentoyControls>().single()
        assertEquals(controls, effect.settings)
    }

    @Test
    fun stageReplacesSameKeyAndRejectsInvalidKnownValue() {
        val staged =
            UiReducer.reduce(
                ready(pending = listOf(VentoyControlSetting("KEY", "old"))),
                UiIntent.StageVentoyControl("KEY", "new"),
            ).state
        assertEquals(listOf(VentoyControlSetting("KEY", "new")), staged.pendingVentoyControls)

        val rejected =
            UiReducer.reduce(
                ready(),
                UiIntent.StageVentoyControl("VTOY_WIN11_BYPASS_CHECK", "2"),
            ).state
        assertEquals(emptyList<VentoyControlSetting>(), rejected.pendingVentoyControls)
        assertTrue(rejected.statusMessage is UiMessage.Raw)
    }

    @Test
    fun confirmationSnapshotDoesNotFollowLaterStagingOrDiscard() {
        val requested =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL),
            ).state
        val snapshot = requested.pendingConfirmation
        requireNotNull(snapshot)

        val restaged =
            UiReducer.reduce(
                requested,
                UiIntent.StageVentoyControl("OTHER_KEY", "new"),
            ).state
        assertEquals(controls, restaged.pendingConfirmation?.ventoyControls)
        assertEquals(1, restaged.pendingVentoyControls.count { it.key == "OTHER_KEY" })

        val discarded =
            UiReducer.reduce(
                restaged,
                UiIntent.DiscardStagedVentoyControl("CUSTOM_KEY"),
            ).state
        assertEquals(controls, discarded.pendingConfirmation?.ventoyControls)
        assertNull(discarded.pendingVentoyControls.firstOrNull { it.key == "CUSTOM_KEY" })

        val confirmed = UiReducer.reduce(discarded, UiIntent.ConfirmDestructive)
        val effect = confirmed.effects.filterIsInstance<UiEffect.SubmitDestructive>().single()
        assertEquals(controls, effect.confirmation.ventoyControls)
        assertEquals(
            "confirmation device identity must be the one snapshotted at request time",
            discarded.device,
            effect.confirmation.identity,
        )
    }

    @Test
    fun newAttachmentClearsStagedControlsButSameStickKeepsThem() {
        val same =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = DeviceIdentity(
                        label = "Test Stick",
                        capacityBytes = 8_000_000_000uL,
                        attachGeneration = 1L,
                    ),
                ),
            ).state
        assertEquals(controls, same.pendingVentoyControls)

        val replaced =
            UiReducer.reduce(
                same,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = DeviceIdentity(
                        label = "Other Stick",
                        capacityBytes = 16_000_000_000uL,
                        attachGeneration = 2L,
                    ),
                ),
            ).state
        assertEquals(emptyList<VentoyControlSetting>(), replaced.pendingVentoyControls)
    }

    @Test
    fun lateSettingsErrorCannotOverwriteNewerRead() {
        val base = ready()
        val first = UiReducer.reduce(base, UiIntent.LoadVentoyGlobalSettings)
        val firstToken = first.state.ventoyGlobalSettingsRequest
        val second = UiReducer.reduce(first.state, UiIntent.LoadVentoyGlobalSettings)
        val secondToken = second.state.ventoyGlobalSettingsRequest

        val fresh =
            UiReducer.reduce(
                second.state,
                UiIntent.VentoyGlobalSettingsLoaded(
                    VentoyGlobalSettings.Present(emptyList()),
                    epoch = 0L,
                    requestToken = secondToken,
                ),
            ).state
        assertEquals(VentoyGlobalSettings.Present(emptyList()), fresh.ventoyGlobalSettings)

        val late =
            UiReducer.reduce(
                fresh,
                UiIntent.VentoyGlobalSettingsLoaded(
                    VentoyGlobalSettings.ReadError("old failure"),
                    epoch = 0L,
                    requestToken = firstToken,
                ),
            ).state
        assertEquals(VentoyGlobalSettings.Present(emptyList()), late.ventoyGlobalSettings)
        assertNull(late.statusMessage)
    }

    @Test
    fun removalOfANonPatternKeyIsStaged() {
        val reduced =
            UiReducer.reduce(
                ready(),
                UiIntent.StageVentoyControl("MY KEY", null),
            ).state
        assertEquals(
            listOf(VentoyControlSetting("MY KEY", null)),
            reduced.pendingVentoyControls,
        )
        assertNull(reduced.statusMessage)
    }

    @Test
    fun retryAfterPartialAppliesTheSameSnapshot() {
        val partial =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Partial,
                        title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                        errorMessage = "settings write failed",
                    ),
                ),
            ).state
        val retry = UiReducer.reduce(partial, UiIntent.RequestApplyVentoyControls)
        val effect = retry.effects.filterIsInstance<UiEffect.ApplyVentoyControls>().single()
        assertEquals(controls, effect.settings)

        val retried =
            UiReducer.reduce(
                retry.state,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Completed,
                        title = OperationTitle.SetVentoyGlobalSetting,
                    ),
                ),
            ).state
        assertEquals(emptyList<VentoyControlSetting>(), retried.pendingVentoyControls)
    }

    @Test
    fun damagedLayoutReadStaysLocalizedAndDoesNotLeakRawStatus() {
        val reduced =
            UiReducer.reduce(
                ready(),
                UiIntent.VentoyGlobalSettingsLoaded(
                    VentoyGlobalSettings.ReadError(
                        "Ventoy layout is present but invalid",
                        damagedLayout = true,
                    ),
                    epoch = 0L,
                ),
            ).state
        assertEquals(
            VentoyGlobalSettings.ReadError(
                "Ventoy layout is present but invalid",
                damagedLayout = true,
            ),
            reduced.ventoyGlobalSettings,
        )
        assertNull("damaged-layout copy is localized in the card", reduced.statusMessage)
    }

    @Test
    fun readErrorStaysDistinctFromMissingAndEmpty() {
        val error =
            UiReducer.reduce(
                ready(),
                UiIntent.VentoyGlobalSettingsLoaded(
                    VentoyGlobalSettings.ReadError("not valid JSON"),
                    epoch = 0L,
                ),
            ).state
        assertEquals(VentoyGlobalSettings.ReadError("not valid JSON"), error.ventoyGlobalSettings)
        assertEquals(UiMessage.Raw("not valid JSON"), error.statusMessage)

        val missing =
            UiReducer.reduce(
                error,
                UiIntent.VentoyGlobalSettingsLoaded(VentoyGlobalSettings.Missing, epoch = 0L),
            ).state
        assertEquals(VentoyGlobalSettings.Missing, missing.ventoyGlobalSettings)
    }
}
