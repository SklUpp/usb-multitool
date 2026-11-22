package usb.diagnostics

/**
 * Names and admission rules for the user-visible "image + map" archive folder.
 *
 * The writer publishes immutable checkpoint generations `disk.map.<n>` plus a final
 * `disk.map`; an in-place rewrite can leave a truncated map. Selection therefore accepts
 * only maps belonging to the image stem and orders them for validation: final canonical
 * first, then generations newest-first. A corrupt/truncated newer file never hides an
 * older valid checkpoint.
 */
object BackupArchive {
    const val IMAGE_SUFFIX = ".img"
    const val MAP_SUFFIX = ".map"
    const val IMAGE_NAME = "disk$IMAGE_SUFFIX"
    const val MAP_NAME = "disk$MAP_SUFFIX"

    fun folderName(stamp: String): String = "usb-rescue-$stamp"

    fun gptFileName(stamp: String): String = "gpt-critical-$stamp.bin"

    /** One document returned by the SAF provider; directories are listed but never selected. */
    data class Entry(
        val name: String,
        val uri: String,
        val isDirectory: Boolean = false,
        val sizeBytes: Long? = null,
    )

    /**
     * [maps] is non-empty and ordered for validation: exact `<stem>.map` first, then
     * `<stem>.map.<generation>` newest-first. [map] is only a convenience for the first
     * candidate and must still be validated.
     */
    data class Pair(
        val image: Entry,
        val maps: List<Entry>,
    ) {
        val map: Entry get() = maps.first()
    }

    enum class Problem {
        Empty,
        MissingImage,
        MissingMap,
        MultipleImages,
        UnexpectedFiles,
    }

    sealed interface Selection {
        data class Ready(val pair: Pair) : Selection

        /** [detail] carries document names for the message; never a guessed file. */
        data class Invalid(
            val problem: Problem,
            val detail: List<String> = emptyList(),
        ) : Selection
    }

    /** Generated checkpoint names must be `<stem>.map.<digits>`; canonical is generation 0. */
    private data class MapCandidate(val entry: Entry, val generation: Int)

    /**
     * Pick exactly one `.img` and the maps with the same stem. Directories and dotfiles are
     * ignored; every other file is unexpected and rejects the folder. A foreign `.map`
     * (other stem) cannot be paired with this image.
     */
    fun select(entries: List<Entry>): Selection {
        val files = entries.filterNot { it.isDirectory || it.name.startsWith('.') }
        if (files.isEmpty()) return Selection.Invalid(Problem.Empty)
        val images = files.filter { it.name.endsWith(IMAGE_SUFFIX, ignoreCase = true) }
        if (images.isEmpty()) return Selection.Invalid(Problem.MissingImage)
        if (images.size > 1) return Selection.Invalid(Problem.MultipleImages, images.map { it.name })
        val image = images.single()
        val stem = image.name.dropLast(IMAGE_SUFFIX.length)
        if (stem.isBlank()) return Selection.Invalid(Problem.MissingImage, listOf(image.name))

        val expectedMap = "$stem$MAP_SUFFIX"
        val candidates = files.mapNotNull { mapCandidate(it, expectedMap) }
        if (candidates.isEmpty()) return Selection.Invalid(Problem.MissingMap, listOf(image.name))

        val mapNames = candidates.map { it.entry.name }.toSet()
        val unexpected = files.filter { it !in images && it.name !in mapNames }
        if (unexpected.isNotEmpty()) {
            return Selection.Invalid(Problem.UnexpectedFiles, unexpected.map { it.name })
        }

        val canonical = candidates.filter { it.generation == 0 }.map { it.entry }
        val generations = candidates
            .filter { it.generation > 0 }
            .sortedByDescending { it.generation }
            .map { it.entry }
        return Selection.Ready(Pair(image = image, maps = canonical + generations))
    }

    private fun mapCandidate(entry: Entry, expectedMap: String): MapCandidate? {
        val name = entry.name
        if (name.equals(expectedMap, ignoreCase = true)) {
            return MapCandidate(entry, generation = 0)
        }
        val prefix = "$expectedMap."
        if (!name.startsWith(prefix, ignoreCase = true)) return null
        val suffix = name.substring(prefix.length)
        if (suffix.isEmpty() || suffix.any { !it.isDigit() }) return null
        val generation = suffix.toIntOrNull() ?: return null
        return if (generation > 0) MapCandidate(entry, generation) else null
    }

    /**
     * Minimum dense raw image length for a decoded map. Returns null when the
     * multiplication overflows ULong or the sector size is unknown; callers must not
     * treat null as a pass when they need a length bound.
     */
    fun expectedImageBytes(sectorCount: ULong, sectorSize: Int?): ULong? {
        if (sectorSize == null || sectorSize <= 0) return null
        val size = sectorSize.toULong()
        if (sectorCount > ULong.MAX_VALUE / size) return null
        return sectorCount * size
    }
}
