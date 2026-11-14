package usb.ventoy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.operation.DiskOperation
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
        assertTrue(volume.paths().none { it.contains(".bak-") || it.contains(".xk-") || it.contains("ventoy_backup") })
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
        assertTrue(volume.paths().none { it.contains(".bak-") || it.contains(".xk-") })
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

    @Test
    fun allocateXmlPathUsesImageStemThenSuffix() {
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/ventoy/Win11.xml" to byteArrayOf(1),
                    "/ventoy/Win11_1.xml" to byteArrayOf(2),
                ),
            )
        assertEquals(
            "/ventoy/Win11_2.xml",
            VentoySetup.allocateXmlPath(volume, "/ISO/Win11.iso"),
        )
        assertEquals("/ventoy/Fresh.xml", VentoySetup.allocateXmlPath(volume, "/ISO/Fresh.wim"))
        assertEquals("/ventoy/Win11.xml", VentoySetup.suggestedXmlPath("/ISO/Win11.iso"))
        volume.mkdir("/custom")
        volume.putFile("/custom/Win11.xml", byteArrayOf(3))
        assertEquals(
            "/custom/Win11_1.xml",
            VentoySetup.allocateXmlPath(volume, "/ISO/Win11.iso", "/custom"),
        )
    }

    @Test
    fun allocateXmlPathSkipsCaseInsensitiveCollision() {
        val volume = InMemoryUsbVolume(mapOf("/ventoy/win11.xml" to byteArrayOf(1)))
        assertEquals(
            "/ventoy/Win11_1.xml",
            VentoySetup.allocateXmlPath(volume, "/ISO/Win11.iso"),
        )
    }

    @Test
    fun createNewRawWriteLeavesExistingPeerUntouchedOnRace() {
        val occupant = byteArrayOf(9, 9, 9)
        val volume = InMemoryUsbVolume(mapOf("/ventoy/Win11.xml" to occupant))
        volume.occupyBeforeRenameTo = "/ventoy/Win11_1.xml"
        // Pretend allocate chose _1, then a peer created it before rename.
        val body =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
            """.trimIndent().toByteArray()
        assertThrows(VentoySetup.XmlNameRaceException::class.java) {
            VentoySetup.writeAutounattendRaw(
                volume = volume,
                xmlName = "Win11_1.xml",
                rawBytes = body,
                overwriteXml = false,
            )
        }
        assertArrayEquals(occupant, requireNotNull(volume.fileBytes("/ventoy/Win11.xml")))
        assertArrayEquals(byteArrayOf(0x72), requireNotNull(volume.fileBytes("/ventoy/Win11_1.xml")))
        // Short exclusive temps use `.xt-…`; destination race must leave none behind.
        assertTrue(volume.paths().none { it.contains(".xt-") || it.contains(".tmp-") })
    }

    @Test
    fun createNewRawWriteRefusesExistingWithoutOverwrite() {
        val previous =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
            """.trimIndent().toByteArray()
        val volume = InMemoryUsbVolume(mapOf("/ventoy/Win11.xml" to previous))
        val edited =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize"/>
            </unattend>
            """.trimIndent().toByteArray()
        assertThrows(VentoySetup.XmlOverwriteRequiredException::class.java) {
            VentoySetup.writeAutounattendRaw(
                volume = volume,
                xmlName = "Win11.xml",
                rawBytes = edited,
                overwriteXml = false,
            )
        }
        assertArrayEquals(previous, requireNotNull(volume.fileBytes("/ventoy/Win11.xml")))
    }

    @Test
    fun allocateXmlPathContinuesPast999WhenFree() {
        val files = HashMap<String, ByteArray>()
        files["/ventoy/Long.xml"] = byteArrayOf(0)
        for (n in 1..1000) {
            files["/ventoy/Long_$n.xml"] = byteArrayOf(n.toByte())
        }
        val volume = InMemoryUsbVolume(files)
        assertEquals(
            "/ventoy/Long_1001.xml",
            VentoySetup.allocateXmlPath(volume, "/ISO/Long.iso"),
        )
    }

    @Test
    fun allocateXmlPathNearLfnLimitUsesShortTempsOnWrite() {
        // 240-char stem → 244-char `.xml` name; still under 255.
        val stem = "W".repeat(240)
        val volume = InMemoryUsbVolume()
        val path = VentoySetup.allocateXmlPath(volume, "/ISO/$stem.iso")
        assertEquals("/ventoy/$stem.xml", path)
        assertTrue(path.substringAfterLast('/').length <= XmlPath.MAX_LFN_UNITS)
        val body =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
            """.trimIndent().toByteArray()
        VentoySetup.writeAutounattendRaw(
            volume = volume,
            xmlName = path,
            rawBytes = body,
            overwriteXml = false,
        )
        assertArrayEquals(body, requireNotNull(volume.fileBytes(path)))
        // Temp siblings must stay short — never `.$stem.tmp-…`.
        assertTrue(volume.paths().none { it.substringAfterLast('/').length > XmlPath.MAX_LFN_UNITS })
        assertTrue(volume.paths().none { it.contains(".$stem") })
    }




    @Test
    fun exclusiveCreateConflictNeverDeletesPeerTemp() {
        val volume = InMemoryUsbVolume()
        volume.occupyBeforeExclusiveCreate = true
        volume.peerExclusiveCreateBytes = byteArrayOf(0x50, 0x45, 0x45, 0x52)
        val body =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
            """.trimIndent().toByteArray()
        assertThrows(VentoySetup.XmlNameRaceException::class.java) {
            VentoySetup.writeAutounattendRaw(
                volume = volume,
                xmlName = "Fresh.xml",
                rawBytes = body,
                overwriteXml = false,
            )
        }
        val peerPath = requireNotNull(volume.lastExclusiveCreatePath)
        assertArrayEquals(
            volume.peerExclusiveCreateBytes,
            requireNotNull(volume.fileBytes(peerPath)),
        )
        assertTrue(volume.statOrNull("/ventoy/Fresh.xml") == null)
    }

    @Test
    fun longXmlBackupFallsBackToShortSibling() {
        val stem = "W".repeat(240)
        val path = "/ventoy/$stem.xml"
        val previous = byteArrayOf(1, 2, 3)
        val volume = InMemoryUsbVolume(mapOf(path to previous))
        val backup = VentoySetup.backupBeforeReplace(volume, path, previous)
        assertTrue(
            "near-LFN stem must use short .xk- backup, got $backup",
            backup.substringAfterLast('/').startsWith(".xk-"),
        )
        assertTrue(backup.substringAfterLast('/').length <= XmlPath.MAX_LFN_UNITS)
        assertArrayEquals(previous, requireNotNull(volume.fileBytes(backup)))
    }

    @Test
    fun generateCreateNewUsesAllocatedImageNameAndPlan() {
        // Selected image → allocate → generate create-new with resealed plan.
        val selectedImage = "/ISO/Win11_23H2_English_x64.iso"
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    selectedImage to byteArrayOf(1),
                    "/ventoy/Win11_23H2_English_x64.xml" to
                        """
                        <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
                        """.trimIndent().toByteArray(),
                ),
            )
        val allocated = VentoySetup.allocateXmlPath(volume, selectedImage)
        assertEquals("/ventoy/Win11_23H2_English_x64_1.xml", allocated)
        val params = AutounattendParams(computerName = "PC-ONE")
        // UI seals plan to suggested name; host reseals after allocate.
        val uiSuggested = VentoySetup.suggestedXmlPath(selectedImage)
        assertEquals("/ventoy/Win11_23H2_English_x64.xml", uiSuggested)
        val confirmedUiPlan = autounattendPlanSha256(uiSuggested, params, listOf(selectedImage))
        val writePlan = autounattendPlanSha256(allocated, params, listOf(selectedImage))
        assertNotEquals(confirmedUiPlan, writePlan)
        var mutated = false
        val result =
            VentoySetup.generateAutounattend(
                volume = volume,
                xmlName = allocated,
                params = params,
                imagePaths = listOf(selectedImage),
                overwriteXml = false,
                expectedPlanSha256 = writePlan,
                onMutation = { mutated = true },
            )
        assertTrue(mutated)
        assertEquals("Win11_23H2_English_x64_1.xml", result.xmlName)
        assertTrue(volume.fileBytes(allocated) != null)
        assertTrue(volume.paths().none { it.contains(".xt-") })
        assertArrayEquals(
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
            """.trimIndent().toByteArray(),
            requireNotNull(volume.fileBytes(uiSuggested)),
        )
        // Stale pre-allocate UI plan must not write over the new file.
        assertThrows(VentoySetup.PlanChangedException::class.java) {
            VentoySetup.generateAutounattend(
                volume = volume,
                xmlName = allocated,
                params = params,
                imagePaths = listOf(selectedImage),
                overwriteXml = true,
                expectedTemplateSha256 =
                    WindowsTemplate.sha256(requireNotNull(volume.fileBytes(allocated))),
                expectedPlanSha256 = confirmedUiPlan,
                onMutation = {},
            )
        }
    }

    @Test
    fun generateRejectsStalePlanHashBeforeMutation() {
        val volume = InMemoryUsbVolume(mapOf("/ISO/Win11.iso" to byteArrayOf(1)))
        val params = AutounattendParams(computerName = "PC-ONE")
        var mutated = false
        assertThrows(VentoySetup.PlanChangedException::class.java) {
            VentoySetup.generateAutounattend(
                volume = volume,
                xmlName = "/ventoy/Win11.xml",
                params = params,
                imagePaths = listOf("/ISO/Win11.iso"),
                overwriteXml = false,
                expectedPlanSha256 = "deadbeef",
                onMutation = { mutated = true },
            )
        }
        assertFalse(mutated)
        assertTrue(volume.statOrNull("/ventoy/Win11.xml") == null)
        assertTrue(volume.paths().none { it.contains(".xt-") || it.contains(".bak-") })
    }

    @Test
    fun importXmlUsesShortTempsAndSelectedImageStem() {
        // Mimic MainActivity inspect: allocate from selected image, then import under that name.
        val selectedImage = "/ISO/Win11.iso"
        val volume =
            InMemoryUsbVolume(
                mapOf(
                    "/ventoy/Win11.xml" to
                        """
                        <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
                        """.trimIndent().toByteArray(),
                ),
            )
        val allocated = VentoySetup.allocateXmlPath(volume, selectedImage)
        assertEquals("/ventoy/Win11_1.xml", allocated)
        val xml =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize"/>
            </unattend>
            """.trimIndent().toByteArray()
        val operation = DiskOperation("import-xml")
        VentoyIsoLibrary.import(
            volume = volume,
            input = xml.inputStream(),
            rawName = allocated.substringAfterLast('/'),
            operation = operation,
            importDirectory = "/ventoy",
            overwrite = false,
        )
        assertArrayEquals(xml, requireNotNull(volume.fileBytes(allocated)))
        assertTrue(operation.mutationAttempted)
        assertTrue(operation.commitReached)
        assertTrue(volume.paths().none { it.contains(".xi-") || it.contains(".xb-") })
        assertArrayEquals(
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"/>
            """.trimIndent().toByteArray(),
            requireNotNull(volume.fileBytes("/ventoy/Win11.xml")),
        )
    }



}
