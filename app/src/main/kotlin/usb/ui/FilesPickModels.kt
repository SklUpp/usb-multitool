package usb.ui

import java.util.Locale

/** Lifecycle of current directory listing; only Loading accepts list callbacks. */
enum class FilesListingState {
    Stale,
    Loading,
    Loaded,
    Failed,
}

/**
 * Exact file selected before the SAF destination picker opens. The callback may arrive after
 * navigation, re-attach or a recreated Activity; every captured field is required to still
 * describe the visible entry before an export effect is emitted.
 */
data class FilesDownloadBinding(
    val directory: String,
    val name: String,
    val sizeBytes: ULong,
    val attachment: UiAttachmentPin,
) {
    val targetPath: String get() = fatChildPath(directory, name)
}

/** Identity + partition geometry captured before a picker or confirmation. */
data class UiAttachmentPin(
    val identity: String,
    val attachGeneration: Long,
    val partitionId: String,
    val startLba: ULong?,
    val sectorCount: ULong?,
) {
    constructor(device: DeviceIdentity, partition: PartitionTab) : this(
        identity = device.identity,
        attachGeneration = device.attachGeneration,
        partitionId = partition.id,
        startLba = partition.startLba,
        sectorCount = partition.sectorCount,
    )

    fun matches(state: AppUiState, directory: String? = null): Boolean {
        val device = state.device ?: return false
        if (device.identity != identity || device.attachGeneration != attachGeneration) return false
        // The pin owns the target partition; the user's current selection is UI state and may
        // legitimately change while a SAF picker is in front. Geometry below is the fence.
        if (directory != null && state.currentPath != directory) return false
        val partition = state.partitions.firstOrNull { it.id == partitionId } ?: return false
        return partition.startLba == startLba && partition.sectorCount == sectorCount
    }
}

fun UiIntent.UploadBatchDocs.matchesAttachment(state: AppUiState): Boolean =
    attachment.matches(state)

fun UiEffect.UploadBatchDocs.matchesAttachment(state: AppUiState): Boolean =
    attachment.matches(state)

/**
 * Attachment pin only: enough to service a destination already chosen in the picker.
 * Listing/current path may be empty after process death; the operation re-stats the
 * exact path before reading, so a stale listing must not discard the picked file.
 */
fun FilesDownloadBinding.matchesAttachment(state: AppUiState): Boolean {
    if (!attachment.matches(state)) return false
    return name.isNotBlank() && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && '\u0000' !in name
}

/** True when binding still names exact visible file on current attachment/partition. */
fun FilesDownloadBinding.matches(state: AppUiState): Boolean {
    if (state.filesListingState != FilesListingState.Loaded) return false
    if (!matchesAttachment(state)) return false
    if (state.currentPath != directory) return false
    val entry = state.files.singleOrNull { it.name == name && !it.isDirectory } ?: return false
    return entry.sizeBytes == sizeBytes
}

/**
 * A loaded listing that still names the file but reports another size: the source
 * changed while the picker was open, so the pinned byte count is no longer valid.
 * An absent entry is not evidence of a change (listing may still be loading).
 */
fun FilesDownloadBinding.listedSizeChanged(state: AppUiState): Boolean {
    if (state.currentPath != directory) return false
    return state.files.singleOrNull { it.name == name && !it.isDirectory }
        ?.let { it.sizeBytes != sizeBytes } == true
}

/** One selected listing entry captured before a batch picker or confirmation. */
data class FilesBatchItem(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: ULong,
)

/**
 * Immutable multi-select snapshot for batch download/delete/upload confirmations.
 * Every field must still describe the live listing before a mutation effect is emitted.
 */
data class FilesBatchSelection(
    val directory: String,
    val items: List<FilesBatchItem>,
    val identity: String,
    val attachGeneration: Long,
    val partitionId: String,
    val startLba: ULong?,
    val sectorCount: ULong?,
)

/** True when selection still names exact visible entries on current attachment/partition. */
fun FilesBatchSelection.matches(state: AppUiState): Boolean {
    if (state.filesListingState != FilesListingState.Loaded) return false
    val device = state.device ?: return false
    if (device.identity != identity || device.attachGeneration != attachGeneration) return false
    if (state.selectedPartitionId != partitionId || state.currentPath != directory) return false
    val partition = state.filesVolumes.firstOrNull { it.id == partitionId } ?: return false
    if (partition.startLba != startLba || partition.sectorCount != sectorCount) return false
    if (items.isEmpty()) return false
    val seen = HashSet<String>(items.size)
    for (item in items) {
        val name = item.name
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name) {
            return false
        }
        if (!seen.add(name)) return false
        val entry = state.files.singleOrNull { it.name == name } ?: return false
        if (entry.isDirectory != item.isDirectory || entry.sizeBytes != item.sizeBytes) return false
    }
    return true
}

/** Batch download uses a SAF tree when more than one entry or any directory is selected. */
fun FilesBatchSelection.requiresTreeDestination(): Boolean =
    items.size != 1 || items.single().isDirectory

/** UI alias for transfer conflict decision; domain type lives in usb.files.transfer. */
typealias FilesConflictDecision = usb.files.transfer.TransferDecision

/** UI alias for transfer conflict; domain type lives in usb.files.transfer. */
typealias FilesConflict = usb.files.transfer.TransferConflict




data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: ULong,
) {
    val isIso: Boolean
        get() = !isDirectory && name.endsWith(".iso", ignoreCase = true)
}

