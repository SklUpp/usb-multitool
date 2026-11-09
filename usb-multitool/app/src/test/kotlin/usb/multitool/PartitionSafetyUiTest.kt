package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import usb.ui.AppUiState
import usb.ui.DestructiveAction
import usb.ui.DeviceIdentity
import usb.ui.DevicePresence
import usb.ui.PartitionTab
import usb.ui.UiIntent
import usb.ui.UiReducer
import usb.partition.Partition

class PartitionSafetyUiTest {
    private val espGuid = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"

    @Test
    fun mapperProtectsGptGuidAndMbrType() {
        val rows = mapPartitionTabs(
            listOf(
                Partition(1UL, 2UL, type = espGuid, name = "ESP"),
                Partition(3UL, 2UL, type = "0xEF", name = "P2"),
            ),
        )
        assertEquals(listOf(true, true), rows.map { it.readOnly })
        assertEquals(listOf(true, true), rows.map { it.isVtoyEfi })
    }

    @Test
    fun reducerBlocksEfiDeleteAndFormatUntilOverride() {
        val base = AppUiState(
            presence = DevicePresence.Ready,
            device = DeviceIdentity("USB", 100uL),
            partitions = listOf(PartitionTab("1", "ESP", readOnly = true, isVtoyEfi = true)),
            selectedPartitionId = "1",
        )
        listOf(DestructiveAction.DELETE_PARTITION, DestructiveAction.FORMAT_PARTITION).forEach { action ->
            val blocked = UiReducer.reduce(base, UiIntent.RequestDestructive(action)).state
            assertNull(blocked.pendingConfirmation)
            assertNotNull(blocked.statusMessage)
            val allowed = UiReducer.reduce(base.copy(vtoyEfiWriteEnabled = true), UiIntent.RequestDestructive(action)).state
            assertNotNull(allowed.pendingConfirmation)
        }
    }
}
