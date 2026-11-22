package usb.ventoy

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XmlTextTest {
    private val sample = """<?xml version="1.0"?><unattend xmlns="urn:schemas-microsoft-com:unattend"/>"""

    @Test
    fun utf8BomRoundtripPreservesBomAndText() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + sample.toByteArray(StandardCharsets.UTF_8)
        val decoded = XmlText.decode(bytes)
        assertEquals(sample, decoded.text)
        assertEquals(StandardCharsets.UTF_8, decoded.charset)
        assertArrayEquals(bom, decoded.bom)
        assertArrayEquals(bytes, XmlText.encode(decoded.text, decoded.charset, decoded.bom))
    }

    @Test
    fun utf16LeBomRoundtripPreservesBomAndText() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + sample.toByteArray(StandardCharsets.UTF_16LE)
        val decoded = XmlText.decode(bytes)
        assertEquals(sample, decoded.text)
        assertEquals(StandardCharsets.UTF_16LE, decoded.charset)
        assertArrayEquals(bom, decoded.bom)
        assertArrayEquals(bytes, XmlText.encode(decoded.text, decoded.charset, decoded.bom))
    }

    @Test
    fun utf16BeBomRoundtripPreservesBomAndText() {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val bytes = bom + sample.toByteArray(StandardCharsets.UTF_16BE)
        val decoded = XmlText.decode(bytes)
        assertEquals(sample, decoded.text)
        assertEquals(StandardCharsets.UTF_16BE, decoded.charset)
        assertArrayEquals(bom, decoded.bom)
        assertArrayEquals(bytes, XmlText.encode(decoded.text, decoded.charset, decoded.bom))
    }

    @Test
    fun declarationWithoutBomUsesUtf16Le() {
        val declared =
            """<?xml version="1.0" encoding="utf-16le"?><root/>"""
        val bytes = declared.toByteArray(StandardCharsets.UTF_16LE)
        val decoded = XmlText.decode(bytes)
        assertEquals(declared, decoded.text)
        assertEquals(StandardCharsets.UTF_16LE, decoded.charset)
        assertEquals(0, decoded.bom.size)
        assertArrayEquals(bytes, XmlText.encode(decoded.text, decoded.charset, decoded.bom))
    }

    @Test
    fun bomlessUtf16LeRoundtripPreservesText() {
        val declared =
            """<?xml version="1.0" encoding="utf-16le"?><unattend xmlns="urn:schemas-microsoft-com:unattend"/>"""
        val bytes = declared.toByteArray(StandardCharsets.UTF_16LE)
        val decoded = XmlText.decode(bytes)
        assertEquals(declared, decoded.text)
        assertEquals(StandardCharsets.UTF_16LE, decoded.charset)
        assertEquals(0, decoded.bom.size)
        assertArrayEquals(bytes, XmlText.encode(decoded.text, decoded.charset, decoded.bom))
    }


    @Test
    fun malformedUtf8Throws() {
        assertThrows(IllegalArgumentException::class.java) {
            XmlText.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun unknownDeclarationEncodingFallsBackToUtf8() {
        val text = """<?xml version="1.0" encoding="windows-1251"?><root/>"""
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val decoded = XmlText.decode(bytes)
        assertEquals(StandardCharsets.UTF_8, decoded.charset)
        assertEquals(text, decoded.text)
    }
}
