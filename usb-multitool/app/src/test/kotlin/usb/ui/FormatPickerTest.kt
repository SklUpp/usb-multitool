package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.fs.FilesystemId
import usb.fs.FormatCatalog
import usb.fs.Formatter

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
        val ids = Formatter.supportedFormatIds()
        assertEquals(listOf(FilesystemId.FAT, FilesystemId.EXFAT, FilesystemId.EXT), ids)
        assertFalse(ids.contains(FilesystemId.NTFS))
    }

    @Test
    fun requireSupportedRejectsNtfs() {
        try {
            Formatter.requireSupported(FilesystemId.NTFS)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
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
    fun selectUnsupportedNtfsLeavesSelectionAndReports() {
        val base = ready(FilesystemId.EXFAT)
        val next =
            UiReducer.reduce(base, UiIntent.SelectFormatFilesystem(FilesystemId.NTFS))
        assertEquals(FilesystemId.EXFAT, next.state.selectedFormatId)
        assertTrue(next.state.statusMessage!!.contains("not supported"))
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
        assertTrue(pending!!.message.contains("exFAT"))
        assertTrue(pending.message.contains("format partition"))
        assertTrue(pending.message.contains(device.identity))
    }

    @Test
    fun formatConfirmationForExt() {
        val result =
            UiReducer.reduce(
                ready(FilesystemId.EXT),
                UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
            )
        assertTrue(result.state.pendingConfirmation!!.message.contains("EXT4"))
    }

    @Test
    fun wipeConfirmationOmitsFilesystemLabel() {
        val result =
            UiReducer.reduce(ready(FilesystemId.EXT), UiIntent.RequestDestructive(DestructiveAction.WIPE))
        val msg = result.state.pendingConfirmation!!.message
        assertFalse(msg.contains("EXT4"))
        assertFalse(msg.contains(" as "))
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
        assertTrue(result.state.pendingConfirmation!!.message.contains("FAT/FAT32"))
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
}
