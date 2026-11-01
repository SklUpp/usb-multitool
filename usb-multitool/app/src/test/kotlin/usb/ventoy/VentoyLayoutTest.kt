package usb.ventoy

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.partition.TableScheme
import usb.testutil.RecordingDisk

class VentoyLayoutTest {

    private val fakeAssets = VentoyAssets.fake(efiPartSectors = 2048)

    @Test
    fun installRefuses4KnWhenUnsupported() {
        val disk4k = RecordingDisk(sectorSize = 4096, sectorCount = 32768UL)
        try {
            VentoyInstaller.install(disk4k, fakeAssets)
            fail("Expected UnsupportedSectorSizeException")
        } catch (_: UnsupportedSectorSizeException) {
            // Expected
        }
        assertEquals("No sectors should be written on 4Kn refusal", 0, disk4k.writeCount)
    }

    @Test
    fun updateRefuses4KnWhenUnsupported() {
        val disk4k = RecordingDisk(sectorSize = 4096, sectorCount = 32768UL)
        try {
            VentoyInstaller.update(disk4k, fakeAssets)
            fail("Expected UnsupportedSectorSizeException")
        } catch (_: UnsupportedSectorSizeException) {
            // Expected
        }
        assertEquals("No sectors should be written on 4Kn refusal", 0, disk4k.writeCount)
    }

    @Test
    fun updateRejectsUnknownDiskWithoutWriting() {
        val blankDisk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        try {
            VentoyInstaller.update(blankDisk, fakeAssets)
            fail("Expected IncompatibleVentoyException on blank/unknown disk")
        } catch (_: IncompatibleVentoyException) {
            // Expected
        }
        assertEquals("No writes allowed when disk layout is unrecognized", 0, blankDisk.writeCount)
    }

    @Test
    fun requireMatchingReleaseAssets() {
        val mismatchedAssets = VentoyAssets(
            release = "1.1.18",
            commit = "6116894ab8abdf3ef8d43411a6732d34ee19214e",
            bootImg = ByteArray(512) { 1 },
            coreImg = ByteArray(1024) { 2 },
            efiPartImg = ByteArray(1024) { 3 },
            bootImgSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        )
        val disk = RecordingDisk()
        try {
            VentoyInstaller.install(disk, mismatchedAssets)
            fail("Expected IllegalStateException on mismatched asset checksum")
        } catch (_: IllegalStateException) {
            // Expected checksum validation failure
        }
        assertEquals("No writes allowed on mismatched asset checksum", 0, disk.writeCount)
    }

    @Test
    fun installCreatesMbrLayout() {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        val table = VentoyInstaller.install(disk, fakeAssets, gpt = false, align4k = true)

        assertEquals(TableScheme.MBR, table.scheme)
        assertEquals(2, table.partitions.size)
        val p1 = table.partitions[0]
        val p2 = table.partitions[1]

        assertEquals(2048UL, p1.startLba)
        assertEquals(VentoyLayout.EFI_PART_SECTORS.toULong(), p2.sectorCount)
        assertEquals(p1.startLba + p1.sectorCount, p2.startLba)
        assertEquals(0UL, p2.startLba % 8UL) // 4KB aligned

        assertTrue(VentoyLayout.isVentoyDisk(disk))
        val probe = VentoyLayout.probe(disk)
        assertNotNull(probe)
        assertEquals(TableScheme.MBR, probe!!.scheme)
        assertEquals(p1.startLba, probe.part1StartLba)
        assertEquals(p2.startLba, probe.part2StartLba)
    }

    @Test
    fun installCreatesGptLayout() {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        val table = VentoyInstaller.install(disk, fakeAssets, gpt = true, align4k = true)

        assertEquals(TableScheme.GPT, table.scheme)
        assertEquals(2, table.partitions.size)
        val p1 = table.partitions[0]
        val p2 = table.partitions[1]

        assertEquals(2048UL, p1.startLba)
        assertEquals(VentoyLayout.EFI_PART_SECTORS.toULong(), p2.sectorCount)
        assertEquals(p1.startLba + p1.sectorCount, p2.startLba)
        assertEquals("Ventoy", p1.name)
        assertEquals("VTOYEFI", p2.name)

        assertTrue(VentoyLayout.isVentoyDisk(disk))
        val probe = VentoyLayout.probe(disk)
        assertNotNull(probe)
        assertEquals(TableScheme.GPT, probe!!.scheme)
        assertEquals(p1.startLba, probe.part1StartLba)
        assertEquals(p2.startLba, probe.part2StartLba)
    }

