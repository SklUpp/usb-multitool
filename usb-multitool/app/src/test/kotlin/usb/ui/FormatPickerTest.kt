package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.fs.FormatCatalog

class FormatPickerTest {
    private val device =
        DeviceIdentity(label = "Test Stick", capacityBytes = 8_000_000_000uL)

    private val partitions =
        listOf(PartitionTab("1", "DATA", readOnly = false))

    private fun ready(format: FilesystemId = FilesystemId.FAT): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = partitions,
            selectedPartitionId = "1",
            selectedFormatId = format,
        )

    @Test
    fun supportedIdsAreRealEnginesOnly() {
        val ids = FormatCatalog.supportedFormatIds()
        assertEquals(
            listOf(FilesystemId.FAT, FilesystemId.EXFAT, FilesystemId.EXT, FilesystemId.NTFS),
            ids,
        )
        assertTrue(ids.contains(FilesystemId.NTFS))
    }

    @Test
    fun requireSupportedAcceptsNtfs() {
        assertEquals(FilesystemId.NTFS, FormatCatalog.requireSupported(FilesystemId.NTFS))
    }

    @Test
    fun selectFormatUpdatesState() {
        val next =
            UiReducer.reduce(ready(), UiIntent.SelectFormatFilesystem(FilesystemId.EXT))
        assertEquals(FilesystemId.EXT, next.state.selectedFormatId)
        assertNull(next.state.statusMessage)
        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun selectNtfsUpdatesState() {
        val base = ready(FilesystemId.EXFAT)
        val next =
            UiReducer.reduce(base, UiIntent.SelectFormatFilesystem(FilesystemId.NTFS))
        assertEquals(FilesystemId.NTFS, next.state.selectedFormatId)
        assertNull(next.state.statusMessage)
        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun formatConfirmationNamesChosenFilesystem() {
        val state = ready(FilesystemId.EXFAT)
        val result =
            UiReducer.reduce(
                state,
                UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
            )
        val pending = result.state.pendingConfirmation
        assertNotNull(pending)
        assertEquals(FilesystemId.EXFAT, pending!!.formatId)
        assertEquals(DestructiveAction.FORMAT_PARTITION, pending.action)
        assertEquals(device, pending.identity)
    }

    @Test
    fun formatConfirmationForExt() {
        val result =
            UiReducer.reduce(
                ready(FilesystemId.EXT),
                UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
            )
        assertEquals(
            FilesystemId.EXT,
            result.state.pendingConfirmation!!.formatId,
        )
    }

    @Test
    fun wipeConfirmationOmitsFilesystemLabel() {
        val result =
            UiReducer.reduce(ready(FilesystemId.EXT), UiIntent.RequestDestructive(DestructiveAction.WIPE))
        assertNull(result.state.pendingConfirmation!!.formatId)
    }

    @Test
    fun confirmFormatSubmitsWithoutChangingSelection() {
        val pending =
            UiReducer.reduce(
                ready(FilesystemId.EXFAT),
                UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
            ).state
        val confirmed = UiReducer.reduce(pending, UiIntent.ConfirmDestructive)
        assertNull(confirmed.state.pendingConfirmation)
        assertEquals(1, confirmed.effects.size)
        assertEquals(UiEffect.Kind.SubmitDestructive, confirmed.effects[0].kind)
        assertEquals(DestructiveAction.FORMAT_PARTITION, confirmed.effects[0].action)
        assertEquals(FilesystemId.EXFAT, confirmed.state.selectedFormatId)
    }

    @Test
    fun selectIgnoredWhileConfirmPending() {
        val pending =
            UiReducer.reduce(
                ready(FilesystemId.FAT),
                UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
            ).state
        assertNotNull(pending.pendingConfirmation)
        assertFalse(pending.actionsEnabled)
        val next =
            UiReducer.reduce(pending, UiIntent.SelectFormatFilesystem(FilesystemId.EXT))
        assertEquals(FilesystemId.FAT, next.state.selectedFormatId)
        assertNotNull(next.state.pendingConfirmation)
    }

    @Test
    fun defaultFatConfirmNamesFatLabel() {
        val result =
            UiReducer.reduce(
                ready(FilesystemId.FAT),
                UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
            )
        assertEquals(
            FilesystemId.FAT,
            result.state.pendingConfirmation!!.formatId,
        )
    }

    @Test
    fun confirmEffectPinsFormatId() {
        val pending =
            UiReducer.reduce(
                ready(FilesystemId.EXT),
                UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
            ).state
        assertEquals(FilesystemId.EXT, pending.pendingConfirmation!!.formatId)
        val confirmed = UiReducer.reduce(pending, UiIntent.ConfirmDestructive)
        assertEquals(FilesystemId.EXT, confirmed.effects.single().formatId)
        assertEquals(DestructiveAction.FORMAT_PARTITION, confirmed.effects.single().action)
    }

    @Test
    fun wipeEffectHasNullFormatId() {
        val pending =
            UiReducer.reduce(ready(FilesystemId.EXT), UiIntent.RequestDestructive(DestructiveAction.WIPE)).state
        assertNull(pending.pendingConfirmation!!.formatId)
        val confirmed = UiReducer.reduce(pending, UiIntent.ConfirmDestructive)
        assertNull(confirmed.effects.single().formatId)
    }

    @Test
    fun createConfirmationPinsNameFormatAndLabel() {
        val base = ready().copy(
            partitionScheme = usb.partition.TableScheme.GPT,
            freeRegions = listOf(FreeRegionUi(2048uL, 4096uL)),
        )
        val pending = UiReducer.reduce(
            base,
            UiIntent.RequestCreatePartition(
                startLba = 2048uL,
                sectorCount = 2048uL,
                name = "  Data  ",
                formatId = FilesystemId.EXT,
                volumeLabel = " MEDIA ",
            ),
        ).state.pendingConfirmation
        assertNotNull(pending)
        assertEquals("Data", pending!!.createName)
        assertEquals(FilesystemId.EXT, pending.createFormatId)
        assertEquals("MEDIA", pending.volumeLabel)
        // CREATE keeps the FORMAT-only field empty so the FORMAT path stays unambiguous.
        assertNull(pending.formatId)

        val confirmed = UiReducer.reduce(
            base.copy(pendingConfirmation = pending),
            UiIntent.ConfirmDestructive,
        )
        assertEquals(DestructiveAction.CREATE_PARTITION, confirmed.effects.single().action)
        assertEquals("Data", confirmed.effects.single().confirmation!!.createName)
        assertEquals(FilesystemId.EXT, confirmed.effects.single().confirmation!!.createFormatId)
    }

    @Test
    fun createRefusesNameWithoutGptAndChecksLabelLimits() {
        val base = ready().copy(
            partitionScheme = usb.partition.TableScheme.MBR,
            freeRegions = listOf(FreeRegionUi(2048uL, 4096uL)),
        )
        val named = UiReducer.reduce(
            base,
            UiIntent.RequestCreatePartition(2048uL, 2048uL, name = "Data"),
        )
        assertNull(named.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.PartitionNameUnsupported), named.state.statusMessage)

        val longLabel = base.copy(partitionScheme = usb.partition.TableScheme.GPT)
        val bad = UiReducer.reduce(
            longLabel,
            UiIntent.RequestCreatePartition(
                2048uL,
                2048uL,
                formatId = FilesystemId.EXT,
                volumeLabel = "x".repeat(16),
            ),
        )
        assertNull(bad.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.InvalidVolumeLabel), bad.state.statusMessage)

        val labelWithoutFormat = UiReducer.reduce(
            longLabel,
            UiIntent.RequestCreatePartition(2048uL, 2048uL, volumeLabel = "MEDIA"),
        )
        assertNull(labelWithoutFormat.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.InvalidVolumeLabel),
            labelWithoutFormat.state.statusMessage,
        )
    }
}
