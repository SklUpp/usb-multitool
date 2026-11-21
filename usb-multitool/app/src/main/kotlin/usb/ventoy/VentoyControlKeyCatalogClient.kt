package usb.ventoy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Manual HTTPS fetch of the official Plugson control-key page for a pinned Ventoy release.
 *
 * Host integration owns Dispatchers and user gestures. This client never writes
 * `ventoy.json`, never auto-refreshes, and never invents keys when the network fails.
 */
object VentoyControlKeyCatalogClient {
    const val MAX_HTML_BYTES: Long = 256L * 1024
    const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
    const val DEFAULT_READ_TIMEOUT_MS = 8_000
    private const val MAX_REDIRECTS = 2
    private const val USER_AGENT = "usb-multitool"
    private const val ALLOWED_HOST = "raw.githubusercontent.com"
    private const val PATH_TEMPLATE = "/ventoy/Ventoy/v%s/Plugson/www/plugson_control.html"

    fun catalogUrl(release: String = VentoyAssets.PINNED_RELEASE): String {
        require(release.matches(Regex("""\d+(?:\.\d+)+"""))) {
            "Ventoy release must look like 1.1.18, got \"$release\""
        }
        return "https://$ALLOWED_HOST${PATH_TEMPLATE.format(Locale.ROOT, release)}"
    }

    fun upstreamRef(release: String = VentoyAssets.PINNED_RELEASE): String =
        "ventoy/Ventoy@v$release:Plugson/www/plugson_control.html"

    /**
     * Downloads and parses the official Plugson page for [release].
     *
     * Failures become [VentoyControlKeyCatalogResult.Failed]; the previous offline state is
     * left untouched by this function. Callers must not treat failure as an empty catalog.
     */
    fun fetch(
        release: String = VentoyAssets.PINNED_RELEASE,
        fetchedAtEpochMs: Long = System.currentTimeMillis(),
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): VentoyControlKeyCatalogResult =
        try {
            val url = catalogUrl(release)
            val bytes = download(url, connectTimeoutMs, readTimeoutMs, openConnection)
            val html =
                try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString()
                } catch (failure: java.nio.charset.CharacterCodingException) {
                    throw VentoyControlKeyCatalogException("Plugson control HTML is not valid UTF-8", failure)
                }
            if (html.length > VentoyControlKeyCatalogParser.MAX_HTML_CHARS) {
                throw VentoyControlKeyCatalogException(
                    "Plugson control HTML is ${html.length} characters; limit is ${VentoyControlKeyCatalogParser.MAX_HTML_CHARS}",
                )
            }
            val catalog =
                VentoyControlKeyCatalogParser.parse(
                    html = html,
                    ventoyRelease = release,
                    upstreamRef = upstreamRef(release),
                    upstreamSha256 = VentoyControlKeyCatalogParser.sha256Hex(bytes),
                    fetchedAtEpochMs = fetchedAtEpochMs,
                )
            VentoyControlKeyCatalogResult.Ok(catalog)
        } catch (failure: VentoyControlKeyCatalogException) {
            VentoyControlKeyCatalogResult.Failed(failure.message ?: "catalog refresh failed")
        } catch (failure: Exception) {
            VentoyControlKeyCatalogResult.Failed(
                failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName,
            )
        }

    /**
     * Parses already-fetched HTML. Useful for tests and for a host that downloaded bytes
     * through its own bounded stream while still using this package's validation.
     */
    fun parseDownloaded(
        bytes: ByteArray,
        release: String = VentoyAssets.PINNED_RELEASE,
        fetchedAtEpochMs: Long,
        sourceUrl: String = catalogUrl(release),
    ): VentoyControlKeyCatalog {
        require(bytes.size.toLong() <= MAX_HTML_BYTES) {
            "Plugson control HTML is ${bytes.size} bytes; limit is $MAX_HTML_BYTES"
        }
        require(sourceUrl.startsWith("https://$ALLOWED_HOST/", ignoreCase = true)) {
            "Refusing non-allowlisted catalog source: $sourceUrl"
        }
        val html =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        return VentoyControlKeyCatalogParser.parse(
            html = html,
            ventoyRelease = release,
            upstreamRef = upstreamRef(release),
            upstreamSha256 = VentoyControlKeyCatalogParser.sha256Hex(bytes),
            fetchedAtEpochMs = fetchedAtEpochMs,
        )
    }

    /** Reads a bounded stream the same way a live download does. */
    fun readBounded(input: InputStream, maxBytes: Long = MAX_HTML_BYTES): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw VentoyControlKeyCatalogException(
                    "Plugson control HTML exceeded the $maxBytes byte limit while reading",
                )
            }
            output.write(buffer, 0, read)
        }
        if (total == 0L) {
            throw VentoyControlKeyCatalogException("Plugson control HTML download was empty")
        }
        return output.toByteArray()
    }

    private fun download(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        openConnection: (URL) -> HttpURLConnection,
    ): ByteArray {
        val connection = openHttps(url, connectTimeoutMs, readTimeoutMs, openConnection)
        try {
            val reported = connection.contentLengthLong
            if (reported > MAX_HTML_BYTES) {
                throw VentoyControlKeyCatalogException(
                    "Plugson control HTML reports $reported bytes; limit is $MAX_HTML_BYTES",
                )
            }
            return readBounded(connection.inputStream, MAX_HTML_BYTES)
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        openConnection: (URL) -> HttpURLConnection,
    ): HttpURLConnection {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val host = current.host.lowercase(Locale.ROOT)
            if (host != ALLOWED_HOST) {
                throw VentoyControlKeyCatalogException("Refusing untrusted catalog host: $host")
            }
            if (!current.protocol.equals("https", ignoreCase = true)) {
                throw VentoyControlKeyCatalogException("Refusing non-HTTPS catalog URL: $current")
            }
            val connection = openConnection(current)
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) {
                    throw VentoyControlKeyCatalogException(
                        "HTTP $status from $current without a Location header",
                    )
                }
                redirects++
                if (redirects > MAX_REDIRECTS) {
                    throw VentoyControlKeyCatalogException("Too many redirects fetching catalog from $url")
                }
                current = URL(current, location)
                continue
            }
            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw VentoyControlKeyCatalogException("HTTP $status from $current")
            }
            return connection
        }
    }
}
