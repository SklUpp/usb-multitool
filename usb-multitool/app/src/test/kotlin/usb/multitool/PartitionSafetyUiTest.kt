package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.partition.EFI_SYSTEM_TYPE_GUID
import usb.partition.Partition
import usb.ui.AppUiState
import usb.ui.DeviceIdentity
import usb.ui.DevicePresence
import usb.ui.DestructiveAction
import usb.ui.PartitionTab
import usb.ui.UiIntent
import usb.ui.UiReducer

class PartitionSafetyUiTest {
    private val espGuid = EFI_SYSTEM_TYPE_GUID

    private val efiA = PartitionTab(
        "1",
        "ESP-A",
        readOnly = true,
        isEfiType = true,
        startLba = 2048uL,
        sectorCount = 4096uL,
    )
    private val efiB = PartitionTab(
        "2",
        "ESP-B",
        readOnly = true,
        isEfiType = true,
        startLba = 8192uL,
        sectorCount = 4096uL,
    )
    private val data = PartitionTab(
        "3",
        "DATA",
        readOnly = false,
        startLba = 16384uL,
        sectorCount = 8192uL,
    )

    private fun attachedState(attached: DeviceIdentity) =
        AppUiState(
            presence = DevicePresence.Ready,
            device = attached,
            partitions = listOf(efiA, efiB, data),
            selectedPartitionId = efiA.id,
        )

    private fun unlockedA(attached: DeviceIdentity): AppUiState =
        UiReducer.reduce(
            attachedState(attached),
            UiIntent.SetVtoyEfiWritable(efiA.id, true),
        ).state

    @Test
    fun mapperProtectsGptGuidAndMbrType() {
        val rows = mapPartitionTabs(
            listOf(
                Partition(1UL, 2UL, type = espGuid, name = "ESP"),
                Partition(3UL, 2UL, type = "0xEF", name = "P2"),
            ),
        )
        assertEquals(listOf(true, true), rows.map { it.readOnly })
        assertEquals(listOf(true, true), rows.map { it.isEfiType })
    }

    @Test
    fun vtoyefiNameStaysProtectedWithoutClaimingEfiType() {
        val rows = mapPartitionTabs(
            listOf(
                Partition(1UL, 2UL, type = "BASIC", name = "VTOYEFI"),
                Partition(3UL, 2UL, type = "BASIC", name = "DATA"),
            ),
        )
        assertTrue(rows[0].readOnly)
        assertFalse(rows[0].isEfiType)
        assertFalse(rows[1].readOnly)
        assertFalse(rows[1].isEfiType)
    }

    @Test
    fun reducerBlocksEfiDeleteAndFormatUntilThatPartitionIsUnlocked() {
        val attached = DeviceIdentity("USB", 100uL, serialNumber = "SN-1", attachGeneration = 1L)
        listOf(DestructiveAction.DELETE_PARTITION, DestructiveAction.FORMAT_PARTITION).forEach { action ->
            val blocked = UiReducer.reduce(attachedState(attached), UiIntent.RequestDestructive(action)).state
            assertNull(blocked.pendingConfirmation)
            assertNotNull(blocked.statusMessage)

            val allowed = UiReducer.reduce(unlockedA(attached), UiIntent.RequestDestructive(action)).state
            assertNotNull(allowed.pendingConfirmation)
        }
    }

    @Test
    fun unlockOfOneEfiNeverAllowsAnotherEfi() {
        val attached = DeviceIdentity("USB", 100uL, serialNumber = "SN-1", attachGeneration = 1L)
        val unlocked = unlockedA(attached)
        assertTrue(unlocked.isEfiWriteAllowed(efiA))
        assertFalse(unlocked.isEfiWriteAllowed(efiB))
        assertTrue(unlocked.isEfiWriteAllowed(data))

        val efiBSelected = unlocked.copy(selectedPartitionId = efiB.id)
        listOf(
            UiIntent.CreateFolder("x"),
            UiIntent.DeleteEntry("x"),
            UiIntent.UploadFile("content://x"),
        ).forEach { intent ->
            val result = UiReducer.reduce(efiBSelected, intent)
            assertTrue("$intent wrote to another EFI", result.effects.isEmpty())
            assertEquals(
                usb.ui.UiMessage.Key(usb.ui.UiMessageKey.ReadOnlyPartition, listOf(efiB.name)),
                result.state.statusMessage,
            )
        }

        UiReducer.reduce(
            efiBSelected,
            UiIntent.RequestDestructive(DestructiveAction.DELETE_PARTITION),
        ).let { assertNull(it.state.pendingConfirmation) }
        UiReducer.reduce(
            efiBSelected,
            UiIntent.RequestDestructive(DestructiveAction.FORMAT_PARTITION),
        ).let { assertNull(it.state.pendingConfirmation) }
    }

