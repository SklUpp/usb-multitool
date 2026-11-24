package usb.ui

/** Why a captured picker target no longer matches live state; messages stay at call sites. */
enum class PickerVerdict { Ok, Busy, Unavailable, TargetChanged, TargetInvalid, SizeChanged }

/**
 * One pending picker target. Main-thread only: staged in a Compose callback, claimed and
 * cleared from the ActivityResult/main-dispatcher consumers. [pendingRequestId] fences
 * re-delivered callbacks; 0 = empty.
 */
class PickerSlot<T> {
    var pending: T? = null
        private set
    var pendingRequestId: Long = 0L
        private set
    private var nextRequestId: Long = 0L
    private var launchedRequestId: Long = 0L

    /** Returns the new request id, or null when a pick is already pending (launch dedup). */
    fun stage(payload: T): Long? {
        if (pendingRequestId != 0L) return null
        val id = ++nextRequestId
        pending = payload
        pendingRequestId = id
        return id
    }

    /** True exactly once per staged id; false for duplicates and foreign ids. */
    fun claimLaunch(requestId: Long): Boolean {
        if (pendingRequestId != requestId || pending == null) return false
        if (launchedRequestId == requestId) return false
        launchedRequestId = requestId
        return true
    }

    fun clear(): T? {
        val previous = pending
        pending = null
        pendingRequestId = 0L
        launchedRequestId = 0L
        return previous
    }

    /** Clears only when [requestId] is the pending one; stale cancels are inert. */
    fun clearIf(requestId: Long): Boolean {
        if (pendingRequestId != requestId) return false
        clear()
        return true
    }

    /** Process-death re-arm; refused when already pending. Request id becomes 1. */
    fun restore(payload: T, requestId: Long = 1L): Boolean {
        if (pendingRequestId != 0L) return false
        pending = payload
        pendingRequestId = requestId
        launchedRequestId = 0L
        return true
    }
}

/** Stage gate for a new single download; mirrors stageDownloadForPicker. */
fun FilesDownloadBinding.stageVerdict(state: AppUiState): PickerVerdict =
    if (matches(state)) PickerVerdict.Ok else PickerVerdict.TargetInvalid

/** Result gate; mirrors submitDownloadResult and the reducer's fromPicker check, in order. */
fun FilesDownloadBinding.resultVerdict(state: AppUiState): PickerVerdict = when {
    state.filesPickMode != null -> PickerVerdict.Busy
    !state.actionsEnabled -> PickerVerdict.Unavailable
    !matchesAttachment(state) -> PickerVerdict.TargetChanged
    listedSizeChanged(state) -> PickerVerdict.SizeChanged
    else -> PickerVerdict.Ok
}

/** Batch download stage/result gate; delegates to FilesBatchSelection.matches. */
fun FilesBatchSelection.stageVerdict(state: AppUiState): PickerVerdict =
    if (matches(state)) PickerVerdict.Ok else PickerVerdict.TargetInvalid

/** Upload destination + attachment pin captured at launch; result may come from any folder. */
data class UploadTarget(val directory: String, val attachment: UiAttachmentPin) {
    fun verdict(state: AppUiState): PickerVerdict =
        if (attachment.matches(state)) PickerVerdict.Ok else PickerVerdict.TargetChanged
}
