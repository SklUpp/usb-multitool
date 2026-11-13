package usb.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.service.OperationTokenGenerator

/**
 * Behavioural contract of the shell log and the two-state theme, asserted without a
 * device or Compose: the reducer owns every log and theme rule the shell relies on, and
 * the authored strings are checked as resources so a translation cannot drop a warning.
 */
class ShellLogBehaviorTest {

    private fun runningOperation() = OperationProgress(
        state = OperationState.Running,
        token = OperationTokenGenerator.next(),
        title = OperationTitle.Destructive(DestructiveAction.RAW_WRITE),
        bytesDone = 40,
        bytesTotal = 100,
    )

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

    private fun string(relative: String, name: String): String {
        val match = Regex("<string name=\"$name\">([^<]*)</string>")
            .find(resource(relative).readText())
        return match?.groupValues?.get(1)
            ?: throw AssertionError("missing string $name in $relative")
    }

    private fun stringOrNull(relative: String, name: String): String? =
        Regex("<string name=\"$name\">([^<]*)</string>")
            .find(resource(relative).readText())
            ?.groupValues
            ?.get(1)

    @Test
    fun clearDropsEntriesDuringOperationWithoutStoppingIt() {
        val running = AppUiState(
            presence = DevicePresence.Busy,
            tab = ShellTab.Partitions,
            operation = runningOperation(),
            statusMessage = UiMessage.Key(UiMessageKey.RunningStatus),
            logLines = listOf("first", "second"),
        )
        val cleared = UiReducer.reduce(running, UiIntent.ClearLog)
        assertEquals(emptyList<String>(), cleared.state.logLines)
        // Clearing is log-only: the running operation and every other field survive.
        assertEquals(running.operation, cleared.state.operation)
        assertEquals(running.presence, cleared.state.presence)
        assertEquals(running.statusMessage, cleared.state.statusMessage)
        // Leaving the full log screen must land on the tab the user had selected.
        assertEquals(ShellTab.Partitions, cleared.state.tab)
        assertTrue(cleared.effects.isEmpty())
    }

    @Test
    fun newEntriesAppearImmediatelyAfterClear() {
        val cleared = UiReducer.reduce(
            AppUiState(logLines = listOf("old")),
            UiIntent.ClearLog,
        ).state
        val appended = UiReducer.reduce(cleared, UiIntent.AppendLog("after clear")).state
        assertEquals(listOf("after clear"), appended.logLines)
    }

    @Test
    fun clearingAnEmptyLogChangesNothing() {
        val empty = AppUiState()
        val cleared = UiReducer.reduce(empty, UiIntent.ClearLog).state
        assertEquals(emptyList<String>(), cleared.logLines)
        assertEquals(empty, cleared)
    }

    @Test
    fun logActionsNeverChangeTheSelectedTab() {
        // The log screen is a layer above the tabs, so nothing the journal does may
        // select another tab: Back has to return to the tab the user already had.
        val base = AppUiState(tab = ShellTab.Partitions, logLines = listOf("x"))
        listOf<UiIntent>(
            UiIntent.AppendLog("y"),
            UiIntent.ClearLog,
            UiIntent.Status(UiMessage.Key(UiMessageKey.LogCopied)),
        ).forEach { intent ->
            assertEquals(
                "intent $intent must not switch tabs",
                ShellTab.Partitions,
                UiReducer.reduce(base, intent).state.tab,
            )
        }
    }

    @Test
    fun copyTextKeepsEveryLineInOrderAndNothingElse() {
        assertEquals("first\nsecond\nthird", logClipboardText(listOf("first", "second", "third")))
        assertEquals("", logClipboardText(emptyList()))
    }

    @Test
    fun themeTogglesBetweenDarkAndLightOnly() {
        val dark = AppUiState()
        assertTrue("dark must be the default", dark.darkTheme)

        val light = UiReducer.reduce(dark, UiIntent.ToggleTheme).state
        assertFalse(light.darkTheme)

        val backToDark = UiReducer.reduce(light, UiIntent.ToggleTheme).state
        assertTrue(backToDark.darkTheme)
    }

    @Test
    fun automaticThemeResourceIsGone() {
        assertNull(stringOrNull("app/src/main/res/values/strings.xml", "theme_auto"))
        assertNull(stringOrNull("app/src/main/res/values-ru/strings.xml", "theme_auto"))
    }

    @Test
    fun copyWarningNamesPathsAndUsbSerialsInBothLanguages() {
        val english = string("app/src/main/res/values/strings.xml", "log_copy_warning")
        assertTrue("warning must name paths: $english", english.contains("path"))
        assertTrue("warning must name serials: $english", english.contains("serial"))

        val russian = string("app/src/main/res/values-ru/strings.xml", "log_copy_warning")
        assertTrue("warning must name paths: $russian", russian.contains("пут"))
        assertTrue("warning must name serials: $russian", russian.contains("серийн"))
    }

    @Test
    fun clearHintStatesNoConfirmationAndUntouchedLogcat() {
        val english = string("app/src/main/res/values/strings.xml", "log_clear_hint")
        assertTrue("no confirmation: $english", english.contains("no confirmation"))
        assertTrue("logcat is untouched: $english", english.contains("logcat", ignoreCase = true))

        val russian = string("app/src/main/res/values-ru/strings.xml", "log_clear_hint")
        assertTrue("no confirmation: $russian", russian.contains("без подтверждения"))
        assertTrue("logcat is untouched: $russian", russian.contains("logcat", ignoreCase = true))
    }

    @Test
    fun copyAllIsAnExplicitLabeledAction() {
        assertTrue(string("app/src/main/res/values/strings.xml", "log_copy_all").isNotBlank())
        assertTrue(string("app/src/main/res/values-ru/strings.xml", "log_copy_all").isNotBlank())
    }
}
