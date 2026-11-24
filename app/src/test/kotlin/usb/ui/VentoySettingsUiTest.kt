package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.ventoy.VentoyControlSetting
import usb.ventoy.SettingStatus
import usb.ventoy.VentoySetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoySettingsSource
import usb.service.OperationTokenGenerator

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

        val completedToken = OperationTokenGenerator.next()
        val completed = reduceTerminalOperation(
            requested,
            OperationProgress(
                state = OperationState.Completed,
                token = completedToken,
                title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
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
        val partialToken = OperationTokenGenerator.next()
        val partial = reduceTerminalOperation(
            ready(pending = controls),
            OperationProgress(
                state = OperationState.Partial,
                token = partialToken,
                title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                failure = OperationFailure.SafIo,
            ),
        ).state
        assertEquals(controls, partial.pendingVentoyControls)

        val completed = reduceTerminalOperation(
            partial,
            OperationProgress(
                state = OperationState.Completed,
                token = partialToken,
                title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
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
        val partialToken = OperationTokenGenerator.next()
        val partial = reduceTerminalOperation(
            ready(pending = controls),
            OperationProgress(
                state = OperationState.Partial,
                token = partialToken,
                title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                failure = OperationFailure.SafIo,
            ),
        ).state
        val retry = UiReducer.reduce(partial, UiIntent.RequestApplyVentoyControls)
        val effect = retry.effects.filterIsInstance<UiEffect.ApplyVentoyControls>().single()
        assertEquals(controls, effect.settings)

        val retried = reduceTerminalOperation(
            retry.state,
            OperationProgress(
                state = OperationState.Completed,
                token = partialToken,
                title = OperationTitle.SetVentoyGlobalSetting,
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
    fun stalePartitionGeometryBlocksApplyButLoadRepinsAndRereads() {
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

        // Automatic reload after install must re-pin to the live part1, not stick on Stale.
        val load = UiReducer.reduce(repartitioned, UiIntent.LoadVentoyGlobalSettings)
        assertEquals(
            listOf(
                UiEffect.LoadVentoyGlobalSettings(
                    requestToken = load.state.ventoyGlobalSettingsRequest,
                    source = VentoySettingsSource.UsbFile("/custom/config.json"),
                ),
            ),
            load.effects,
        )
        assertEquals(8192uL, load.state.ventoySettingsSourceStartLba)
        assertEquals(4096uL, load.state.ventoySettingsSourceSectorCount)
        assertTrue(load.state.ventoySettingsSourceLoading)
    }

    @Test
    fun installCompletionThenLoadWithStaleDefaultPinsRepinsToNewPart1() {
        val before =
            ready(pending = controls).copy(
                ventoyGlobalSettings = VentoyGlobalSettings.Empty,
                ventoySettingsSource = VentoySettingsSource.Default,
                ventoySettingsSourcePartitionId = "1",
                ventoySettingsSourceStartLba = 2048uL,
                ventoySettingsSourceSectorCount = 4096uL,
                ventoyCacheEpoch = 3L,
            )
        val afterInstall =
            reduceTerminalOperation(
                before,
                OperationProgress(
                    state = OperationState.Completed,
                    token = OperationTokenGenerator.next(),
                    title = OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL),
                ),
            ).state
        assertNull(afterInstall.ventoyGlobalSettings)
        assertEquals(4L, afterInstall.ventoyCacheEpoch)
        // dropVentoyStickCaches clears geometry pins so the next Default load rebinds.
        assertNull(afterInstall.ventoySettingsSourcePartitionId)
        assertNull(afterInstall.ventoySettingsSourceStartLba)
        assertNull(afterInstall.ventoySettingsSourceSectorCount)
        // Install Completed on Default clears pending (snapshot already on stick).
        assertEquals(emptyList<VentoyControlSetting>(), afterInstall.pendingVentoyControls)
        // Simulate the refreshed table with a moved part1 after install.
        val refreshed =
            afterInstall.copy(
                partitions =
                    listOf(
                        afterInstall.partitions.single().copy(
                            startLba = 2048uL,
                            sectorCount = 2_000_000uL,
                        ),
                    ),
            )
        val load = UiReducer.reduce(refreshed, UiIntent.LoadVentoyGlobalSettings)
        assertTrue(
            "post-install load must emit a settings read, not SettingsSourceStale",
            load.effects.any { it is UiEffect.LoadVentoyGlobalSettings },
        )
        assertEquals(2048uL, load.state.ventoySettingsSourceStartLba)
        assertEquals(2_000_000uL, load.state.ventoySettingsSourceSectorCount)
        assertEquals(VentoySettingsSource.Default, load.state.ventoySettingsSource)
    }

    @Test
    fun defaultLoadWithoutDataPartitionStillEmitsUnpinnedRead() {
        // Right after install, partitions may still be empty while refreshPartitions runs.
        // Default path must not require UI part1 — host probes the live Ventoy layout.
        val bare =
            ready().copy(
                partitions = emptyList(),
                selectedPartitionId = null,
                ventoyGlobalSettings = null,
                ventoySettingsSource = VentoySettingsSource.Default,
                ventoySettingsSourcePartitionId = null,
                ventoySettingsSourceStartLba = null,
                ventoySettingsSourceSectorCount = null,
            )
        val load = UiReducer.reduce(bare, UiIntent.LoadVentoyGlobalSettings)
        assertEquals(
            listOf(
                UiEffect.LoadVentoyGlobalSettings(
                    requestToken = load.state.ventoyGlobalSettingsRequest,
                    source = VentoySettingsSource.Default,
                ),
            ),
            load.effects,
        )
        assertNull(load.state.ventoySettingsSourceStartLba)
        assertTrue(load.state.ventoySettingsSourceLoading)
    }

    @Test
    fun updateCompletionClearsSettingsSoLoadRereadsPreservedFile() {
        val loaded =
            ready().copy(
                ventoyGlobalSettings = VentoyGlobalSettings.Present(emptyList()),
                ventoySettingsSource = VentoySettingsSource.Default,
                ventoySettingsSourcePartitionId = "1",
                ventoySettingsSourceStartLba = 2048uL,
                ventoySettingsSourceSectorCount = 4096uL,
                ventoyCacheEpoch = 1L,
            )
        val afterUpdate =
            reduceTerminalOperation(
                loaded,
                OperationProgress(
                    state = OperationState.Completed,
                    token = OperationTokenGenerator.next(),
                    title = OperationTitle.Destructive(DestructiveAction.VENTOY_UPDATE),
                ),
            ).state
        assertNull(
            "update must drop the cached settings so the preserved ventoy.json is re-read",
            afterUpdate.ventoyGlobalSettings,
        )
        assertEquals(2L, afterUpdate.ventoyCacheEpoch)
        assertNull(afterUpdate.ventoySettingsSourcePartitionId)
        assertNull(afterUpdate.ventoySettingsSourceStartLba)
        assertNull(afterUpdate.ventoySettingsSourceSectorCount)
        val load = UiReducer.reduce(afterUpdate, UiIntent.LoadVentoyGlobalSettings)
        assertTrue(load.effects.any { it is UiEffect.LoadVentoyGlobalSettings })
        assertTrue(load.state.ventoySettingsSourceLoading)
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

    @Test
    fun emptyEditorCommitKeepsStagedKnownKeysSoWriteStaysEnabled() {
        val staged =
            UiReducer.reduce(
                ready(),
                UiIntent.StageVentoyControl("VTOY_WIN11_BYPASS_CHECK", "1"),
            ).state
        val committed =
            UiReducer.reduce(
                staged,
                UiIntent.CommitVentoyControls(
                    removedKeys = emptySet(),
                    settings = emptyList(),
                ),
            ).state
        assertEquals(
            "empty Apply must merge, not wipe staged known keys",
            listOf(VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1")),
            committed.pendingVentoyControls,
        )
        val apply = UiReducer.reduce(committed, UiIntent.RequestApplyVentoyControls)
        assertTrue(apply.effects.any { it is UiEffect.ApplyVentoyControls })
    }

    @Test
    fun editorCommitMergesCustomDraftsOverExistingStagedRows() {
        val staged =
            UiReducer.reduce(
                ready(pending = listOf(VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"))),
                UiIntent.StageVentoyControl("KEEP_ME", "yes"),
            ).state
        val committed =
            UiReducer.reduce(
                staged,
                UiIntent.CommitVentoyControls(
                    removedKeys = emptySet(),
                    settings = listOf(VentoyControlSetting("CUSTOM_KEY", "new")),
                    discardedKeys = setOf("KEEP_ME"),
                ),
            ).state
        assertEquals(
            listOf(
                VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"),
                VentoyControlSetting("CUSTOM_KEY", "new"),
            ),
            committed.pendingVentoyControls,
        )
    }

    @Test
    fun discardingStagedCustomUnknownKeepsOthersAndCancelRestoresBaseline() {
        // Mirrors: stage custom on a Document source, Save, reopen, delete custom row, Save.
        // discardedKeys erase the pending-only row; known staged rows and other customs stay.
        val baseline =
            listOf(
                VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"),
                VentoyControlSetting("KEEP_OTHER", "yes"),
                VentoyControlSetting("DROP_ME", "gone"),
            )
        val documentSource =
            ready(pending = baseline).copy(
                ventoySettingsSource = VentoySettingsSource.Document("content://ventoy/custom.json"),
                ventoyGlobalSettings = VentoyGlobalSettings.Present(emptyList()),
            )
        val afterDiscard =
            UiReducer.reduce(
                documentSource,
                UiIntent.CommitVentoyControls(
                    removedKeys = emptySet(),
                    settings = emptyList(),
                    discardedKeys = setOf("DROP_ME"),
                ),
            ).state
        assertEquals(
            listOf(
                VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"),
                VentoyControlSetting("KEEP_OTHER", "yes"),
            ),
            afterDiscard.pendingVentoyControls,
        )
        // Cancel restores the pre-edit baseline without writing the stick.
        val restored =
            UiReducer.reduce(
                afterDiscard,
                UiIntent.RestoreVentoyControls(baseline),
            ).state
        assertEquals(baseline, restored.pendingVentoyControls)
        // Default path: discard alone is a real change (no silent no-changes ignore).
        val defaultDiscard =
            UiReducer.reduce(
                ready(pending = baseline).copy(
                    ventoySettingsSource = VentoySettingsSource.Default,
                    ventoyGlobalSettings = VentoyGlobalSettings.Empty,
                ),
                UiIntent.CommitVentoyControls(
                    removedKeys = emptySet(),
                    settings = emptyList(),
                    discardedKeys = setOf("DROP_ME"),
                ),
            ).state
        assertEquals(
            listOf(
                VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"),
                VentoyControlSetting("KEEP_OTHER", "yes"),
            ),
            defaultDiscard.pendingVentoyControls,
        )
        // Import-original semantics: deleting a key that exists on the source file stages a
        // removal (null), it is not discardedKeys (pending-only erase).
        val withImportOriginal =
            ready().copy(
                ventoySettingsSource = VentoySettingsSource.Document("content://ventoy/custom.json"),
                ventoyGlobalSettings =
                    VentoyGlobalSettings.Present(
                        listOf(
                            VentoySetting(
                                key = "IMPORT_KEY",
                                rawValue = "from-file",
                                status = SettingStatus.UnknownKey,
                            ),
                        ),
                    ),
                pendingVentoyControls = listOf(VentoyControlSetting("STAGED_ONLY", "temp")),
            )
        val removeImported =
            UiReducer.reduce(
                withImportOriginal,
                UiIntent.CommitVentoyControls(
                    removedKeys = setOf("IMPORT_KEY"),
                    settings = emptyList(),
                    discardedKeys = setOf("STAGED_ONLY"),
                ),
            ).state
        assertEquals(
            listOf(VentoyControlSetting("IMPORT_KEY", null)),
            removeImported.pendingVentoyControls,
        )
    }

    @Test
    fun defaultApplyWithStalePinsRebindsToLivePart1() {
        val stale =
            ready(pending = controls).copy(
                ventoySettingsSource = VentoySettingsSource.Default,
                ventoyGlobalSettings = VentoyGlobalSettings.Empty,
                ventoySettingsSourcePartitionId = "1",
                ventoySettingsSourceStartLba = 2048uL,
                ventoySettingsSourceSectorCount = 4096uL,
                partitions =
                    listOf(
                        PartitionTab(
                            id = "1",
                            name = "Ventoy",
                            readOnly = false,
                            startLba = 8192uL,
                            sectorCount = 2_000_000uL,
                            filesystemId = FilesystemId.FAT,
                        ),
                    ),
            )
        val apply = UiReducer.reduce(stale, UiIntent.RequestApplyVentoyControls)
        val effect = apply.effects.filterIsInstance<UiEffect.ApplyVentoyControls>().single()
        assertEquals(8192uL, effect.partitionStartLba)
        assertEquals(2_000_000uL, effect.partitionSectorCount)
        assertNull(apply.state.statusMessage)
    }

    @Test
    fun loadedSettingsAnswerCanRefreshGeometryPins() {
        val loading =
            ready().copy(
                ventoyGlobalSettingsRequest = 3L,
                ventoySettingsSourceLoading = true,
                ventoySettingsSourcePartitionId = "1",
                ventoySettingsSourceStartLba = 2048uL,
                ventoySettingsSourceSectorCount = 4096uL,
            )
        val loaded =
            UiReducer.reduce(
                loading,
                UiIntent.VentoyGlobalSettingsLoaded(
                    VentoyGlobalSettings.Empty,
                    epoch = loading.ventoyCacheEpoch,
                    requestToken = 3L,
                    source = VentoySettingsSource.Default,
                    partitionId = "1",
                    partitionStartLba = 8192uL,
                    partitionSectorCount = 2_000_000uL,
                ),
            ).state
        assertEquals(8192uL, loaded.ventoySettingsSourceStartLba)
        assertEquals(2_000_000uL, loaded.ventoySettingsSourceSectorCount)
        assertEquals(false, loaded.ventoySettingsSourceLoading)
    }

    @Test
    fun refreshControlKeyCatalogEmitsNetworkFetchOutsideUsbClaim() {
        val reduced = UiReducer.reduce(ready(), UiIntent.RefreshVentoyControlKeyCatalog)
        assertTrue(reduced.state.ventoyControlKeyCatalogLoading)
        assertEquals(1L, reduced.state.ventoyControlKeyCatalogRequest)
        assertEquals(
            listOf(
                UiEffect.FetchVentoyControlKeyCatalog(
                    requestToken = 1L,
                    release = usb.ventoy.VentoyAssets.PINNED_RELEASE,
                ),
            ),
            reduced.effects,
        )
        val failed =
            UiReducer.reduce(
                reduced.state.copy(ventoyControlKeyCatalog = null),
                UiIntent.SetVentoyControlKeyCatalog(
                    requestToken = 1L,
                    error = "offline",
                ),
            ).state
        assertEquals("offline", failed.ventoyControlKeyCatalogError)
        assertNull(failed.ventoyControlKeyCatalog)
        assertEquals(false, failed.ventoyControlKeyCatalogLoading)
    }

    @Test
    fun refreshControlKeyCatalogUsesShownUpstreamRelease() {
        val reduced = UiReducer.reduce(
            ready().copy(upstreamVentoyRelease = "1.1.19"),
            UiIntent.RefreshVentoyControlKeyCatalog,
        )
        assertEquals(
            listOf(
                UiEffect.FetchVentoyControlKeyCatalog(
                    requestToken = 1L,
                    release = "1.1.19",
                ),
            ),
            reduced.effects,
        )
    }

    @Test
    fun catalogNetworkFailureKeepsPreviousKeys() {
        val previous =
            usb.ventoy.VentoyControlKeyCatalog(
                ventoyRelease = "1.1.07",
                upstreamRef = "ventoy/Ventoy@v1.1.07:Plugson/www/plugson_control.html",
                upstreamSha256 = "a".repeat(64),
                fetchedAtEpochMs = 1L,
                keys = listOf(
                    usb.ventoy.VentoyControlKeyDef(
                        key = "VTOY_MENU_TIMEOUT",
                        kind = usb.ventoy.VentoyControlKeyKind.INTEGER,
                    ),
                ),
            )
        val loading =
            ready().copy(
                ventoyControlKeyCatalog = previous,
                ventoyControlKeyCatalogRequest = 2L,
                ventoyControlKeyCatalogLoading = true,
            )
        val failed =
            UiReducer.reduce(
                loading,
                UiIntent.SetVentoyControlKeyCatalog(
                    requestToken = 2L,
                    error = "timeout",
                ),
            ).state
        assertEquals(previous, failed.ventoyControlKeyCatalog)
        assertEquals("timeout", failed.ventoyControlKeyCatalogError)
        assertEquals(false, failed.ventoyControlKeyCatalogLoading)
    }
}
