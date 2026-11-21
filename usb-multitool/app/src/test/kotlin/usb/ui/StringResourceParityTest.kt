package usb.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety net for the screen pass: every English strings file and its Russian counterpart
 * must expose exactly the same keys, and no file may declare a key twice — the later
 * declaration would silently win at build time.
 */
class StringResourceParityTest {
    private fun resource(relative: String): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val current = dir ?: return@repeat
            for (candidate in listOf(File(current, relative), File(current, "usb-multitool/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = current.parentFile
        }
        throw AssertionError("cannot locate $relative from ${File("").absolutePath}")
    }

    private fun stringFiles(relative: String): List<File> {
        val dir = resource(relative).parentFile
            ?: throw AssertionError("cannot locate directory for $relative")
        return dir.listFiles()
            ?.filter { it.isFile && (it.name == "strings.xml" || it.name.startsWith("strings_")) && it.extension == "xml" }
            ?.sortedBy { it.name }
            ?: throw AssertionError("cannot list strings files in $dir")
    }

    private fun keys(file: File): List<String> =
        Regex("<(?:string|plurals) name=\"([^\"]+)\"")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun englishAndRussianDeclareTheSameKeys() {
        val englishDir = stringFiles("app/src/main/res/values/strings.xml")
        val russianDir = stringFiles("app/src/main/res/values-ru/strings.xml")
        assertEquals(
            "strings file sets differ",
            englishDir.map { it.name },
            russianDir.map { it.name },
        )
        englishDir.zip(russianDir).forEach { (english, russian) ->
            val englishKeys = keys(english)
            val russianKeys = keys(russian)
            assertTrue("no string keys parsed in ${english.name}", englishKeys.isNotEmpty())
            assertTrue("no string keys parsed in ${russian.name}", russianKeys.isNotEmpty())
            assertEquals(
                "missing in ${russian.name}",
                emptySet<String>(),
                englishKeys.toSet() - russianKeys.toSet(),
            )
            assertEquals(
                "missing in ${english.name}",
                emptySet<String>(),
                russianKeys.toSet() - englishKeys.toSet(),
            )
        }
    }

    @Test
    fun noKeyIsDeclaredTwice() {
        stringFiles("app/src/main/res/values/strings.xml").forEach { file ->
            val keys = keys(file)
            assertEquals(
                "duplicate resource key in ${file.name}",
                keys.size,
                keys.toSet().size,
            )
        }
        stringFiles("app/src/main/res/values-ru/strings.xml").forEach { file ->
            val keys = keys(file)
            assertEquals(
                "duplicate resource key in values-ru/${file.name}",
                keys.size,
                keys.toSet().size,
            )
        }
    }
}
