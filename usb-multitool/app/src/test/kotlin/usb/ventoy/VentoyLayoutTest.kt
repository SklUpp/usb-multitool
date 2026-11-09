package usb.ventoy

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.security.MessageDigest
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.partition.TableScheme
import usb.testutil.RecordingDisk

class VentoyLayoutTest {



    private val fakeAssets = syntheticVentoyAssets(efiPartSectors = 2048)

    /** Layout only: require native-format failure; never synthesize an install return. */
    private fun installLayoutExpectingMissingNativeFormat(
        disk: RecordingDisk,
        assets: VentoyAssets = fakeAssets,
        gpt: Boolean = true,
        align4k: Boolean = true,
    ): VentoyProbeResult {
        try {
            VentoyInstaller.install(disk, assets, gpt = gpt, align4k = align4k)
            fail("host JVM must not fake successful native formatting")
        } catch (e: VentoyMutationFailedException) {
            assertTrue("expected missing FatFs after layout write", e.cause is usb.EngineMissingException)
        }
        return VentoyLayout.probe(disk)
            ?: throw AssertionError("Ventoy layout absent after expected native-format failure")
    }

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
    fun installCreatesMbrLayoutBeforeNativeFormatFailure() {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        val probe = installLayoutExpectingMissingNativeFormat(disk, gpt = false, align4k = true)
        assertEquals(TableScheme.MBR, probe.scheme)
        assertEquals(2048UL, probe.part1StartLba)
        assertEquals(probe.part1StartLba + probe.part1SectorCount, probe.part2StartLba)
        assertEquals(VentoyLayout.EFI_PART_SECTORS.toULong(), probe.part2SectorCount)
        assertEquals(0UL, probe.part2StartLba % 8UL)
        assertTrue(VentoyLayout.isVentoyDisk(disk))
    }

    @Test
    fun installCreatesGptLayoutBeforeNativeFormatFailure() {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        val probe = installLayoutExpectingMissingNativeFormat(disk, gpt = true, align4k = true)
        assertEquals(TableScheme.GPT, probe.scheme)
        assertEquals(2048UL, probe.part1StartLba)
        assertEquals(probe.part1StartLba + probe.part1SectorCount, probe.part2StartLba)
        assertEquals(VentoyLayout.EFI_PART_SECTORS.toULong(), probe.part2SectorCount)
        assertTrue(VentoyLayout.isVentoyDisk(disk))
        val p2End = probe.part2StartLba + probe.part2SectorCount - 1UL
        assertTrue(p2End < disk.sectorCount - 34UL)
    }

