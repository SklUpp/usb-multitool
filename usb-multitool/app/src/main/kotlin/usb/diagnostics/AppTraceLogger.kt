package usb.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Query strings carry tokens, filenames and ids; only scheme/host/path stay readable. */
private val URI_QUERY = Regex("""(?i)\b([a-z][a-z0-9+.\-]*://[^\s?#]*\?)[^\s#]*""")

/** Credential-shaped `key=value` / `key: value` pairs, optionally quoted. */
private val CREDENTIAL = Regex(
    """(?i)((?:admin|local|user)?[_.-]?password|passwd|pwd|token|access[_.-]?token|""" +
        """refresh[_.-]?token|api[_.-]?key|secret|authorization|auth)["']?\s*[=:]\s*""" +
        """("[^"]*"|'[^']*'|[^\s&,;]+)""",
)

private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")

/** Autounattend local-account password element. */
private val XML_PASSWORD = Regex("""(?i)(<(?:password|adminpassword)>\s*<value>)[^<]*""")

/**
 * Removes secrets from a trace before it is written or exported: password/adminPassword
 * values, tokens and every raw URI query parameter. Pure and allocation-light; safe to
 * apply twice.
 */
fun redactTrace(text: String): String =
    text
        .replace(URI_QUERY, "$1<redacted>")
        // Before the generic key rule, which would otherwise eat the "Bearer" marker first.
        .replace(BEARER, "Bearer <redacted>")
        .replace(CREDENTIAL, "$1=<redacted>")
        .replace(XML_PASSWORD, "$1<redacted>")

/**
 * Structured on-device trace of app/lifecycle decisions: one redacted line per event,
 * two files of at most [maxBytes] (`trace.log` live, `trace.log.1` previous). The screen
 * journal stays short; this file keeps the detail needed to diagnose a failure.
 *
 * Writes and [snapshot] serialize on a process-wide lock, so a concurrent export never
 * reads a half-written line. Every failure (no directory, full disk) is swallowed:
 * tracing must never break an operation.
 */
class AppTraceLogger(
    private val root: File,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** Appends one event; returns false when the trace could not be written. */
    fun log(event: String, vararg fields: Pair<String, Any?>): Boolean =
        synchronized(LOCK) {
            val dir = File(root, DIR_NAME)
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@synchronized false
            val bytes = (redactTrace(buildLine(event, fields)).take(MAX_LINE_CHARS) + "\n")
                .toByteArray(Charsets.UTF_8)
            val live = File(dir, LIVE_NAME)
            runCatching {
                if (live.length() + bytes.size > maxBytes) rotate(dir, live)
                FileOutputStream(live, true).use { it.write(bytes) }
            }.isSuccess
        }

    /** Previous + live trace as one redacted document; "" when nothing was logged yet. */
    fun snapshot(): String =
        synchronized(LOCK) {
            val dir = File(root, DIR_NAME)
            listOf(PREVIOUS_NAME, LIVE_NAME)
                .map { File(dir, it) }
                .filter { it.isFile }
                .joinToString("") { file ->
                    runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                }
                .let(::redactTrace)
        }

    private fun buildLine(event: String, fields: Array<out Pair<String, Any?>>): String =
        buildString {
            append(STAMP.format(Date()))
            append(" event=").append(event)
            fields.forEach { (key, value) ->
                append(' ').append(key).append('=').append(scalar(value))
            }
        }

    /** One line stays one line: newlines would forge entries. */
    private fun scalar(value: Any?): String =
        (value?.toString() ?: "null")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')

    /** `trace.log` -> `trace.log.1`; the newest bytes always land in `trace.log`. */
    private fun rotate(dir: File, live: File) {
        val previous = File(dir, PREVIOUS_NAME)
        if (previous.exists()) previous.delete()
        if (live.exists() && !live.renameTo(previous)) live.delete()
    }

    companion object {
        const val DIR_NAME = "trace"
        const val LIVE_NAME = "trace.log"
        const val PREVIOUS_NAME = "trace.log.1"
        const val MAX_BYTES = 512L * 1024L
        const val MAX_LINE_CHARS = 8 * 1024

        private val LOCK = Any()
        private val STAMP = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    }
}