    @Test
    fun efiWriteOverrideOnlyAppliesToTheCurrentAttachmentAndGeometry() {
        val attached = DeviceIdentity("Ventoy Stick", 100uL, serialNumber = "SN-1", attachGeneration = 7L)
        val base = unlockedA(attached)
        assertTrue(base.vtoyEfiWriteEnabled)

        // A partition refresh of the same attach keeps the exact partition binding.
        val refreshed = UiReducer.reduce(
            base,
            UiIntent.DeviceChanged(DevicePresence.Ready, attached, partitions = base.partitions),
        ).state
        assertTrue(refreshed.vtoyEfiWriteEnabled)

        // Same ID but different geometry is a different partition: the binding must drop.
        val shifted = UiReducer.reduce(
            base,
            UiIntent.DeviceChanged(
                DevicePresence.Ready,
                attached,
                partitions = listOf(efiA.copy(startLba = 4096uL), efiB, data),
            ),
        ).state
        assertFalse(shifted.vtoyEfiWriteEnabled)

        // Re-attaching the same stick is a new generation: the override must not survive.
        val reattached = UiReducer.reduce(
            base,
            UiIntent.DeviceChanged(
                DevicePresence.Ready,
                attached.copy(attachGeneration = 8L),
                partitions = base.partitions,
            ),
        ).state
        assertFalse(reattached.vtoyEfiWriteEnabled)

        // A different stick never inherits the override.
        val swapped = UiReducer.reduce(
            base,
            UiIntent.DeviceChanged(
                DevicePresence.Ready,
                DeviceIdentity("Other Stick", 100uL, serialNumber = "SN-2", attachGeneration = 7L),
                partitions = base.partitions,
            ),
        ).state
        assertFalse(swapped.vtoyEfiWriteEnabled)

        // Detach drops it as well.
        val detached = UiReducer.reduce(base, UiIntent.DeviceChanged(DevicePresence.Disconnected)).state
        assertFalse(detached.vtoyEfiWriteEnabled)
    }

    @Test
    fun efiWriteOverrideCannotBeEnabledWithoutMatchingEfiGeometry() {
        val noEfi = AppUiState(
            presence = DevicePresence.Ready,
            device = DeviceIdentity("USB", 100uL),
            partitions = listOf(data),
            selectedPartitionId = data.id,
        )
        val missing = UiReducer.reduce(noEfi, UiIntent.SetVtoyEfiWritable("1", true)).state
        assertFalse(missing.vtoyEfiWriteEnabled)
        assertNotNull(missing.statusMessage)

        val noGeometry = AppUiState(
            presence = DevicePresence.Ready,
            device = DeviceIdentity("USB", 100uL),
            partitions = listOf(PartitionTab("1", "ESP", readOnly = true, isEfiType = true)),
            selectedPartitionId = "1",
        )
        val unknown = UiReducer.reduce(noGeometry, UiIntent.SetVtoyEfiWritable("1", true)).state
        assertFalse(unknown.vtoyEfiWriteEnabled)
        assertNotNull(unknown.statusMessage)

        val disabled = UiReducer.reduce(
            unlockedA(DeviceIdentity("USB", 100uL, serialNumber = "SN", attachGeneration = 1L)),
            UiIntent.SetVtoyEfiWritable(efiA.id, false),
        ).state
        assertFalse(disabled.vtoyEfiWriteEnabled)
    }
}
