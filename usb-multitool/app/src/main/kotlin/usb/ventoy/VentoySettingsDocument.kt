package usb.ventoy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The picked phone document could not be read or fully written. */
class VentoySettingsDocumentException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Pure read/merge/write contract for a phone (SAF) settings document.
 *
 * The host owns the content resolver, URI permission and the operation state; this object
 * only validates bytes and merges. A document is always read first and merged in memory;
 * the writer only replaces the same URI it read. There is deliberately no temporary copy,
 * because a provider document cannot be atomically replaced and pretending otherwise would
 * be a false rollback promise.
 */
object VentoySettingsDocument {
    const val MAX_BYTES: Long = VentoySetup.MAX_SETTINGS_BYTES

    data class MergeOutcome(val text: String, val changed: Boolean)

    /** Read at most [maxBytes]; a provider-reported size must match exactly when positive. */
    fun read(
        input: InputStream,
        expectedBytes: Long? = null,
        maxBytes: Long = MAX_BYTES,
    ): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw VentoySettingsDocumentException(
                    "document exceeds $maxBytes bytes; refusing to load it",
                )
            }
            output.write(buffer, 0, read)
        }
        if (expectedBytes != null && expectedBytes > 0 && total != expectedBytes) {
            throw VentoySettingsDocumentException(
                "document size is $total bytes; provider reported $expectedBytes",
            )
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /**
     * Parse the real document as JSON and apply the explicit control writes. Unknown sections
     * and unrelated keys stay byte-semantics-equivalent through [WindowsSetup.applyControls].
     * Invalid JSON or a non-array `control` throws before the caller opens an output stream.
     */
    fun merge(existing: String, settings: List<VentoyControlSetting>): MergeOutcome {
        require(settings.isNotEmpty()) { "no Ventoy control setting to apply" }
        VentoyControlCatalog.problem(settings)?.let { throw IllegalArgumentException(it) }
        val merged = WindowsSetup.applyControls(existing, settings)
        return if (merged == null) {
            MergeOutcome(existing, changed = false)
        } else {
            MergeOutcome(merged, changed = true)
        }
    }

    /**
     * Write [text] to an already-truncating stream, flushing before return. The caller marks
     * the operation Partial when this throws after the stream was opened: the provider may
     * already have truncated the document, and no rollback is possible here.
     */
    fun write(output: OutputStream, text: String): Int {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
        return bytes.size
    }

    /**
     * Open the provider's truncating stream, write, and close it. Any failure is reported with
     * [WriteOutcome.failure]; because opening a stream for write may already truncate the
     * document, [WriteOutcome.mayBeTruncated] is true for every failure, including a failed
     * open. There is deliberately no rollback claim.
     */
    data class WriteOutcome(val bytes: Int, val failure: Throwable?) {
        val mayBeTruncated: Boolean get() = failure != null
    }

    fun writeTruncating(open: () -> OutputStream, text: String): WriteOutcome =
        try {
            val output = open()
            val written = output.use { stream -> write(stream, text) }
            WriteOutcome(written, null)
        } catch (failure: Throwable) {
            if (failure is Error || failure is java.util.concurrent.CancellationException) {
                throw failure
            }
            WriteOutcome(0, failure)
        }
}
