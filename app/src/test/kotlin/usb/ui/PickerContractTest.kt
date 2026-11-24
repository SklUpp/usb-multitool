package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven gates for [PickerSlot] and the download/batch/upload verdict helpers.
 * Local fixture only — does not import ConfirmationTest.ready().
 */
class PickerContractTest {
    private val device =
        DeviceIdentity(
            label = "Picker Stick",
            capacityBytes = 8_000_000_000uL,
            serialNumber = "PICK-CONTRACT",
            attachGeneration = 3L,
        )

    private val partition =
        PartitionTab(
            id = "1",
            name = "DATA",
            readOnly = false,
            startLba = 2048uL,
            sectorCount = 4096uL,
        )

    private val files =
        listOf(
            FileEntry("manual.pdf", isDirectory = false, sizeBytes = 42uL),
            FileEntry("sub", isDirectory = true, sizeBytes = 0uL),
        )

    private fun ready(
        path: String = "/docs",
        listing: List<FileEntry> = files,
        listingState: FilesListingState = FilesListingState.Loaded,
        deviceOverride: DeviceIdentity? = device,
        partitionsOverride: List<PartitionTab> = listOf(partition),
        selectedId: String? = "1",
    ): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = deviceOverride,
            partitions = partitionsOverride,
            selectedPartitionId = selectedId,
            currentPath = path,
            files = listing,
            filesListingState = listingState,
        )

    private fun binding(
        directory: String = "/docs",
        name: String = "manual.pdf",
        sizeBytes: ULong = 42uL,
        attachment: UiAttachmentPin =
            UiAttachmentPin(
                device.identity,
                device.attachGeneration,
                partition.id,
                partition.startLba,
                partition.sectorCount,
            ),
    ) = FilesDownloadBinding(
        directory = directory,
        name = name,
        sizeBytes = sizeBytes,
        attachment = attachment,
    )

    private fun selection(
        directory: String = "/docs",
        items: List<FilesBatchItem> =
            listOf(
                FilesBatchItem("manual.pdf", false, 42uL),
                FilesBatchItem("sub", true, 0uL),
            ),
        identity: String = device.identity,
        attachGeneration: Long = device.attachGeneration,
        partitionId: String = partition.id,
        startLba: ULong? = partition.startLba,
        sectorCount: ULong? = partition.sectorCount,
    ) = FilesBatchSelection(
        directory = directory,
        items = items,
        identity = identity,
        attachGeneration = attachGeneration,
        partitionId = partitionId,
        startLba = startLba,
        sectorCount = sectorCount,
    )

    private fun uploadTarget(
        directory: String = "/docs",
        attachment: UiAttachmentPin =
            UiAttachmentPin(
                device.identity,
                device.attachGeneration,
                partition.id,
                partition.startLba,
                partition.sectorCount,
            ),
    ) = UploadTarget(directory, attachment)

    private fun pickMode(): FilesPickMode =
        FilesPickMode(
            sessionId = "s1",
            purpose = FilesPickPurpose.WindowsImage,
            returnTab = ShellTab.Ventoy,
            target = FilesPickTarget.File,
            identity = device.identity,
            attachGeneration = device.attachGeneration,
            partitionId = partition.id,
            startLba = partition.startLba,
            sectorCount = partition.sectorCount,
        )

    data class VerdictCase(
        val desc: String,
        val state: AppUiState,
        val expected: PickerVerdict,
        val binding: FilesDownloadBinding? = null,
        val batch: FilesBatchSelection? = null,
        val upload: UploadTarget? = null,
    )

    @Test
    fun downloadStageVerdictTable() {
        val base = binding()
        val cases =
            listOf(
                VerdictCase("exact entry", ready(), PickerVerdict.Ok, binding = base),
                VerdictCase(
                    "currentPath changed, attachment live",
                    ready(path = "/other"),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "entry missing from refreshed listing",
                    ready(listing = listOf(FileEntry("sub", true, 0uL))),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "entry size changed",
                    ready(listing = listOf(FileEntry("manual.pdf", false, 99uL), FileEntry("sub", true, 0uL))),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "listing reports directory",
                    ready(listing = listOf(FileEntry("manual.pdf", true, 42uL))),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "identity changed",
                    ready(),
                    PickerVerdict.TargetInvalid,
                    binding = base.copy(attachment = base.attachment.copy(identity = "other")),
                ),
                VerdictCase(
                    "attachGeneration changed",
                    ready(),
                    PickerVerdict.TargetInvalid,
                    binding = base.copy(attachment = base.attachment.copy(attachGeneration = 99L)),
                ),
                VerdictCase(
                    "selectedPartitionId changed",
                    ready(selectedId = "2", partitionsOverride = listOf(partition, partition.copy(id = "2"))),
                    PickerVerdict.TargetInvalid,
                    binding = base,
                ),
                VerdictCase(
                    "startLba changed",
                    ready(partitionsOverride = listOf(partition.copy(startLba = 4096uL))),
                    PickerVerdict.TargetInvalid,
                    binding = base,
                ),
                VerdictCase(
                    "sectorCount changed",
                    ready(partitionsOverride = listOf(partition.copy(sectorCount = 1uL))),
                    PickerVerdict.TargetInvalid,
                    binding = base,
                ),
                VerdictCase(
                    "listing not Loaded",
                    ready(listingState = FilesListingState.Loading),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "name ..",
                    ready(),
                    PickerVerdict.TargetInvalid,
                    binding = base.copy(name = ".."),
                ),
                VerdictCase(
                    "name a/b",
                    ready(),
                    PickerVerdict.TargetInvalid,
                    binding = base.copy(name = "a/b"),
                ),
                VerdictCase(
                    "device null",
                    ready(deviceOverride = null),
                    PickerVerdict.TargetInvalid,
                    binding = base,
                ),
            )
        for (case in cases) {
            assertEquals(
                "stageVerdict: ${case.desc}",
                case.expected,
                case.binding!!.stageVerdict(case.state),
            )
        }
    }

    @Test
    fun downloadResultVerdictTable() {
        val base = binding()
        val cases =
            listOf(
                VerdictCase(
                    "stale currentPath same attach → Ok",
                    ready(path = "/other"),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "filesPickMode set → Busy",
                    ready().copy(filesPickMode = pickMode()),
                    PickerVerdict.Busy,
                    binding = base,
                ),
                VerdictCase(
                    "actionsEnabled false, attachment live → Ok",
                    ready().copy(presence = DevicePresence.PermissionRequired),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "attach generation changed → TargetChanged",
                    ready(deviceOverride = device.copy(attachGeneration = 99L)),
                    PickerVerdict.TargetChanged,
                    binding = base,
                ),
                VerdictCase(
                    "geometry changed → TargetChanged",
                    ready(partitionsOverride = listOf(partition.copy(sectorCount = 1uL))),
                    PickerVerdict.TargetChanged,
                    binding = base,
                ),
                VerdictCase(
                    "device null → TargetChanged",
                    ready(deviceOverride = null),
                    PickerVerdict.TargetChanged,
                    binding = base,
                ),
                VerdictCase(
                    "listing names entry with new size → Ok",
                    ready(listing = listOf(FileEntry("manual.pdf", false, 99uL), FileEntry("sub", true, 0uL))),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "listing lacks entry (not loaded) → Ok",
                    ready(listing = emptyList(), listingState = FilesListingState.Loading),
                    PickerVerdict.Ok,
                    binding = base,
                ),
                VerdictCase(
                    "invalid name chars → TargetChanged",
                    ready(),
                    PickerVerdict.TargetChanged,
                    binding = base.copy(name = "a/b"),
                ),
            )
        for (case in cases) {
            assertEquals(
                "resultVerdict: ${case.desc}",
                case.expected,
                case.binding!!.resultVerdict(case.state),
            )
        }
    }

    @Test
    fun batchStageVerdictTable() {
        val base = selection()
        val cases =
            listOf(
                VerdictCase("baseline", ready(), PickerVerdict.Ok, batch = base),
                VerdictCase(
                    "currentPath changed",
                    ready(path = "/other"),
                    PickerVerdict.TargetInvalid,
                    batch = base,
                ),
                VerdictCase(
                    "one item size changed",
                    ready(listing = listOf(FileEntry("manual.pdf", false, 99uL), FileEntry("sub", true, 0uL))),
                    PickerVerdict.TargetInvalid,
                    batch = base,
                ),
                VerdictCase(
                    "isDirectory mismatch",
                    ready(listing = listOf(FileEntry("manual.pdf", true, 42uL), FileEntry("sub", true, 0uL))),
                    PickerVerdict.TargetInvalid,
                    batch = base,
                ),
                VerdictCase(
                    "duplicate names",
                    ready(),
                    PickerVerdict.TargetInvalid,
                    batch =
                        selection(
                            items =
                                listOf(
                                    FilesBatchItem("manual.pdf", false, 42uL),
                                    FilesBatchItem("manual.pdf", false, 42uL),
                                ),
                        ),
                ),
                VerdictCase(
                    "empty items",
                    ready(),
                    PickerVerdict.TargetInvalid,
                    batch = selection(items = emptyList()),
                ),
                VerdictCase(
                    "filesVolumes geometry changed",
                    ready(partitionsOverride = listOf(partition.copy(startLba = 4096uL))),
                    PickerVerdict.TargetInvalid,
                    batch = base,
                ),
                VerdictCase(
                    "listing not Loaded",
                    ready(listingState = FilesListingState.Stale),
                    PickerVerdict.TargetInvalid,
                    batch = base,
                ),
                VerdictCase(
                    "attach generation changed",
                    ready(deviceOverride = device.copy(attachGeneration = 9L)),
                    PickerVerdict.TargetInvalid,
                    batch = base,
                ),
            )
        for (case in cases) {
            assertEquals(
                "batch.stageVerdict: ${case.desc}",
                case.expected,
                case.batch!!.stageVerdict(case.state),
            )
        }
    }

    @Test
    fun uploadTargetVerdictTable() {
        val base = uploadTarget()
        val cases =
            listOf(
                VerdictCase(
                    "different currentPath → Ok",
                    ready(path = "/elsewhere"),
                    PickerVerdict.Ok,
                    upload = base,
                ),
                VerdictCase(
                    "identity changed → TargetChanged",
                    ready(),
                    PickerVerdict.TargetChanged,
                    upload = uploadTarget(attachment = base.attachment.copy(identity = "other")),
                ),
                VerdictCase(
                    "attach changed → TargetChanged",
                    ready(deviceOverride = device.copy(attachGeneration = 9L)),
                    PickerVerdict.TargetChanged,
                    upload = base,
                ),
                VerdictCase(
                    "partition changed → TargetChanged",
                    ready(selectedId = "2", partitionsOverride = listOf(partition, partition.copy(id = "2"))),
                    PickerVerdict.TargetChanged,
                    upload = base,
                ),
                VerdictCase(
                    "geometry changed → TargetChanged",
                    ready(partitionsOverride = listOf(partition.copy(sectorCount = 1uL))),
                    PickerVerdict.TargetChanged,
                    upload = base,
                ),
                VerdictCase(
                    "device null → TargetChanged",
                    ready(deviceOverride = null),
                    PickerVerdict.TargetChanged,
                    upload = base,
                ),
            )
        for (case in cases) {
            assertEquals(
                "UploadTarget.verdict: ${case.desc}",
                case.expected,
                case.upload!!.verdict(case.state),
            )
        }
    }

    @Test
    fun pickerSlotLifecycleTable() {
        val slot = PickerSlot<String>()
        assertNull(slot.pending)
        assertEquals(0L, slot.pendingRequestId)

        val id1 = slot.stage("a")
        assertEquals(1L, id1)
        assertEquals("a", slot.pending)
        assertEquals(1L, slot.pendingRequestId)

        assertNull("second stage while pending → null", slot.stage("b"))
        assertEquals("pending kept", "a", slot.pending)
        assertEquals(1L, slot.pendingRequestId)

        assertTrue(slot.claimLaunch(1L))
        assertFalse("repeat claim → false", slot.claimLaunch(1L))
        assertFalse("foreign claim → false", slot.claimLaunch(2L))

        assertFalse(slot.clearIf(2L))
        assertEquals("clearIf foreign keeps pending", "a", slot.pending)
        assertEquals(1L, slot.pendingRequestId)

        assertTrue(slot.clearIf(1L))
        assertNull(slot.pending)
        assertEquals(0L, slot.pendingRequestId)

        assertTrue(slot.restore("restored"))
        assertEquals("restored", slot.pending)
        assertEquals(1L, slot.pendingRequestId)
        assertFalse("restore while pending → refused", slot.restore("again"))
        assertEquals("restored", slot.pending)
        assertEquals(1L, slot.pendingRequestId)

        slot.clear()
        val next = slot.stage("after-clear")
        assertEquals("stage after clear → next id", 2L, next)
        assertEquals("after-clear", slot.pending)
        assertNotNull(next)
    }
}
