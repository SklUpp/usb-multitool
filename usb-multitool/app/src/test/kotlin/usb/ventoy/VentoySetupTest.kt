package usb.ventoy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.testutil.InMemoryUsbVolume

/**
 * Pins the `/ventoy/ventoy.json` merge contract used by VentoySetup. FatFs is native,
 * so the raw volume I/O path cannot run on the JVM; the merge half is exercised here.
 */
class VentoySetupTest {
    @Test
    fun configPathIsAbsoluteAndKeyIsVolumeRelative() {
        assertEquals("ventoy/ventoy.json", VentoySetup.CONFIG_KEY)
        assertEquals("/" + VentoySetup.CONFIG_KEY, VentoySetup.CONFIG_PATH)
    }

    @Test
    fun xmlPathRejectsVentoyConfigCaseInsensitively() {
        assertThrows(IllegalArgumentException::class.java) {
            VentoySetup.xmlPath("ventoy.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VentoySetup.xmlPath("/ventoy/VENTOY.JSON")
        }
        assertEquals("/ventoy/Win.xml", VentoySetup.xmlPath("Win.xml"))
        assertEquals("/Win/foo.xml", VentoySetup.xmlPath("/Win/foo.xml"))
        assertEquals("/Win/foo.xml", VentoySetup.xmlPath("Win/foo.xml"))
    }

    @Test
    fun unknownSearchLevelIsNotTreatedAsUnlimited() {
        assertNull(
            VentoySetup.pathInSearchScope(
                "/dir/a.iso",
                listOf("/"),
                "VTOY_MAX_SEARCH_LEVEL=unknown",
            ),
        )
        assertEquals(
            false,
            VentoySetup.pathInSearchScope(
                "/dir/a.iso",
                listOf("/"),
                "VTOY_MAX_SEARCH_LEVEL=0",
            ),
        )
    }

    @Test
    fun linkingASecondIsoKeepsTheFirstLinkAndControlBlock() {
        val firstJson =
            WindowsSetup.mergeAutoInstall(
                null,
                listOf("Windows11_Pro.iso"),
                "/ventoy/Windows11_Pro.xml",
                SetupOptions(bypassChecks = true),
                overwrite = false,
            )
        val merged =
            WindowsSetup.mergeAutoInstall(
                firstJson,
                listOf("Ubuntu.iso"),
                "/ventoy/Ubuntu.xml",
                SetupOptions(),
                overwrite = false,
            )

        val root = MiniJson.parse(merged) as Map<*, *>
        val images = (root["auto_install"] as List<*>).map { (it as Map<*, *>)["image"] }
        assertEquals(listOf("/ISO/Windows11_Pro.iso", "/ISO/Ubuntu.iso"), images)

        val controlKeys = (root["control"] as List<*>)
            .flatMap { (it as Map<*, *>).keys }
            .map { it.toString() }
        assertFalse(
            "binding must not invent a search root: $controlKeys",
            controlKeys.contains("VTOY_DEFAULT_SEARCH_ROOT"),
        )
        assertTrue("control block lost: $controlKeys", controlKeys.contains("VTOY_WIN11_BYPASS_CHECK"))
    }

    @Test
    fun relinkingTheSameIsoStillRequiresOverwrite() {
        val firstJson =
            WindowsSetup.mergeAutoInstall(
                null,
                listOf("Win11.iso"),
                "/ventoy/Win11.xml",
                SetupOptions(),
                overwrite = false,
            )
        try {
            WindowsSetup.mergeAutoInstall(
                firstJson,
                listOf("Win11.iso"),
                "/ventoy/Win11.xml",
                SetupOptions(),
                overwrite = false,
            )
            fail("expected VentoyOverwriteRequiredException")
        } catch (_: VentoyOverwriteRequiredException) {
        }
    }

    @Test
    fun listingReturnsOnlyDirectChildDirectories() {
        val volume = InMemoryUsbVolume()
        volume.mkdir("/ventoy")
        volume.mkdir("/ISO/Windows")
        volume.putFile("/readme.txt", "x".toByteArray())

        assertEquals(listOf("/ISO", "/ventoy"), VentoySetup.listSubdirectories(volume, "/"))
        assertEquals(listOf("/ISO/Windows"), VentoySetup.listSubdirectories(volume, "/ISO"))
    }

    @Test
    fun listingHasNoEntryOrDepthCapBeyond512() {
        val volume = InMemoryUsbVolume()
        // The fixture always carries /ISO, like a real Ventoy data partition.
        val expected = listOf("/ISO") + (1..600).map { "/D$it" }
        expected.forEach { path ->
            volume.mkdir(path)
            // Nested folders must not leak into the parent's answer.
            volume.mkdir("$path/nested")
        }

        val listed = VentoySetup.listSubdirectories(volume, "/")

        assertEquals(expected.size, listed.size)
        assertEquals(expected.toSet(), listed.toSet())
    }

    @Test
    fun missingOrNonDirectoryPathIsAnErrorNotEmptyList() {
        val volume = InMemoryUsbVolume()
        val missing =
            assertThrows(VentoySetup.FolderUnavailableException::class.java) {
                VentoySetup.listSubdirectories(volume, "/gone")
            }
        assertEquals(FolderReadProblem.Missing, missing.problem)

        volume.putFile("/file.txt", byteArrayOf())
        val notDirectory =
            assertThrows(VentoySetup.FolderUnavailableException::class.java) {
                VentoySetup.listSubdirectories(volume, "/file.txt")
            }
        assertEquals(FolderReadProblem.NotDirectory, notDirectory.problem)
    }

    @Test
    fun xmlBackupKeepsExactPreviousBytesBeforeReplacement() {
        val volume = InMemoryUsbVolume(
            mapOf("/ventoy/autounattend.xml" to byteArrayOf(0, 1, 2, 3)),
        )
        val previous = requireNotNull(volume.fileBytes("/ventoy/autounattend.xml"))

        VentoySetup.backupBeforeReplace(volume, "/ventoy/autounattend.xml", previous)
        volume.putFile("/ventoy/autounattend.xml", byteArrayOf(9, 8, 7))

        val backup = volume.paths().single { it.startsWith("/ventoy/autounattend.xml.bak-") }
        assertTrue(previous.contentEquals(requireNotNull(volume.fileBytes(backup))))
        assertTrue(
            byteArrayOf(9, 8, 7).contentEquals(
                requireNotNull(volume.fileBytes("/ventoy/autounattend.xml")),
            ),
        )
    }

    @Test
    fun jsonBackupUsesVentoyNameAndNeverOverwritesExistingCopy() {
        val existingBackup = byteArrayOf(7, 7)
        val previous = byteArrayOf(1, 2, 3)
        val volume = InMemoryUsbVolume(
            mapOf(
                "/ventoy/ventoy.json" to previous,
                VentoySetup.CONFIG_BACKUP_PATH to existingBackup,
            ),
        )

        VentoySetup.backupBeforeReplace(volume, VentoySetup.CONFIG_PATH, previous)

        assertTrue(existingBackup.contentEquals(requireNotNull(volume.fileBytes(VentoySetup.CONFIG_BACKUP_PATH))))
        val fallback = volume.paths().single { it.startsWith("${VentoySetup.CONFIG_BACKUP_PATH}.bak-") }
        assertTrue(previous.contentEquals(requireNotNull(volume.fileBytes(fallback))))
    }

    @Test
    fun jsonByteReaderKeepsReadTextIntLimitNotXmlLimit() {
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/ventoy/ventoy.json" to ByteArray(2 * 1024 * 1024 + 1) { '{'.code.toByte() },
                ),
            )

