package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutounattendTest {
    @Test
    fun freshDraftWritesOnlyRequestedSafeBlocks() {
        val bytes =
            Autounattend.generate(
                baseXml = null,
                params = AutounattendParams(
                    languageTag = "en-US",
                    localeId = "en-US",
                    computerName = "LAB-PC",
                    localAccount = LocalAccount("runner", inAdministrators = true),
                ),
            )
        val xml = String(bytes, Charsets.UTF_8)
        assertTrue(xml.contains("UILanguage>en-US<"))
        assertTrue(xml.contains("UserLocale>en-US<"))
        assertTrue(xml.contains("ComputerName>LAB-PC<"))
        assertTrue(xml.contains("LocalAccount"))
        assertTrue(xml.contains("Administrators"))
        assertFalse(xml.contains("DiskConfiguration"))
        assertFalse(xml.contains("Password"))
    }

    @Test
    fun disabledBlocksAreAbsentNotEmpty() {
        val xml =
            String(
                Autounattend.generate(null, AutounattendParams(languageTag = "en-GB")),
                Charsets.UTF_8,
            )
        assertTrue(xml.contains("UILanguage>en-GB<"))
        assertFalse(xml.contains("ComputerName"))
        assertFalse(xml.contains("LocalAccount"))
        assertFalse(xml.contains("DiskConfiguration"))
    }

    @Test
    fun diskLayoutRequiresExplicitWriteLayoutAndContainsWipe() {
        val layout =
            DiskLayout(
                writeLayout = true,
                diskId = 0,
                wipe = true,
                partitions =
                    listOf(
                        LayoutPartition(100, DiskPartitionRole.Efi, "EFI"),
                        LayoutPartition(16, DiskPartitionRole.Msr),
                        LayoutPartition(50_000, DiskPartitionRole.Primary, "Windows"),
                    ),
            )
        val validation = Autounattend.validate(AutounattendParams(languageTag = "en-US", diskLayout = layout))
        assertTrue(validation.problems.isEmpty())
        assertFalse(validation.schemaChecked)
        assertNotNull(validation.unavailableReason)
        val xml = String(
            Autounattend.generate(null, AutounattendParams(languageTag = "en-US", diskLayout = layout)),
            Charsets.UTF_8,
        )
        assertTrue(xml.contains("WillWipeDisk>true<"))
        assertTrue(xml.contains("<DiskID>0</DiskID>"))
        assertTrue(xml.contains("CreatePartition"))
        assertTrue(xml.contains("InstallTo"))
        assertTrue(xml.contains("DiskID>0<"))
        assertTrue(xml.contains("PartitionID>3<"))
    }

    @Test
    fun layoutIsNotGeneratedWhenWriteLayoutIsFalse() {
        val layout = DiskLayout(writeLayout = false, diskId = 0, wipe = true)
        val xml = String(
            Autounattend.generate(null, AutounattendParams(languageTag = "en-US", diskLayout = layout)),
            Charsets.UTF_8,
        )
        assertFalse(xml.contains("DiskConfiguration"))
    }

    @Test
    fun layoutValidationIsDataNotExceptions() {
        val bad = AutounattendParams(
            languageTag = "not a tag",
            computerName = "this-name-is-way-too-long",
            localAccount = LocalAccount("bad/name"),
            diskLayout = DiskLayout(writeLayout = true, diskId = null),
        )
        val problems = Autounattend.validate(bad).problems
        assertTrue(AutounattendProblem.BadLanguageTag in problems)
        assertTrue(AutounattendProblem.BadComputerName in problems)
        assertTrue(AutounattendProblem.BadAccountName in problems)
        assertTrue(AutounattendProblem.LayoutIncomplete in problems)
    }

    @Test
    fun modifyPartitionOrderSkipsMsrButStaysSequential() {
        val layout =
            DiskLayout(
                writeLayout = true,
                diskId = 2,
                wipe = false,
                partitions =
                    listOf(
                        LayoutPartition(100, DiskPartitionRole.Efi, "EFI"),
                        LayoutPartition(16, DiskPartitionRole.Msr),
                        LayoutPartition(50_000, DiskPartitionRole.Primary, "Windows"),
                        LayoutPartition(1_000, DiskPartitionRole.Recovery, "Recovery"),
                    ),
            )
        val xml = String(
            Autounattend.generate(null, AutounattendParams(diskLayout = layout)),
            Charsets.UTF_8,
        )
        val modify =
            Regex("<ModifyPartition[^>]*>(.*?)</ModifyPartition>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(3, modify.size)
        assertTrue(modify[0].contains("<Order>1</Order>"))
        assertTrue(modify[0].contains("<PartitionID>1</PartitionID>"))
        assertTrue(modify[1].contains("<Order>2</Order>"))
        assertTrue(modify[1].contains("<PartitionID>3</PartitionID>"))
        assertTrue(modify[2].contains("<Order>3</Order>"))
        assertTrue(modify[2].contains("<PartitionID>4</PartitionID>"))
        assertTrue(modify[2].contains("de94bba4-06d1-4d40-a16a-bfd50179d6ac"))
    }

    @Test
    fun localAccountGoesToOobeSystemAndLocaleDoesNotRequireLanguage() {
        val xml = String(
            Autounattend.generate(
                null,
                AutounattendParams(
                    localeId = "de-DE",
                    localAccount = LocalAccount("runner", "pw", true),
                ),
            ),
            Charsets.UTF_8,
        )
        assertTrue(xml.contains("<UserLocale>de-DE</UserLocale>"))
        assertTrue(xml.contains("<SystemLocale>de-DE</SystemLocale>"))
        val oobe = xml.substringAfter("pass=\"oobeSystem\"")
        assertTrue(oobe.contains("<LocalAccount>"))
        assertFalse(oobe.contains("<ComputerName>"))
    }

    @Test
    fun readerKeepsDiskTargetAndWipeFlag() {
        val xml = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend"
                      xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">
              <settings pass="windowsPE">
                <component name="Microsoft-Windows-Setup">
                  <DiskConfiguration>
                    <Disk wcm:action="add">
                      <DiskID>3</DiskID>
                      <WillWipeDisk>true</WillWipeDisk>
                      <CreatePartitions>
                        <CreatePartition wcm:action="add"><Order>1</Order><Type>EFI</Type><Size>100</Size></CreatePartition>
                        <CreatePartition wcm:action="add"><Order>2</Order><Type>Primary</Type><Size>50000</Size></CreatePartition>
                      </CreatePartitions>
                      <ModifyPartitions>
                        <ModifyPartition wcm:action="add"><Order>1</Order><PartitionID>1</PartitionID><Format>FAT32</Format></ModifyPartition>
                        <ModifyPartition wcm:action="add"><Order>2</Order><PartitionID>2</PartitionID><Format>NTFS</Format></ModifyPartition>
                      </ModifyPartitions>
                    </Disk>
                  </DiskConfiguration>
                </component>
              </settings>
            </unattend>
        """.trimIndent()
        val state = Autounattend.read("/ventoy/a.xml", xml.toByteArray())
        val document = (state as AutounattendState.Parsed).document
        assertEquals(3L, document.existing?.diskLayout?.diskId ?: -1L)
        assertEquals(true, document.existing?.diskLayout?.wipe ?: false)
        assertEquals(
            listOf(DiskPartitionRole.Efi, DiskPartitionRole.Primary),
            document.existing?.diskLayout?.partitions?.map { it.role } ?: emptyList<DiskPartitionRole>(),
        )
    }

    @Test
    fun documentCarriesSha256OfInspectedBytes() {
        val bytes = "<unattend xmlns=\"urn:schemas-microsoft-com:unattend\"><settings pass=\"oobeSystem\"/></unattend>".toByteArray()
        val state = Autounattend.read("/ventoy/a.xml", bytes) as AutounattendState.Parsed
        assertEquals(WindowsTemplate.sha256(bytes), state.document.templateSha256)
    }

    @Test
    fun planDigestChangesWhenAnyConfirmedValueChanges() {
        val base = AutounattendParams(languageTag = "en-US")
        val first = autounattendPlanSha256("a.xml", base, listOf("/ISO/a.iso"))
        assertEquals(first, autounattendPlanSha256("a.xml", base, listOf("/ISO/a.iso")))
        assertNotEquals(first, autounattendPlanSha256("a.xml", base.copy(localeId = "en-GB"), listOf("/ISO/a.iso")))
        assertNotEquals(first, autounattendPlanSha256("b.xml", base, listOf("/ISO/a.iso")))
        assertNotEquals(first, autounattendPlanSha256("a.xml", base, listOf("/ISO/b.iso")))
    }

    @Test
    fun planDigestSeparatesNullableAndDelimitedValues() {
        val nullLabel = AutounattendParams(
            diskLayout = DiskLayout(
                partitions = listOf(LayoutPartition(100, DiskPartitionRole.Primary, null)),
            ),
        )
        val literalLabel = nullLabel.copy(
            diskLayout = DiskLayout(
                partitions = listOf(LayoutPartition(100, DiskPartitionRole.Primary, "null")),
            ),
        )
        assertNotEquals(autounattendPlanSha256("a\n.xml", nullLabel, listOf("/ISO/a\n.iso")), autounattendPlanSha256("a\n.xml", literalLabel, listOf("/ISO/a\n.iso")))
        assertNotEquals(autounattendPlanSha256("a.xml", nullLabel, listOf("/ISO/a.iso")), autounattendPlanSha256("a.xml", nullLabel, listOf("/ISO/a.iso", "/ISO/b.iso")))
    }

    @Test
    fun layoutKeepsInstallSelectionAndRejectsUnknownSiblings() {
        fun base(extra: String) = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE">
                <component name="Microsoft-Windows-Setup">
                  <ImageInstall>
                    <OSImage>
                      <InstallFrom><MetaData><Key>/IMAGE/INDEX</Key><Value>3</Value></MetaData></InstallFrom>
                      $extra
                    </OSImage>
                  </ImageInstall>
                </component>
              </settings>
            </unattend>
        """.trimIndent()

        val layout =
            DiskLayout(
                writeLayout = true,
                diskId = 1,
                partitions = listOf(LayoutPartition(50000, DiskPartitionRole.Primary, "Windows")),
            )
        val xml = String(
            Autounattend.generate(base("").toByteArray(), AutounattendParams(diskLayout = layout)),
            Charsets.UTF_8,
        )
        assertTrue(xml.contains("<InstallFrom>"))
        assertTrue(xml.contains("/IMAGE/INDEX"))
        assertTrue(xml.contains("<InstallTo>"))
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base("<UnsupportedOption/>").toByteArray(),
                AutounattendParams(diskLayout = layout),
            )
        }
    }

    @Test
    fun layoutKeepsValidDataImageAndRemovesAutoInstallTarget() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE">
                <component name="Microsoft-Windows-Setup">
                  <ImageInstall>
                    <OSImage>
                      <InstallFrom><MetaData><Key>/IMAGE/INDEX</Key><Value>1</Value></MetaData></InstallFrom>
                      <InstallToAvailablePartition>true</InstallToAvailablePartition>
                    </OSImage>
                    <DataImage><Path>D:\data.wim</Path><DoNotResize>true</DoNotResize></DataImage>
                  </ImageInstall>
                </component>
              </settings>
            </unattend>
        """.trimIndent()
        val layout =
            DiskLayout(
                writeLayout = true,
                diskId = 0,
                partitions = listOf(LayoutPartition(50000, DiskPartitionRole.Primary, "Windows")),
            )
        val xml = String(
            Autounattend.generate(base.toByteArray(), AutounattendParams(diskLayout = layout)),
            Charsets.UTF_8,
        )
        assertTrue(xml.contains("<DataImage>"))
        assertTrue(xml.contains("D:\\data.wim"))
        assertTrue(xml.contains("<InstallTo>"))
        assertFalse(xml.contains("InstallToAvailablePartition"))
    }

    @Test
    fun replacedLayoutSubtreeRejectsUnknownAttributesAndNamespaces() {
        fun base(attribute: String, namespace: String) = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE">
                <component name="Microsoft-Windows-Setup">
                  <DiskConfiguration>
                    <Disk>
                      <CreatePartitions>
                        <CreatePartition $attribute xmlns="$namespace">
                          <Order>1</Order><Type>Primary</Type><Size>100</Size>
                        </CreatePartition>
                      </CreatePartitions>
                    </Disk>
                  </DiskConfiguration>
                </component>
              </settings>
            </unattend>
        """.trimIndent()
        val layout =
            DiskLayout(
                writeLayout = true,
                diskId = 0,
                partitions = listOf(LayoutPartition(100, DiskPartitionRole.Primary)),
            )
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base("vendor=\"x\"", "urn:schemas-microsoft-com:unattend").toByteArray(),
                AutounattendParams(diskLayout = layout),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base("", "urn:vendor:extension").toByteArray(),
                AutounattendParams(diskLayout = layout),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base("", "").toByteArray(),
                AutounattendParams(diskLayout = layout),
            )
        }
    }

    @Test
    fun staleSpecializeAccountsFailClosed() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize">
                <component name="Microsoft-Windows-Shell-Setup">
                  <UserAccounts><LocalAccounts><LocalAccount><Name>old</Name></LocalAccount></LocalAccounts></UserAccounts>
                </component>
              </settings>
            </unattend>
        """.trimIndent()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base.toByteArray(),
                AutounattendParams(localAccount = LocalAccount("new")),
            )
        }
    }

    @Test
    fun readerUsesOrderNotDomIndexForRecoveryRoles() {
        val xml = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">
              <settings pass="windowsPE">
                <component name="Microsoft-Windows-Setup">
                  <DiskConfiguration>
                    <Disk>
                      <CreatePartitions>
                        <CreatePartition wcm:action="add"><Order>2</Order><Type>Primary</Type><Size>50000</Size></CreatePartition>
                        <CreatePartition wcm:action="add"><Order>1</Order><Type>EFI</Type><Size>100</Size></CreatePartition>
                      </CreatePartitions>
                      <ModifyPartitions>
                        <ModifyPartition wcm:action="add"><Order>1</Order><PartitionID>2</PartitionID><TypeID>de94bba4-06d1-4d40-a16a-bfd50179d6ac</TypeID></ModifyPartition>
                      </ModifyPartitions>
                    </Disk>
                  </DiskConfiguration>
                </component>
              </settings>
            </unattend>
        """.trimIndent()
        val document =
            (Autounattend.read("/ventoy/a.xml", xml.toByteArray()) as AutounattendState.Parsed).document
        assertEquals(
            listOf(DiskPartitionRole.Efi, DiskPartitionRole.Recovery),
            document.existing?.diskLayout?.partitions?.map { it.role } ?: emptyList<DiskPartitionRole>(),
        )
    }

    @Test
    fun layoutRefusesDataImageWithInstallTo() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE">
                <component name="Microsoft-Windows-Setup">
                  <ImageInstall>
                    <DataImage><Path>D:\data.wim</Path><InstallTo><DiskID>0</DiskID><PartitionID>9</PartitionID></InstallTo></DataImage>
                  </ImageInstall>
                </component>
              </settings>
            </unattend>
        """.trimIndent()
        val document =
            (Autounattend.read("/ventoy/a.xml", base.toByteArray()) as AutounattendState.Parsed)
                .document
        assertTrue(
            AutounattendProblem.LayoutImageInstallNotSupported in document.layoutProblems,
        )
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base.toByteArray(),
                AutounattendParams(
                    diskLayout =
                        DiskLayout(
                            writeLayout = true,
                            diskId = 0,
                            partitions = listOf(LayoutPartition(100, DiskPartitionRole.Primary)),
                        ),
                ),
            )
        }
    }

    @Test
    fun nonUnattendRootIsUnreadable() {
        val state = Autounattend.read("/ventoy/a.xml", "<root/>".toByteArray())
        assertTrue(state is AutounattendState.Unreadable)
        assertEquals(
            UnreadableProblem.NotUnattendRoot,
            (state as AutounattendState.Unreadable).problem,
        )
    }

    @Test
    fun unknownExistingElementsSurviveRewrite() {
        val base =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize">
                <component name="Vendor-Extension">
                  <VendorThing keep="yes">value</VendorThing>
                </component>
              </settings>
            </unattend>
            """.trimIndent().toByteArray()
        val generated =
            String(
                Autounattend.generate(base, AutounattendParams(languageTag = "en-US")),
                Charsets.UTF_8,
            )
        assertTrue(generated.contains("VendorThing"))
        assertTrue(generated.contains("keep=\"yes\""))
    }

    @Test
    fun readerReportsUnknownElementsAndExistingParams() {
        val base =
            """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem">
                <component name="Microsoft-Windows-International-Core">
                  <UILanguage>de-DE</UILanguage>
                  <UserLocale>de-DE</UserLocale>
                </component>
                <component name="Vendor-Extension"><VendorThing/></component>
              </settings>
            </unattend>
            """.trimIndent().toByteArray()
        val state = Autounattend.read("/ventoy/a.xml", base) as AutounattendState.Parsed
        assertEquals("de-DE", state.document.existing?.languageTag)
        assertEquals("de-DE", state.document.existing?.localeId)
        assertTrue(state.document.unknownElements.any { it.name == "Vendor-Extension" || it.name == "VendorThing" })
    }

    @Test
    fun unknownReportIncludesStructuralPathAndUnsupportedAttributes() {
        val xml = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem">
                <component name="Microsoft-Windows-International-Core">
                  <UILanguage vendorFlag="keep">de-DE</UILanguage>
                  <VendorBlock><VendorLeaf/></VendorBlock>
                </component>
              </settings>
            </unattend>
        """.trimIndent().toByteArray()
        val document = (Autounattend.read("/ventoy/a.xml", xml) as AutounattendState.Parsed).document
        assertTrue(
            document.unknownElements.any {
                it.name == "UILanguage" &&
                    it.path == "unattend/settings/component/UILanguage" &&
                    "vendorFlag" in it.attributes
            },
        )
        assertTrue(
            document.unknownElements.any {
                it.name == "VendorBlock" && it.path == "unattend/settings/component/VendorBlock"
            },
        )
    }

    @Test
    fun textMutationRefusesUnknownChildrenInsteadOfDroppingThem() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize">
                <component name="Microsoft-Windows-Shell-Setup">
                  <ComputerName><VendorValue/></ComputerName>
                </component>
              </settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(base, AutounattendParams(computerName = "new-name"))
        }
    }

    @Test
    fun unrelatedUnknownComponentRemainsWritableWhenTargetStructureIsSafe() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize">
                <component name="Vendor-Extension"><VendorThing keep="yes"/></component>
              </settings>
            </unattend>
        """.trimIndent().toByteArray()
        val generated = String(
            Autounattend.generate(base, AutounattendParams(computerName = "new-name")),
            Charsets.UTF_8,
        )
        assertTrue(generated.contains("VendorThing"))
        assertTrue(generated.contains("keep=\"yes\""))
        assertTrue(generated.contains("ComputerName"))
    }

    @Test
    fun groupRemovalRefusesUnknownGroupStructure() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts><LocalAccounts><LocalAccount><Name>Alice</Name>
                  <Group vendorFlag="keep"><VendorGroup/></Group>
                </LocalAccount></LocalAccounts></UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(base, AutounattendParams(localAccount = LocalAccount("Alice", inAdministrators = false)))
        }
    }

    @Test
    fun vendorNamespaceTargetIsNotImportedOrEdited() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend" xmlns:v="urn:vendor">
              <settings pass="specialize"><component name="Microsoft-Windows-Shell-Setup">
                <v:ComputerName>VENDOR</v:ComputerName>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val parsed = (Autounattend.read("/ventoy/a.xml", base) as AutounattendState.Parsed).document
        assertEquals(null, parsed.existing?.computerName)
        val generated = String(Autounattend.generate(base, AutounattendParams(computerName = "NEW")), Charsets.UTF_8)
        assertTrue(generated.contains("v:ComputerName"))
        assertTrue(generated.contains("VENDOR"))
        assertTrue(generated.contains("<ComputerName>NEW</ComputerName>"))
    }

    @Test
    fun generalizeComputerNameIsReportedUnknownAndNotImported() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="generalize"><component name="Microsoft-Windows-Shell-Setup">
                <ComputerName>OLD</ComputerName>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val parsed = (Autounattend.read("/ventoy/a.xml", base) as AutounattendState.Parsed).document
        assertEquals(null, parsed.existing?.computerName)
        assertTrue(parsed.unknownElements.any { it.path == "unattend/settings/component/ComputerName" })
    }

    @Test
    fun duplicatePasswordContainersRefuseRewrite() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts><LocalAccounts><LocalAccount><Name>Alice</Name>
                  <Password><Value>first</Value><PlainText>true</PlainText></Password>
                  <Password><Value>second</Value><PlainText>true</PlainText></Password>
                </LocalAccount></LocalAccounts></UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(base, AutounattendParams(localAccount = LocalAccount("Alice", "new")))
        }
    }

    @Test
    fun duplicateTargetComponentsRefuseRewrite() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize">
                <component name="Microsoft-Windows-Shell-Setup"/>
                <component name="Microsoft-Windows-Shell-Setup"/>
              </settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(base, AutounattendParams(computerName = "NEW"))
        }
    }

    @Test
    fun duplicateTargetSettingsRefuseRewrite() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize"/>
              <settings pass="specialize"/>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(base, AutounattendParams(computerName = "NEW"))
        }
    }


    @Test
    fun unsupportedModifyPartitionFieldsBlockLayoutReplacement() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE"><component name="Microsoft-Windows-Setup">
                <DiskConfiguration><Disk><ModifyPartitions><ModifyPartition>
                  <Order>1</Order><PartitionID>2</PartitionID><Active>true</Active>
                </ModifyPartition></ModifyPartitions></Disk></DiskConfiguration>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base,
                AutounattendParams(
                    diskLayout = DiskLayout(
                        writeLayout = true,
                        diskId = 0,
                        partitions = listOf(LayoutPartition(100, DiskPartitionRole.Primary)),
                    ),
                ),
            )
        }
    }

    @Test
    fun encodedPasswordIsNotImportedAsPlaintextOrRewritten() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts><LocalAccounts><LocalAccount><Name>Alice</Name>
                  <Password><Value>encoded</Value><PlainText>false</PlainText></Password>
                </LocalAccount></LocalAccounts></UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val parsed = (Autounattend.read("/ventoy/a.xml", base) as AutounattendState.Parsed).document
        assertEquals(false, parsed.existing?.localAccount?.passwordPlainText)
        assertEquals(null, parsed.existing?.localAccount?.password)
        assertTrue(parsed.unknownElements.any { it.detail == "encoded password format" })
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(base, AutounattendParams(localAccount = LocalAccount("Alice", "new")))
        }
    }
    @Test
    fun importedNonAdministratorGroupIsNotSilentlyRemoved() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts><LocalAccounts><LocalAccount><Name>Alice</Name><Group>Users</Group></LocalAccount></LocalAccounts></UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val parsed = (Autounattend.read("/ventoy/a.xml", base) as AutounattendState.Parsed).document
        assertEquals("Users", parsed.existing?.localAccount?.preservedGroup)
        assertEquals(false, parsed.existing?.localAccount?.inAdministrators)
        val account = parsed.existing?.localAccount ?: error("account not parsed")
        val generated = String(Autounattend.generate(base, AutounattendParams(localAccount = account)), Charsets.UTF_8)
        assertTrue(generated.contains("<Group>Users</Group>"))
    }

    @Test
    fun nonRoundTrippableModifyPartitionFormatBlocksReplacement() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE"><component name="Microsoft-Windows-Setup">
                <DiskConfiguration><Disk><ModifyPartitions><ModifyPartition>
                  <Order>1</Order><PartitionID>2</PartitionID><Format>FAT32</Format>
                </ModifyPartition></ModifyPartitions></Disk></DiskConfiguration>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base,
                AutounattendParams(
                    diskLayout = DiskLayout(
                        writeLayout = true,
                        diskId = 0,
                        partitions = listOf(LayoutPartition(100, DiskPartitionRole.Primary)),
                    ),
                ),
            )
        }
    }
    @Test
    fun reorderChangesCreateOrderModifyIdsAndInstallTarget() {
        fun layout(partitions: List<LayoutPartition>) =
            DiskLayout(
                writeLayout = true,
                diskId = 0,
                partitions = partitions,
                declaredSizeMb = 64_000,
            )
        val original =
            layout(
                listOf(
                    LayoutPartition(100, DiskPartitionRole.Efi, "EFI"),
                    LayoutPartition(16, DiskPartitionRole.Msr),
                    LayoutPartition(50_000, DiskPartitionRole.Primary, "WindowsA"),
                    LayoutPartition(10_000, DiskPartitionRole.Primary, "WindowsB"),
                ),
            )
        val reordered =
            layout(
                listOf(
                    LayoutPartition(100, DiskPartitionRole.Efi, "EFI"),
                    LayoutPartition(10_000, DiskPartitionRole.Primary, "WindowsB"),
                    LayoutPartition(16, DiskPartitionRole.Msr),
                    LayoutPartition(50_000, DiskPartitionRole.Primary, "WindowsA"),
                ),
            )

        fun xml(layout: DiskLayout) =
            String(
                Autounattend.generate(null, AutounattendParams(diskLayout = layout)),
                Charsets.UTF_8,
            )

        fun blocks(xml: String, tag: String): List<String> =
            Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .map { it.groupValues[1] }
                .toList()

        fun field(block: String, name: String): String? =
            Regex("<$name>(.*?)</$name>").find(block)?.groupValues?.get(1)

        val originalXml = xml(original)
        val reorderedXml = xml(reordered)
        assertEquals(
            listOf("1", "2", "3", "4"),
            blocks(originalXml, "CreatePartition").map { field(it, "Order") },
        )
        assertEquals(
            listOf("EFI", "MSR", "Primary", "Primary"),
            blocks(originalXml, "CreatePartition").map { field(it, "Type") },
        )
        assertEquals(
            listOf("1", "3", "4"),
            blocks(originalXml, "ModifyPartition").map { field(it, "PartitionID") },
        )
        assertEquals(
            "3",
            blocks(originalXml, "InstallTo").single().let { field(it, "PartitionID") },
        )

        assertEquals(
            listOf("1", "2", "3", "4"),
            blocks(reorderedXml, "CreatePartition").map { field(it, "Order") },
        )
        assertEquals(
            listOf("EFI", "Primary", "MSR", "Primary"),
            blocks(reorderedXml, "CreatePartition").map { field(it, "Type") },
        )
        assertEquals(
            listOf("1", "2", "4"),
            blocks(reorderedXml, "ModifyPartition").map { field(it, "PartitionID") },
        )
        assertEquals(
            "2",
            blocks(reorderedXml, "InstallTo").single().let { field(it, "PartitionID") },
        )
    }

    @Test
    fun declaredSizeIsEditorOnlyAndNeverChangesGeneratedXml() {
        val withDeclared =
            DiskLayout(
                writeLayout = true,
                diskId = 0,
                partitions =
                    listOf(
                        LayoutPartition(100, DiskPartitionRole.Efi, "EFI"),
                        LayoutPartition(50_000, DiskPartitionRole.Primary, "Windows"),
                    ),
                declaredSizeMb = 64_000,
            )
        val withoutDeclared = withDeclared.copy(declaredSizeMb = null)
        fun xml(layout: DiskLayout) =
            String(
                Autounattend.generate(null, AutounattendParams(diskLayout = layout)),
                Charsets.UTF_8,
            )
        assertEquals(xml(withDeclared), xml(withoutDeclared))
        assertTrue(Autounattend.validate(AutounattendParams(diskLayout = withDeclared)).problems.isEmpty())
        assertTrue(Autounattend.validate(AutounattendParams(diskLayout = withoutDeclared)).problems.isEmpty())
        assertTrue(
            AutounattendProblem.LayoutDiskSizeMissing in
                Autounattend.validate(
                    AutounattendParams(diskLayout = withoutDeclared),
                    requireDeclaredDiskSize = true,
                ).problems,
        )
    }

    @Test
    fun wipeFalseStillWritesTheExplicitLayout() {
        val layout =
            DiskLayout(
                writeLayout = true,
                diskId = 0,
                wipe = false,
                partitions =
                    listOf(
                        LayoutPartition(100, DiskPartitionRole.Efi, "EFI"),
                        LayoutPartition(50_000, DiskPartitionRole.Primary, "Windows"),
                    ),
                declaredSizeMb = 64_000,
            )
        val xml =
            String(
                Autounattend.generate(null, AutounattendParams(diskLayout = layout)),
                Charsets.UTF_8,
            )
        assertTrue(xml.contains("<WillWipeDisk>false</WillWipeDisk>"))
        assertFalse(xml.contains("<WillWipeDisk>true</WillWipeDisk>"))
        assertTrue(xml.contains("<CreatePartition"))
        assertTrue(xml.contains("<InstallTo>"))
        assertTrue(xml.contains("<PartitionID>2</PartitionID>"))
    }

    @Test
    fun partitionLabelControlCharactersAreRejected() {
        val layout =
            DiskLayout(
                writeLayout = true,
                diskId = 0,
                partitions =
                    listOf(
                        LayoutPartition(100, DiskPartitionRole.Efi, "EFI"),
                        LayoutPartition(50_000, DiskPartitionRole.Primary, "bad\u0000label"),
                    ),
                declaredSizeMb = 64_000,
            )
        assertTrue(
            AutounattendProblem.BadPartitionLabel in
                Autounattend.validate(
                    AutounattendParams(diskLayout = layout),
                    requireDeclaredDiskSize = true,
                ).problems,
        )
    }

    @Test
    fun unreadableAndMissingAreDistinct() {
        assertTrue(Autounattend.read("/ventoy/a.xml", null) is AutounattendState.Missing)
        assertTrue(Autounattend.read("/ventoy/a.xml", ByteArray(0)) is AutounattendState.Unreadable)
        assertTrue(
            Autounattend.read("/ventoy/a.xml", "<unattend>".toByteArray()) is AutounattendState.Unreadable,
        )
    }

    @Test
    fun importedSnapshotPreservesWithoutMutationIntent() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE"><component name="Microsoft-Windows-Setup">
                <DiskConfiguration><Disk><DiskID>0</DiskID><WillWipeDisk>false</WillWipeDisk>
                  <CreatePartitions><CreatePartition><Order>1</Order><Type>Primary</Type><Size>100</Size></CreatePartition></CreatePartitions>
                </Disk></DiskConfiguration>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val existing = (Autounattend.read("/ventoy/a.xml", base) as AutounattendState.Parsed).document.existing
            ?: error("missing imported snapshot")
        val generated = String(Autounattend.generate(base, existing), Charsets.UTF_8)
        assertTrue(generated.contains("<DiskID>0</DiskID>"))
        assertTrue(generated.contains("<Type>Primary</Type>"))
    }

    @Test
    fun importedPasswordAndGroupPreserveWithoutMutationIntent() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts><LocalAccounts><LocalAccount><Name>Alice</Name>
                  <Password><Value>encoded</Value><PlainText>false</PlainText></Password><Group>Users</Group>
                </LocalAccount></LocalAccounts></UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val existing = (Autounattend.read("/ventoy/a.xml", base) as AutounattendState.Parsed).document.existing
            ?: error("missing imported snapshot")
        val generated = String(Autounattend.generate(base, existing), Charsets.UTF_8)
        assertTrue(generated.contains("<PlainText>false</PlainText>"))
        assertTrue(generated.contains("<Group>Users</Group>"))
    }

    @Test
    fun defaultLocalAccountMayBeNonAdministrator() {
        val xml = String(
            Autounattend.generate(null, AutounattendParams(localAccount = LocalAccount("Alice"))),
            Charsets.UTF_8,
        )
        assertTrue(xml.contains("<LocalAccount>"))
        assertFalse(xml.contains("Administrators"))
    }

    @Test
    fun explicitRenameRunsWhenPasswordAndGroupArePreserved() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts><LocalAccounts><LocalAccount><Name>Alice</Name><Group>Users</Group></LocalAccount></LocalAccounts></UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val params = AutounattendParams(
            localAccount = LocalAccount(
                name = "Bob",
                preservedGroup = "Users",
                passwordIntent = XmlFieldIntent.Preserve,
                groupIntent = XmlFieldIntent.Preserve,
            ),
        )
        val xml = String(Autounattend.generate(base, params), Charsets.UTF_8)
        assertTrue(xml.contains("<Name>Bob</Name>"))
        assertFalse(xml.contains("<Name>Alice</Name>"))
        assertTrue(xml.contains("<Group>Users</Group>"))
    }

    @Test
    fun nestedCommentsAndProcessingInstructionsBlockRemoval() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts><LocalAccounts><LocalAccount><Name>Alice</Name><?vendor keep?><!--keep--></LocalAccount></LocalAccounts></UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base,
                AutounattendParams(
                    intents = AutounattendIntents(
                        language = XmlFieldIntent.Preserve,
                        locale = XmlFieldIntent.Preserve,
                        computerName = XmlFieldIntent.Preserve,
                        localAccount = XmlFieldIntent.Remove,
                        diskLayout = XmlFieldIntent.Preserve,
                    ),
                ),
            )
        }
    }

    @Test
    fun duplicateModifyPartitionOrdersBlockReplacement() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE"><component name="Microsoft-Windows-Setup">
                <DiskConfiguration><Disk><DiskID>0</DiskID><WillWipeDisk>false</WillWipeDisk>
                  <CreatePartitions>
                    <CreatePartition><Order>1</Order><Type>Primary</Type><Size>100</Size></CreatePartition>
                    <CreatePartition><Order>2</Order><Type>Primary</Type><Size>100</Size></CreatePartition>
                  </CreatePartitions>
                  <ModifyPartitions>
                    <ModifyPartition><Order>1</Order><PartitionID>1</PartitionID><Format>NTFS</Format></ModifyPartition>
                    <ModifyPartition><Order>1</Order><PartitionID>2</PartitionID><Format>NTFS</Format></ModifyPartition>
                  </ModifyPartitions>
                </Disk></DiskConfiguration>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val layout = DiskLayout(
            writeLayout = true,
            diskId = 0,
            partitions = listOf(
                LayoutPartition(100, DiskPartitionRole.Primary),
                LayoutPartition(100, DiskPartitionRole.Primary),
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base,
                AutounattendParams(diskLayout = layout, originalDiskLayout = layout),
            )
        }
    }

    @Test
    fun nestedCommentsBlockDiskReplacement() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="windowsPE"><component name="Microsoft-Windows-Setup">
                <DiskConfiguration><Disk><?vendor keep?><CreatePartitions><CreatePartition><Order>1</Order><Type>Primary</Type><Size>100</Size></CreatePartition></CreatePartitions></Disk></DiskConfiguration>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base,
                AutounattendParams(
                    diskLayout = DiskLayout(
                        writeLayout = true,
                        diskId = 0,
                        partitions = listOf(LayoutPartition(100, DiskPartitionRole.Primary)),
                    ),
                ),
            )
        }
    }

    @Test
    fun unknownReportIncludesCommentsProcessingInstructionsAndCdata() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="specialize"><component name="Microsoft-Windows-Shell-Setup">
                <ComputerName>OLD<?vendor keep?><!--keep--><![CDATA[cdata]]></ComputerName>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val document = (Autounattend.read("/ventoy/a.xml", base) as AutounattendState.Parsed).document
        assertTrue(document.unknownElements.any { it.name == "#comment" })
        assertTrue(document.unknownElements.any { it.name == "#processing-instruction" })
        assertTrue(document.unknownElements.any { it.name == "#cdata" })
    }

    @Test
    fun removingAccountRefusesUnsupportedDescription() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts><LocalAccounts><LocalAccount><Name>Alice</Name><Description>vendor metadata</Description></LocalAccount></LocalAccounts></UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            Autounattend.generate(
                base,
                AutounattendParams(
                    intents = AutounattendIntents(
                        language = XmlFieldIntent.Preserve,
                        locale = XmlFieldIntent.Preserve,
                        computerName = XmlFieldIntent.Preserve,
                        localAccount = XmlFieldIntent.Remove,
                        diskLayout = XmlFieldIntent.Preserve,
                    ),
                ),
            )
        }
    }

    @Test
    fun validationIgnoresPreservedImportedValues() {
        val params = AutounattendParams(
            computerName = "*",
            diskLayout = DiskLayout(
                writeLayout = true,
                diskId = 0,
                partitions = listOf(LayoutPartition(100, DiskPartitionRole.Primary)),
            ),
            intents = AutounattendIntents(
                language = XmlFieldIntent.Set,
                locale = XmlFieldIntent.Preserve,
                computerName = XmlFieldIntent.Preserve,
                localAccount = XmlFieldIntent.Preserve,
                diskLayout = XmlFieldIntent.Preserve,
            ),
        )
        assertTrue(Autounattend.validate(params, requireDeclaredDiskSize = true).problems.isEmpty())
    }

    @Test
    fun removingLocalAccountPreservesOtherUserAccountSettings() {
        val base = """
            <unattend xmlns="urn:schemas-microsoft-com:unattend">
              <settings pass="oobeSystem"><component name="Microsoft-Windows-Shell-Setup">
                <UserAccounts>
                  <AdministratorPassword><Value>keep-me</Value></AdministratorPassword>
                  <LocalAccounts><LocalAccount><Name>Alice</Name></LocalAccount></LocalAccounts>
                </UserAccounts>
              </component></settings>
            </unattend>
        """.trimIndent().toByteArray()
        val params = AutounattendParams(
            intents = AutounattendIntents(
                language = XmlFieldIntent.Preserve,
                locale = XmlFieldIntent.Preserve,
                computerName = XmlFieldIntent.Preserve,
                localAccount = XmlFieldIntent.Remove,
                diskLayout = XmlFieldIntent.Preserve,
            ),
        )
        val xml = String(Autounattend.generate(base, params), Charsets.UTF_8)
        assertTrue(xml.contains("AdministratorPassword"))
        assertTrue(xml.contains("keep-me"))
        assertFalse(xml.contains("<LocalAccounts>"))
    }
}
