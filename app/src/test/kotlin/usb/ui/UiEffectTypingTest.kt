package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.partition.Partition
import usb.partition.PartitionTable
import usb.partition.RawRangeGeometry
import usb.partition.TableScheme

/**
 * Sealed [UiEffect] carries required payloads; host `when (effect)` is exhaustive.
 * Locks reducer emissions that previously relied on nullable bag fields.
 */
class UiEffectTypingTest {
    private val device =
        DeviceIdentity(label = "Kingston DataTraveler", capacityBytes = 15_728_640_000uL)

    private val partitions =
        listOf(
            PartitionTab("1", "VENTOY", readOnly = false, startLba = 0uL, sectorCount = 2048uL),
            PartitionTab(
                "2",
                "VTOYEFI",
                readOnly = true,
                isEfiType = true,
                startLba = 2048uL,
                sectorCount = 4096uL,
            ),
        )

    private val geometry =
        RawRangeGeometry.from(
            PartitionTable(
                scheme = TableScheme.MBR,
                partitions = listOf(Partition(2048UL, 4096UL, name = "DATA")),
                freeRegions = emptyList(),
                diskSectorCount = 131_072UL,
                sectorSize = 512,
            ),
            diskSectorCount = 131_072UL,
            sectorSize = 512,
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
            vtoyBypassChecks = true,
            diskSectorSize = 512,
            partitionScheme = TableScheme.GPT,
            tableValid = true,
            rawGeometry = geometry,
        )

    @Test
    fun submitDestructiveCarriesActionAndPinnedConfirmation() {
        val pending =
            UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.WIPE)).state
        val effect =
            UiReducer.reduce(pending, UiIntent.ConfirmDestructive).effects.single()
                as UiEffect.SubmitDestructive
        assertEquals(DestructiveAction.WIPE, effect.action)
        assertEquals(device, effect.confirmation.identity)
        assertEquals(DestructiveAction.WIPE, effect.confirmation.action)
    }

    @Test
    fun rangeProbeCarriesAbsoluteRangeAndRequestId() {
        val result =
            UiReducer.reduce(
                ready(),
                UiIntent.RequestRangeProbe(startLba = 2048UL, sectorCount = 4096UL),
            )
        val effect = result.effects.single() as UiEffect.RangeProbe
        assertEquals(2048UL, effect.startLba)
        assertEquals(4096UL, effect.sectorCount)
        assertEquals(result.state.rangeProbeRequestId, effect.probeRequestId)
    }

    @Test
    fun loadFilesPathIsRequiredNotNullable() {
        val effect =
            UiReducer.reduce(ready(), UiIntent.OpenDirectory("/ISO")).effects.single()
                as UiEffect.LoadFiles
        assertEquals("/ISO", effect.path)
    }

    @Test
    fun linkXmlRequiresImagePathsAndConfirmation() {
        val link =
            UiReducer.reduce(ready(), UiIntent.IsoActionRequested("ubuntu.iso", IsoAction.LinkXml))
        val effect =
            UiReducer.reduce(link.state, UiIntent.ConfirmDestructive).effects.single()
                as UiEffect.LinkXml
        assertEquals(listOf("ubuntu.iso"), effect.imagePaths)
        assertEquals(device, effect.confirmation.identity)
        assertFalse(effect.linkOverwrite)
    }

    @Test
    fun filesystemUsageBindsPartitionKeyAndGeneration() {
        val data =
            PartitionTab(
                id = "1",
                name = "DATA",
                readOnly = false,
                startLba = 2048uL,
                sectorCount = 4096uL,
                filesystemId = FilesystemId.FAT,
            )
        val state =
            ready().copy(
                partitions = listOf(data),
                selectedPartitionId = "1",
            )
        val result = UiReducer.reduce(state, UiIntent.RequestFilesystemUsage)
        val effect = result.effects.single() as UiEffect.LoadFilesystemUsage
        assertEquals("1", effect.partitionId)
        assertEquals(state.filesystemUsageKey(data), effect.usageKey)
        assertEquals(result.state.filesystemUsageGeneration, effect.usageGeneration)
    }

    @Test
    fun cooperativeCancelLivesOnDestructiveAction() {
        assertTrue(DestructiveAction.RAW_WRITE.cooperative)
        assertTrue(DestructiveAction.RESCUE_RESTORE.cooperative)
        assertFalse(DestructiveAction.WIPE.cooperative)
        assertFalse(DestructiveAction.FORMAT_PARTITION.cooperative)
        assertFalse(DestructiveAction.VENTOY_INSTALL.cooperative)
    }

    @Test
    fun unitEffectsHaveNoPayloadFields() {
        assertTrue(
            UiReducer.reduce(ready(), UiIntent.StartReadScan).effects.single() is UiEffect.ReadScan,
        )
        val token = usb.service.OperationTokenGenerator.next()
        val running = ready().copy(
            presence = DevicePresence.Busy,
            operation = OperationProgress(
                state = OperationState.Running,
                title = OperationTitle.Destructive(DestructiveAction.RAW_WRITE),
                token = token,
            ),
        )
        assertTrue(
            UiReducer.reduce(running, UiIntent.CancelOperation(token)).effects.single()
                is UiEffect.CancelOperation,
        )
        assertTrue(
            UiReducer.reduce(ready(), UiIntent.CancelOperation(token)).effects.isEmpty(),
        )
    }
}