        val bytes = VentoySetup.readTextBytes(volume, VentoySetup.CONFIG_PATH)

        assertEquals(2 * 1024 * 1024 + 1, requireNotNull(bytes).size)
    }

    @Test
    fun emptyImagePathsBacksUpXmlButDoesNotCreateConfigBackup() {
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/ventoy/autounattend.xml" to byteArrayOf(1, 2),
                    VentoySetup.CONFIG_PATH to "{}".toByteArray(),
                ),
            )

        VentoySetup.backupGenerationFiles(
            volume = volume,
            xmlPath = "/ventoy/autounattend.xml",
            existingXml = requireNotNull(volume.fileBytes("/ventoy/autounattend.xml")),
            imagePaths = emptyList(),
            existingJsonBytes = null,
        )

        assertEquals(1, volume.paths().count { it.startsWith("/ventoy/autounattend.xml.bak-") })
        assertTrue(volume.paths().none { it.contains("ventoy_backup") })
        assertTrue("{}".toByteArray().contentEquals(requireNotNull(volume.fileBytes(VentoySetup.CONFIG_PATH))))
    }

    @Test
    fun backupFailureBeforeTargetReplacementLeavesTargetsUntouched() {
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/ventoy/autounattend.xml" to byteArrayOf(1, 2),
                    VentoySetup.CONFIG_PATH to byteArrayOf(3, 4),
                ),
            )
        volume.failAnyWrite = true

        assertThrows(java.io.IOException::class.java) {
            VentoySetup.backupGenerationFiles(
                volume = volume,
                xmlPath = "/ventoy/autounattend.xml",
                existingXml = requireNotNull(volume.fileBytes("/ventoy/autounattend.xml")),
                imagePaths = listOf("/ISO/windows.iso"),
                existingJsonBytes = requireNotNull(volume.fileBytes(VentoySetup.CONFIG_PATH)),
            )
        }

        assertTrue(byteArrayOf(1, 2).contentEquals(requireNotNull(volume.fileBytes("/ventoy/autounattend.xml"))))
        assertTrue(byteArrayOf(3, 4).contentEquals(requireNotNull(volume.fileBytes(VentoySetup.CONFIG_PATH))))
        assertTrue(volume.paths().none { it.contains(".bak-") || it.contains("ventoy_backup") })
    }

    @Test
    fun rawWriteKeepsExactBytesIncludingUnknownComment() {
        val previous =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <!-- keep-me -->
              <settings pass="specialize">
                <component name="Vendor-Extension"><VendorThing keep="yes"/></component>
              </settings>
            </unattend>
            """.trimIndent().toByteArray()
        val volume = InMemoryUsbVolume(mapOf("/ventoy/autounattend.xml" to previous))
        val edited =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <!-- keep-me -->
              <settings pass="specialize">
                <component name="Vendor-Extension"><VendorThing keep="yes"/></component>
                <component name="Microsoft-Windows-Shell-Setup">
                  <ComputerName>RAW-PC</ComputerName>
                </component>
              </settings>
            </unattend>
            """.trimIndent().toByteArray()

        VentoySetup.writeAutounattendRaw(
            volume = volume,
            xmlName = "autounattend.xml",
            rawBytes = edited,
            overwriteXml = true,
            allowDestructiveTemplate = false,
            expectedTemplateSha256 = WindowsTemplate.sha256(previous),
        )

        assertArrayEquals(edited, requireNotNull(volume.fileBytes("/ventoy/autounattend.xml")))
        assertEquals(1, volume.paths().count { it.startsWith("/ventoy/autounattend.xml.bak-") })
        assertArrayEquals(
            previous,
            requireNotNull(
                volume.fileBytes(
                    volume.paths().single { it.startsWith("/ventoy/autounattend.xml.bak-") },
                ),
            ),
        )
    }

    @Test
    fun rawWriteRefusesDoctypeBeforeMutation() {
        val previous =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
            """.trimIndent().toByteArray()
        val volume = InMemoryUsbVolume(mapOf("/ventoy/autounattend.xml" to previous))
        val hostile =
            """
            <?xml version="1.0"?>
            <!DOCTYPE unattend [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <unattend xmlns="urn:schemas-microsoft-com:unattend">&xxe;</unattend>
            """.trimIndent().toByteArray()

        assertThrows(InvalidTemplateException::class.java) {
            VentoySetup.writeAutounattendRaw(
                volume = volume,
                xmlName = "autounattend.xml",
                rawBytes = hostile,
                overwriteXml = true,
                expectedTemplateSha256 = WindowsTemplate.sha256(previous),
            )
        }
        assertArrayEquals(previous, requireNotNull(volume.fileBytes("/ventoy/autounattend.xml")))
        assertTrue(volume.paths().none { it.contains(".bak-") })
    }

    @Test
    fun rawWriteRefusesStaleSourceDigest() {
        val previous =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
            """.trimIndent().toByteArray()
        val volume = InMemoryUsbVolume(mapOf("/ventoy/autounattend.xml" to previous))
        val edited =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize"/>
            </unattend>
            """.trimIndent().toByteArray()

        assertThrows(VentoySetup.TemplateChangedException::class.java) {
            VentoySetup.writeAutounattendRaw(
                volume = volume,
                xmlName = "autounattend.xml",
                rawBytes = edited,
                overwriteXml = true,
                expectedTemplateSha256 = "deadbeef",
            )
        }
        assertArrayEquals(previous, requireNotNull(volume.fileBytes("/ventoy/autounattend.xml")))
    }

}
