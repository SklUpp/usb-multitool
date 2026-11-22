package usb.fs

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.partition.ImageBlockDevice

class IsoVolumeTest {
    @Test
    fun jolietNamesArePreferredAndFileBytesAreReadable() {
        val iso = isoImage(jolietName = "hello.txt", primaryName = "HELLO.TXT;1")
        val volume = IsoVolume(iso.device)

        assertTrue(IsoVolume.probe(iso.device))
        assertEquals("TESTVOL", volume.volumeLabel)
        val listing = volume.list("/").associateBy { it.name }
        assertEquals(setOf("hello.txt"), listing.keys)
        assertFalse(listing.getValue("hello.txt").isDirectory)
        assertEquals(5L, listing.getValue("hello.txt").size)
        assertEquals("world", String(volume.read("/hello.txt", 0, 5), StandardCharsets.US_ASCII))
        assertEquals("rld", String(volume.read("/hello.txt", 2, 3), StandardCharsets.US_ASCII))
        assertEquals(0, volume.read("/hello.txt", 5, 3).size)
        assertTrue(volume.statOrNull("/missing") == null)
        assertNull(volume.statOrNull("/HELLO.TXT;1"))
    }

    @Test
    fun multiExtentFileReadsAcrossExtents() {
        val iso = isoImage(
            jolietName = "hello.txt",
            primaryName = "HELLO.TXT;1",
            firstExtentSize = 3,
            secondExtentText = "de",
        )
        val volume = IsoVolume(iso.device)

        assertEquals(5L, volume.stat("/hello.txt").size)
        assertEquals("rde", String(volume.read("/hello.txt", 2, 4), StandardCharsets.US_ASCII))
    }

    @Test
    fun directoriesAreResolvedCaseInsensitively() {
        val iso = isoImage(
            jolietName = "hello.txt",
            primaryName = "HELLO.TXT;1",
            childDirectory = "SUB",
        )
        val volume = IsoVolume(iso.device)

        assertTrue(volume.stat("/sub").isDirectory)
        assertEquals("hello.txt", volume.list("/SUB").single().name)
        assertTrue(runCatching { volume.list("/missing") }.exceptionOrNull() is FileNotFoundException)
    }

    @Test
    fun mutationsAreRejected() {
        val volume = IsoVolume(isoImage("hello.txt", "HELLO.TXT;1").device)
        assertTrue(runCatching { volume.mkdir("/x") }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { volume.write("/hello.txt", ByteArray(1), 0, false, 1, false) }
            .exceptionOrNull() is java.io.IOException)
    }

    private class IsoFixture(val device: ImageBlockDevice)

