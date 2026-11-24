package usb.multitool

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.files.FilesVolumeSession
import usb.files.UsbVolume
import usb.saf.SafBackupFolder
import usb.saf.SafTree
import usb.service.DiskController
import usb.service.OperationSubmitter

/**
 * Architecture fence: storage ports stay implemented by their owners and the
 * `usb.files` / `usb.saf` sources never import the UI or activity layers.
 */
class ArchPortsTest {
    @Test
    fun volumePortImplementedBySession() {
        val session: Any = FilesVolumeSession({ null }) { _, _, _, _ -> error("unused") }
        assertTrue(session is UsbVolume)
        assertTrue(UsbVolume::class.java.isAssignableFrom(FilesVolumeSession::class.java))
    }

    @Test
    fun treePortImplementedByBackupFolder() {
        assertTrue(SafTree::class.java.isAssignableFrom(SafBackupFolder::class.java))
    }

    @Test
    fun submitterPortImplementedByController() {
        val controller: Any = DiskController()
        assertTrue(controller is OperationSubmitter)
        assertTrue(OperationSubmitter::class.java.isAssignableFrom(DiskController::class.java))
    }

    @Test
    fun filesAndSafSourcesAvoidUiAndMultitoolImports() {
        val module = moduleDir()
        val roots = listOf(
            File(module, "src/main/kotlin/usb/files"),
            File(module, "src/main/kotlin/usb/saf"),
        )
        for (root in roots) {
            assertTrue("missing source root: $root", root.isDirectory)
        }
        val forbidden = listOf("import usb.ui.", "import usb.multitool.")
        val violations = mutableListOf<String>()
        var checked = 0
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    checked++
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trimStart()
                        if (forbidden.any { trimmed.startsWith(it) }) {
                            violations += "${file.relativeTo(module)}:${index + 1}: $trimmed"
                        }
                    }
                }
        }
        assertTrue("expected to scan port sources", checked > 0)
        assertEquals(
            "forbidden layer imports (allow-list is empty):\n" + violations.joinToString("\n"),
            0,
            violations.size,
        )
    }

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "src/main/kotlin/usb/files").isDirectory) return dir
            dir = dir.parentFile ?: error("cannot locate module dir from ${System.getProperty("user.dir")}")
        }
    }
}
