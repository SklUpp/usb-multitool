package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsTemplateTest {
    @Test
    fun safeTemplateHasNoDestructiveMarkers() {
        val xml =
            """
            <unattend>
              <settings pass="specialize">
                <component name="Microsoft-Windows-Shell-Setup">
                  <ComputerName>PC</ComputerName>
                </component>
              </settings>
            </unattend>
            """.trimIndent().toByteArray()
        val inspection = WindowsTemplate.inspect(xml)
        assertTrue(inspection.destructiveMarkers.isEmpty())
        WindowsTemplate.requireAllowed(inspection, allowDestructive = false)
    }

    @Test
    fun diskConfigurationAndWipeRequireSeparateOptIn() {
        val xml =
            """
            <unattend xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">
              <settings pass="windowsPE">
                <component name="Microsoft-Windows-Setup">
                  <DiskConfiguration>
                    <Disk wcm:action="add">
                      <WillWipeDisk>true</WillWipeDisk>
                      <CreatePartitions/>
                      <ModifyPartitions/>
                    </Disk>
                  </DiskConfiguration>
                  <ImageInstall><InstallTo><DiskID>0</DiskID></InstallTo></ImageInstall>
                </component>
              </settings>
            </unattend>
            """.trimIndent().toByteArray()
        val inspection = WindowsTemplate.inspect(xml)
        assertEquals(
            listOf("DiskConfiguration", "WillWipeDisk", "CreatePartitions", "ModifyPartitions", "ImageInstall"),
            inspection.destructiveMarkers,
        )
        val failure =
            assertThrows(DestructiveTemplateRequiresOptInException::class.java) {
                WindowsTemplate.requireAllowed(inspection, allowDestructive = false)
            }
        assertTrue(failure.message!!.contains("WillWipeDisk"))
        WindowsTemplate.requireAllowed(inspection, allowDestructive = true)
    }

    @Test
    fun willWipeDiskFalseIsNotTreatedAsDestructiveByItself() {
        val inspection =
            WindowsTemplate.inspect("<unattend><WillWipeDisk>false</WillWipeDisk></unattend>".toByteArray())
        assertTrue(inspection.destructiveMarkers.isEmpty())
    }

    @Test
    fun malformedXmlIsRejectedWithoutWriting() {
        assertThrows(InvalidTemplateException::class.java) {
            WindowsTemplate.inspect("<unattend><broken>".toByteArray())
        }
    }

    @Test
    fun acknowledgementMustMatchInspectedBytes() {
        val bytes =
            """<unattend xmlns="urn:schemas-microsoft-com:unattend"><settings pass="windowsPE"><component name="Microsoft-Windows-Setup"><DiskConfiguration><Disk><WillWipeDisk>true</WillWipeDisk></Disk></DiskConfiguration></component></settings></unattend>"""
                .toByteArray()
        val inspection = WindowsTemplate.inspect(bytes)
        val digest = WindowsTemplate.sha256(bytes)
        VentoySetup.requireAcknowledgedTemplate(inspection, bytes, true, digest)
        assertThrows(VentoySetup.TemplateChangedException::class.java) {
            VentoySetup.requireAcknowledgedTemplate(inspection, bytes, true, "00")
        }
        assertThrows(VentoySetup.TemplateChangedException::class.java) {
            VentoySetup.requireAcknowledgedTemplate(inspection, bytes, true, null)
        }
        assertThrows(DestructiveTemplateRequiresOptInException::class.java) {
            VentoySetup.requireAcknowledgedTemplate(inspection, bytes, false, digest)
        }
    }

    @Test
    fun doctypeIsRejectedInUtf16() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><unattend/>"""
        assertThrows(InvalidTemplateException::class.java) {
            WindowsTemplate.inspect(xml.toByteArray(Charsets.UTF_16LE))
        }
    }

    @Test
    fun bindRootCheckRequiresUnattendNamespace() {
        assertThrows(InvalidTemplateException::class.java) {
            WindowsTemplate.inspect("<root/>".toByteArray(), requireUnattendRoot = true)
        }
        WindowsTemplate.inspect(
            "<unattend xmlns=\"urn:schemas-microsoft-com:unattend\"/>".toByteArray(),
            requireUnattendRoot = true,
        )
    }

    @Test
    fun doctypeIsRejected() {
        val xml = """<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><unattend>&xxe;</unattend>"""
        assertThrows(InvalidTemplateException::class.java) {
            WindowsTemplate.inspect(xml.toByteArray())
        }
    }
}