    @Test
    fun updatePreservesPartition1() {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        val initialTable = VentoyInstaller.install(disk, fakeAssets, gpt = true)

        val p1 = initialTable.partitions[0]
        val p2 = initialTable.partitions[1]

        // Write canary user data inside partition 1 (sectors 2048 to 2058)
        val canaryData = ByteArray(10 * 512) { i -> (0x7E xor (i and 0xFF)).toByte() }
        val canaryBuf = ByteBuffer.wrap(canaryData)
        disk.write(p1.startLba.toLong(), canaryBuf)

        // Reset tracking before running update
        disk.resetStats()

        // Run update with new asset set
        val updatedAssets = VentoyAssets.fake(release = "1.1.18", efiPartSectors = 2048)
        val updatedTable = VentoyInstaller.update(disk, updatedAssets)

        // 1. Verify partition geometry is identical
        assertEquals(initialTable.partitions.size, updatedTable.partitions.size)
        assertEquals(p1.startLba, updatedTable.partitions[0].startLba)
        assertEquals(p1.sectorCount, updatedTable.partitions[0].sectorCount)
        assertEquals(p2.startLba, updatedTable.partitions[1].startLba)

        // 2. CRITICAL CONTRACT: partition 1 range (2048 until p2.startLba) was NEVER written
        assertFalse(
            "Partition 1 range must NEVER be written to during update!",
            disk.wasRangeWritten(p1.startLba.toLong(), p1.sectorCount.toLong())
        )

        // 3. Canary data must remain completely intact byte-for-byte
        val readBackBuf = ByteBuffer.allocate(canaryData.size)
        disk.read(p1.startLba.toLong(), readBackBuf)
        val readBack = readBackBuf.array()
        for (i in canaryData.indices) {
            assertEquals("Canary byte at $i corrupted!", canaryData[i], readBack[i])
        }
    }

    @Test
    fun windowsSetupRequiresOverwrite() {
        val volumeRoot = mutableMapOf<String, Any?>()
        val options = SetupOptions(tpmBypass = true, bypassRamCheck = true, localAccount = true)

        // Initial setup write succeeds
        val json1 = WindowsSetup.write(volumeRoot, "Windows11_Pro.iso", options, overwrite = false)
        assertTrue(json1.contains("Windows11_Pro.iso"))
        assertTrue(volumeRoot.containsKey("ventoy/ventoy.json"))
        assertTrue(volumeRoot.containsKey("ventoy/Windows11_Pro.xml"))

        // Second write with overwrite = false MUST throw VentoyOverwriteRequiredException
        try {
            WindowsSetup.write(volumeRoot, "Windows11_Pro.iso", options, overwrite = false)
            fail("Expected VentoyOverwriteRequiredException when overwrite is false")
        } catch (_: VentoyOverwriteRequiredException) {
            // Expected
        }

        // With overwrite = true, write succeeds
        val json2 = WindowsSetup.write(volumeRoot, "Windows11_Pro.iso", options, overwrite = true)
        assertTrue(json2.contains("Windows11_Pro.iso"))
    }

    @Test
    fun windowsSetupLinksIsoToXml() {
        val volumeRoot = mutableMapOf<String, Any?>()
        val options = SetupOptions(tpmBypass = true, bypassRamCheck = true, localAccount = true)

        val json = WindowsSetup.write(volumeRoot, "Win11_23H2.iso", options, overwrite = true)

        // Linkage verification
        assertTrue(json.contains("\"image\": \"/Win11_23H2.iso\""))
        assertTrue(json.contains("\"template\": \"/ventoy/Win11_23H2.xml\""))
        assertTrue(json.contains("\"VTOY_WIN11_BYPASS_TPM\": \"1\""))
        assertTrue(json.contains("\"VTOY_WIN11_BYPASS_RAM\": \"1\""))
        assertTrue(json.contains("\"VTOY_WIN11_BYPASS_LOCAL_ACCOUNT\": \"1\""))

        val xml = volumeRoot["ventoy/Win11_23H2.xml"] as String
        assertTrue(xml.contains("<unattend"))
        assertTrue(xml.contains("BypassTPMCheck"))
        assertTrue(xml.contains("BypassRAMCheck"))
        assertTrue(xml.contains("BypassNRO"))
    }
}
