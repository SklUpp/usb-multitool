package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.ventoy.Autounattend
import usb.ventoy.AutounattendParams
import usb.ventoy.AutounattendProblem
import usb.ventoy.DiskLayout
import usb.ventoy.DiskPartitionRole
import usb.ventoy.LayoutPartition
import usb.ventoy.autounattendPlanSha256
import usb.ventoy.diskLayoutInstallTargetPartitionId
import usb.ventoy.diskLayoutRemainingMb
import usb.ventoy.diskLayoutTotalMb

/**
 * Target-PC disk editor contracts: checked sum/excess, install target, mandatory declared
 * size at the confirmation gate, immutable snapshot, and plan-hash tamper refusal.
 *
 * No target-PC I/O exists here; the app only writes the answer file to the stick.
 */
class TargetDiskLayoutEditorTest {
    private val xmlName = "autounattend.xml"

    private fun partition(id: String = "1") = PartitionTab(
        id = id,
        name = "Ventoy",
        readOnly = false,
        startLba = 2048uL,
        sectorCount = 4096uL,
        filesystemId = FilesystemId.FAT,
    )

    private fun ready() = AppUiState(
        presence = DevicePresence.Ready,
        device =
            DeviceIdentity(
                label = "Test Stick",
                capacityBytes = 8_000_000_000uL,
                serialNumber = "SN-EDITOR-1",
                attachGeneration = 1L,
            ),
        partitions = listOf(partition()),
        selectedPartitionId = "1",
        diskSectorSize = 512,
    )

    private fun layout(
        partitions: List<LayoutPartition>,
        declaredSizeMb: Long? = 64_000,
        wipe: Boolean = false,
        diskId: Long? = 0,
    ) = DiskLayout(
        writeLayout = true,
        diskId = diskId,
        wipe = wipe,
        partitions = partitions,
        declaredSizeMb = declaredSizeMb,
    )

    private fun standardLayout() = layout(
        partitions =
            listOf(
                LayoutPartition(100, DiskPartitionRole.Efi, "EFI"),
                LayoutPartition(16, DiskPartitionRole.Msr),
                LayoutPartition(50_000, DiskPartitionRole.Primary, "Windows"),
                LayoutPartition(512, DiskPartitionRole.Recovery, "Recovery"),
            ),
    )

    @Test
    fun totalAndRemainingUseCheckedArithmetic() {
        val partitions =
            listOf(
                LayoutPartition(100, DiskPartitionRole.Efi),
                LayoutPartition(16, DiskPartitionRole.Msr),
                LayoutPartition(50_000, DiskPartitionRole.Primary),
            )
        assertEquals(50_116L, requireNotNull(diskLayoutTotalMb(partitions)))
        assertEquals(13_884L, requireNotNull(diskLayoutRemainingMb(layout(partitions))))
        assertNull(
            diskLayoutTotalMb(
                listOf(
                    LayoutPartition(Long.MAX_VALUE, DiskPartitionRole.Primary),
                    LayoutPartition(1, DiskPartitionRole.Primary),
                ),
            ),
        )
        assertNull(diskLayoutRemainingMb(layout(partitions, declaredSizeMb = null)))
    }

    @Test
    fun installTargetIsTheFirstPrimary() {
        val partitions =
            listOf(
                LayoutPartition(100, DiskPartitionRole.Efi),
                LayoutPartition(10, DiskPartitionRole.Primary, "Data"),
                LayoutPartition(20, DiskPartitionRole.Primary, "Windows"),
            )
        assertEquals(2, requireNotNull(diskLayoutInstallTargetPartitionId(partitions)))
        assertNull(diskLayoutInstallTargetPartitionId(listOf(LayoutPartition(100, DiskPartitionRole.Msr))))
    }

