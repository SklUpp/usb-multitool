package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.service.OperationTokenGenerator

/**
 * Behavioural contract of the shell log and the two-state theme, asserted without a
 * device or Compose: the reducer owns every log and theme rule the shell relies on.
 */
class ShellLogBehaviorTest {

    private fun runningOperation() = OperationProgress(
        state = OperationState.Running,
        token = OperationTokenGenerator.next(),
        title = OperationTitle.Destructive(DestructiveAction.RAW_WRITE),
        bytesDone = 40,
        bytesTotal = 100,
    )

    @Test
    fun clearDropsEntriesDuringOperationWithoutStoppingIt() {
        val running = AppUiState(
            presence = DevicePresence.Busy,
            tab = ShellTab.Partitions,
            operation = runningOperation(),
            logLines = listOf("first", "second"),
        ).withStatus(UiMessage.Key(UiMessageKey.RunningStatus))
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
    fun readOnlyTabsOpenWhileOperationRunsButPrimaryTabsRemainBlocked() {
        val running = AppUiState(
            presence = DevicePresence.Ready,
            tab = ShellTab.Partitions,
            operation = runningOperation(),
        )
        for (target in listOf(ShellTab.Settings, ShellTab.Diagnostics)) {
            val opened = UiReducer.reduce(running, UiIntent.SelectTab(target)).state
            assertEquals(target, opened.tab)
            assertFalse(opened.actionsEnabled)
        }
        val blocked = UiReducer.reduce(running, UiIntent.SelectTab(ShellTab.Files)).state
        assertEquals(ShellTab.Partitions, blocked.tab)
        assertEquals(UiMessage.Key(UiMessageKey.WaitForOperation), blocked.statusMessage)

        val backFromSettings = UiReducer.reduce(
            running.copy(tab = ShellTab.Settings),
            UiIntent.SelectTab(ShellTab.Files),
        ).state
        assertEquals(ShellTab.Files, backFromSettings.tab)
    }

    @Test
    fun diagnosticsReturnsToWorkTabWhileRunningOrQueued() {
        val running = AppUiState(
            presence = DevicePresence.Ready,
            tab = ShellTab.Partitions,
            operation = runningOperation(),
        )
        val queued = running.copy(
            operation = runningOperation().copy(state = OperationState.Queued),
        )
        for (state in listOf(running, queued)) {
            val returned = UiReducer.reduce(
                state.copy(tab = ShellTab.Diagnostics),
                UiIntent.SelectTab(ShellTab.Partitions),
            ).state
            assertEquals(ShellTab.Partitions, returned.tab)
            assertFalse(returned.actionsEnabled)
            assertEquals(state.operation, returned.operation)
        }
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
}
