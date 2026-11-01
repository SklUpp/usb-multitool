package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.testutil.RecordingDisk

class VentoyLayoutTest {
    private val assets = VentoyAssets(
        release = "1.1.18",
        versionBytes = "1.1.18".toByteArray(),
        supports4Kn = false,
    )

    @Test fun updateRejectsUnknownDiskWithoutWriting() {
        val disk = RecordingDisk.fromAsset("ventoy/plain-fat.img")
        try {
            VentoyInstaller.update(disk, assets)
            fail("expected IncompatibleVentoyException")
        } catch (_: IncompatibleVentoyException) {
        }
        assertEquals(0, disk.bytesWritten)
    }

    @Test fun installThenUpdateSucceeds() {
        val disk = RecordingDisk(sectorCount = 262144UL, sectorSize = 512)
        val table = VentoyInstaller.install(disk, assets, gpt = true)
        assertTrue(table.partitions.any { it.name == "VTOYEFI" })
        assertTrue(VentoyLayout.isVentoyDisk(disk))
        val writtenAfterInstall = disk.bytesWritten
        VentoyInstaller.update(disk, assets)
        assertTrue(disk.bytesWritten >= writtenAfterInstall)
    }

    @Test fun installRefuses4KnWhenUnsupported() {
        val disk = RecordingDisk(sectorCount = 32768UL, sectorSize = 4096)
        try {
            VentoyInstaller.install(disk, assets, gpt = true)
            fail("expected UnsupportedSectorSizeException")
        } catch (_: UnsupportedSectorSizeException) {
        }
        assertEquals(0, disk.bytesWritten)
    }

    @Test fun windowsSetupRequiresOverwrite() {
        val root = mutableMapOf<String, Any?>("ventoy.json" to "{ }")
        try {
            WindowsSetup.write(root, "Win11.iso", SetupOptions(), overwrite = false)
            fail("expected VentoyOverwriteRequiredException")
        } catch (_: VentoyOverwriteRequiredException) {
        }
        val json = WindowsSetup.write(
            root,
            "Win11.iso",
            SetupOptions(tpmBypass = true, localAccount = true),
            overwrite = true,
        )
        assertTrue(json.contains("Win11.iso"))
        assertTrue(root.containsKey("Win11.xml"))
    }
}
