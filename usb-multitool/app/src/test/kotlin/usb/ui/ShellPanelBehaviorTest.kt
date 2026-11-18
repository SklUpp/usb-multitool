package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the shell-owned presentation state, the tab swipe policy and the gesture
 * arbiter. All assertions drive pure code; no source text is inspected.
 */
class ShellPanelBehaviorTest {

    @Test
    fun repeatedStatusIntentsBumpTheSequence() {
        val message = UiMessage.Key(UiMessageKey.LogCopied)
        val first = UiReducer.reduce(AppUiState(), UiIntent.Status(message)).state
        assertEquals(1L, first.statusSequence)
        val second = UiReducer.reduce(first, UiIntent.Status(message)).state
        assertEquals(2L, second.statusSequence)
        assertEquals(message, second.statusMessage)
    }

    @Test
    fun repeatedRunningTicksDoNotBumpTheSequence() {
        val token = usb.service.OperationToken(1L)
        val progress = OperationProgress(
            state = OperationState.Running,
            title = OperationTitle.Upload,
            token = token,
        )
        val begun = UiReducer.reduce(
            AppUiState(),
            UiIntent.OperationBegan(progress),
        ).state
        val tick = begun.copy(statusSequence = 0L)
        val first = UiReducer.reduce(tick, UiIntent.OperationUpdated(progress)).state
        val second = UiReducer.reduce(first, UiIntent.OperationUpdated(progress)).state
        assertEquals(
            "progress tickers post every 120 ms; only a state change may re-emit the status",
            first.statusSequence,
            second.statusSequence,
        )
    }

    @Test
    fun clearingStatusKeepsTheSequence() {
        val message = UiMessage.Key(UiMessageKey.LogCopied)
        val shown = UiReducer.reduce(AppUiState(), UiIntent.Status(message)).state
        val cleared = UiReducer.reduce(shown, UiIntent.Status(null)).state
        assertEquals(1L, cleared.statusSequence)
        assertNull(cleared.statusMessage)
    }

    @Test
    fun journalLayerOpensAndClosesIdempotently() {
        val closed = ShellPanelState()
        assertFalse(closed.journalScreenOpen)
        val opened = closed.openJournalScreen()
        assertTrue(opened.journalScreenOpen)
        assertEquals(opened, opened.openJournalScreen())
    }

    @Test
    fun closingTheJournalReturnsToTheIdleShell() {
        val closed = ShellPanelState(journalScreenOpen = true).closeJournalScreen()
        assertFalse(closed.journalScreenOpen)
        assertEquals(ShellPanelState(), closed)
        assertEquals(closed, closed.closeJournalScreen())
    }


    @Test
    fun swipeNeedsDistanceAndHorizontalDominance() {
        val threshold = 72f
        assertNull(ShellSwipePolicy.direction(0f, 0f, threshold))
        assertNull("just under the threshold", ShellSwipePolicy.direction(-71f, 1f, threshold))
        assertNull("vertical drag", ShellSwipePolicy.direction(-100f, -140f, threshold))
        assertNull("diagonal tie", ShellSwipePolicy.direction(-100f, 100f, threshold))
        assertEquals(
            ShellSwipeDirection.Forward,
            ShellSwipePolicy.direction(-100f, 20f, threshold),
        )
        assertEquals(
            ShellSwipeDirection.Backward,
            ShellSwipePolicy.direction(100f, -20f, threshold),
        )
        assertNull("non-positive threshold disables swiping", ShellSwipePolicy.direction(500f, 0f, 0f))
    }

    @Test
    fun rtlFlipsThePhysicalDirectionOfNextTab() {
        val threshold = 72f
        assertEquals(
            ShellSwipeDirection.Forward,
            ShellSwipePolicy.direction(100f, 10f, threshold, isRtl = true),
        )
        assertEquals(
            ShellSwipeDirection.Backward,
            ShellSwipePolicy.direction(-100f, 10f, threshold, isRtl = true),
        )
        // Dominance still decides before reading direction.
        assertNull(ShellSwipePolicy.direction(100f, 140f, threshold, isRtl = true))
    }

    @Test
    fun swipeStartingInSystemBackEdgeIsRejected() {
        assertTrue(
            ShellSwipePolicy.startsInSystemBackEdge(
                xPx = 10f,
                widthPx = 1000f,
                leftInsetPx = 48f,
                rightInsetPx = 0f,
            ),
        )
        assertFalse(
            ShellSwipePolicy.startsInSystemBackEdge(
                xPx = 100f,
                widthPx = 1000f,
                leftInsetPx = 48f,
                rightInsetPx = 0f,
            ),
        )
        assertTrue(
            ShellSwipePolicy.startsInSystemBackEdge(
                xPx = 953f,
                widthPx = 1000f,
                leftInsetPx = 0f,
                rightInsetPx = 48f,
            ),
        )
        assertFalse(
            "no system gesture zone means no exclusion",
            ShellSwipePolicy.startsInSystemBackEdge(
                xPx = 1f,
                widthPx = 1000f,
                leftInsetPx = 0f,
                rightInsetPx = 0f,
            ),
        )
    }