    private fun isoImage(
        jolietName: String,
        primaryName: String,
        firstExtentSize: Int = 5,
        secondExtentText: String? = null,
        childDirectory: String? = null,
    ): IsoFixture {
        val block = 2048
        val image = ByteArray(block * 40)
        val rootDirBlock = 20
        val jolietRootDirBlock = 21
        val childDirBlock = 22
        val jolietChildDirBlock = 23
        val fileBlock = 24
        val secondBlock = 25

        val fileText = "world"
        putAscii(image, fileBlock * block, fileText)
        if (secondExtentText != null) putAscii(image, secondBlock * block, secondExtentText)

        val primaryRoot = directoryBytes(
            records = listOf(
                directoryRecord(".", rootDirBlock, 2048, joliet = false, directory = true),
                directoryRecord("..", rootDirBlock, 2048, joliet = false, directory = true),
                directoryRecord(
                    primaryName,
                    fileBlock,
                    firstExtentSize,
                    joliet = false,
                    directory = false,
                    multiExtent = secondExtentText != null,
                ),
            ) + if (secondExtentText != null) {
                listOf(
                    directoryRecord(
                        primaryName,
                        secondBlock,
                        secondExtentText.length,
                        joliet = false,
                        directory = false,
                    ),
                )
            } else {
                emptyList()
            } + if (childDirectory != null) {
                listOf(
                    directoryRecord(
                        childDirectory,
                        childDirBlock,
                        2048,
                        joliet = false,
                        directory = true,
                    ),
                )
            } else {
                emptyList()
            },
        )
        val jolietRoot = directoryBytes(
            records = listOf(
                directoryRecord(".", jolietRootDirBlock, 2048, joliet = true, directory = true),
                directoryRecord("..", jolietRootDirBlock, 2048, joliet = true, directory = true),
                directoryRecord(
                    jolietName,
                    fileBlock,
                    firstExtentSize,
                    joliet = true,
                    directory = false,
                    multiExtent = secondExtentText != null,
                ),
                directoryRecord(
                    jolietName,
                    secondBlock,
                    secondExtentText?.length ?: 0,
                    joliet = true,
                    directory = false,
                ).takeIf { secondExtentText != null },
                directoryRecord(
                    childDirectory?.lowercase() ?: "",
                    jolietChildDirBlock,
                    2048,
                    joliet = true,
                    directory = true,
                ).takeIf { childDirectory != null },
            ).filterNotNull(),
        )
        primaryRoot.copyInto(image, rootDirBlock * block)
        jolietRoot.copyInto(image, jolietRootDirBlock * block)
        if (childDirectory != null) {
            directoryBytes(
                listOf(
                    directoryRecord(".", childDirBlock, 2048, joliet = false, directory = true),
                    directoryRecord("..", rootDirBlock, 2048, joliet = false, directory = true),
                    directoryRecord(primaryName, fileBlock, firstExtentSize, joliet = false, directory = false),
                ),
            ).copyInto(image, childDirBlock * block)
            directoryBytes(
                listOf(
                    directoryRecord(".", jolietChildDirBlock, 2048, joliet = true, directory = true),
                    directoryRecord("..", jolietRootDirBlock, 2048, joliet = true, directory = true),
                    directoryRecord(jolietName, fileBlock, firstExtentSize, joliet = true, directory = false),
                ),
            ).copyInto(image, jolietChildDirBlock * block)
        }

        descriptor(
            image,
            type = 1,
            block = 16,
            rootBlock = rootDirBlock,
            rootSize = primaryRoot.size,
            joliet = false,
        )
        descriptor(
            image,
            type = 2,
            block = 17,
            rootBlock = jolietRootDirBlock,
            rootSize = jolietRoot.size,
            joliet = true,
        )
        image[18 * block] = 255.toByte()
        putAscii(image, 18 * block + 1, "CD001")
        image[18 * block + 6] = 1

        return IsoFixture(ImageBlockDevice(image, 512, (image.size / 512).toULong()))
    }

    private fun descriptor(
        image: ByteArray,
        type: Int,
        block: Int,
        rootBlock: Int,
        rootSize: Int,
        joliet: Boolean,
    ) {
        val offset = block * 2048
        image[offset] = type.toByte()
        putAscii(image, offset + 1, "CD001")
        image[offset + 6] = 1
        putAscii(image, offset + 40, "TESTVOL".padEnd(32, ' '))
        putLe32(image, offset + 80, 40)
        putLe16(image, offset + 120, 1)
        putLe16(image, offset + 124, 1)
        putLe16(image, offset + 128, 2048)
        putLe32(image, offset + 132, 0)
        if (joliet) putAscii(image, offset + 88, "%/E")
        directoryRecord(
            "",
            rootBlock,
            rootSize,
            joliet = false,
            directory = true,
        ).copyInto(image, offset + 156)
    }

    private fun directoryBytes(records: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>(2048)
        for (record in records) {
            out.addAll(record.toList())
        }
        return out.toByteArray()
    }

    private fun directoryRecord(
        name: String,
        extent: Int,
        dataLength: Int,
        joliet: Boolean,
        directory: Boolean,
        multiExtent: Boolean = false,
    ): ByteArray {
        val nameBytes = if (joliet) name.toByteArray(Charsets.UTF_16BE) else
            name.toByteArray(Charsets.US_ASCII)
        val length = ((33 + nameBytes.size + 1) / 2) * 2
        val record = ByteArray(length)
        record[0] = length.toByte()
        putLe32(record, 2, extent)
        putBe32(record, 6, extent)
        putLe32(record, 10, dataLength)
        putBe32(record, 14, dataLength)
        record[25] = ((if (directory) 0x02 else 0x00) or (if (multiExtent) 0x80 else 0x00)).toByte()
        putLe16(record, 28, 1)
        putBe16(record, 30, 1)
        record[32] = nameBytes.size.toByte()
        nameBytes.copyInto(record, 33)
        return record
    }

    private fun putLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putBe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun putBe32(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr ((3 - index) * 8)).toByte()
    }

    private fun putAscii(bytes: ByteArray, offset: Int, text: String) {
        text.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
    }
}
