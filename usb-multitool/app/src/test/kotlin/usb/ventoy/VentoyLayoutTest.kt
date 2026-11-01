package usb.ventoy

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.security.MessageDigest
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.partition.TableScheme
import usb.testutil.RecordingDisk

class VentoyLayoutTest {

    private val fakeAssets = syntheticVentoyAssets(efiPartSectors = 2048)

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
        val p2End = p2.startLba + p2.sectorCount - 1UL
        val backupGptStart = disk.sectorCount - 34UL
        assertTrue("Partition 2 end ($p2End) must be strictly less than backup GPT start ($backupGptStart)", p2End < backupGptStart)
    }

    @Test
    fun installRefusesMbrAbove2Tb() {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 0x100000000UL)
        try {
            VentoyInstaller.install(disk, fakeAssets, gpt = false)
            fail("Expected IllegalArgumentException when installing MBR on disk > 2TB")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun pinnedHashesRejectAssetsThatAreNotFromTheRelease() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ventoy-pinned-" + System.nanoTime())
        try {
            // Synthetic files without a local sha256.txt must be judged against the pinned table.
            writeSyntheticRelease(dir, withSha256File = false)
            try {
                VentoyAssets.loadFromDirectory(dir, strict = true)
                fail("Synthetic assets must not pass the pinned release digests")
            } catch (e: IllegalStateException) {
                assertTrue(e.message?.contains("boot.img SHA-256 mismatch") == true)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sha256FileOverridesThePinnedTableAndDetectsTampering() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ventoy-sha-" + System.nanoTime())
        try {
            writeSyntheticRelease(dir, withSha256File = true)
            val assets = VentoyAssets.loadFromDirectory(dir, strict = true)
            assertEquals(VentoyAssets.RELEASE_1_1_05, assets.release)
            assets.validateChecksums(strict = true)

            val tamperedBoot = java.io.File(dir, "boot.img").readBytes()
            tamperedBoot[10] = (tamperedBoot[10].toInt() xor 0xFF).toByte()
            java.io.File(dir, "boot.img").writeBytes(tamperedBoot)

            try {
                VentoyAssets.loadFromDirectory(dir, strict = true)
                fail("Expected IllegalStateException due to checksum mismatch on tampered boot.img")
            } catch (e: IllegalStateException) {
                assertTrue(e.message?.contains("boot.img SHA-256 mismatch") == true)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun externalStorageAndFallbackLoading() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ventoy-ext-" + System.nanoTime())
        try {
            writeSyntheticRelease(dir)
            val assets = VentoyDownloader.loadFromExternalStorage(dir, strict = true)
            assertEquals(VentoyAssets.RELEASE_1_1_05, assets.release)
            assets.validateChecksums(strict = true)

            val fallbackAssets = VentoyDownloader.loadWithFallback(externalDir = dir)
            assertEquals(VentoyAssets.RELEASE_1_1_05, fallbackAssets.release)

            try {
                VentoyDownloader.loadWithFallback(externalDir = null, assetManager = null)
                fail("Expected VentoyAssetsMissingException when no local release source exists")
            } catch (e: VentoyAssetsMissingException) {
                assertTrue(e.message?.contains("boot.img") == true)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun installHonoursCancellationBeforeWriting() {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        try {
            VentoyInstaller.install(disk, fakeAssets, isActive = { false })
            fail("Expected CancellationException")
        } catch (_: java.util.concurrent.CancellationException) {
            // Expected
        }
        assertEquals("Cancelled install must not touch the disk", 0, disk.writeCount)
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
        val updatedAssets = syntheticVentoyAssets(release = "1.1.18", efiPartSectors = 2048)
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
    fun linkingASecondIsoMergesWithoutOverwrite() {
        val volumeRoot = mutableMapOf<String, Any?>()
        WindowsSetup.write(volumeRoot, "Ubuntu.iso", SetupOptions(), overwrite = false)

        // A different ISO on a configured volume is a merge, not an overwrite.
        val json = WindowsSetup.write(volumeRoot, "Windows11.iso", SetupOptions(), overwrite = false)
        assertTrue(json.contains("/Ubuntu.iso"))
        assertTrue(json.contains("/Windows11.iso"))
        assertTrue(volumeRoot.containsKey("ventoy/Windows11.xml"))
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

    private fun compressXz(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val xzOut = XZOutputStream(baos, LZMA2Options())
        xzOut.write(data)
        xzOut.close()
        return baos.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testDecompressXzAssetsAndPatchByte500() {
        val boot = ByteArray(512) { 0x90.toByte() }
        boot[510] = 0x55.toByte()
        boot[511] = 0xAA.toByte()

        val rawCore = ByteArray(2048) { 0xCC.toByte() }
        rawCore[500] = 1

        val rawEfi = ByteArray(2048 * 512)
        rawEfi[0] = 0xEB.toByte()
        rawEfi[1] = 0x3C.toByte()
        rawEfi[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray().copyInto(rawEfi, 3)
        rawEfi[510] = 0x55.toByte()
        rawEfi[511] = 0xAA.toByte()

        val compressedCore = compressXz(rawCore)
        val compressedEfi = compressXz(rawEfi)

        assertTrue(VentoyAssets.isXz(compressedCore))
        assertTrue(VentoyAssets.isXz(compressedEfi))

        val assets = VentoyAssets(
            bootImg = boot,
            coreImg = compressedCore,
            efiPartImg = compressedEfi,
            bootImgSha256 = sha256Hex(boot),
            coreImgSha256 = sha256Hex(compressedCore),
            diskImgSha256 = sha256Hex(compressedEfi),
        )

        assets.validateChecksums(strict = true)

        val disk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        val table = VentoyInstaller.install(disk, assets, gpt = true)

        // Read core.img at LBA 34
        val coreBuf = ByteBuffer.allocate(2048)
        disk.read(34L, coreBuf)
        val writtenCore = coreBuf.array()

        // Must NOT be XZ compressed on disk
        assertFalse("Core image on disk must not be XZ compressed", VentoyAssets.isXz(writtenCore))
        assertEquals(0xCC.toByte(), writtenCore[0])
        assertEquals("Byte 500 must be patched to 35 for GPT", 35.toByte(), writtenCore[500])

        // Read EFI partition at part2Start
        val p2 = table.partitions[1]
        val efiBuf = ByteBuffer.allocate(512)
        disk.read(p2.startLba.toLong(), efiBuf)
        val writtenEfi = efiBuf.array()
        assertFalse("EFI partition on disk must not be XZ compressed", VentoyAssets.isXz(writtenEfi))
        assertEquals(0xEB.toByte(), writtenEfi[0])
        assertEquals(0x3C.toByte(), writtenEfi[1])
        assertEquals("MSDOS5.0", String(writtenEfi, 3, 8))
    }

    @Test
    fun testMbrProbeDisksGe1TiB() {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 2500000000UL)
        val mbrBuf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        val mbr = mbrBuf.array()
        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()

        val p0Start = 2048L
        val p0Count = 2200000000L // > 2^31 sectors (~1.12 TB), bit 31 set
        val p1Start = p0Start + p0Count // 2200002048L, bit 31 set
        val p1Count = 65536L

        // Partition 0
        mbr[446] = 0x80.toByte()
        mbr[450] = 0x07.toByte()
        ByteBuffer.wrap(mbr, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((p0Start and 0xFFFFFFFFL).toInt())
        ByteBuffer.wrap(mbr, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((p0Count and 0xFFFFFFFFL).toInt())

        // Partition 1
        mbr[462] = 0x00.toByte()
        mbr[462 + 4] = 0xEF.toByte()
        ByteBuffer.wrap(mbr, 462 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((p1Start and 0xFFFFFFFFL).toInt())
        ByteBuffer.wrap(mbr, 462 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((p1Count and 0xFFFFFFFFL).toInt())

        disk.write(0L, ByteBuffer.wrap(mbr))

        // Write reserved data at 2040
        val rsv = ByteBuffer.allocate(8 * 512)
        disk.write(2040L, rsv)

        val probe = VentoyLayout.probe(disk)
        assertNotNull("Probe must succeed on disks >= 1 TiB without sign extension corruption", probe)
        assertTrue(probe!!.isVentoy)
        assertEquals(TableScheme.MBR, probe.scheme)
        assertEquals(p0Start.toULong(), probe.part1StartLba)
        assertEquals(p0Count.toULong(), probe.part1SectorCount)
        assertEquals(p1Start.toULong(), probe.part2StartLba)
        assertEquals(p1Count.toULong(), probe.part2SectorCount)
    }

    @Test
    fun testVentoyJsonMergePreservesThemeAndControl() {
        val volumeRoot = mutableMapOf<String, Any?>()
        val existingJson = """
        {
          "theme": {
            "file": "/ventoy/theme/custom.txt",
            "gfxmode": "1920x1080"
          },
          "control": [
            { "VTOY_DEFAULT_SEARCH_ROOT": "/MY_ISOS" },
            { "VTOY_SECONDARY_BOOT": "1" },
            { "VTOY_WIN11_BYPASS_TPM": "0" }
          ],
          "auto_install": [
            { "image": "/ubuntu-24.04.iso", "template": "/ventoy/ubuntu.xml" }
          ]
        }
        """.trimIndent()
        volumeRoot["ventoy/ventoy.json"] = existingJson

        val options = SetupOptions(tpmBypass = true, bypassRamCheck = true, localAccount = true)
        val resultJson = WindowsSetup.write(volumeRoot, "Windows11_Pro.iso", options, overwrite = true)

        assertTrue("Theme file must be preserved", resultJson.contains("/ventoy/theme/custom.txt"))
        assertTrue("Theme gfxmode must be preserved", resultJson.contains("1920x1080"))
        assertTrue("Custom search root must be preserved", resultJson.contains("/MY_ISOS"))
        assertTrue("Secondary boot control must be preserved", resultJson.contains("VTOY_SECONDARY_BOOT"))
        assertTrue("Existing Linux ISO must be preserved", resultJson.contains("ubuntu-24.04.iso"))
        assertTrue("New Windows ISO must be added", resultJson.contains("Windows11_Pro.iso"))
        assertTrue("VTOY_WIN11_BYPASS_TPM must be updated to 1", resultJson.contains("\"VTOY_WIN11_BYPASS_TPM\": \"1\""))
        assertTrue("VTOY_WIN11_BYPASS_RAM must be set to 1", resultJson.contains("\"VTOY_WIN11_BYPASS_RAM\": \"1\""))
        assertTrue("VTOY_WIN11_BYPASS_LOCAL_ACCOUNT must be set to 1", resultJson.contains("\"VTOY_WIN11_BYPASS_LOCAL_ACCOUNT\": \"1\""))
    }

    @Test
    fun testAutounattendBypassNroInOobeSystem() {
        val volumeRoot = mutableMapOf<String, Any?>()
        val options = SetupOptions(tpmBypass = true, bypassRamCheck = false, localAccount = true)
        WindowsSetup.write(volumeRoot, "Win11.iso", options, overwrite = true)

        val xml = volumeRoot["ventoy/Win11.xml"] as String
        assertTrue("Must declare oobeSystem settings pass", xml.contains("<settings pass=\"oobeSystem\">"))
        assertTrue("Must contain BypassNRO command", xml.contains("BypassNRO"))

        // Ensure BypassNRO is NOT inside windowsPE pass
        val peSection = xml.substringAfter("<settings pass=\"windowsPE\">").substringBefore("</settings>")
        assertFalse("BypassNRO must NOT be in windowsPE pass", peSection.contains("BypassNRO"))
        assertTrue("BypassTPMCheck must be in windowsPE pass", peSection.contains("BypassTPMCheck"))

        // Ensure BypassNRO IS inside oobeSystem pass
        val oobeSection = xml.substringAfter("<settings pass=\"oobeSystem\">").substringBefore("</settings>")
        assertTrue("BypassNRO must be in oobeSystem pass", oobeSection.contains("BypassNRO"))
    }

    @Test
    fun testVentoyNativeDeclarations() {
        val methods = VentoyNative::class.java.declaredMethods.map { it.name }
        assertTrue(methods.contains("nativeEngineLinked"))
        assertTrue(methods.contains("nativeProbe"))
        assertTrue(methods.contains("nativeInstall"))
        assertTrue(methods.contains("nativeUpdate"))
    }
}
