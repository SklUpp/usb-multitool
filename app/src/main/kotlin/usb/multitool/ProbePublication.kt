package usb.multitool

import kotlinx.coroutines.CancellationException

internal data class ProbePublicationResult(
    val failure: Throwable? = null,
    val closeFailure: Throwable? = null,
)

/** Closes probe claim before publishing state; preserves body and close failures. */
internal object ProbePublication {
    suspend fun run(
        body: suspend () -> Throwable?,
        close: () -> Unit,
        publish: suspend () -> Unit,
    ): ProbePublicationResult {
        var failure: Throwable? = null
        var bodyThrew = false
        var cancellation: CancellationException? = null
        try {
            failure = body()
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
            bodyThrew = true
            failure = cancelled
        } catch (thrown: Throwable) {
            bodyThrew = true
            failure = thrown
        }

        var closeFailure: Throwable? = null
        try {
            close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Throwable) {
            closeFailure = thrown
            if (failure == null) {
                failure = thrown
            } else if (failure !== thrown && failure?.suppressed?.none { it === thrown } == true) {
                failure?.addSuppressed(thrown)
            }
        }
        cancellation?.let { throw it }
        if (closeFailure == null && !bodyThrew) {
            try {
                publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                if (failure == null) failure = thrown else if (failure !== thrown) failure?.addSuppressed(thrown)
            }
        }
        return ProbePublicationResult(failure, closeFailure)
    }
}
