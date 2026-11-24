package usb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTabsBehaviorTest {
    @Test
    fun idleAllowsEveryTab() {
        for (tab in PrimaryShellTabs) {
            assertFalse(
                shellTabSwitchBlocked(
                    tab = tab,
                    selected = ShellTab.Files,
                    operationLocked = false,
                    readOnlyReturn = ShellTab.Files,
                ),
            )
        }
    }

    @Test
    fun lockedKeepsSelectedAndDiagnostics() {
        assertFalse(
            shellTabSwitchBlocked(
                tab = ShellTab.Files,
                selected = ShellTab.Files,
                operationLocked = true,
                readOnlyReturn = null,
            ),
        )
        assertFalse(
            shellTabSwitchBlocked(
                tab = ShellTab.Diagnostics,
                selected = ShellTab.Files,
                operationLocked = true,
                readOnlyReturn = null,
            ),
        )
        assertTrue(
            shellTabSwitchBlocked(
                tab = ShellTab.Ventoy,
                selected = ShellTab.Files,
                operationLocked = true,
                readOnlyReturn = null,
            ),
        )
        assertTrue(
            shellTabSwitchBlocked(
                tab = ShellTab.Partitions,
                selected = ShellTab.Files,
                operationLocked = true,
                readOnlyReturn = null,
            ),
        )
    }

    @Test
    fun lockedReadOnlyScreenAllowsReturnTab() {
        assertFalse(
            shellTabSwitchBlocked(
                tab = ShellTab.Files,
                selected = ShellTab.Settings,
                operationLocked = true,
                readOnlyReturn = ShellTab.Files,
            ),
        )
        assertFalse(
            shellTabSwitchBlocked(
                tab = ShellTab.Ventoy,
                selected = ShellTab.Diagnostics,
                operationLocked = true,
                readOnlyReturn = ShellTab.Ventoy,
            ),
        )
        assertTrue(
            shellTabSwitchBlocked(
                tab = ShellTab.Partitions,
                selected = ShellTab.Settings,
                operationLocked = true,
                readOnlyReturn = ShellTab.Files,
            ),
        )
    }

    @Test
    fun nullReadOnlyReturnIsSafe() {
        assertTrue(
            shellTabSwitchBlocked(
                tab = ShellTab.Ventoy,
                selected = ShellTab.Settings,
                operationLocked = true,
                readOnlyReturn = null,
            ),
        )
        assertFalse(
            shellTabSwitchBlocked(
                tab = ShellTab.Diagnostics,
                selected = ShellTab.Settings,
                operationLocked = true,
                readOnlyReturn = null,
            ),
        )
    }

    @Test
    fun nonReadOnlySelectedHasNoReturnBypass() {
        assertTrue(
            shellTabSwitchBlocked(
                tab = ShellTab.Files,
                selected = ShellTab.Ventoy,
                operationLocked = true,
                readOnlyReturn = ShellTab.Files,
            ),
        )
    }
}