    @Test
    fun installRefusesMbrAbove2Tb() {
        try {
            VentoyInstaller.computeInstallGeometry(0x100000000UL, gpt = false)
            fail("Expected IllegalArgumentException when installing MBR on disk > 2TB")
        } catch (_: IllegalArgumentException) {
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
        val initialProbe = installLayoutExpectingMissingNativeFormat(disk, gpt = true)
        val p1Start = initialProbe.part1StartLba
        val p1Count = initialProbe.part1SectorCount
        val p2Start = initialProbe.part2StartLba
        val canaryData = ByteArray(10 * 512) { i -> (0x7E xor (i and 0xFF)).toByte() }
        disk.write(p1Start.toLong(), ByteBuffer.wrap(canaryData))
        disk.resetStats()
        val updatedAssets = syntheticVentoyAssets(release = "1.1.18", efiPartSectors = 2048)
        val updatedTable = VentoyInstaller.update(disk, updatedAssets)
        assertEquals(2, updatedTable.partitions.size)
        assertEquals(p1Start, updatedTable.partitions[0].startLba)
        assertEquals(p1Count, updatedTable.partitions[0].sectorCount)
        assertEquals(p2Start, updatedTable.partitions[1].startLba)
        assertFalse(
            "Partition 1 range must NEVER be written to during update!",
            disk.wasRangeWritten(p1Start.toLong(), p1Count.toLong()),
        )
        val readBack = ByteBuffer.allocate(canaryData.size)
        disk.read(p1Start.toLong(), readBack)
        assertArrayEquals(canaryData, readBack.array())
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
        assertTrue(json.contains("\"image\": \"/ISO/Win11_23H2.iso\""))
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

    @Test
    fun updateRefusesToTruncateAnUndersizedVtoyefi() {
        val disk = syntheticStick(part1Sectors = 8192, part2Sectors = 2048)
        val probe = VentoyLayout.probe(disk)
        assertNotNull("synthetic stick must probe as Ventoy", probe)
        assertEquals(2048UL, probe!!.part2SectorCount)

        val bytesBefore = disk.bytes.toSortedMap().mapValues { it.value.toList() }
        disk.resetStats()

        // 65536-sector release image into a 2048-sector partition.
        try {
            VentoyInstaller.update(disk, syntheticVentoyAssets(efiPartSectors = 65536))
            fail("Expected VentoyPayloadTooLargeException instead of a silent truncation")
        } catch (e: VentoyPayloadTooLargeException) {
            assertTrue("message must name the needed sectors: ${e.message}", e.message!!.contains("65536"))
            assertTrue("message must name the available sectors: ${e.message}", e.message!!.contains("2048"))
        }

        assertEquals("update must not write when the payload cannot fit", 0, disk.writeCount)
        assertEquals(bytesBefore, disk.bytes.toSortedMap().mapValues { it.value.toList() })
    }

    @Test
    fun installRefusesToTruncateAnOversizedVtoyefi() {
        try {
            VentoyInstaller.preflightInstallPayloads(
                gpt = true,
                decompressedCore = ByteArray(2048),
                decompressedEfi = ByteArray(65537 * 512),
            )
            fail("Expected VentoyPayloadTooLargeException instead of a silent truncation")
        } catch (e: VentoyPayloadTooLargeException) {
            assertTrue("message must name needed sectors: ${e.message}", e.message!!.contains("65537"))
            assertTrue("message must name available sectors: ${e.message}", e.message!!.contains("65536"))
        }
    }

    @Test
    fun windowsSetupEscapesXmlFullName() {
        val volumeRoot = mutableMapOf<String, Any?>()
        WindowsSetup.write(volumeRoot, "Win & Test <1>.iso", SetupOptions(), overwrite = true)
        val xml = volumeRoot["ventoy/Win & Test <1>.xml"] as String
        assertTrue(xml.contains("<FullName>Win &amp; Test &lt;1&gt;</FullName>"))
    }

    private fun syntheticStick(part1Sectors: Long, part2Sectors: Long): RecordingDisk {
        val disk = RecordingDisk(sectorSize = 512, sectorCount = 262144UL)
        val mbr = ByteArray(512)
        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        // Partition 0: Ventoy data volume starting at LBA 2048.
        mbr[446] = 0x00
        mbr[446 + 4] = 0x07
        putLe32(mbr, 446 + 8, 2048)
        putLe32(mbr, 446 + 12, part1Sectors.toInt())
        // Partition 1: VTOYEFI immediately after partition 0.
        mbr[462] = 0x00
        mbr[462 + 4] = 0xEF.toByte()
        putLe32(mbr, 462 + 8, (2048 + part1Sectors).toInt())
        putLe32(mbr, 462 + 12, part2Sectors.toInt())
        disk.write(0L, ByteBuffer.wrap(mbr))
        return disk
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
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
        val probe = installLayoutExpectingMissingNativeFormat(disk, assets, gpt = true)

        // Read core.img at LBA 34
        val coreBuf = ByteBuffer.allocate(2048)
        disk.read(34L, coreBuf)
        val writtenCore = coreBuf.array()

        // Must NOT be XZ compressed on disk
        assertFalse("Core image on disk must not be XZ compressed", VentoyAssets.isXz(writtenCore))
        assertEquals(0xCC.toByte(), writtenCore[0])
        assertEquals("Byte 500 must be patched to 35 for GPT", 35.toByte(), writtenCore[500])

        // Read EFI partition at part2Start
        val efiBuf = ByteBuffer.allocate(512)
        disk.read(probe.part2StartLba.toLong(), efiBuf)
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
    fun probeRejectsGptWhenP1LastBeforeP1Start() {
        val disk = RecordingDisk(sectorCount = 2_097_152UL, sectorSize = 512)
        val mbr = ByteArray(512)
        mbr[450] = 0xEE.toByte()
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        disk.write(0UL, java.nio.ByteBuffer.wrap(mbr))
        val hdr = ByteArray(512)
        "EFI PART".toByteArray().copyInto(hdr, 0)
        disk.write(1UL, java.nio.ByteBuffer.wrap(hdr))
        val tbl = ByteArray(32 * 512)
        java.nio.ByteBuffer.wrap(tbl, 32, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(2048)
        java.nio.ByteBuffer.wrap(tbl, 40, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(100)
        disk.write(2UL, java.nio.ByteBuffer.wrap(tbl))
        assertNull(VentoyLayout.probe(disk))
    }

    @Test
    fun mergeVentoyJsonRefusesCorruptExisting() {
        try {
            WindowsSetup.mergeVentoyJson(
                existingJson = "{not-json",
                isoName = "a.iso",
                xmlName = "a.xml",
                options = SetupOptions(),
            )
            fail("expected refuse")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("refusing"))
        }
    }

    @Test
    fun miniJsonRefusesTrailingJunkAsObject() {
        try {
            MiniJson.parse("{not-json")
            fail("expected parse failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message != null)
        }
    }

    @Test
    fun gptHeaderCrcHelperRejectsZeroedCrcFieldMismatch() {
        val hdr = ByteArray(92)
        "EFI PART".toByteArray().copyInto(hdr, 0)
        java.nio.ByteBuffer.wrap(hdr, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(92)
        java.nio.ByteBuffer.wrap(hdr, 80, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(2)
        java.nio.ByteBuffer.wrap(hdr, 84, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(128)
        val entries = ByteArray(256)
        assertFalse(VentoyLayout.gptHeaderAndEntriesCrcOk(hdr, entries))
    }

    @Test
    fun probeAcceptsValidCrcGptLayoutAfterNativeFormatFailure() {
        val disk = RecordingDisk(sectorCount = 2_097_152UL, sectorSize = 512)
        val probe = installLayoutExpectingMissingNativeFormat(disk, gpt = true)
        assertEquals(TableScheme.GPT, probe.scheme)
    }

    @Test
    fun gptPreflightAcceptsUpstream2047SectorCore() {
        VentoyInstaller.preflightInstallPayloads(
            gpt = true,
            decompressedCore = ByteArray(2047 * 512),
            decompressedEfi = ByteArray(2048 * 512),
        )
    }

    @Test
    fun installRejectsOversizedCoreBeforeDiskWrites() {
        val disk = RecordingDisk(sectorCount = 2_097_152UL, sectorSize = 512)
        val assets = syntheticVentoyAssets(
            efiPartSectors = 2048,
            coreBytes = ByteArray(2047 * 512 + 1) { 0x33 },
        )
        try {
            VentoyInstaller.install(disk, assets, gpt = true)
            fail("expected oversized core preflight failure")
        } catch (_: VentoyPayloadTooLargeException) {
        }
        assertEquals("oversized core must fail before disk writes", 0, disk.writeCount)
    }

    @Test
    fun installGeometryAlignAndReserveKeepGptMinimumPart1() {
        val base = 2048L + VentoyLayout.EFI_PART_SECTORS + 35L
        for (align in listOf(false, true)) {
            val alignmentSlack = if (align) 7L else 0L
            val minSectors = base + 2047L + alignmentSlack
            val exact = VentoyInstaller.computeInstallGeometry(minSectors.toULong(), gpt = true, align4k = align)
            if (align) assertTrue("aligned minimum keeps at least 2048 data sectors", exact.part1Count >= 2048L)
            else assertEquals("exact minimum keeps 2048 data sectors", 2048L, exact.part1Count)
            val withReserve = VentoyInstaller.computeInstallGeometry(
                (minSectors + 3L).toULong(), gpt = true, reserveBytes = 3L * 512L, align4k = align,
            )
            assertTrue("reserve and alignment preserve minimum", withReserve.part1Count >= 2048L)
            if (align) assertEquals(0L, withReserve.part2Start % 8L)
            try {
                VentoyInstaller.computeInstallGeometry(
                    (minSectors - 1L).toULong(), gpt = true, align4k = align,
                )
                fail("disk below exact minimum must fail")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            VentoyInstaller.computeInstallGeometry(base.plus(2047L).toULong(), gpt = true, reserveBytes = 512L)
            fail("reserve leaving undersized GPT part1 must fail preflight")
        } catch (_: IllegalArgumentException) {
        }
     }

    @Test
    fun gptHeaderCrcUsesDeclaredNinetyTwoByteRange() {
        val hdr = ByteArray(92)
        "EFI PART".toByteArray().copyInto(hdr)
        val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(8, 0x00010000)
        b.putInt(12, 92)
        b.putLong(24, 1L)
        b.putLong(32, 4095L)
        b.putLong(40, 34L)
        b.putLong(48, 4000L)
        b.putLong(72, 2L)
        b.putInt(80, 128)
        b.putInt(84, 128)
        val entries = ByteArray(128 * 128)
        val entriesCrc = java.util.zip.CRC32().apply { update(entries) }.value.toInt()
        b.putInt(88, entriesCrc)
        val copy = hdr.copyOf()
        copy.fill(0, 16, 20)
        val headerCrc = java.util.zip.CRC32().apply { update(copy) }.value.toInt()
        b.putInt(16, headerCrc)
        assertTrue(VentoyLayout.gptHeaderAndEntriesCrcOk(hdr, entries, 4096UL))
        assertFalse("truncated entry bytes rejected", VentoyLayout.gptHeaderAndEntriesCrcOk(hdr, entries.copyOf(entries.size - 1), 4096UL))
    }

    @Test
    fun miniJsonRejectsTrailingJunkAfterValidValue() {
        try {
            MiniJson.parse("{} trailing")
            fail("expected trailing junk rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun updateRefusesOversizedCoreBeforeAnyWrite() {
        val disk = RecordingDisk(sectorCount = 2_097_152UL, sectorSize = 512)
        installLayoutExpectingMissingNativeFormat(disk, gpt = true)
        disk.resetStats()
        val hugeCore = ByteArray(2047 * 512 + 1) { 0x5A }
        val assets = syntheticVentoyAssets(efiPartSectors = 2048, coreBytes = hugeCore)
        try {
            VentoyInstaller.update(disk, assets)
            fail("expected VentoyPayloadTooLargeException")
        } catch (_: VentoyPayloadTooLargeException) {
        }
        assertEquals("oversized core update must not mutate disk", 0, disk.writeCount)
    }

    @Test
    fun installReachesRealExfatFormatterAfterLayout() {
        val disk = RecordingDisk(sectorCount = 2_097_152UL, sectorSize = 512)
        try {
            VentoyInstaller.install(disk, fakeAssets, gpt = true)
            fail("host JVM lacks FatFs JNI; formatting must not fake success")
        } catch (e: VentoyMutationFailedException) {
            assertTrue(e.cause is usb.EngineMissingException)
            assertNotNull("layout must be present before native formatter failure", VentoyLayout.probe(disk))
        }
    }

    @Test
    fun mergeVentoyJsonPreservesUnsetWin11Flags() {
        val existing = """
        {
          "control": [
            {"VTOY_WIN11_BYPASS_TPM": "1"},
            {"VTOY_WIN11_BYPASS_RAM": "1"},
            {"VTOY_DEFAULT_SEARCH_ROOT": "/ISO"}
          ],
          "auto_install": []
        }
        """.trimIndent()
        val out = WindowsSetup.mergeVentoyJson(
            existingJson = existing,
            isoName = "Win.iso",
            xmlName = "Win.xml",
            options = SetupOptions(tpmBypass = true),
        )
        assertTrue("RAM bypass key must remain", out.contains("VTOY_WIN11_BYPASS_RAM"))
        assertFalse(
            "unset RAM flag must not be forced to 0",
            out.contains("\"VTOY_WIN11_BYPASS_RAM\": \"0\"") ||
                out.contains("\"VTOY_WIN11_BYPASS_RAM\":\"0\""),
        )
        assertTrue(
            "RAM bypass must stay 1",
            out.contains("\"VTOY_WIN11_BYPASS_RAM\": \"1\"") ||
                out.contains("\"VTOY_WIN11_BYPASS_RAM\":\"1\""),
        )
    }

    @Test
    fun windowsSetupRefusesOverwriteWithoutPermission() {
        val root = linkedMapOf<String, Any?>(
            "ventoy/ventoy.json" to """{"auto_install":[{"image":"/ISO/a.iso","template":"/ventoy/a.xml"}]}""",
            "ventoy/a.xml" to "<xml/>",
        )
        try {
            WindowsSetup.write(root, "a.iso", SetupOptions(), overwrite = false)
            fail("expected VentoyOverwriteRequiredException")
        } catch (_: VentoyOverwriteRequiredException) {
        }
    }

    @Test
    fun updateMarksPartialAfterFirstWriteFault() {
        val disk = RecordingDisk(sectorCount = 2_097_152UL, sectorSize = 512)
        installLayoutExpectingMissingNativeFormat(disk, gpt = true)
        disk.resetStats()
        var blows = 0
        disk.writeHook = {
            blows++
            if (blows == 1) throw RuntimeException("injected write fault")
        }
        try {
            VentoyInstaller.update(disk, fakeAssets)
            fail("expected VentoyMutationFailedException")
        } catch (e: VentoyMutationFailedException) {
            assertTrue(e.message!!.contains("after the disk was modified"))
            assertTrue(disk.writeCount >= 1)
        }
    }

    @Test
    fun gptHeaderRejectsWrongHeaderSize() {
        val hdr = ByteArray(92)
        "EFI PART".toByteArray().copyInto(hdr, 0)
        java.nio.ByteBuffer.wrap(hdr, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(91)
        java.nio.ByteBuffer.wrap(hdr, 80, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(2)
        java.nio.ByteBuffer.wrap(hdr, 84, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(128)
        assertFalse(VentoyLayout.gptHeaderAndEntriesCrcOk(hdr, ByteArray(256)))
    }

    @Test
    fun miniJsonStringifyEscapesTabAndControlsAndPreservesLong() {
        val encoded = MiniJson.stringify(
            linkedMapOf(
                "path" to "a\tb\u0001c",
                "n" to 42L,
                "f" to 1.5,
            ),
        )
        assertTrue(encoded.contains("\\t"))
        assertTrue(encoded.contains("\\u0001"))
        assertTrue(encoded.contains("42"))
        assertFalse("raw tab must not appear", encoded.contains("\t"))
        val parsed = MiniJson.parse(encoded) as Map<*, *>
        assertEquals(42L, parsed["n"])
        assertEquals(1.5, parsed["f"])
        assertEquals("a\tb\u0001c", parsed["path"])
    }

    @Test
    fun installMarksPartialAfterFirstWriteFault() {
        val disk = RecordingDisk(sectorCount = 2_097_152UL, sectorSize = 512)
        var blows = 0
        disk.writeHook = {
            blows++
            if (blows == 1) throw RuntimeException("injected install write fault")
        }
        try {
            VentoyInstaller.install(disk, fakeAssets, gpt = true)
            fail("expected VentoyMutationFailedException")
        } catch (e: VentoyMutationFailedException) {
            assertTrue(e.message!!.contains("after the disk was modified"))
            assertTrue(disk.writeCount >= 1)
        }
    }

    @Test
    fun installCancelAfterMutationMarksPartial() {
        val disk = RecordingDisk(sectorCount = 2_097_152UL, sectorSize = 512)
        var active = true
        disk.writeHook = {
            if (disk.writeCount >= 1) active = false
        }
        try {
            VentoyInstaller.install(disk, fakeAssets, gpt = true, isActive = { active })
            fail("expected VentoyMutationFailedException")
        } catch (e: VentoyMutationFailedException) {
            assertTrue(e.message!!.contains("after the disk was modified"))
            assertTrue(
                "cause must be CancellationException",
                e.cause is java.util.concurrent.CancellationException,
            )
            assertTrue("cancel path must have mutated before cancel", disk.writeCount >= 1)
        } catch (e: java.util.concurrent.CancellationException) {
            fail("post-mutation cancel must be Partial via VentoyMutationFailedException, got bare cancel: $e")
        }
    }

    @Test
    fun installPreflightRejectsNegativeReserve() {
        try {
            VentoyInstaller.computeInstallGeometry(2_097_152UL, gpt = true, reserveBytes = -1L)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

}
