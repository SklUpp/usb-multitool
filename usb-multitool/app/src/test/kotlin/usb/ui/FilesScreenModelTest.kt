package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.fs.FilesystemAccess
import usb.fs.FilesystemSpace
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageSnapshot

/**
 * Truthfulness rules behind the Files screen: the RO/RW badge only reports guaranteed EFI
 * protection as RO and never claims verified write access, the folder header is rooted at "/",
 * and the format label is not inferred past the characters in the name.
 */
class FilesScreenModelTest {
    private fun partition(
        id: String = "1",
        name: String = "Ventoy",
        readOnly: Boolean = false,
        filesystem: FilesystemId? = FilesystemId.FAT,
        startLba: ULong? = 2048uL,
        sectorCount: ULong? = 4096uL,
    ) = PartitionTab(
        id = id,
        name = name,
        readOnly = readOnly,
        filesystemId = filesystem,
        startLba = startLba,
        sectorCount = sectorCount,
    )

    private fun state(
        partition: PartitionTab,
        overrideId: String? = null,
        overrideStart: ULong? = null,
        overrideCount: ULong? = null,
    ) = AppUiState(
        presence = DevicePresence.Ready,
        device = DeviceIdentity(label = "Test Stick", capacityBytes = 8_000_000_000uL),
        partitions = listOf(partition),
        selectedPartitionId = partition.id,
        vtoyEfiWritePartitionId = overrideId,
        vtoyEfiWriteStartLba = overrideStart,
        vtoyEfiWriteSectorCount = overrideCount,
    )

    @Test
    fun efiProtectionWinsWithoutOverride() {
        val efi = partition(readOnly = true)
        assertEquals(PartitionWriteAccess.ReadOnly, state(efi).partitionWriteAccess(efi))
    }

    @Test
    fun efiOverrideLiftsProtectionButDoesNotClaimVerifiedWrite() {
        val efi = partition(readOnly = true)
        val state = state(efi, overrideId = efi.id, overrideStart = 2048uL, overrideCount = 4096uL)
        assertEquals(PartitionWriteAccess.Unknown, state.partitionWriteAccess(efi))
    }

    @Test
    fun efiOverrideOnOtherGeometryStaysReadOnly() {
        val efi = partition(readOnly = true)
        val state = state(efi, overrideId = efi.id, overrideStart = 2048uL, overrideCount = 4095uL)
        assertEquals(PartitionWriteAccess.ReadOnly, state.partitionWriteAccess(efi))
    }

    @Test
    fun knownFilesystemDoesNotClaimVerifiedWrite() {
        val data = partition(filesystem = FilesystemId.EXFAT)
        assertEquals(PartitionWriteAccess.Unknown, state(data).partitionWriteAccess(data))
    }

    @Test
    fun matchingDriverReadWriteIsDisplayedOnlyForMatchingMountKey() {
        val data = partition()
        val base = state(data)
        val key = requireNotNull(base.filesystemUsageKey(data))
        val measured = base.copy(
            filesystemUsage = FilesystemUsageSnapshot(
                key,
                FilesystemUsage.Known(FilesystemSpace(10uL, 5uL), 1L),
                FilesystemAccess.ReadWrite,
            ),
        )
        assertEquals(PartitionWriteAccess.ReadWrite, measured.partitionWriteAccess(data))
        assertEquals(PartitionWriteAccess.Unknown, measured.partitionWriteAccess(data.copy(sectorCount = 4095uL)))
    }

    @Test
    fun unknownFilesystemIsNeverRw() {
        val data = partition(filesystem = null)
        assertEquals(PartitionWriteAccess.Unknown, state(data).partitionWriteAccess(data))
    }

    @Test
    fun ntfsWriteStateIsUnknownUntilMounted() {
        val data = partition(filesystem = FilesystemId.NTFS)
        assertEquals(PartitionWriteAccess.Unknown, state(data).partitionWriteAccess(data))
    }

    @Test
    fun folderPathIsAlwaysRootedWithTrailingSlash() {
        assertEquals("/", folderPathLabel("/"))
        assertEquals("/", folderPathLabel(""))
        assertEquals("/masterdvd/", folderPathLabel("/masterdvd"))
        assertEquals("/a/b/", folderPathLabel("/a/b/"))
    }

    @Test
    fun formatIsTakenFromTheNameOnly() {
        assertEquals("ISO", fileFormatLabel("win11.iso", false))
        assertEquals("GZ", fileFormatLabel("archive.tar.gz", false))
        assertNull(fileFormatLabel("README", false))
        assertNull(fileFormatLabel(".hidden", false))
        assertNull(fileFormatLabel("trailing.", false))
        assertNull(fileFormatLabel("folder.iso", true))
    }

    @Test
    fun folderNamesAreNeverSplitAsExtensions() {
        assertEquals(FileNameParts("backup.2024", ""), fileNameParts("backup.2024", true))
        assertEquals(FileNameParts("win11.old", ""), fileNameParts("win11.old", true))
        assertEquals(FileNameParts("win11", ".iso"), fileNameParts("win11.iso", false))
    }

