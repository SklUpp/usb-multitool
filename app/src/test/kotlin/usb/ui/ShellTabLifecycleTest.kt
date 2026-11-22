package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId

/**
 * Selected shell tab survives USB lifecycle transitions; attachment-scoped data does not.
 * Recreation restore is covered by [ShellTabPersistenceTest] + MainViewModel saved-state ctor.
 */
class ShellTabLifecycleTest {
    private val data =
        PartitionTab(
            id = "1",
            name = "DATA",
            readOnly = false,
            startLba = 2048uL,
            sectorCount = 4_000_000uL,
            filesystemId = FilesystemId.FAT,
        )
    private val efi =
        PartitionTab(
            id = "2",
            name = "VTOYEFI",
            readOnly = true,
            isEfiType = true,
            startLba = 100uL,
            sectorCount = 64uL,
            filesystemId = FilesystemId.FAT,
        )
    private val device =
        DeviceIdentity(label = "Test Stick", capacityBytes = 8_000_000_000uL, attachGeneration = 1L)
    private val otherDevice =
        DeviceIdentity(label = "Other Stick", capacityBytes = 4_000_000_000uL, attachGeneration = 2L)

    private fun ready(tab: ShellTab = ShellTab.Ventoy): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            tab = tab,
            partitions = listOf(data, efi),
            selectedPartitionId = data.id,
            partitionSelectionInitialized = true,
            currentPath = "/ISO",
            files = listOf(FileEntry("win.iso", isDirectory = false, sizeBytes = 10uL)),
            filesListingState = FilesListingState.Loaded,
            filesListGeneration = 7L,
            vtoyEfiWritePartitionId = efi.id,
            vtoyEfiWriteStartLba = efi.startLba,
            vtoyEfiWriteSectorCount = efi.sectorCount,
            windowsTemplatePath = "/ventoy/autounattend.xml",
            windowsSelectedImages = listOf("/ISO/win.iso"),
            pendingConfirmation =
                DestructiveConfirmation.request(device, DestructiveAction.WIPE),
        )

    @Test
    fun detachPreservesTabAndClearsAttachmentScopedState() {
        for (tab in ShellTab.entries) {
            val detached =
                UiReducer.reduce(
                    ready(tab),
                    UiIntent.DeviceChanged(presence = DevicePresence.Disconnected),
                ).state
            assertEquals(tab, detached.tab)
            assertNull(detached.device)
            assertTrue(detached.partitions.isEmpty())
            assertNull(detached.selectedPartitionId)
            assertEquals("/", detached.currentPath)
            assertTrue(detached.files.isEmpty())
            assertEquals(FilesListingState.Stale, detached.filesListingState)
            assertNull(detached.vtoyEfiWritePartitionId)
            assertNull(detached.windowsTemplatePath)
            assertTrue(detached.windowsSelectedImages.isEmpty())
            assertNull(detached.pendingConfirmation)
        }
    }

    @Test
    fun permissionRequiredAndBusyRetainTabWithoutNavigating() {
        for (tab in listOf(ShellTab.Ventoy, ShellTab.Partitions, ShellTab.Diagnostics)) {
            val base = ready(tab)
            val permission =
                UiReducer.reduce(
                    base,
                    UiIntent.DeviceChanged(
                        presence = DevicePresence.PermissionRequired,
                        device = device,
                        partitions = emptyList(),
                        files = emptyList(),
                    ),
                )
            assertEquals(tab, permission.state.tab)
            assertEquals(listOf(data, efi), permission.state.partitions)
            assertEquals("/ISO", permission.state.currentPath)
            assertTrue(permission.effects.none { it is UiEffect.LoadFiles })

            val busy =
                UiReducer.reduce(
                    permission.state,
                    UiIntent.DeviceChanged(
                        presence = DevicePresence.Busy,
                        device = device,
                        partitions = emptyList(),
                        files = emptyList(),
                    ),
                )
            assertEquals(tab, busy.state.tab)
            assertEquals(listOf(data, efi), busy.state.partitions)
            assertTrue(busy.effects.none { it is UiEffect.LoadFiles })
        }
    }

    @Test
    fun readyAfterProbeKeepsNonFilesTabAndSkipsLoadFiles() {
        for (tab in listOf(ShellTab.Ventoy, ShellTab.Partitions, ShellTab.Diagnostics)) {
            val probing =
                ready(tab).copy(
                    presence = DevicePresence.Busy,
                    pendingConfirmation = null,
                )
            val ready =
                UiReducer.reduce(
                    probing,
                    UiIntent.DeviceChanged(
                        presence = DevicePresence.Ready,
                        device = device,
                        partitions = listOf(data, efi),
                        sectorSize = 512,
                    ),
                )
            assertEquals(tab, ready.state.tab)
            assertTrue(
                "non-Files tab must not mount on Ready",
                ready.effects.none { it is UiEffect.LoadFiles },
            )
            assertEquals(FilesListingState.Stale, ready.state.filesListingState)
        }
    }

    @Test
    fun readyOnFilesTabStillLoadsListing() {
        val probing =
            ready(ShellTab.Files).copy(
                presence = DevicePresence.Busy,
                pendingConfirmation = null,
                files = emptyList(),
                filesListingState = FilesListingState.Stale,
            )
        val ready =
            UiReducer.reduce(
                probing,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    partitions = listOf(data, efi),
                    sectorSize = 512,
                ),
            )
        assertEquals(ShellTab.Files, ready.state.tab)
        assertTrue(ready.effects.any { it is UiEffect.LoadFiles })
        assertEquals(FilesListingState.Loading, ready.state.filesListingState)
    }

    @Test
    fun differentDeviceAttachPreservesTabButDropsOldScopedData() {
        val old = ready(ShellTab.Diagnostics)
        val next =
            UiReducer.reduce(
                old,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = otherDevice,
                    partitions = listOf(data.copy(startLba = 4096uL)),
                    sectorSize = 512,
                ),
            ).state
        assertEquals(ShellTab.Diagnostics, next.tab)
        assertEquals(otherDevice, next.device)
        assertEquals("/", next.currentPath)
        assertNull(next.vtoyEfiWritePartitionId)
        assertNull(next.windowsTemplatePath)
        assertTrue(next.windowsSelectedImages.isEmpty())
        assertNull(next.pendingConfirmation)
    }

    @Test
    fun explicitSelectTabRemainsOnlyNavigationSourceAcrossLifecycle() {
        val selected =
            UiReducer.reduce(ready(ShellTab.Files), UiIntent.SelectTab(ShellTab.Partitions)).state
        assertEquals(ShellTab.Partitions, selected.tab)

        val afterDetach =
            UiReducer.reduce(
                selected,
                UiIntent.DeviceChanged(presence = DevicePresence.Disconnected),
            ).state
        assertEquals(ShellTab.Partitions, afterDetach.tab)

        val afterReattach =
            UiReducer.reduce(
                afterDetach,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device.copy(attachGeneration = 3L),
                    partitions = listOf(data, efi),
                    sectorSize = 512,
                ),
            ).state
        assertEquals(ShellTab.Partitions, afterReattach.tab)
        assertFalse(afterReattach.files.any())
    }
}