    @Test
    fun validationChecksExcessOverflowAndLabels() {
        val valid =
            Autounattend.validate(
                AutounattendParams(diskLayout = standardLayout()),
                requireDeclaredDiskSize = true,
            )
        assertTrue(valid.problems.isEmpty())
        assertFalse(valid.schemaChecked)

        val excess =
            Autounattend.validate(
                AutounattendParams(
                    diskLayout =
                        layout(
                            partitions =
                                listOf(
                                    LayoutPartition(100, DiskPartitionRole.Efi),
                                    LayoutPartition(64_000, DiskPartitionRole.Primary),
                                ),
                            declaredSizeMb = 64_000,
                        ),
                ),
                requireDeclaredDiskSize = true,
            )
        assertTrue(AutounattendProblem.LayoutExceedsDiskSize in excess.problems)

        val overflow =
            Autounattend.validate(
                AutounattendParams(
                    diskLayout =
                        layout(
                            partitions =
                                listOf(
                                    LayoutPartition(100, DiskPartitionRole.Efi),
                                    LayoutPartition(Long.MAX_VALUE, DiskPartitionRole.Primary),
                                    LayoutPartition(1, DiskPartitionRole.Primary),
                                ),
                            declaredSizeMb = Long.MAX_VALUE,
                        ),
                ),
                requireDeclaredDiskSize = true,
            )
        assertTrue(AutounattendProblem.LayoutSizeOverflow in overflow.problems)

        val missingSize =
            Autounattend.validate(
                AutounattendParams(diskLayout = standardLayout().copy(declaredSizeMb = null)),
                requireDeclaredDiskSize = true,
            )
        assertTrue(AutounattendProblem.LayoutDiskSizeMissing in missingSize.problems)
        assertTrue(
            Autounattend.validate(
                AutounattendParams(diskLayout = standardLayout().copy(declaredSizeMb = null)),
            ).problems.isEmpty(),
        )

        val badLabel =
            Autounattend.validate(
                AutounattendParams(
                    diskLayout =
                        layout(
                            partitions =
                                listOf(
                                    LayoutPartition(100, DiskPartitionRole.Efi, " "),
                                    LayoutPartition(64_000, DiskPartitionRole.Primary, "x".repeat(33)),
                                ),
                        ),
                ),
                requireDeclaredDiskSize = true,
            )
        assertTrue(AutounattendProblem.BadPartitionLabel in badLabel.problems)
    }

    @Test
    fun planHashCoversTheDeclaredDiskSize() {
        val base = AutounattendParams(diskLayout = standardLayout())
        assertNotEquals(
            autounattendPlanSha256(xmlName, base, emptyList()),
            autounattendPlanSha256(
                xmlName,
                base.copy(diskLayout = standardLayout().copy(declaredSizeMb = 128_000)),
                emptyList(),
            ),
        )
    }

    @Test
    fun invalidLayoutNeverReachesConfirmation() {
        val params =
            AutounattendParams(
                languageTag = "en-US",
                diskLayout =
                    layout(
                        partitions =
                            listOf(
                                LayoutPartition(100, DiskPartitionRole.Efi),
                                LayoutPartition(64_001, DiskPartitionRole.Primary),
                            ),
                        declaredSizeMb = 64_000,
                    ),
            )
        val result = UiReducer.reduce(ready(), UiIntent.RequestGenerateAutounattend(xmlName, params))
        assertNull(result.state.diskLayoutConfirmation)
        assertNull(result.state.pendingAutounattendGeneration)
        assertTrue(result.effects.isEmpty())
        val status = result.state.statusMessage
        assertTrue(status is UiMessage.InvalidTargetLayout)
        assertTrue(
            AutounattendProblem.LayoutExceedsDiskSize in
                (status as UiMessage.InvalidTargetLayout).problems,
        )
    }

    @Test
    fun missingDeclaredSizeNeverReachesConfirmation() {
        val params =
            AutounattendParams(
                languageTag = "en-US",
                diskLayout = standardLayout().copy(declaredSizeMb = null),
            )
        val result = UiReducer.reduce(ready(), UiIntent.RequestGenerateAutounattend(xmlName, params))
        assertNull(result.state.diskLayoutConfirmation)
        assertTrue(result.effects.isEmpty())
        val status = result.state.statusMessage
        assertTrue(status is UiMessage.InvalidTargetLayout)
        assertTrue(
            AutounattendProblem.LayoutDiskSizeMissing in
                (status as UiMessage.InvalidTargetLayout).problems,
        )
    }

