package usb.image

import usb.partition.RawWriteRange

/**
 * Truthful outcome of the mandatory readback of the bytes actually written.
 * Declared in a UI-friendly file: it carries no Android or disk-operation types.
 */
sealed interface ReadbackVerdict {
    data object Verified : ReadbackVerdict

    data class Mismatch(
        val mismatchedSectors: ULong,
        val firstMismatchLba: ULong,
    ) : ReadbackVerdict

    data class Incomplete(
        val reason: ReadbackProblem,
        val detail: String? = null,
    ) : ReadbackVerdict

    /**
     * A write request failed after it may have reached the medium, so this subrange could
     * not be proven even when the manifest prefix read back correctly. Never downgraded
     * to [Verified]: a transport can throw after transferring data.
     */
    data class Unverified(
        val range: RawWriteRange,
        val detail: String? = null,
    ) : ReadbackVerdict
}

enum class ReadbackProblem {
    Cancelled,
    SourceChanged,
    IoError,
}

/** Terminal state of one raw write; never inferred from progress alone. */
enum class RawWriteStatus {
    Completed,
    Partial,
}

/**
 * Outcome of one raw write. [writtenBytes] is the decoded payload committed inside
 * [range]; on [RawWriteStatus.Partial] compare it with the requested range before
 * claiming that the whole range was written.
 */
data class RawWriteResult(
    val range: RawWriteRange,
    val writtenBytes: Long,
    val readback: ReadbackVerdict,
    val state: RawWriteStatus,
    val failure: Throwable? = null,
) {
    val completed: Boolean get() = state == RawWriteStatus.Completed
}
