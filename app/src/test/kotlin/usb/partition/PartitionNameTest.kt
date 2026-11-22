package usb.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.fs.FormatCatalog
import usb.fs.VolumeLabel
import usb.fs.VolumeLabelProblem

class PartitionNameTest {
    @Test
    fun gptNamesAreLimitedTo36Utf16Units() {
        assertNull(PartitionName.problem("x".repeat(36)))
        assertEquals(PartitionNameProblem.TooLong, PartitionName.problem("x".repeat(37)))
        assertEquals(PartitionNameProblem.Blank, PartitionName.problem("   "))
        assertEquals(PartitionNameProblem.ControlCharacter, PartitionName.problem("bad\nname"))
        assertEquals("trimmed", PartitionName.normalize("  trimmed "))
    }

    @Test
    fun gptNamesNeverSplitSurrogatePairs() {
        // 18 emoji = 36 UTF-16 units; one more pair would exceed the field.
        assertNull(PartitionName.problem("😀".repeat(18)))
        assertEquals(PartitionNameProblem.TooLong, PartitionName.problem("😀".repeat(19)))
        assertEquals(
            PartitionNameProblem.UnpairedSurrogate,
            PartitionName.problem("\uD83D"),
        )
        assertEquals(
            PartitionNameProblem.UnpairedSurrogate,
            PartitionName.problem("\uDE00"),
        )
    }

    @Test
    fun onlyGptStoresNames() {
        assertTrue(PartitionName.supported(TableScheme.GPT))
        assertFalse(PartitionName.supported(TableScheme.MBR))
        assertFalse(PartitionName.supported(TableScheme.HYBRID))
    }

    @Test
    fun volumeLabelLimitsFollowTheNativeWriters() {
        assertEquals(VolumeLabelProblem.TooLong, VolumeLabel.problem("x".repeat(12), FilesystemId.FAT))
        assertNull(VolumeLabel.problem("x".repeat(11), FilesystemId.FAT))
        assertEquals(
            VolumeLabelProblem.InvalidCharacter,
            VolumeLabel.problem("bad/name", FilesystemId.FAT),
        )
        assertEquals(
            VolumeLabelProblem.InvalidCharacter,
            VolumeLabel.problem("bad?name", FilesystemId.EXFAT),
        )
        // `+` is legal on exFAT but not on FAT.
        assertNull(VolumeLabel.problem("a+b", FilesystemId.EXFAT))
        assertEquals(VolumeLabelProblem.InvalidCharacter, VolumeLabel.problem("a+b", FilesystemId.FAT))
        assertEquals(VolumeLabelProblem.TooLong, VolumeLabel.problem("x".repeat(129), FilesystemId.NTFS))
        assertNull(VolumeLabel.problem("x".repeat(128), FilesystemId.NTFS))
        // 16 ASCII bytes do not fit ext4_superblock.volume_name[16].
        assertEquals(VolumeLabelProblem.TooLong, VolumeLabel.problem("x".repeat(16), FilesystemId.EXT))
        assertNull(VolumeLabel.problem("x".repeat(15), FilesystemId.EXT))
        // Blank means "no label" for every filesystem.
        assertNull(VolumeLabel.problem("  ", FilesystemId.FAT))
    }

    @Test
    fun labelCapabilitiesMatchTheEngines() {
        FilesystemId.entries.forEach {
            assertTrue("$it must accept a label at mkfs", FormatCatalog.supportsVolumeLabel(it))
        }
        assertTrue(FormatCatalog.supportsVolumeLabelRename(FilesystemId.FAT))
        assertTrue(FormatCatalog.supportsVolumeLabelRename(FilesystemId.EXFAT))
        assertTrue(FormatCatalog.supportsVolumeLabelRename(FilesystemId.NTFS))
        assertFalse(FormatCatalog.supportsVolumeLabelRename(FilesystemId.EXT))
    }
}
