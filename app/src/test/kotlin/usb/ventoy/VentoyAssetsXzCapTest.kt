package usb.ventoy

import java.io.ByteArrayOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VentoyAssetsXzCapTest {

    @Test
    fun decompressXzRejectsOutputBeyondTheCap() {
        val compressed = xz(ByteArray(8 * 1024) { 0x5A })
        try {
            VentoyAssets.decompressXz(compressed, maxBytes = 1024)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("limit"))
        }
    }

    @Test
    fun decompressXzAllowsOutputExactlyAtTheCap() {
        val payload = ByteArray(1024) { 0x33 }
        assertArrayEquals(payload, VentoyAssets.decompressXz(xz(payload), maxBytes = 1024))
    }

    @Test
    fun defaultCapIs64MiB() {
        assertEquals(64L * 1024 * 1024, VentoyAssets.MAX_DECOMPRESSED_ASSET_BYTES)
    }

    private fun xz(payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            XZCompressorOutputStream(out).use { it.write(payload) }
        }.toByteArray()
}
