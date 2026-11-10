package usb.fs

/**
 * Format-picker catalog shared by UI (host-checkable) and [Formatter].
 * No partition/engine types — keeps scripts/run-ui-host-check.sh free of JNI deps.
 */
object FormatCatalog {
    /** Engines the app can actually format today (all four are real engines). */
    fun supportedFormatIds(): List<FilesystemId> =
        listOf(FilesystemId.FAT, FilesystemId.EXFAT, FilesystemId.EXT, FilesystemId.NTFS)

    fun requireSupported(id: FilesystemId): FilesystemId {
        require(id in supportedFormatIds()) {
            "filesystem $id is not offered by the format picker"
        }
        return id
    }

    fun label(id: FilesystemId): String =
        when (id) {
            FilesystemId.FAT -> "FAT/FAT32"
            FilesystemId.EXFAT -> "exFAT"
            FilesystemId.EXT -> "EXT4"
            FilesystemId.NTFS -> "NTFS"
        }

    /**
     * Every offered engine can write a volume label while formatting: FatFs
     * `f_setlabel` (FAT/exFAT), mkntfs `label` (NTFS), lwext4 `info.label` (EXT4).
     */
    fun supportsVolumeLabel(id: FilesystemId): Boolean = id in supportedFormatIds()

    /**
     * Renaming the label of an already formatted volume. EXT4 is refused: lwext4 exposes no
     * superblock label setter in this tree, so its label is only written by mkfs.
     */
    fun supportsVolumeLabelRename(id: FilesystemId): Boolean =
        id == FilesystemId.FAT || id == FilesystemId.EXFAT || id == FilesystemId.NTFS
}

/** Why a typed volume label cannot be stored by the selected filesystem. */
enum class VolumeLabelProblem {
    TooLong,
    ControlCharacter,
    InvalidCharacter,
}

/**
 * Per-filesystem volume label limits, matching the native writers exactly:
 * - FAT: 11 characters, no `+.,;=[]&#47;*:<>|\"?` (FatFs `f_setlabel` FAT branch)
 * - exFAT: 11 UTF-16 units, no `&#47;*:<>|\"?` (FatFs `f_setlabel` exFAT branch)
 * - NTFS: 128 UTF-16 units (ntfslabel's 0x100-byte attribute ceiling)
 * - EXT4: 15 UTF-8 bytes (`ext4_superblock.volume_name[16]`)
 * A blank label means "no label" and is always allowed.
 */
object VolumeLabel {
    const val FAT_MAX_CHARS = 11
    const val NTFS_MAX_CHARS = 128

    /** Widest ceiling across the engines; the per-filesystem check is [problem] with an id. */
    const val MAX_CHARS = NTFS_MAX_CHARS
    private const val EXT_MAX_UTF8_BYTES = 15
    private const val FAT_INVALID = "+.,;=[]/*:<>|\\\"?\u007F"
    private const val EXFAT_INVALID = "/*:<>|\\\"?\u007F"

    /**
     * High half of CP437, which is the compiled FatFs code page (FF_CODE_PAGE 437), copied
     * from `uc437` in `third_party/fatfs/source/ffunicode.c`: exactly the characters
     * `ff_uni2oem` maps to an OEM byte. `f_setlabel` upper-cases each character first and
     * returns FR_INVALID_NAME when the conversion yields zero, so a label outside ASCII or
     * this set would be refused only after the volume was already formatted.
     */
    private val FAT_CP437: IntArray =
        intArrayOf(
            0x00C7, 0x00FC, 0x00E9, 0x00E2, 0x00E4, 0x00E0, 0x00E5, 0x00E7,
            0x00EA, 0x00EB, 0x00E8, 0x00EF, 0x00EE, 0x00EC, 0x00C4, 0x00C5,
            0x00C9, 0x00E6, 0x00C6, 0x00F4, 0x00F6, 0x00F2, 0x00FB, 0x00F9,
            0x00FF, 0x00D6, 0x00DC, 0x00A2, 0x00A3, 0x00A5, 0x20A7, 0x0192,
            0x00E1, 0x00ED, 0x00F3, 0x00FA, 0x00F1, 0x00D1, 0x00AA, 0x00BA,
            0x00BF, 0x2310, 0x00AC, 0x00BD, 0x00BC, 0x00A1, 0x00AB, 0x00BB,
            0x2591, 0x2592, 0x2593, 0x2502, 0x2524, 0x2561, 0x2562, 0x2556,
            0x2555, 0x2563, 0x2551, 0x2557, 0x255D, 0x255C, 0x255B, 0x2510,
            0x2514, 0x2534, 0x252C, 0x251C, 0x2500, 0x253C, 0x255E, 0x255F,
            0x255A, 0x2554, 0x2569, 0x2566, 0x2560, 0x2550, 0x256C, 0x2567,
            0x2568, 0x2564, 0x2565, 0x2559, 0x2558, 0x2552, 0x2553, 0x256B,
            0x256A, 0x2518, 0x250C, 0x2588, 0x2584, 0x258C, 0x2590, 0x2580,
            0x03B1, 0x00DF, 0x0393, 0x03C0, 0x03A3, 0x03C3, 0x00B5, 0x03C4,
            0x03A6, 0x0398, 0x03A9, 0x03B4, 0x221E, 0x03C6, 0x03B5, 0x2229,
            0x2261, 0x00B1, 0x2265, 0x2264, 0x2320, 0x2321, 0x00F7, 0x2248,
            0x00B0, 0x2219, 0x00B7, 0x221A, 0x207F, 0x00B2, 0x25A0, 0x00A0,
        )

