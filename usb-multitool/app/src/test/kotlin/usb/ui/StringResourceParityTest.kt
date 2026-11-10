package usb.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety net for the screen pass: the English default and the Russian translation must
 * expose exactly the same keys, and neither file may declare a key twice — the later
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

    private fun keys(relative: String): List<String> =
        Regex("<(?:string|plurals) name=\"([^\"]+)\"")
            .findAll(resource(relative).readText())
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun englishAndRussianDeclareTheSameKeys() {
        val english = keys("app/src/main/res/values/strings.xml")
        val russian = keys("app/src/main/res/values-ru/strings.xml")
        assertTrue("no string keys parsed", english.isNotEmpty() && russian.isNotEmpty())
        assertEquals("missing in values-ru", emptySet<String>(), english.toSet() - russian.toSet())
        assertEquals("missing in values", emptySet<String>(), russian.toSet() - english.toSet())
    }

    @Test
    fun noKeyIsDeclaredTwice() {
        listOf("app/src/main/res/values/strings.xml", "app/src/main/res/values-ru/strings.xml")
            .forEach { relative ->
                val keys = keys(relative)
                assertEquals(
                    "duplicate resource key in $relative",
                    keys.size,
                    keys.toSet().size,
                )
            }
    }
}
