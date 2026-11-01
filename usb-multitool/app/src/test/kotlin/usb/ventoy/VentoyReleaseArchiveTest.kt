package usb.ventoy

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VentoyReleaseArchiveTest {

    @Test
    fun locatesMembersByBaseNameRegardlessOfArchiveLayout() {
        val boot = ByteArray(512) { 0x11 }
        val core = ByteArray(4096) { 0x22 }
        val disk = ByteArray(8192) { 0x33 }
        val archive =
            tarGz(
                listOf(
                    "ventoy-1.1.18/Ventoy2Disk.sh" to ByteArray(64) { 0x44 },
                    "ventoy-1.1.18/boot/boot.img" to boot,
                    "ventoy-1.1.18/boot/core.img.xz" to core,
                    "ventoy-1.1.18/ventoy/ventoy.disk.img.xz" to disk,
                    "ventoy-1.1.18/ventoy/version" to "1.1.18\n".toByteArray(),
                ),
            )

        val members =
            VentoyReleaseArchive.extract(
                archive.inputStream(),
                setOf("boot.img", "core.img.xz", "ventoy.disk.img.xz"),
            )

        assertEquals(3, members.size)
        assertArrayEquals(boot, members.getValue("boot.img"))
        assertArrayEquals(core, members.getValue("core.img.xz"))
        assertArrayEquals(disk, members.getValue("ventoy.disk.img.xz"))
    }

    @Test
    fun failsWhenAMemberIsMissing() {
        val archive = tarGz(listOf("ventoy-1.1.18/boot/boot.img" to ByteArray(512)))
        try {
            VentoyReleaseArchive.extract(
                archive.inputStream(),
                setOf("boot.img", "core.img.xz", "ventoy.disk.img.xz"),
            )
            fail("Expected VentoyReleaseArchiveException")
        } catch (e: VentoyReleaseArchive.VentoyReleaseArchiveException) {
            assertTrue(e.message!!.contains("core.img.xz"))
            assertTrue(e.message!!.contains("ventoy.disk.img.xz"))
        }
    }

    @Test
    fun failsWhenAMemberExceedsTheLimit() {
        val archive = tarGz(listOf("ventoy-1.1.18/boot/boot.img" to ByteArray(1024)))
        try {
            VentoyReleaseArchive.extract(archive.inputStream(), setOf("boot.img"), maxMemberBytes = 512)
            fail("Expected VentoyReleaseArchiveException")
        } catch (e: VentoyReleaseArchive.VentoyReleaseArchiveException) {
            assertTrue(e.message!!.contains("member limit"))
        }
    }

    private fun tarGz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val tar = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val header = ByteArray(512)
            name.toByteArray(Charsets.UTF_8).copyInto(header, 0, 0, minOf(name.length, 100))
            "0000644\u0000".toByteArray().copyInto(header, 100)
            "0000000\u0000".toByteArray().copyInto(header, 108)
            "0000000\u0000".toByteArray().copyInto(header, 116)
            val size = data.size.toString(8).padStart(11, '0') + "\u0000"
            size.toByteArray().copyInto(header, 124)
            "00000000000\u0000".toByteArray().copyInto(header, 136)
            header[156] = '0'.code.toByte()
            "ustar".toByteArray().copyInto(header, 257)
            "00".toByteArray().copyInto(header, 263)
            var checksum = 0
            for (i in header.indices) {
                checksum += if (i in 148 until 156) ' '.code else (header[i].toInt() and 0xFF)
            }
            (checksum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray().copyInto(header, 148)
            tar.write(header)
            tar.write(data)
            val padding = (512 - data.size % 512) % 512
            tar.write(ByteArray(padding))
        }
        tar.write(ByteArray(1024))

        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(tar.toByteArray()) }
        return out.toByteArray()
    }
}