    @Test
    fun validLayoutOpensACompleteSnapshotAndGeneratesOnlyAfterAck() {
        val params =
            AutounattendParams(
                languageTag = "en-US",
                diskLayout = standardLayout().copy(wipe = true),
            )
        val requested =
            UiReducer.reduce(ready(), UiIntent.RequestGenerateAutounattend(xmlName, params))
        assertTrue(requested.effects.isEmpty())
        val confirmation = requireNotNull(requested.state.diskLayoutConfirmation)
        assertNotNull(requested.state.pendingAutounattendGeneration)
        assertEquals(0L, requireNotNull(confirmation.diskId))
        assertEquals(64_000L, requireNotNull(confirmation.declaredSizeMb))
        assertEquals(50_628L, requireNotNull(confirmation.totalMb))
        assertEquals(13_372L, requireNotNull(confirmation.remainingMb))
        assertEquals(3, requireNotNull(confirmation.installTargetPartitionId))
        assertEquals(4, confirmation.partitionCount)
        assertEquals(requireNotNull(params.diskLayout), confirmation.toLayout())
        assertTrue(confirmation.wipe)
        assertEquals(
            autounattendPlanSha256(xmlName, params, emptyList()),
            confirmation.planSha256,
        )

        val unacknowledged =
            UiReducer.reduce(requested.state, UiIntent.ConfirmDiskLayout(acknowledged = false))
        assertTrue(unacknowledged.effects.isEmpty())
        assertNotNull(unacknowledged.state.diskLayoutConfirmation)

        val confirmed =
            UiReducer.reduce(requested.state, UiIntent.ConfirmDiskLayout(acknowledged = true))
        val effect = confirmed.effects.single() as UiEffect.GenerateAutounattend
        assertEquals(xmlName, effect.xmlName)
        assertEquals(params, effect.autounattendParams)
        assertEquals(confirmation.planSha256, effect.expectedPlanSha256)
        assertNull(confirmed.state.diskLayoutConfirmation)
        assertNull(confirmed.state.pendingAutounattendGeneration)
    }

    @Test
    fun confirmationRevalidatesAPendingInvalidLayout() {
        val state = ready()
        val params =
            AutounattendParams(
                languageTag = "en-US",
                diskLayout =
                    layout(
                        partitions =
                            listOf(
                                LayoutPartition(100, DiskPartitionRole.Efi),
                                LayoutPartition(64_001, DiskPartitionRole.Primary),
                            ),
                        declaredSizeMb = 64_000,
                    ),
            )
        val plan = autounattendPlanSha256(xmlName, params, emptyList())
        val pending =
            PendingAutounattendGeneration(
                xmlName = xmlName,
                params = params,
                imagePaths = emptyList(),
                overwriteXml = false,
                allowDestructiveTemplate = false,
                identity = requireNotNull(state.device).identity,
                attachGeneration = 1L,
                expectedPlanSha256 = plan,
            )
        val withConfirmation =
            state.copy(
                pendingAutounattendGeneration = pending,
                diskLayoutConfirmation =
                    DiskLayoutConfirmation(
                        diskId = 0,
                        partitionCount = 2,
                        writeLayout = true,
                        planSha256 = plan,
                    ),
            )
        val result = UiReducer.reduce(withConfirmation, UiIntent.ConfirmDiskLayout(acknowledged = true))
        assertTrue(result.effects.isEmpty())
        assertNull(result.state.diskLayoutConfirmation)
        assertNull(result.state.pendingAutounattendGeneration)
        val status = result.state.statusMessage
        assertTrue(status is UiMessage.InvalidTargetLayout)
        assertTrue(
            AutounattendProblem.LayoutExceedsDiskSize in
                (status as UiMessage.InvalidTargetLayout).problems,
        )
    }

    @Test
    fun changedSnapshotIsRefusedBeforeGeneration() {
        val params = AutounattendParams(languageTag = "en-US", diskLayout = standardLayout())
        val requested =
            UiReducer.reduce(ready(), UiIntent.RequestGenerateAutounattend(xmlName, params))
        val confirmation = requireNotNull(requested.state.diskLayoutConfirmation)
        assertFalse(confirmation.wipe)
        val tampered =
            requested.state.copy(
                diskLayoutConfirmation = confirmation.copy(planSha256 = "not-the-plan"),
            )
        val result = UiReducer.reduce(tampered, UiIntent.ConfirmDiskLayout(acknowledged = true))
        assertTrue(result.effects.isEmpty())
        assertNull(result.state.diskLayoutConfirmation)
        assertNull(result.state.pendingAutounattendGeneration)
        val status = result.state.statusMessage
        assertTrue(status is UiMessage.Raw)
        assertTrue((status as UiMessage.Raw).text.contains("plan changed"))
    }

    private fun DiskLayoutConfirmation.toLayout() =
        DiskLayout(
            writeLayout = writeLayout,
            diskId = diskId,
            wipe = wipe,
            partitions = partitions,
            declaredSizeMb = declaredSizeMb,
        )
}