/** SAF CreateDocument download is valid only for a single regular file. */
fun FileEntry.canDownload(): Boolean = !isDirectory
fun downloadActionEnabled(canAct: Boolean, selection: FileEntry?): Boolean =
    canAct && selection?.canDownload() == true

/** Batch download allows folders; tree destination is required when [FilesBatchSelection.requiresTreeDestination]. */
fun filesBatchDownloadEnabled(canAct: Boolean, items: List<FilesBatchItem>): Boolean =
    canAct && items.isNotEmpty()

/** Rename stays single-entry only (file or directory); multi-select never renames. */
fun filesBatchRenameEnabled(canWrite: Boolean, items: List<FilesBatchItem>): Boolean =
    canWrite && items.size == 1

/** Batch delete allows folders; caller still confirms before mutation. */
fun filesBatchDeleteEnabled(canWrite: Boolean, items: List<FilesBatchItem>): Boolean =
    canWrite && items.isNotEmpty()

/** Path shown relative to the root of a mounted volume, with both slashes: "/" or "/a/b/". */
fun folderPathLabel(path: String): String {
    val trimmed = path.trim('/')
    return if (trimmed.isEmpty()) "/" else "/$trimmed/"
}

/**
 * Extension-derived format of a real file name, uppercase; null when the name has no
 * extension or starts with a dot. Nothing is inferred beyond the characters in the name.
 */
fun fileFormatLabel(name: String, isDirectory: Boolean): String? {
    if (isDirectory) return null
    val dot = name.lastIndexOf('.')
    if (dot !in 1 until name.lastIndex) return null
    return name.substring(dot + 1).uppercase(Locale.ROOT)
}

/**
 * Display split of a real entry name: folders stay whole (dots and all), files keep their
 * extension in a separately ellipsized suffix, and names without an extension stay whole.
 */
data class FileNameParts(val base: String, val suffix: String)

fun fileNameParts(name: String, isDirectory: Boolean): FileNameParts {
    if (isDirectory) return FileNameParts(name, "")
    val dot = name.lastIndexOf('.')
    return if (dot in 1 until name.lastIndex) {
        FileNameParts(name.substring(0, dot), name.substring(dot))
    } else {
        FileNameParts(name, "")
    }
}


enum class FilesPickPurpose {
    WindowsImage,
    RawSource,
    VentoyConfig,
    WindowsTemplate,
}

enum class FilesPickLaunchSource {
    Files,
    Phone,
}

/** File picks a regular file; Folder picks the folder that is currently open (root allowed). */
enum class FilesPickTarget {
    File,
    Folder,
}

/**
 * Attachment-scoped pick session. Every field is pinned when the session starts: a replaced
 * stick, a new attach generation, another partition or different partition geometry can
 * never be selected under the old pin. [returnTab] is where a confirmed or cancelled pick
 * returns; any other tab cancels the session.
 */
data class FilesPickMode(
    /** Unique id of this exact session; an older SAF/copy result can never join it. */
    val sessionId: String,
    val purpose: FilesPickPurpose,
    val launchSource: FilesPickLaunchSource = FilesPickLaunchSource.Files,
    val returnTab: ShellTab,
    val target: FilesPickTarget,
    val identity: String?,
    val attachGeneration: Long,
    val partitionId: String,
    val startLba: ULong?,
    val sectorCount: ULong?,
){
    /** Result pinned to exactly this session's attachment and partition geometry. */
    fun selection(
        path: String,
        name: String,
        sizeBytes: ULong,
        isDirectory: Boolean,
    ): FilesPickSelection =
        FilesPickSelection(
            purpose = purpose,
            path = path,
            name = name,
            sizeBytes = sizeBytes,
            isDirectory = isDirectory,
            identity = identity,
            attachGeneration = attachGeneration,
            partitionId = partitionId,
            startLba = startLba,
            sectorCount = sectorCount,
        )
}

/** One confirmed on-stick choice; immutable and bound to the attachment it was read from. */
data class FilesPickSelection(
    val purpose: FilesPickPurpose,
    val path: String,
    val name: String,
    /** 0 means the source did not report a size; never treat it as a real empty file. */
    val sizeBytes: ULong,
    val isDirectory: Boolean,
    val identity: String?,
    val attachGeneration: Long,
    val partitionId: String,
    val startLba: ULong?,
    val sectorCount: ULong?,
)

/**
 * SAF source inspected against the real Ventoy data partition. [exists] requires an
 * explicit overwrite confirmation; [attachGeneration] pins the USB attachment.
 */

fun normalizeFatPath(path: String): String {
    val trimmed = path.trim('/')
    return if (trimmed.isEmpty()) "/" else "/$trimmed"
}

/** Absolute volume path of [name] inside directory [parent]; root is "/". */
fun fatChildPath(parent: String, name: String): String {
    val base = parent.trim('/')
    // Never trim or normalize [name]: spaces are valid on EXT/NTFS and trimming would
    // retarget a distinct USB sibling (e.g. `victim` vs `victim `).
    require(
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name,
    ) {
        "invalid child name \"$name\""
    }
    return when {
        base.isEmpty() -> "/$name"
        else -> "/$base/$name"
    }
}

/** Parent directory of absolute [path]; the root has no parent. */
fun fatParentPath(path: String): String {
    val trimmed = path.trim('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut <= 0) "/" else "/" + trimmed.substring(0, cut)
}


/** Whole-partition image discovery plus the real Ventoy search scope that may narrow it. */