    /**
     * FatFs stores the label as `ff_uni2oem(ff_wtoupper(ch), 437)`, i.e. it upper-cases each
     * character with its own Unicode tables and then looks the result up in CP437. Most of
     * that table is identity for our purposes, so only the ranges whose upper case is a
     * CP437 character are mapped here; everything else stays unchanged and is accepted only
     * if the character itself is in CP437 (`ff_wtoupper` leaves `µ` alone, so `µ` is
     * writable even though Kotlin's Unicode upper case for it is `Μ`).
     */
    private fun ffUpper(ch: Char): Char =
        when (ch) {
            in 'a'..'z' -> ch - 32
            in 'à'..'ö', in 'ø'..'þ' -> ch - 0x20
            'ÿ' -> 'Ÿ'
            // U+0192 is the only CP437 character inside FatFs's Latin Extended-B block; its
            // table maps it to U+0191, which CP437 does not contain, so `f_setlabel` fails.
            'ƒ' -> 'Ƒ'
            in 'α'..'ρ' -> ch - 0x20
            'ς' -> 'Σ'
            in 'σ'..'ω' -> ch - 0x20
            else -> ch
        }

    /** Exactly what FatFs stores: ASCII, or a CP437 entry after `ff_wtoupper`. */
    private fun fatEncodable(ch: Char): Boolean =
        ch.code < 0x80 || ffUpper(ch).code in FAT_CP437

    /** Lone surrogates cannot be written by any of the three writers, so they are refused early. */
    private fun wellFormedUtf16(label: String): Boolean {
        var index = 0
        while (index < label.length) {
            val ch = label[index]
            if (ch.isHighSurrogate()) {
                if (index + 1 >= label.length || !label[index + 1].isLowSurrogate()) return false
                index += 2
            } else if (ch.isLowSurrogate()) {
                return false
            } else {
                index++
            }
        }
        return true
    }

    fun normalize(raw: String): String = raw.trim()

    /** Hard input cap for an editor field; [problem] is the authoritative check. */
    fun maxChars(id: FilesystemId?): Int =
        when (id) {
            null -> MAX_CHARS
            FilesystemId.FAT, FilesystemId.EXFAT -> FAT_MAX_CHARS
            FilesystemId.NTFS -> NTFS_MAX_CHARS
            // ext4 stores the label in bytes, not characters.
            FilesystemId.EXT -> EXT_MAX_UTF8_BYTES
        }

    /**
     * Filesystem-independent shape check for input widgets: only the NTFS ceiling and
     * control characters. The real per-filesystem limit is enforced by [problem] with [id].
     */
    fun problem(raw: String): VolumeLabelProblem? = problem(raw, FilesystemId.NTFS)

    /**
     * Per-filesystem check against what the native writer can actually store: FAT stores
     * `ff_uni2oem(ff_wtoupper(ch), 437)`, so a character is allowed when its FatFs upper
     * case is ASCII or present in CP437 (see [ffUpper]); ext4 counts standard UTF-8 bytes
     * (15), not UTF-16 units; NTFS counts 128 UTF-16 units. Lone surrogates are refused.
     */
    fun problem(raw: String, id: FilesystemId): VolumeLabelProblem? {
        val label = normalize(raw)
        if (label.isEmpty()) return null
        if (label.any { it.isISOControl() }) return VolumeLabelProblem.ControlCharacter
        if (!wellFormedUtf16(label)) return VolumeLabelProblem.InvalidCharacter
        return when (id) {
            FilesystemId.FAT -> when {
                label.length > FAT_MAX_CHARS -> VolumeLabelProblem.TooLong
                label.any { it in FAT_INVALID || !fatEncodable(it) } -> VolumeLabelProblem.InvalidCharacter
                else -> null
            }
            FilesystemId.EXFAT -> when {
                label.length > FAT_MAX_CHARS -> VolumeLabelProblem.TooLong
                label.any { it in EXFAT_INVALID } -> VolumeLabelProblem.InvalidCharacter
                else -> null
            }
            FilesystemId.NTFS ->
                if (label.length > NTFS_MAX_CHARS) VolumeLabelProblem.TooLong else null
            FilesystemId.EXT ->
                if (label.toByteArray(Charsets.UTF_8).size > EXT_MAX_UTF8_BYTES) {
                    VolumeLabelProblem.TooLong
                } else {
                    null
                }
        }
    }
}
