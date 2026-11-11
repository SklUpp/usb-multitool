package usb.files

/**
 * Shared host policy for FAT file operations. Pure for JVM tests without Android.
 *
 * O2: mount close after body error runs under the session lock inside the op's
 * execution scope — not via deferred launch. This policy only decides how the
 * **await** side publishes primary errors and optional close diagnostics.
 *
 * Close failures may arrive via [Throwable.suppressed] on the primary, or via an
 * explicit channel when OperationExecutor drops [CancellationException] failureCause
 * on read-only cancel. Terminal Cancelled is never rewritten to Failed.
 */
object FilesOperationPolicy {
    fun requireChildName(rawName: String, source: String): String {
        val name = rawName.substringAfterLast('/').trim()
        require(name.isNotEmpty() && name != "." && name != "..") {
            "invalid file name \"$rawName\" for $source"
        }
        return name
    }

    fun duplicateMessage(target: String): String =
        "$target already exists: delete it first (delete asks for confirmation)"

    fun deleteError(name: String, target: String, message: String): String? =
        if (message.contains("Directory not empty")) {
            "Cannot delete \"$name\": directory is not empty — remove its contents first"
        } else null

    fun publishOperationProgress(quiet: Boolean): Boolean = !quiet

    /**
     * Await-side publish plan. Close is already done under lock in run(); [acquired]
     * is diagnostic only (tests / logging).
     */
    fun planTerminal(
        quiet: Boolean,
        hasBoundFailure: Boolean,
        acquired: Boolean = false,
    ): FilesOpTerminalPlan =
        FilesOpTerminalPlan(
            postBoundFailure = hasBoundFailure,
            postStatus = !quiet && !hasBoundFailure,
            postOperationProgress = !quiet,
            acquired = acquired,
        )

    /**
     * Surface a close failure without changing terminal outcome.
     * [explicitClose] wins when executor cleared failureCause on RO cancel;
     * otherwise first suppressed on [primary] is used. Always AppendLog-only —
     * never a second Status that could clobber op B.
     */
    fun planCloseDiagnostic(
        primary: Throwable?,
        quiet: Boolean,
        hasBoundFailure: Boolean,
        opStillCurrent: Boolean,
        explicitClose: Throwable? = null,
    ): CloseDiagnosticPublish {
        val close =
            explicitClose
                ?: primary?.suppressed?.firstOrNull()
                ?: return CloseDiagnosticPublish.None
        val detail = close.message ?: close.javaClass.simpleName
        val log = "Volume close/sync unconfirmed: $detail"
        // Log regardless of quiet/stale/bound — diagnostic must not vanish.
        // opStillCurrent / quiet kept in signature for call-site clarity + future UX.
        @Suppress("UNUSED_PARAMETER")
        val _ignored = quiet || hasBoundFailure || opStillCurrent
        return CloseDiagnosticPublish.LogOnly(log)
    }

    /** Primary UI message: never replace with close-only text. */
    fun primaryErrorMessage(cause: Throwable): String =
        cause.message ?: cause.javaClass.simpleName
}

data class FilesOpTerminalPlan(
    val postBoundFailure: Boolean,
    val postStatus: Boolean,
    val postOperationProgress: Boolean,
    val acquired: Boolean,
)

sealed class CloseDiagnosticPublish {
    data object None : CloseDiagnosticPublish()
    data class LogOnly(val message: String) : CloseDiagnosticPublish()
}
