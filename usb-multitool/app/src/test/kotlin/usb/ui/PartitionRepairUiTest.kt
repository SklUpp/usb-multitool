package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.partition.RawRangeGeometry
import usb.partition.RawSchemeProblem
import usb.partition.SchemeTrust
import usb.partition.TableScheme

/**
 * Nested scheme prompt: backup-GPT repair is offered only for a readable GPT, pins the
 * live partition geometry/names, and is re-dropped when anything changes. Also covers the
 * exact EFI override geometry that the moved switch relies on.
 */
class PartitionRepairUiTest {
    private val device = DeviceIdentity(
        label = "Stick",
        capacityBytes = 8_000_000_000uL,
        serialNumber = "SN1",
    )
    private val data = PartitionTab(
        "1",
        "DATA",
        readOnly = false,
        startLba = 2048uL,
        sectorCount = 4096uL,
    )
    private val esp = PartitionTab(
        "2",
        "VTOYEFI",
        readOnly = true,
        isEfiType = true,
        startLba = 8192uL,
        sectorCount = 2048uL,
    )

    private fun ready(scheme: TableScheme? = TableScheme.GPT) =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = listOf(data, esp),
            selectedPartitionId = data.id,
            partitionScheme = scheme,
        )

    private fun requestRepair(state: AppUiState): ReduceResult =
        UiReducer.reduce(state, UiIntent.RequestDestructive(DestructiveAction.GPT_REPAIR))

    @Test
    fun repairIsRefusedWithoutAReadableGpt() {
        val mbr = requestRepair(ready(TableScheme.MBR))
        assertNull(mbr.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.GptRepairRequiresGpt),
            mbr.state.statusMessage,
        )

        val unknown = requestRepair(ready(null))
        assertNull(unknown.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.GptRepairRequiresGpt),
            unknown.state.statusMessage,
        )
    }

    @Test
    fun repairPinsSchemeGeometryAndNames() {
        val result = requestRepair(ready())
        val confirmation = requireNotNull(result.state.pendingConfirmation)
        assertEquals(DestructiveAction.GPT_REPAIR, confirmation.action)
        assertEquals(PartitionScheme.Gpt, confirmation.initialScheme)
        assertEquals(
            listOf(PartitionRef(2048uL, 4096uL), PartitionRef(8192uL, 2048uL)),
            confirmation.initialPartitions,
        )
        assertEquals(listOf("DATA", "VTOYEFI"), confirmation.initialPartitionNames)
    }

    @Test
    fun repairConfirmationIsDroppedWhenGeometryChanges() {
        val requested = requestRepair(ready())
        val pending = requireNotNull(requested.state.pendingConfirmation)
        val changed = requested.state.copy(
            pendingConfirmation = pending,
            partitions = listOf(data.copy(sectorCount = 8192uL), esp),
        )
        val confirmed = UiReducer.reduce(changed, UiIntent.ConfirmDestructive)
        assertNull(confirmed.state.pendingConfirmation)
        assertTrue(confirmed.effects.isEmpty())
    }

    @Test
    fun efiOverrideOnlyMatchesTheExactPinnedGeometry() {
        val allowed = ready().copy(
            vtoyEfiWritePartitionId = esp.id,
            vtoyEfiWriteStartLba = esp.startLba,
            vtoyEfiWriteSectorCount = esp.sectorCount,
        )
        assertTrue(allowed.isEfiWriteAllowed(esp))

        assertFalse(allowed.copy(vtoyEfiWritePartitionId = data.id).isEfiWriteAllowed(esp))
        assertFalse(allowed.copy(vtoyEfiWriteStartLba = 8193uL).isEfiWriteAllowed(esp))
        assertFalse(allowed.copy(vtoyEfiWriteSectorCount = 2047uL).isEfiWriteAllowed(esp))
    }

    @Test
    fun efiOverrideIsDroppedWhenTheTableBecomesUntrusted() {
        val allowed = ready().copy(
            vtoyEfiWritePartitionId = esp.id,
            vtoyEfiWriteStartLba = esp.startLba,
            vtoyEfiWriteSectorCount = esp.sectorCount,
        )
        val damaged = UiReducer.reduce(
            allowed,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device,
                partitions = listOf(data, esp),
                tableValid = true,
                partitionTableDamaged = true,
                partitionScheme = TableScheme.GPT,
            ),
        )
        assertNull(damaged.state.vtoyEfiWritePartitionId)

        val ambiguous = UiReducer.reduce(
            allowed,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device,
                partitions = listOf(data, esp),
                tableValid = true,
                partitionTableAmbiguous = true,
                partitionScheme = TableScheme.GPT,
            ),
        )
        assertNull(ambiguous.state.vtoyEfiWritePartitionId)
    }

    @Test
    fun writeAccessNeverClaimsActualRw() {
        val base = ready()
        assertEquals(PartitionWriteAccess.ReadOnly, base.partitionWriteAccess(esp))
        assertEquals(PartitionWriteAccess.Unknown, base.partitionWriteAccess(data))

        val overridden = base.copy(
            vtoyEfiWritePartitionId = esp.id,
            vtoyEfiWriteStartLba = esp.startLba,
            vtoyEfiWriteSectorCount = esp.sectorCount,
        )
        // Policy allows writes, but no writable mount was verified: still Unknown.
        assertEquals(PartitionWriteAccess.Unknown, overridden.partitionWriteAccess(esp))
        // Wrong geometry cannot inherit the override.
        assertEquals(
            PartitionWriteAccess.ReadOnly,
            overridden.copy(vtoyEfiWriteSectorCount = 2047uL).partitionWriteAccess(esp),
        )
    }

    @Test
    fun schemeChangeIsRefusedWhenBackupMetadataIsUnknown() {
        val rawUnknown = RawRangeGeometry(
            diskSectorCount = device.capacityBytes / 512UL,
            sectorSize = 512,
            trust = SchemeTrust.Confirmed(TableScheme.GPT),
            metadataProblem = RawSchemeProblem.GptBackupMetadataUnknown,
        )
        val result = UiReducer.reduce(
            ready().copy(rawGeometry = rawUnknown),
            UiIntent.RequestInitializePartitionTable(PartitionScheme.Mbr),
        )
        assertNull(result.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.SchemeBackupMetadataUnknown),
            result.state.statusMessage,
        )
    }

    @Test
    fun schemeChangeFromGptIsRefusedWhenBackupDataIsUnverified() {
        val result = UiReducer.reduce(
            ready().copy(rawGeometry = null),
            UiIntent.RequestInitializePartitionTable(PartitionScheme.Mbr),
        )
        assertNull(result.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.SchemeBackupUnverified),
            result.state.statusMessage,
        )
    }

    @Test
    fun mbrLimitComparesSectorsNotRawBytes() {
        val limit = UiReducer.mbrAddressLimitSectors()
        assertEquals(0xFFFF_FFFFUL, limit)
        val sectorSize = 512UL
        val exactlyAtLimitBytes = limit * sectorSize + (sectorSize - 1UL)
        val trustedRaw = RawRangeGeometry(
            diskSectorCount = exactlyAtLimitBytes / sectorSize,
            sectorSize = sectorSize.toInt(),
            trust = SchemeTrust.Confirmed(TableScheme.GPT),
        )
        val allowed = UiReducer.reduce(
            ready().copy(
                device = device.copy(capacityBytes = exactlyAtLimitBytes),
                rawGeometry = trustedRaw,
            ),
            UiIntent.RequestInitializePartitionTable(PartitionScheme.Mbr),
        )
        assertNotNull(allowed.state.pendingConfirmation)

        val oneSectorOver = limit * sectorSize + sectorSize
        val blocked = UiReducer.reduce(
            ready().copy(
                device = device.copy(capacityBytes = oneSectorOver),
            ),
            UiIntent.RequestInitializePartitionTable(PartitionScheme.Mbr),
        )
        assertNull(blocked.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.SchemeMbrTooLarge),
            blocked.state.statusMessage,
        )
    }
}
