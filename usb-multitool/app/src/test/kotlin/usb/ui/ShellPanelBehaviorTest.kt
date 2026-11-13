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
    fun collapseFoldsTheStatusAndKeepsEveryOtherFlag() {
        val open = ShellPanelState(journalExpanded = true, journalScreenOpen = true)
        val folded = open.collapseStatus()
        assertTrue(folded.statusCollapsed)
        // The journal flags survive the fold; the panel only stops drawing them.
        assertTrue(folded.journalExpanded)
        assertTrue(folded.journalScreenOpen)
        // Folding twice changes nothing.
        assertEquals(folded, folded.collapseStatus())
    }

    @Test
    fun arrowTogglesOnlyTheJournalWhileTheStatusIsUnfolded() {
        val rest = ShellPanelState()
        val expanded = rest.toggleJournal()
        assertTrue(expanded.journalExpanded)
        assertFalse(expanded.statusCollapsed)
        assertEquals(rest, expanded.toggleJournal())
    }

    @Test
    fun arrowTogglesJournalWithoutUnfoldingStatus() {
        val folded = ShellPanelState(statusCollapsed = true)
        val opened = folded.toggleJournal()
        assertTrue(opened.statusCollapsed)
        assertTrue(opened.journalExpanded)
        assertEquals(folded, opened.toggleJournal())
    }

    @Test
    fun fullJournalLayerIsIndependentOfTheStatusAndInlineJournal() {
        val collapsed = ShellPanelState(statusCollapsed = true, journalExpanded = true)
        val opened = collapsed.openJournalScreen()
        assertTrue(opened.journalScreenOpen)
        assertTrue(opened.statusCollapsed)
        assertTrue(opened.journalExpanded)

        val closed = opened.closeJournalScreen()
        assertFalse(closed.journalScreenOpen)
        // Closing the layer leaves the tab where it was and the status folded.
        assertTrue(closed.statusCollapsed)
        assertTrue(closed.journalExpanded)
    }

    @Test
    fun clearingTheLogClosesOnlyAnExpandedInlineJournal() {
        val expanded = ShellPanelState(journalExpanded = true)
        assertFalse(expanded.journalCleared().journalExpanded)

        val folded = ShellPanelState(statusCollapsed = true, journalExpanded = true)
        assertEquals(
            ShellPanelState(statusCollapsed = true),
            folded.journalCleared(),
        )

        val untouched = ShellPanelState()
        assertEquals(untouched, untouched.journalCleared())
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
}
