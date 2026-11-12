package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoySettingsSource

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
    fun selectingTheStandardFileOnTheStickKeepsDefaultSemantics() {
        val selected =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.SelectVentoySettingsUsbFile(
                    path = "/VENTOY/Ventoy.JSON",
                    identity = "Test Stick",
                    attachGeneration = 1L,
                ),
            ).state
        assertEquals(
            "the standard file is the same default file, not a custom path",
            VentoySettingsSource.Default,
            selected.ventoySettingsSource,
        )
        assertEquals(
            "re-selecting the same standard file must not drop staged controls",
            controls,
            selected.pendingVentoyControls,
        )

        // Even if an older state still holds a UsbFile wrapper for the standard path, the
        // install snapshot and completion must use default semantics instead of dropping
        // staged controls.
        val direct =
            ready(pending = controls).copy(
                ventoySettingsSource = VentoySettingsSource.UsbFile("/ventoy/ventoy.json"),
            )
        val requested =
            UiReducer.reduce(
                direct,
                UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL),
            ).state
        assertEquals(controls, requested.pendingConfirmation?.ventoyControls)

        val completed =
            UiReducer.reduce(
                requested,
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Completed,
                        title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                    ),
                ),
            ).state
        assertEquals(
            "default-path updates must clear written controls",
            emptyList<VentoyControlSetting>(),
            completed.pendingVentoyControls,
        )
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

    @Test
    fun selectingAStickFilePinsSourceDropsStagedEditsAndReadsExactlyThatFile() {
        val selected =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.SelectVentoySettingsUsbFile(
                    path = "/custom/ventoy-config.json",
                    identity = "Test Stick",
                    attachGeneration = 1L,
                ),
            )
        assertEquals(
            VentoySettingsSource.UsbFile("/custom/ventoy-config.json"),
            selected.state.ventoySettingsSource,
        )
        assertEquals("Test Stick", selected.state.ventoySettingsSourceIdentity)
        assertEquals(1L, selected.state.ventoySettingsSourceAttachGeneration)
        assertNull(selected.state.ventoyGlobalSettings)
        assertEquals(emptyList<VentoyControlSetting>(), selected.state.pendingVentoyControls)
        assertTrue(selected.state.ventoySettingsSourceLoading)
        val effect =
            selected.effects.filterIsInstance<UiEffect.LoadVentoyGlobalSettings>().single()
        assertEquals(
            VentoySettingsSource.UsbFile("/custom/ventoy-config.json"),
            effect.source,
        )
    }

    @Test
    fun aFilePinnedToAnotherAttachmentIsRefusedWithoutARead() {
        val reduced =
            UiReducer.reduce(
                ready(),
                UiIntent.SelectVentoySettingsUsbFile(
                    path = "/custom/ventoy-config.json",
                    identity = "Other Stick",
                    attachGeneration = 2L,
                ),
            )
        assertEquals(VentoySettingsSource.Default, reduced.state.ventoySettingsSource)
        assertTrue(reduced.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.SettingsSourceStale),
            reduced.state.statusMessage,
        )
    }

    @Test
    fun aReadForAnotherSourceCannotOverwriteTheSelectedFile() {
        val selected =
            UiReducer.reduce(
                ready(),
                UiIntent.SelectVentoySettingsUsbFile(
                    path = "/custom/one.json",
                    identity = "Test Stick",
                    attachGeneration = 1L,
                ),
            ).state
        val late =
            UiReducer.reduce(
                selected,
                UiIntent.VentoyGlobalSettingsLoaded(
                    VentoyGlobalSettings.Present(emptyList()),
                    epoch = selected.ventoyCacheEpoch,
                    requestToken = selected.ventoyGlobalSettingsRequest,
                    source = VentoySettingsSource.UsbFile("/custom/two.json"),
                ),
            ).state
        assertNull(late.ventoyGlobalSettings)
        assertTrue(late.ventoySettingsSourceLoading)
    }

    @Test
    fun applyEffectCarriesTheSelectedSourceAndAttachmentPin() {
        val selected =
            UiReducer.reduce(
                UiReducer.reduce(
                    ready(),
                    UiIntent.SelectVentoySettingsUsbFile(
                        path = "/custom/ventoy-config.json",
                        identity = "Test Stick",
                        attachGeneration = 1L,
                    ),
                ).state,
                UiIntent.VentoyGlobalSettingsLoaded(
                    VentoyGlobalSettings.Present(emptyList()),
                    epoch = 0L,
                    requestToken = 1L,
                    source = VentoySettingsSource.UsbFile("/custom/ventoy-config.json"),
                ),
            ).state
        val staged =
            UiReducer.reduce(
                selected,
                UiIntent.StageVentoyControl("VTOY_WIN11_BYPASS_CHECK", "1"),
            ).state
        val effect =
            UiReducer.reduce(staged, UiIntent.RequestApplyVentoyControls)
                .effects.filterIsInstance<UiEffect.ApplyVentoyControls>()
                .single()
        assertEquals(
            VentoySettingsSource.UsbFile("/custom/ventoy-config.json"),
            effect.source,
        )
        assertEquals("Test Stick", effect.identity)
        assertEquals(1L, effect.attachGeneration)
    }

    @Test
    fun aMissingChosenFileFailsClosedInsteadOfCreatingIt() {
        val selected =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.SelectVentoySettingsUsbFile(
                    path = "/custom/ventoy-config.json",
                    identity = "Test Stick",
                    attachGeneration = 1L,
                ),
            ).state
        val missing =
            UiReducer.reduce(
                selected,
                UiIntent.VentoyGlobalSettingsLoaded(
                    VentoyGlobalSettings.Missing,
                    epoch = 0L,
                    requestToken = 1L,
                    source = VentoySettingsSource.UsbFile("/custom/ventoy-config.json"),
                ),
            ).state
        val staged =
            UiReducer.reduce(
                missing,
                UiIntent.StageVentoyControl("VTOY_WIN11_BYPASS_CHECK", "1"),
            ).state
        val blocked = UiReducer.reduce(staged, UiIntent.RequestApplyVentoyControls)
        assertTrue(blocked.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.SettingsSourceUnavailable),
            blocked.state.statusMessage,
        )
    }

    @Test
    fun installConfirmationDoesNotCarryEditsForANonDefaultFile() {
        val selected =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.SelectVentoySettingsUsbFile(
                    path = "/custom/ventoy-config.json",
                    identity = "Test Stick",
                    attachGeneration = 1L,
                ),
            ).state
        val staged =
            UiReducer.reduce(
                selected,
                UiIntent.StageVentoyControl("VTOY_WIN11_BYPASS_CHECK", "1"),
            ).state
        val requested =
            UiReducer.reduce(
                staged,
                UiIntent.RequestDestructive(DestructiveAction.VENTOY_INSTALL),
            ).state
        assertEquals(
            emptyList<VentoyControlSetting>(),
            requested.pendingConfirmation?.ventoyControls,
        )
    }

    @Test
    fun reselectingTheSameDocumentUriWithAnotherNameKeepsStagedControls() {
        val first =
            UiReducer.reduce(
                ready(),
                UiIntent.SelectVentoySettingsDocument(
                    uri = "content://provider/config.json",
                    displayName = "config.json",
                ),
            ).state
        val staged =
            UiReducer.reduce(
                first,
                UiIntent.StageVentoyControl("VTOY_WIN11_BYPASS_CHECK", "1"),
            ).state
        val renamed =
            UiReducer.reduce(
                staged,
                UiIntent.SelectVentoySettingsDocument(
                    uri = "content://provider/config.json",
                    displayName = "renamed.json",
                ),
            ).state
        assertEquals(
            "a display-name change is not a different document",
            staged.pendingVentoyControls,
            renamed.pendingVentoyControls,
        )
        assertEquals(
            "renamed.json",
            (renamed.ventoySettingsSource as VentoySettingsSource.Document).displayName,
        )

        val other =
            UiReducer.reduce(
                renamed,
                UiIntent.SelectVentoySettingsDocument(
                    uri = "content://provider/other.json",
                    displayName = "renamed.json",
                ),
            ).state
        assertEquals(
            "a different URI must drop staged edits",
            emptyList<VentoyControlSetting>(),
            other.pendingVentoyControls,
        )
    }

    @Test
    fun stalePartitionGeometryBlocksReadAndUpdate() {
        val base =
            ready(pending = controls).copy(
                ventoySettingsSource = VentoySettingsSource.UsbFile("/custom/config.json"),
                ventoyGlobalSettings = VentoyGlobalSettings.Present(emptyList()),
                ventoySettingsSourcePartitionId = "1",
                ventoySettingsSourceStartLba = 2048uL,
                ventoySettingsSourceSectorCount = 4096uL,
            )
        val repartitioned =
            base.copy(
                partitions =
                    listOf(
                        base.partitions.single().copy(
                            startLba = 8192uL,
                            sectorCount = 4096uL,
                        ),
                    ),
            )

        val apply = UiReducer.reduce(repartitioned, UiIntent.RequestApplyVentoyControls)
        assertTrue(apply.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.SettingsSourceStale),
            apply.state.statusMessage,
        )

        val load = UiReducer.reduce(repartitioned, UiIntent.LoadVentoyGlobalSettings)
        assertTrue(load.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.SettingsSourceStale),
            load.state.statusMessage,
        )
    }

    @Test
    fun blankDocumentSelectionIsRejectedWithoutChangingTheSource() {
        val reduced =
            UiReducer.reduce(
                ready(),
                UiIntent.SelectVentoySettingsDocument(""),
            )
        assertEquals(VentoySettingsSource.Default, reduced.state.ventoySettingsSource)
        assertTrue(reduced.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.SettingsSourceInvalid),
            reduced.state.statusMessage,
        )
    }

    @Test
    fun aNewAttachmentResetsTheChosenSourceToTheDefaultPath() {
        val selected =
            UiReducer.reduce(
                ready(pending = controls),
                UiIntent.SelectVentoySettingsDocument("content://provider/config.json"),
            ).state
        assertEquals(
            VentoySettingsSource.Document("content://provider/config.json"),
            selected.ventoySettingsSource,
        )
        val replaced =
            UiReducer.reduce(
                selected,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = DeviceIdentity(
                        label = "Other Stick",
                        capacityBytes = 16_000_000_000uL,
                        attachGeneration = 2L,
                    ),
                ),
            ).state
        assertEquals(VentoySettingsSource.Default, replaced.ventoySettingsSource)
        assertNull(replaced.ventoySettingsSourceIdentity)
        assertNull(replaced.ventoySettingsSourceAttachGeneration)
    }
}