    @Test
    fun partitionDisplayNameKeepsNameAndOptionalVolumeLabel() {
        assertEquals("Ventoy (DATA)", partition().copy(volumeLabel = "DATA").let(::partitionDisplayName))
        assertEquals("Ventoy", partitionDisplayName(partition()))
        assertEquals(
            "1",
            partitionDisplayName(partition(name = "", id = "1").copy(volumeLabel = "  ")),
        )
    }

    @Test
    fun resolveListingSelectionDropsMissingNames() {
        val files = listOf(
            FileEntry("a.bin", false, 1uL),
            FileEntry("docs", true, 0uL),
        )
        val selection = resolveListingSelection(files, setOf("a.bin", "gone", "docs"))
        assertEquals(listOf(files[0], files[1]), selection.entries)
        assertNull(selection.single)
    }

    @Test
    fun toggleSelectedNameAddsAndRemoves() {
        assertEquals(setOf("a"), toggleSelectedName(emptySet(), "a"))
        assertEquals(emptySet<String>(), toggleSelectedName(setOf("a"), "a"))
        assertEquals(setOf("a", "b"), toggleSelectedName(setOf("a"), "b"))
    }

    @Test
    fun singleFileBatchActionsStayEnabled() {
        val file = FileEntry("win.iso", false, 42uL).toBatchItem()
        assertTrue(filesBatchDownloadEnabled(true, listOf(file)))
        assertTrue(filesBatchRenameEnabled(true, listOf(file)))
        assertTrue(filesBatchDeleteEnabled(true, listOf(file)))
    }

    @Test
    fun folderAndMultiEnableDownloadDeleteMultiBlocksRename() {
        val folder = FileEntry("docs", true, 0uL).toBatchItem()
        val multi = listOf(
            FileEntry("a.bin", false, 1uL).toBatchItem(),
            FileEntry("b.bin", false, 2uL).toBatchItem(),
        )
        assertTrue(filesBatchDownloadEnabled(true, listOf(folder)))
        assertTrue(filesBatchDeleteEnabled(true, listOf(folder)))
        // Single-entry rename includes directories (volume.rename); only multi is blocked.
        assertTrue(filesBatchRenameEnabled(true, listOf(folder)))
        assertTrue(filesBatchDownloadEnabled(true, multi))
        assertTrue(filesBatchDeleteEnabled(true, multi))
        assertFalse(filesBatchRenameEnabled(true, multi))
        assertTrue(FilesBatchSelection(
            directory = "/",
            items = multi,
            identity = "id",
            attachGeneration = 1L,
            partitionId = "1",
            startLba = 0uL,
            sectorCount = 1uL,
        ).requiresTreeDestination())
        assertFalse(
            FilesBatchSelection(
                directory = "/",
                items = listOf(FileEntry("win.iso", false, 42uL).toBatchItem()),
                identity = "id",
                attachGeneration = 1L,
                partitionId = "1",
                startLba = 0uL,
                sectorCount = 1uL,
            ).requiresTreeDestination(),
        )
    }

    @Test
    fun disabledBatchActionsStayDisabledWithoutSelection() {
        assertFalse(filesBatchDownloadEnabled(true, emptyList()))
        assertFalse(filesBatchRenameEnabled(true, emptyList()))
        assertFalse(filesBatchDeleteEnabled(true, emptyList()))
        assertFalse(
            filesBatchDownloadEnabled(
                false,
                listOf(FileEntry("a", false, 1uL).toBatchItem()),
            ),
        )
        assertFalse(downloadActionEnabled(true, FileEntry("folder", true, 0uL)))
    }

    @Test
    fun filesBatchSelectionOrNullPinsGeometry() {
        val partition = partition(startLba = 2048uL, sectorCount = 4096uL)
        val device = DeviceIdentity(
            label = "Test Stick",
            capacityBytes = 8_000_000_000uL,
            serialNumber = "SN1",
            attachGeneration = 7L,
        )
        val entries = listOf(
            FileEntry("docs", true, 0uL),
            FileEntry("a.bin", false, 12uL),
        )
        val ready = AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = listOf(partition),
            selectedPartitionId = partition.id,
            currentPath = "/work",
        )
        val pinned = filesBatchSelectionOrNull(ready, entries)
        assertEquals(
            FilesBatchSelection(
                directory = "/work",
                items = entries.map { it.toBatchItem() },
                identity = device.identity,
                attachGeneration = 7L,
                partitionId = partition.id,
                startLba = 2048uL,
                sectorCount = 4096uL,
            ),
            pinned,
        )
        assertNull(filesBatchSelectionOrNull(ready, emptyList()))
        assertNull(filesBatchSelectionOrNull(ready.copy(device = null), entries))
        assertNull(
            filesBatchSelectionOrNull(ready.copy(selectedPartitionId = null), entries),
        )
    }
}
