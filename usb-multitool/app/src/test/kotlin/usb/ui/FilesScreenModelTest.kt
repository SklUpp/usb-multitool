package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import usb.fs.FilesystemId

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
}
