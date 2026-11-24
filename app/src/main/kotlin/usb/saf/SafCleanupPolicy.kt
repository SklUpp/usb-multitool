package usb.saf

/**
 * Shared SAF failure-cleanup decisions. Single-download deletes only empty
 * destinations; batch-download deletes only documents this call created
 * (including partials). Policies stay distinct on purpose — routing batch
 * through empty-only cleanup would retain created partials.
 */
object SafCleanupPolicy {
    /** Pure empty-destination rule for single-download [usb.files.SafWriter.write]. */
    fun isEmptyDestination(
        offset: Long,
        reportedSize: Long?,
        hasData: Boolean?,
    ): Boolean = offset == 0L || (reportedSize == 0L && hasData == false)

    fun emptyDestinationNote(deleted: Boolean): String =
        if (deleted) "empty destination deleted" else "destination retained"

    /** Batch-download: only created targets are eligible for deletion. */
    fun shouldDeleteCreated(created: Boolean): Boolean = created

    fun createdDestinationNote(deleted: Boolean): String =
        if (deleted) "partial destination deleted" else "destination retained"
}
