package usb.files.transfer

/** Domain conflict for USB↔SAF transfers. UI maps to/from its own types at the boundary. */
data class TransferConflict(
    val pathLabel: String,
    val name: String,
    val isDirectory: Boolean,
    val destinationEmpty: Boolean? = null,
)

enum class TransferDecision {
    Skip,
    ReplaceFile,
    Cancel,
}

/**
 * Park/resume cursor for [BatchTransferService.downloadChildren]. [remainingRoots] are
 * not-yet-started sibling roots; when [activeRoot] is set, [remainingRelative] continues
 * inside that root (including the conflicted entry) without re-placing the nonempty dest dir.
 */
data class DownloadCursor(
    val remainingRoots: List<String>,
    val activeRoot: String? = null,
    val remainingRelative: List<String> = emptyList(),
)
