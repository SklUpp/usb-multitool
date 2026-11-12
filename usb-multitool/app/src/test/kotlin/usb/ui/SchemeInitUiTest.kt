package usb.ui

import org.junit.Assert.assertEquals

import org.junit.Assert.assertNull
import org.junit.Test
import usb.partition.TableScheme

/** Empty-table initialization and scheme replacement: pinned confirmation, fail-closed guards. */
class SchemeInitUiTest {
    private val device = DeviceIdentity(label = "Stick", capacityBytes = 8_000_000_000uL)

    private fun ready(scheme: TableScheme? = TableScheme.MBR) =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = emptyList(),
            partitionScheme = scheme,
        )

    private fun pending(
        state: AppUiState,
        scheme: PartitionScheme,
    ): ReduceResult =
        UiReducer.reduce(state, UiIntent.RequestInitializePartitionTable(scheme))

    @Test
    fun initializationPinsSchemeAndEmptySnapshot() {
        val result = pending(ready(TableScheme.MBR), PartitionScheme.Gpt)
        val confirmation = requireNotNull(result.state.pendingConfirmation)
        assertEquals(DestructiveAction.INITIALIZE_PARTITION_TABLE, confirmation.action)
        assertEquals(PartitionScheme.Mbr, confirmation.initialScheme)
        assertEquals(PartitionScheme.Gpt, confirmation.targetScheme)
        assertEquals(emptyList<PartitionRef>(), confirmation.initialPartitions)

        val confirmed = UiReducer.reduce(result.state, UiIntent.ConfirmDestructive)
        assertNull(confirmed.state.pendingConfirmation)
        assertEquals(DestructiveAction.INITIALIZE_PARTITION_TABLE, (confirmed.effects.single() as UiEffect.SubmitDestructive).action)
        assertEquals(PartitionScheme.Gpt, (confirmed.effects.single() as UiEffect.SubmitDestructive).confirmation.targetScheme)
    }

    @Test
    fun existingPartitionsArePinnedForSchemeChange() {
        val base = ready(TableScheme.MBR).copy(
            partitions = listOf(
                PartitionTab("1", "DATA", readOnly = false, startLba = 2048uL, sectorCount = 4096uL),
                PartitionTab("2", "SECOND", readOnly = false, startLba = 8192uL, sectorCount = 1024uL),
            ),
        )
        val result = pending(base, PartitionScheme.Gpt)
        val confirmation = requireNotNull(result.state.pendingConfirmation)
        assertEquals(
            listOf(PartitionRef(2048uL, 4096uL), PartitionRef(8192uL, 1024uL)),
            confirmation.initialPartitions,
        )
        assertEquals(listOf("DATA", "SECOND"), confirmation.initialPartitionNames)
    }

    @Test
    fun sameSchemeUnknownSchemeAndNoGeometryAreRefused() {
        val same = pending(ready(TableScheme.GPT), PartitionScheme.Gpt)
        assertNull(same.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.SchemeAlreadyCurrent), same.state.statusMessage)

        val unknown = pending(ready(null), PartitionScheme.Gpt)
        assertNull(unknown.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.SchemeUnknown), unknown.state.statusMessage)

        val noGeometry = pending(
            ready(TableScheme.MBR).copy(
                partitions = listOf(PartitionTab("1", "DATA", readOnly = false)),
            ),
            PartitionScheme.Gpt,
        )
        assertNull(noGeometry.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.PartitionGeometryUnknown),
            noGeometry.state.statusMessage,
        )
    }

    @Test
    fun damagedAndAmbiguousLayoutsAreRefusedFailClosed() {
        val damaged = pending(ready(TableScheme.GPT).copy(partitionTableDamaged = true), PartitionScheme.Mbr)
        assertNull(damaged.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.GptPrimaryDamaged), damaged.state.statusMessage)

        val ambiguous = pending(ready(TableScheme.MBR).copy(partitionTableAmbiguous = true), PartitionScheme.Gpt)
        assertNull(ambiguous.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.PartitionTableAmbiguous), ambiguous.state.statusMessage)
    }

    @Test
    fun mbrTargetIsRefusedForDiskBeyond32BitLba() {
        val huge = ready(TableScheme.GPT).copy(
            device = DeviceIdentity(label = "Huge", capacityBytes = 3_000_000_000_000uL),
        )
        val result = pending(huge, PartitionScheme.Mbr)
        assertNull(result.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.SchemeMbrTooLarge), result.state.statusMessage)
    }

    @Test
    fun directInitializeActionCannotBypassThePinnedIntent() {
        val result = UiReducer.reduce(
            ready(TableScheme.MBR),
            UiIntent.RequestDestructive(DestructiveAction.INITIALIZE_PARTITION_TABLE),
        )
        assertNull(result.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.SchemeUnknown), result.state.statusMessage)
    }

    @Test
    fun confirmationOfAMismatchedSchemeIsDropped() {
        val result = pending(ready(TableScheme.MBR), PartitionScheme.Gpt)
        val pendingConfirmation = result.state.pendingConfirmation!!
        val mismatched = result.state.copy(
            partitionScheme = TableScheme.GPT,
            pendingConfirmation = pendingConfirmation,
        )
        val confirmed = UiReducer.reduce(mismatched, UiIntent.ConfirmDestructive)
        assertNull(confirmed.state.pendingConfirmation)
        assertEquals(0, confirmed.effects.size)
    }
}