    @Test
    fun swipeMovesExactlyOneTabAndNeverWraps() {
        val tabs = ShellTab.entries
        assertEquals(ShellTab.Ventoy, ShellSwipePolicy.target(ShellTab.Files, ShellSwipeDirection.Forward))
        assertEquals(
            ShellTab.Partitions,
            ShellSwipePolicy.target(ShellTab.Ventoy, ShellSwipeDirection.Forward),
        )
        assertEquals(
            ShellTab.Files,
            ShellSwipePolicy.target(ShellTab.Ventoy, ShellSwipeDirection.Backward),
        )
        assertNull("first tab has no previous", ShellSwipePolicy.target(ShellTab.Files, ShellSwipeDirection.Backward))
        assertNull("last tab has no next", ShellSwipePolicy.target(ShellTab.Diagnostics, ShellSwipeDirection.Forward))
        assertNull("unknown tab", ShellSwipePolicy.target(ShellTab.Files, ShellSwipeDirection.Forward, tabs - ShellTab.Files))
    }

    @Test
    fun tapOnClickableConsumesDownAndUpWithoutSwipe() {
        val recognizer = ShellSwipeRecognizer(thresholdPx = 72f, isRtl = false)
        // Compose clickable consumes its down; that alone must not abort the shell.
        recognizer.down(x = 200f, y = 300f, consumed = true)
        assertFalse(recognizer.move(202f, 301f, consumed = false))
        // A tap ends with the clickable consuming the up and handling its own action.
        assertFalse(recognizer.move(205f, 303f, consumed = true))
        assertTrue(recognizer.isAborted)
        assertNull("short tap must not switch tabs", recognizer.up(consumed = true))
    }

    @Test
    fun clickableRowSwipeQualifiesDespiteConsumedDown() {
        val recognizer = ShellSwipeRecognizer(thresholdPx = 72f, isRtl = false)
        // Full-width clickable rows consume the down before the shell sees it.
        recognizer.down(x = 300f, y = 200f, consumed = true)
        // Moves are not consumed by a clickable before its up.
        assertFalse(recognizer.move(280f, 200f, consumed = false))
        // Crossing the threshold consumes the change: the row's tap detector is cancelled.
        assertTrue(recognizer.move(180f, 205f, consumed = false))
        assertTrue(recognizer.move(170f, 205f, consumed = false))
        assertEquals(ShellSwipeDirection.Forward, recognizer.up(consumed = false))
    }

    @Test
    fun consumedUpAtTheEndCancelsTheCommittedSwipe() {
        val recognizer = ShellSwipeRecognizer(thresholdPx = 72f, isRtl = false)
        recognizer.down(x = 300f, y = 200f)
        assertTrue(recognizer.move(180f, 200f, consumed = false))
        // Another handler claimed the terminal up (system cancellation): no tab change.
        assertNull(recognizer.up(consumed = true))
    }

    @Test
    fun childDragWinsBeforeTheSwipeQualifies() {
        // RangeSlider and horizontalScroll/vertical lists consume their moves, not the down.
        val recognizer = ShellSwipeRecognizer(thresholdPx = 72f, isRtl = false)
        recognizer.down(x = 300f, y = 200f, consumed = true)
        assertFalse(recognizer.move(290f, 200f, consumed = true))
        assertTrue(recognizer.isAborted)
        assertNull(recognizer.up())
    }

    @Test
    fun childVerticalDragWinsBeforeTheSwipeQualifies() {
        val recognizer = ShellSwipeRecognizer(thresholdPx = 72f, isRtl = false)
        recognizer.down(x = 300f, y = 200f)
        assertFalse(recognizer.move(300f, 260f, consumed = true))
        assertTrue(recognizer.isAborted)
        assertNull(recognizer.up())
    }

    @Test
    fun cancellationAfterQualificationDispatchesNothing() {
        val recognizer = ShellSwipeRecognizer(thresholdPx = 72f, isRtl = false)
        recognizer.down(x = 300f, y = 200f)
        assertTrue(recognizer.move(200f, 200f, consumed = false))
        recognizer.cancel()
        assertNull("system back / ACTION_CANCEL must not switch tabs", recognizer.up())
    }

    @Test
    fun openPickerKeepsItsContentWhenTheStickDisappears() {
        assertTrue(
            "the picker owns Back/cancel even without a listed volume",
            shellTabContentVisible(ShellTab.Files, DevicePresence.Absent, pickOpen = true),
        )
        assertFalse(shellTabContentVisible(ShellTab.Files, DevicePresence.Absent, pickOpen = false))
        assertFalse(
            shellTabContentVisible(ShellTab.Partitions, DevicePresence.Disconnected, pickOpen = false),
        )
        assertTrue(shellTabContentVisible(ShellTab.Ventoy, DevicePresence.Absent, pickOpen = false))
    }

    @Test
    fun progressLineShowsOnlyMeasuredFacts() {
        assertEquals(
            "42% · 12.3 MiB/s · 3 min",
            operationProgressFacts("42%", "12.3 MiB/s", "3 min"),
        )
        assertEquals("42% · 3 min", operationProgressFacts("42%", null, "3 min"))
        assertEquals("42% · 12.3 MiB/s", operationProgressFacts("42%", "12.3 MiB/s", null))
        assertEquals("", operationProgressFacts(null, null, null))
    }
}
