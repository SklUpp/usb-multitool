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
    /**
     * Last path segment of [rawName], or IllegalArgumentException when it is not a
     * single entry name: blank, leading/trailing whitespace, `.`/`..`, a `\` separator,
     * or a NUL byte.
     * Never silently trims — a name that would change under trim is rejected so
     * recursive delete/upload cannot retarget a distinct sibling (e.g. EXT `victim `).
     * Mirrors UiReducer.validEntryName at the policy boundary (UI gating is not enough
     * for SAF-reported display names).
     */
    fun requireChildName(rawName: String, source: String): String {
        val name = rawName.substringAfterLast('/')
        require(
            name.isNotEmpty() &&
                name == name.trim() &&
                name != "." &&
                name != ".." &&
                '\\' !in name &&
                '\u0000' !in name,
        ) {
            "invalid file name \"$rawName\" for $source"
        }
        return name
    }

    fun duplicateMessage(target: String): String =
        "$target already exists: delete it first (delete asks for confirmation)"

    fun insufficientSpaceMessage(freeBytes: ULong, needBytes: Long): String =
        "not enough free space: free=$freeBytes, need=$needBytes"

    fun deleteError(name: String, target: String, message: String): String? =
        if (message.contains("Directory not empty")) {
            "Cannot delete \"$name\": directory is not empty — remove its contents first"
        } else null

    /**
     * Join [basePath] with relative [childSegments] staying inside the tree.
     * Rejects blank/`.`/`..` segments and separators so SAF or USB names cannot escape.
     */
    fun resolveInsideTree(basePath: String, vararg childSegments: String): String {
        var current = normalizeVolumePath(basePath)
        for (raw in childSegments) {
            require('/' !in raw && '\\' !in raw) {
                "invalid path segment \"$raw\" under $current"
            }
            val segment = requireChildName(raw, "path under $current")
            current = if (current == "/") "/$segment" else "$current/$segment"
        }
        return current
    }

    /** Absolute volume path with a single leading slash and no trailing slash (except root). */
    fun normalizeVolumePath(path: String): String {
        val trimmed = path.trim().trim('/')
        return if (trimmed.isEmpty()) "/" else "/$trimmed"
    }

    /** True when [relative] has no empty/`.`/`..` segment and no backslash/NUL. */
    fun isSafeRelativePath(relative: String): Boolean {
        if (relative.isEmpty() || relative.startsWith('/') || '\\' in relative || '\u0000' in relative) {
            return false
        }
        return relative.split('/').all { part ->
            part.isNotEmpty() && part != "." && part != ".." && '\\' !in part && '\u0000' !in part
        }
    }

    fun nonemptyDirectoryRefuseMessage(pathLabel: String): String =
        "Cannot replace nonempty directory \"$pathLabel\": skip or cancel (merge/replace refused)"

    fun replaceFileWarningMessage(pathLabel: String): String =
        "Replace existing file \"$pathLabel\"? New contents are staged before replacement."

    fun directoryConflictSkipOnlyMessage(pathLabel: String): String =
        "Directory \"$pathLabel\" already exists and is not empty; only Skip or Cancel are allowed"

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
