package usb.ventoy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read/merge/write contract for a phone settings document, without Android. */
class VentoySettingsDocumentTest {
    @Test
    fun readReturnsExactBytesAndRejectsATruncatedProviderReport() {
        val text = """{"control": [{"KEEP": "yes"}]}"""
        assertEquals(
            text,
            VentoySettingsDocument.read(
                ByteArrayInputStream(text.toByteArray()),
                expectedBytes = text.toByteArray().size.toLong(),
            ),
        )
        assertThrows(VentoySettingsDocumentException::class.java) {
            VentoySettingsDocument.read(
                ByteArrayInputStream(text.toByteArray()),
                expectedBytes = text.toByteArray().size + 1L,
            )
        }
    }

    @Test
    fun readRefusesADocumentOverTheLimit() {
        val bytes = ByteArray(9) { 'a'.code.toByte() }
        assertThrows(VentoySettingsDocumentException::class.java) {
            VentoySettingsDocument.read(ByteArrayInputStream(bytes), maxBytes = 8)
        }
    }

    @Test
    fun mergePreservesUnknownSectionsAndOrderAndOnlyChangesChosenKeys() {
        val existing =
            """
            {
              "control": [
                {"VTOY_WIN11_BYPASS_CHECK": "0"},
                {"USER_ONE": "keep"},
                {"USER_TWO": "remove"}
              ],
              "auto_install": [{"image": "/ISO/Win.iso"}],
              "custom_section": {"keep": true}
            }
            """.trimIndent()
        val merged =
            VentoySettingsDocument.merge(
                existing,
                listOf(
                    VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"),
                    VentoyControlSetting("USER_TWO", null),
                    VentoyControlSetting("NEW_KEY", "value"),
                ),
            )
        assertTrue(merged.changed)
        val root = MiniJson.parse(merged.text) as Map<*, *>
        val control = root["control"] as List<*>
        val keys = control.flatMap { (it as Map<*, *>).keys }.map { it.toString() }
        assertEquals(
            listOf("VTOY_WIN11_BYPASS_CHECK", "USER_ONE", "NEW_KEY"),
            keys,
        )
        val values =
            control.flatMap { (it as Map<*, *>).entries }.associate {
                it.key.toString() to it.value
            }
        assertEquals("1", values["VTOY_WIN11_BYPASS_CHECK"])
        assertEquals("keep", values["USER_ONE"])
        assertEquals("value", values["NEW_KEY"])
        assertEquals(true, (root["custom_section"] as Map<*, *>)["keep"])
        assertTrue(root.containsKey("auto_install"))
    }

    @Test
    fun invalidJsonIsRejectedBeforeAnyWriteCanOpen() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                VentoySettingsDocument.merge("{broken", listOf(VentoyControlSetting("A", "1")))
            }
        assertTrue(failure.message.orEmpty().contains("not valid JSON"))
    }

    @Test
    fun aNoOpMergeKeepsTheOriginalText() {
        val existing = """{"control": [{"A": "1"}]}"""
        val merged =
            VentoySettingsDocument.merge(existing, listOf(VentoyControlSetting("A", "1")))
        assertFalse(merged.changed)
        assertEquals(existing, merged.text)
    }

    @Test
    fun writeFlushesEveryUtf8Byte() {
        val sink = ByteArrayOutputStream()
        val text = """{"control": [{"KEY": "значение"}]}"""
        val written = VentoySettingsDocument.write(sink, text)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, written)
        assertEquals(text, sink.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun aFailingStreamSurfacesTheFailureAfterPartialBytes() {
        val sink =
            object : OutputStream() {
                var written = 0
                override fun write(b: Int) {
                    written++
                    if (written > 3) throw IOException("injected write failure")
                }
            }
        val failure =
            assertThrows(IOException::class.java) {
                VentoySettingsDocument.write(sink, "0123456789")
            }
        assertEquals("injected write failure", failure.message)
    }

    @Test
    fun everyTruncatingWriteFailureIsReportedAsPotentiallyTruncated() {
        val writeFailure =
            VentoySettingsDocument.writeTruncating(
                open = {
                    object : OutputStream() {
                        override fun write(b: Int) = throw IOException("write failed")
                    }
                },
                text = "{}",
            )
        assertTrue(writeFailure.mayBeTruncated)
        assertEquals("write failed", writeFailure.failure?.message)

        val openFailure =
            VentoySettingsDocument.writeTruncating(
                open = { throw IOException("open failed") },
                text = "{}",
            )
        assertTrue(
            "a failed open may still have truncated the provider document",
            openFailure.mayBeTruncated,
        )

        val success =
            VentoySettingsDocument.writeTruncating(
                open = { ByteArrayOutputStream() },
                text = """{"control": []}""",
            )
        assertFalse(success.mayBeTruncated)
        assertEquals("""{"control": []}""".toByteArray().size, success.bytes)
    }

    @Test
    fun cancellationIsNotConvertedIntoAWriteFailure() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            VentoySettingsDocument.writeTruncating(
                open = { throw java.util.concurrent.CancellationException("cancelled") },
                text = "{}",
            )
        }
    }
}
