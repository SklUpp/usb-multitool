package usb.ventoy

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import usb.device.BlockDevice
import usb.device.DiskException
import usb.fs.BlockDeviceFatCallback
import usb.fs.FatFs
import usb.fs.FatMkfsType
import usb.partition.SyntheticGpt
import usb.partition.VirtualPartition
import usb.testutil.RecordingDisk

/**
 * Volume-level contract for the settings workflow. Native FatFs is required; without the
 * linked engine these tests skip rather than fake a filesystem.
 */
class VentoySettingsVolumeTest {
    private val part1Sectors = 8192L
    private val part2Sectors = VentoyLayout.EFI_PART_SECTORS

    private fun mountableVentoyStick(): RecordingDisk {
        val disk = RecordingDisk(sectorCount = 262144UL, sectorSize = 512)
        val mbr = ByteArray(512)
        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        mbr[446 + 4] = 0x07
        putLe32(mbr, 446 + 8, VentoyLayout.PART1_START_SECTOR.toInt())
        putLe32(mbr, 446 + 12, part1Sectors.toInt())
        mbr[462 + 4] = 0xEF.toByte()
        putLe32(mbr, 462 + 8, (VentoyLayout.PART1_START_SECTOR + part1Sectors).toInt())
        putLe32(mbr, 462 + 12, part2Sectors.toInt())
        disk.write(0L, ByteBuffer.wrap(mbr))
        val part1 = VirtualPartition(
            startLba = VentoyLayout.PART1_START_SECTOR.toULong(),
            sectorCount = part1Sectors.toULong(),
            disk = disk,
        )
        val callback = BlockDeviceFatCallback(part1)
        try {
            FatFs.mkfs(callback, part1.sectorSize, part1.sectorCount.toLong(), FatMkfsType.FAT)
        } catch (missingNative: UnsatisfiedLinkError) {
            Assume.assumeNoException("FatFs native not linked in this test run", missingNative)
        }
        disk.resetStats()
        return disk
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun withPart1(disk: RecordingDisk, body: (usb.fs.UsbFsVolume) -> Unit) {
        val part1 = VirtualPartition(
            startLba = VentoyLayout.PART1_START_SECTOR.toULong(),
            sectorCount = part1Sectors.toULong(),
            disk = disk,
        )
        FatFs.mount(
            BlockDeviceFatCallback(part1),
            part1.sectorSize,
            part1.sectorCount.toLong(),
        ).use(body)
    }

    private fun seedConfig(disk: RecordingDisk, json: String) {
        withPart1(disk) { volume ->
            if (volume.statOrNull("/ventoy") == null) volume.mkdir("/ventoy")
            if (volume.statOrNull("/ventoy/ventoy.json") != null) {
                volume.delete("/ventoy/ventoy.json")
            }
            volume.write(
                "/ventoy/ventoy.json",
                json.toByteArray(Charsets.UTF_8),
                create = true,
            )
            volume.sync()
        }
    }

    private class ProbeThenFailReadDisk : BlockDevice {
        override val sectorSize: Int = 512
        override val sectorCount: ULong = 262144UL
        private var reads = 0

        override fun read(lba: ULong, destination: ByteBuffer) {
            reads++
            if (reads == 1) {
                while (destination.hasRemaining()) destination.put(0)
            } else {
                throw DiskException.IoFailure("injected read failure")
            }
        }

        override fun write(lba: ULong, source: ByteBuffer) {
            throw UnsupportedOperationException("read-only test disk")
        }

        override fun sync() = Unit
    }

    private class ProbeThenCancelReadDisk : BlockDevice {
        override val sectorSize: Int = 512
        override val sectorCount: ULong = 262144UL
        private var reads = 0

        override fun read(lba: ULong, destination: ByteBuffer) {
            reads++
            if (reads == 1) {
                while (destination.hasRemaining()) destination.put(0)
            } else {
                throw java.util.concurrent.CancellationException("injected cancel")
            }
        }

        override fun write(lba: ULong, source: ByteBuffer) {
            throw UnsupportedOperationException("read-only test disk")
        }

        override fun sync() = Unit
    }

    @Test
    fun layoutInspectionCancellationIsNotSwallowed() {
        try {
            VentoySetup.readGlobalSettings(ProbeThenCancelReadDisk())
            org.junit.Assert.fail("cancellation must propagate")
        } catch (cancelled: java.util.concurrent.CancellationException) {
            assertEquals("injected cancel", cancelled.message)
        }
    }

    @Test
    fun layoutInspectionReadFailureIsReadErrorNotMissing() {
        val read = VentoySetup.readGlobalSettings(ProbeThenFailReadDisk())
        assertTrue(
            "an I/O failure while inspecting the layout must be a ReadError",
            read is VentoyGlobalSettings.ReadError,
        )
        read as VentoyGlobalSettings.ReadError
        assertFalse("I/O failure is not a damaged-layout verdict", read.damagedLayout)
        assertTrue(read.message.contains("could not be inspected"))
    }

    @Test
    fun damagedVentoyLayoutIsReadErrorNotMissing() {
        val disk = RecordingDisk(sectorCount = 262144UL, sectorSize = 512)
        val mbr = ByteArray(512)
        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        mbr[446 + 4] = 0x07
        putLe32(mbr, 446 + 8, VentoyLayout.PART1_START_SECTOR.toInt())
        putLe32(mbr, 446 + 12, 1024) // too small for probe, but signature is still Ventoy-shaped
        mbr[462 + 4] = 0xEF.toByte()
        putLe32(mbr, 462 + 8, 2048 + 1024)
        putLe32(mbr, 462 + 12, part2Sectors.toInt())
        disk.write(0L, ByteBuffer.wrap(mbr))

        assertTrue(VentoyLayout.looksLikeVentoyLayout(disk))
        assertNull(VentoyLayout.probe(disk))
        assertTrue(
            "a Ventoy-shaped but invalid layout must not be reported as a clean missing file",
            VentoySetup.readGlobalSettings(disk) is VentoyGlobalSettings.ReadError,
        )
    }

    @Test
    fun damagedGptVentoyLayoutIsLocalizedReadError() {
        val sectorCount = (2048 + 8192 + VentoyLayout.EFI_PART_SECTORS + 2048).toULong()
        val disk = SyntheticGpt.build(
            sectorCount = sectorCount,
            layout = listOf(
                SyntheticGpt.Layout(0, 2048UL, 2048UL + 8191UL, "Ventoy"),
                SyntheticGpt.Layout(
                    1,
                    2048UL + 8192UL,
                    2048UL + 8192UL + VentoyLayout.EFI_PART_SECTORS.toULong() - 1UL,
                    "VTOYEFI",
                    efi = true,
                ),
            ),
        )
        // Corrupt only the primary header CRC field: probe must fail closed, while the raw
        // VTOYEFI signature is still readable for classification.
        val header = ByteArray(512)
        disk.read(1L, ByteBuffer.wrap(header))
        header[16] = (header[16].toInt() xor 0xFF).toByte()
        disk.write(1L, ByteBuffer.wrap(header))

        assertNull("probe must reject the bad GPT CRC", VentoyLayout.probe(disk))
        assertTrue("damaged GPT must still carry Ventoy signatures: ${VentoyLayout.looksLikeVentoyLayout(disk)}", VentoyLayout.looksLikeVentoyLayout(disk))
        val read = VentoySetup.readGlobalSettings(disk)
        assertTrue(read is VentoyGlobalSettings.ReadError)
        assertTrue(
            "damaged layout must be flagged for a localized message",
            (read as VentoyGlobalSettings.ReadError).damagedLayout,
        )
        assertFalse(
            "damaged-layout message must not advise reinstalling",
            read.message.contains("install", ignoreCase = true),
        )
    }

    @Test
    fun ventoyDirectoryThatIsAFileIsReadErrorNotMissing() {
        val disk = mountableVentoyStick()
        withPart1(disk) { volume ->
            volume.write("/ventoy", "not a directory".toByteArray(), create = true)
            volume.sync()
        }
        assertTrue(
            "/ventoy as a file must be a read error, not missing settings",
            VentoySetup.readGlobalSettings(disk) is VentoyGlobalSettings.ReadError,
        )
    }

    @Test
    fun volumeDistinguishesMissingEmptyAndReadError() {
        val disk = mountableVentoyStick()
        assertEquals(VentoyGlobalSettings.Missing, VentoySetup.readGlobalSettings(disk))

        seedConfig(disk, "")
        assertEquals(VentoyGlobalSettings.Empty, VentoySetup.readGlobalSettings(disk))

        seedConfig(disk, "{broken")
        assertTrue(
            "invalid JSON must be a read error, never empty",
            VentoySetup.readGlobalSettings(disk) is VentoyGlobalSettings.ReadError,
        )

        val directoryDisk = mountableVentoyStick()
        withPart1(directoryDisk) { volume ->
            volume.mkdir("/ventoy")
            volume.mkdir("/ventoy/ventoy.json")
            volume.sync()
        }
        assertTrue(
            "a directory named ventoy.json is a read error",
            VentoySetup.readGlobalSettings(directoryDisk) is VentoyGlobalSettings.ReadError,
        )
    }

    @Test
    fun applyPreservesUnknownSectionsAndSkipsNoOpWrites() {
        val disk = mountableVentoyStick()
        seedConfig(
            disk,
            """
            {
              "control": [
                {"VTOY_WIN11_BYPASS_CHECK": "0"},
                {"MY KEY": "old"},
                {"KEEP_ME": "same"}
              ],
              "auto_install": [{"image": "/ISO/Win.iso"}],
              "custom_section": {"keep": true}
            }
            """.trimIndent(),
        )
        val changed =
            VentoySetup.applyGlobalControls(
                disk,
                listOf(
                    VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1"),
                    VentoyControlSetting("MY KEY", null),
                    VentoyControlSetting("NEW_KEY", "value"),
                ),
            )
        assertTrue(changed)
        assertTrue("block device must be flushed", disk.syncCount > 0)

        withPart1(disk) { volume ->
            val size = requireNotNull(volume.statOrNull("/ventoy/ventoy.json")).size.toInt()
            val text = String(volume.read("/ventoy/ventoy.json", 0L, size))
            val root = MiniJson.parse(text) as Map<*, *>
            val control = root["control"] as List<*>
            val flattened = control.flatMap { (it as Map<*, *>).entries }.associate {
                it.key.toString() to it.value
            }
            assertEquals("1", flattened["VTOY_WIN11_BYPASS_CHECK"])
            assertEquals("same", flattened["KEEP_ME"])
            assertEquals("value", flattened["NEW_KEY"])
            assertFalse("removal must drop only the chosen key", "MY KEY" in flattened)
            assertEquals(true, (root["custom_section"] as Map<*, *>)["keep"])
            assertNotNull(root["auto_install"])
        }

        val writesBefore = disk.writeCount
        assertFalse(
            "applying the same value must not rewrite the file",
            VentoySetup.applyGlobalControls(
                disk,
                listOf(VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", "1")),
            ),
        )
        assertEquals("no-op must not write bytes", writesBefore, disk.writeCount)
    }

    @Test
    fun removalOnlyOnMissingFileCreatesNothing() {
        val disk = mountableVentoyStick()
        assertFalse(
            VentoySetup.applyGlobalControls(
                disk,
                listOf(VentoyControlSetting("VTOY_WIN11_BYPASS_CHECK", null)),
            ),
        )
        assertTrue(
            "a no-op removal must not create ventoy.json",
            VentoySetup.readGlobalSettings(disk) == VentoyGlobalSettings.Missing,
        )
    }
}
