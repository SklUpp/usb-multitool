package usb.ventoy

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the Plugson control-key catalog: real reduced upstream HTML,
 * duplicate/empty failures, byte caps, and a pinned HTTPS URL shape.
 */
class VentoyControlKeyCatalogTest {
    @Test
    fun reducedOfficialFixtureYieldsRealControlKeysOnly() {
        val html = resourceText("ventoy/plugson_control_reduced.html")
        val bytes = html.toByteArray(Charsets.UTF_8)
        val catalog =
            VentoyControlKeyCatalogParser.parse(
                html = html,
                ventoyRelease = VentoyAssets.PINNED_RELEASE,
                upstreamRef = VentoyControlKeyCatalogClient.upstreamRef(),
                upstreamSha256 = VentoyControlKeyCatalogParser.sha256Hex(bytes),
                fetchedAtEpochMs = 1_700_000_000_000L,
            )

        assertEquals(VentoyAssets.PINNED_RELEASE, catalog.ventoyRelease)
        assertEquals(
            listOf(
                "VTOY_MENU_LANGUAGE",
                "VTOY_DEFAULT_SEARCH_ROOT",
                "VTOY_SECURE_BOOT_POLICY",
                "VTOY_WIN11_BYPASS_CHECK",
                "VTOY_DEFAULT_MENU_MODE",
                "VTOY_MENU_TIMEOUT",
                "VTOY_MAX_SEARCH_LEVEL",
                "VTOY_DEFAULT_KBD_LAYOUT",
            ),
            catalog.keys.map { it.key },
        )
        assertFalse(catalog.keys.any { it.key.equals("theme", ignoreCase = true) })

        assertEquals(VentoyControlKeyKind.TEXT, catalog.key("VTOY_MENU_LANGUAGE")!!.kind)
        assertTrue(catalog.key("VTOY_MENU_LANGUAGE")!!.allowed.isEmpty())

        assertEquals(VentoyControlKeyKind.PATH, catalog.key("VTOY_DEFAULT_SEARCH_ROOT")!!.kind)
        assertEquals(VentoyControlKeyKind.INTEGER, catalog.key("VTOY_MENU_TIMEOUT")!!.kind)

        assertEquals(
            VentoyControlKeyDef(
                key = "VTOY_SECURE_BOOT_POLICY",
                kind = VentoyControlKeyKind.FLAG,
                allowed = listOf("0", "1"),
            ),
            catalog.key("VTOY_SECURE_BOOT_POLICY"),
        )
        assertEquals(
            VentoyControlKeyDef(
                key = "VTOY_WIN11_BYPASS_CHECK",
                kind = VentoyControlKeyKind.FLAG,
                allowed = listOf("0", "1"),
            ),
            catalog.key("VTOY_WIN11_BYPASS_CHECK"),
        )
        assertEquals(
            VentoyControlKeyDef(
                key = "VTOY_DEFAULT_MENU_MODE",
                kind = VentoyControlKeyKind.FLAG,
                allowed = listOf("0", "1"),
            ),
            catalog.key("VTOY_DEFAULT_MENU_MODE"),
        )

        val levels = catalog.key("VTOY_MAX_SEARCH_LEVEL")!!
        assertEquals(VentoyControlKeyKind.CHOICE, levels.kind)
        assertEquals(
            listOf("max", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
            levels.allowed,
        )

        val kbd = catalog.key("VTOY_DEFAULT_KBD_LAYOUT")!!
        assertEquals(VentoyControlKeyKind.CHOICE, kbd.kind)
        assertEquals(
            listOf(
                "QWERTY_USA",
                "AZERTY",
                "CZECH_QWERTY",
                "CZECH_QWERTZ",
                "DANISH",
                "DVORAK_USA",
                "FRENCH",
                "GERMAN",
                "ITALIANO",
                "JAPAN_106",
                "LATIN_USA",
                "PORTU_BRAZIL",
                "QWERTY_UK",
                "QWERTZ",
                "QWERTZ_HUN",
                "QWERTZ_SLOV_CROAT",
                "SPANISH",
                "SWEDISH",
                "TURKISH_Q",
                "VIETNAMESE",
            ),
            kbd.allowed,
        )
    }

    @Test
    fun duplicateKeysRejectEntireCatalog() {
        val html = resourceText("ventoy/plugson_control_duplicate.html")
        val error =
            assertThrows(VentoyControlKeyCatalogException::class.java) {
                VentoyControlKeyCatalogParser.parse(
                    html = html,
                    ventoyRelease = VentoyAssets.PINNED_RELEASE,
                    upstreamRef = VentoyControlKeyCatalogClient.upstreamRef(),
                    upstreamSha256 = "a".repeat(64),
                    fetchedAtEpochMs = 0L,
                )
            }
        assertTrue(error.message!!.contains("duplicate key \"VTOY_MENU_TIMEOUT\""))
    }

    @Test
    fun missingBoxTitlesRejectEntireCatalog() {
        val html = resourceText("ventoy/plugson_control_empty.html")
        val error =
            assertThrows(VentoyControlKeyCatalogException::class.java) {
                VentoyControlKeyCatalogParser.parse(
                    html = html,
                    ventoyRelease = VentoyAssets.PINNED_RELEASE,
                    upstreamRef = VentoyControlKeyCatalogClient.upstreamRef(),
                    upstreamSha256 = "b".repeat(64),
                    fetchedAtEpochMs = 0L,
                )
            }
        assertTrue(error.message!!.contains("no h3.box-title"))
    }

    @Test
    fun parserRejectsOversizedHtmlBeforeScanning() {
        val huge = "x".repeat(VentoyControlKeyCatalogParser.MAX_HTML_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) {
            VentoyControlKeyCatalogParser.parse(
                html = huge,
                ventoyRelease = VentoyAssets.PINNED_RELEASE,
                upstreamRef = VentoyControlKeyCatalogClient.upstreamRef(),
                upstreamSha256 = "c".repeat(64),
                fetchedAtEpochMs = 0L,
            )
        }
    }

    @Test
    fun readBoundedStopsAtByteCap() {
        val payload = ByteArray(32) { 'a'.code.toByte() }
        assertThrows(VentoyControlKeyCatalogException::class.java) {
            VentoyControlKeyCatalogClient.readBounded(ByteArrayInputStream(payload), maxBytes = 16)
        }
        val ok = VentoyControlKeyCatalogClient.readBounded(ByteArrayInputStream(payload), maxBytes = 32)
        assertEquals(32, ok.size)
    }

    @Test
    fun catalogUrlIsPinnedHttpsRawGithub() {
        assertEquals(
            "https://raw.githubusercontent.com/ventoy/Ventoy/v1.1.18/Plugson/www/plugson_control.html",
            VentoyControlKeyCatalogClient.catalogUrl(VentoyAssets.PINNED_RELEASE),
        )
        assertThrows(IllegalArgumentException::class.java) {
            VentoyControlKeyCatalogClient.catalogUrl("../evil")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VentoyControlKeyCatalogClient.catalogUrl("master")
        }
    }

    @Test
    fun fetchParsesInjectedHttpsBodyAndSurfacesHardFailures() {
        val html = resourceText("ventoy/plugson_control_reduced.html")
        val bytes = html.toByteArray(Charsets.UTF_8)
        FakeHttp.install(bytes = bytes, responseCode = 200)

        val ok =
            VentoyControlKeyCatalogClient.fetch(
                release = VentoyAssets.PINNED_RELEASE,
                fetchedAtEpochMs = 42L,
                openConnection = FakeHttp::open,
            )
        assertTrue(ok is VentoyControlKeyCatalogResult.Ok)
        val catalog = (ok as VentoyControlKeyCatalogResult.Ok).catalog
        assertEquals(8, catalog.keys.size)
        assertEquals(42L, catalog.fetchedAtEpochMs)
        assertEquals(VentoyControlKeyCatalogParser.sha256Hex(bytes), catalog.upstreamSha256)

        FakeHttp.install(bytes = ByteArray(0), responseCode = 500)
        val failed =
            VentoyControlKeyCatalogClient.fetch(
                release = VentoyAssets.PINNED_RELEASE,
                openConnection = FakeHttp::open,
            )
        assertTrue(failed is VentoyControlKeyCatalogResult.Failed)
        assertTrue((failed as VentoyControlKeyCatalogResult.Failed).message.contains("HTTP 500"))

        FakeHttp.install(
            bytes = ByteArray(0),
            responseCode = 200,
            contentLength = VentoyControlKeyCatalogClient.MAX_HTML_BYTES + 1,
        )
        val oversized =
            VentoyControlKeyCatalogClient.fetch(
                release = VentoyAssets.PINNED_RELEASE,
                openConnection = FakeHttp::open,
            )
        assertTrue(oversized is VentoyControlKeyCatalogResult.Failed)
        assertTrue((oversized as VentoyControlKeyCatalogResult.Failed).message.contains("reports"))
    }

    @Test
    fun parseDownloadedRejectsNonAllowlistedSource() {
        val html = resourceText("ventoy/plugson_control_reduced.html")
        assertThrows(IllegalArgumentException::class.java) {
            VentoyControlKeyCatalogClient.parseDownloaded(
                bytes = html.toByteArray(Charsets.UTF_8),
                fetchedAtEpochMs = 0L,
                sourceUrl = "https://evil.example/plugson_control.html",
            )
        }
    }

    private fun resourceText(path: String): String {
        val stream =
            checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
                "missing test resource $path"
            }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * Minimal HTTPS stand-in so fetch() stays free of a real network and of extra test deps.
     * Bodies are swapped per assertion; openConnection is injected by the test.
     */
    private object FakeHttp {
        @Volatile private var body: ByteArray = ByteArray(0)
        @Volatile private var code: Int = 200
        @Volatile private var length: Long? = null

        fun install(bytes: ByteArray, responseCode: Int, contentLength: Long? = null) {
            body = bytes
            code = responseCode
            length = contentLength
        }

        fun open(url: URL): HttpURLConnection = FakeConnection(url, body, code, length)
    }

    private class FakeConnection(
        url: URL,
        private val body: ByteArray,
        private val code: Int,
        private val lengthOverride: Long?,
    ) : HttpURLConnection(url) {
        override fun connect() {
            connected = true
        }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getContentLengthLong(): Long = lengthOverride ?: body.size.toLong()

        override fun getInputStream(): InputStream {
            if (code != HTTP_OK) throw IOException("HTTP $code")
            return ByteArrayInputStream(body)
        }

        override fun getHeaderField(name: String?): String? = null
    }
}
