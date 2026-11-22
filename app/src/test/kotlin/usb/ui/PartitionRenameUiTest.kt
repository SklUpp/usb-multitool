package usb.ui

import org.junit.Assert.assertEquals

import org.junit.Assert.assertNull
import org.junit.Test
import usb.fs.FilesystemId
import usb.partition.TableScheme

/** Rename flows: GPT entry names and filesystem volume labels. */
class PartitionRenameUiTest {
    private val device = DeviceIdentity(label = "Test Stick", capacityBytes = 8_000_000_000uL)

    private val partitions = listOf(
        PartitionTab(
            id = "1",
            name = "DATA",
            readOnly = false,
            startLba = 2048uL,
            sectorCount = 4096uL,
            filesystemId = FilesystemId.NTFS,
        ),
        PartitionTab(
            id = "2",
            name = "VTOYEFI",
            readOnly = true,
            isEfiType = true,
            startLba = 8192uL,
            sectorCount = 2048uL,
            filesystemId = FilesystemId.FAT,
        ),
    )

    private fun ready(scheme: TableScheme? = TableScheme.GPT) =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = partitions,
            selectedPartitionId = "1",
            partitionScheme = scheme,
        )

    @Test
    fun renamePinsGptNameAndGeometry() {
        val pending = requireNotNull(
            UiReducer.reduce(ready(), UiIntent.RequestRenamePartition("  Media  ")).state.pendingConfirmation,
        )
        assertEquals(DestructiveAction.RENAME_PARTITION, pending.action)
        assertEquals("Media", pending.partitionName)
        assertEquals(2048uL, pending.partitionStartLba)
        assertEquals(4096uL, pending.partitionSectorCount)

        val confirmed = UiReducer.reduce(
            ready().copy(pendingConfirmation = pending),
            UiIntent.ConfirmDestructive,
        )
        assertEquals(DestructiveAction.RENAME_PARTITION, (confirmed.effects.single() as UiEffect.SubmitDestructive).action)
        assertEquals("Media", (confirmed.effects.single() as UiEffect.SubmitDestructive).confirmation.partitionName)
    }

    @Test
    fun renameNeedsGptAndAnActualNewName() {
        val mbr = UiReducer.reduce(
            ready(TableScheme.MBR),
            UiIntent.RequestRenamePartition("Media"),
        )
        assertNull(mbr.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.PartitionNameUnsupported), mbr.state.statusMessage)

        val unknown = UiReducer.reduce(ready(null), UiIntent.RequestRenamePartition("Media"))
        assertNull(unknown.state.pendingConfirmation)

        val same = UiReducer.reduce(ready(), UiIntent.RequestRenamePartition(" DATA "))
        assertNull(same.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.RenameSameName), same.state.statusMessage)

        val tooLong = UiReducer.reduce(ready(), UiIntent.RequestRenamePartition("x".repeat(37)))
        assertNull(tooLong.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.InvalidPartitionName), tooLong.state.statusMessage)
    }

    @Test
    fun protectedEfiNameCannotBeRenamedEvenWithOverride() {
        val state = ready().copy(
            selectedPartitionId = "2",
            vtoyEfiWritePartitionId = "2",
            vtoyEfiWriteStartLba = 8192uL,
            vtoyEfiWriteSectorCount = 2048uL,
        )
        val result = UiReducer.reduce(state, UiIntent.RequestRenamePartition("NEW"))
        assertNull(result.state.pendingConfirmation)
    }

    @Test
    fun volumeLabelRenamePinsLabelWhenTheEngineCanWriteIt() {
        val pending = requireNotNull(
            UiReducer.reduce(ready(), UiIntent.RequestRenameVolumeLabel(" MEDIA ")).state.pendingConfirmation,
        )
        assertEquals(DestructiveAction.RENAME_VOLUME_LABEL, pending.action)
        assertEquals("MEDIA", pending.volumeLabel)
        assertEquals(2048uL, pending.partitionStartLba)

        val confirmed = UiReducer.reduce(
            ready().copy(pendingConfirmation = pending),
            UiIntent.ConfirmDestructive,
        )
        assertEquals(DestructiveAction.RENAME_VOLUME_LABEL, (confirmed.effects.single() as UiEffect.SubmitDestructive).action)
        assertEquals("MEDIA", (confirmed.effects.single() as UiEffect.SubmitDestructive).confirmation.volumeLabel)
    }

    @Test
    fun volumeLabelRenameRefusesExtUnknownFsAndBadLabel() {
        val ext = ready().copy(
            partitions = partitions.map {
                if (it.id == "1") it.copy(filesystemId = FilesystemId.EXT) else it
            },
        )
        val extResult = UiReducer.reduce(ext, UiIntent.RequestRenameVolumeLabel("MEDIA"))
        assertNull(extResult.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.VolumeLabelUnsupported, listOf("EXT4")),
            extResult.state.statusMessage,
        )

        val unknownFs = ready().copy(
            partitions = partitions.map {
                if (it.id == "1") it.copy(filesystemId = null) else it
            },
        )
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesystemUnknown),
            UiReducer.reduce(unknownFs, UiIntent.RequestRenameVolumeLabel("MEDIA")).state.statusMessage,
        )

        val fat = ready().copy(
            partitions = partitions.map {
                if (it.id == "1") it.copy(filesystemId = FilesystemId.FAT) else it
            },
        )
        assertEquals(
            UiMessage.Key(UiMessageKey.InvalidVolumeLabel),
            UiReducer.reduce(fat, UiIntent.RequestRenameVolumeLabel("bad/name")).state.statusMessage,
        )
        assertEquals(
            UiMessage.Key(UiMessageKey.InvalidVolumeLabel),
            UiReducer.reduce(fat, UiIntent.RequestRenameVolumeLabel("   ")).state.statusMessage,
        )
    }

    @Test
    fun directRenameActionsCannotBypassTheValueIntent() {
        val result = UiReducer.reduce(
            ready(),
            UiIntent.RequestDestructive(DestructiveAction.RENAME_PARTITION),
        )
        assertNull(result.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.InvalidPartitionName), result.state.statusMessage)
        val label = UiReducer.reduce(
            ready(),
            UiIntent.RequestDestructive(DestructiveAction.RENAME_VOLUME_LABEL),
        )
        assertNull(label.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.InvalidVolumeLabel), label.state.statusMessage)
    }
}
