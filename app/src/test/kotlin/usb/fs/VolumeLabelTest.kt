package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Volume labels checked against what each native writer can store: FatFs converts FAT
 * labels through the compiled CP437 code page, ext4 counts standard UTF-8 bytes, NTFS
 * counts UTF-16 units. A mismatch here fails only after the destructive format.
 */
class VolumeLabelTest {
    @Test
    fun fatAcceptsAsciiAndCp437Characters() {
        for (label in listOf("BACKUP", "Café", "GB-Ω", "ß", "ABCDEFGHIJK")) {
            assertNull(label, VolumeLabel.problem(label, FilesystemId.FAT))
        }
    }

    @Test
    fun fatAcceptsCharactersWhoseFatFsUppercaseStaysInTheCodePage() {
        // ff_wtoupper leaves 'µ' alone (its tables only cover the lowercase Latin-1 and Greek
        // ranges), so 'µ' is stored as-is even though Unicode upper-cases it to 'Μ'.
        // γ θ ς ω are accepted because ff_wtoupper maps them to Γ Θ Σ Ω, all present in CP437.
        for (label in listOf("µ", "γ", "θ", "ς", "ω", "σφ", "Ω")) {
            assertNull(label, VolumeLabel.problem(label, FilesystemId.FAT))
        }
    }

    @Test
    fun fatRejectsLowercaseGreekWhoseUppercaseIsAbsent() {
        // π -> Π and α -> Α; neither Π (U+03A0) nor Α (U+0391) exists in CP437.
        // δ and τ are themselves in CP437, but their upper-case forms Δ and Τ are not, so
        // FatFs rejects them too. The same holds for ƒ: FatFs upper-cases it to Ƒ, which CP437
        // lacks, so a label containing it fails after the format would have run.
        for (label in listOf("π", "α", "β", "η", "λ", "δ", "τ", "ƒ", "AƒC", "price ƒ9.99")) {
            assertEquals(
                label,
                VolumeLabelProblem.InvalidCharacter,
                VolumeLabel.problem(label, FilesystemId.FAT),
            )
        }
    }

    @Test
    fun fatRejectsCharactersTheCodePageCannotEncode() {
        // ff_uni2oem never maps these, so f_setlabel would return FR_INVALID_NAME.
        for (label in listOf("Ж", "東京", "😀", "Café-日本")) {
            assertEquals(
                label,
                VolumeLabelProblem.InvalidCharacter,
                VolumeLabel.problem(label, FilesystemId.FAT),
            )
        }
    }

    @Test
    fun fatUsesTheUppercasedCodePageForm() {
        // 'é' upper-cases to 'É', which CP437 has; 'ï' upper-cases to 'Ï', which it does not,
        // so "naïve" is stored by neither FatFs nor the validator. 'â' maps to an absent 'Â'.
        assertEquals(VolumeLabelProblem.InvalidCharacter, VolumeLabel.problem("naïve", FilesystemId.FAT))
        assertEquals(VolumeLabelProblem.InvalidCharacter, VolumeLabel.problem("â", FilesystemId.FAT))
        assertNull(VolumeLabel.problem("é", FilesystemId.FAT))
    }

    @Test
    fun fatLengthBoundaryIsElevenCharacters() {
        assertNull(VolumeLabel.problem("ABCDEFGHIJK", FilesystemId.FAT))
        assertEquals(VolumeLabelProblem.TooLong, VolumeLabel.problem("ABCDEFGHIJKL", FilesystemId.FAT))
    }

    @Test
    fun extCountsStandardUtf8BytesNotUtf16Units() {
        // One supplementary character is 4 bytes, not Java's 6-byte surrogate pair.
        assertNull(VolumeLabel.problem("ABC😀😀😀", FilesystemId.EXT)) // 3 + 12 = 15
        assertEquals(VolumeLabelProblem.TooLong, VolumeLabel.problem("😀😀😀😀", FilesystemId.EXT)) // 16
        assertNull(VolumeLabel.problem("ééééééé", FilesystemId.EXT)) // 14
        assertEquals(VolumeLabelProblem.TooLong, VolumeLabel.problem("éééééééé", FilesystemId.EXT)) // 16
    }

    @Test
    fun ntfsCountsUtf16Units() {
        assertNull(VolumeLabel.problem("A".repeat(128), FilesystemId.NTFS))
        assertEquals(VolumeLabelProblem.TooLong, VolumeLabel.problem("A".repeat(129), FilesystemId.NTFS))
        assertNull(VolumeLabel.problem("😀".repeat(64), FilesystemId.NTFS))
        assertEquals(
            VolumeLabelProblem.TooLong,
            VolumeLabel.problem("😀".repeat(65), FilesystemId.NTFS),
        )
    }

    @Test
    fun loneSurrogatesAreRefusedForEveryFilesystem() {
        for (id in FilesystemId.values()) {
            assertEquals(id.name, VolumeLabelProblem.InvalidCharacter, VolumeLabel.problem("\uD83D", id))
            assertEquals(id.name, VolumeLabelProblem.InvalidCharacter, VolumeLabel.problem("A\uDE00", id))
        }
        // A valid pair is accepted where the filesystem allows the characters at all.
        assertNull(VolumeLabel.problem("😀", FilesystemId.EXT))
        assertNull(VolumeLabel.problem("😀", FilesystemId.NTFS))
    }

    @Test
    fun controlCharactersWinOverEncodingRules() {
        assertEquals(
            VolumeLabelProblem.ControlCharacter,
            VolumeLabel.problem("AB\u0007C", FilesystemId.FAT),
        )
    }
}
