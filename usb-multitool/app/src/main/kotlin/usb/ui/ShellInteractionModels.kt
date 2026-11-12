package usb.ui

import kotlin.math.abs

/**
 * Shell-owned presentation state: how the status block and the two log surfaces are shown.
 *
 * It is deliberately separate from [AppUiState]: collapsing the status or closing the log
 * screen never touches the running operation, its progress, cancel action or readback
 * verdict. Only the tab lives in the reducer, because only the reducer may change it.
 *
 * Nesting follows the approved answer: the journal arrow sits on the status title line, and
 * collapsing the status hides the journal with it while keeping its expanded flag, so the
 * arrow restores both. The full journal screen is a third, independent layer.
 */
internal data class ShellPanelState(
    /** Status body is folded into the thin line attached to the navigation bar. */
    val statusCollapsed: Boolean = false,
    /** Journal list is expanded inside the status block. */
    val journalExpanded: Boolean = false,
    /** Opaque full journal layer covers the tabs. */
    val journalScreenOpen: Boolean = false,
) {
    /** Cross button: fold the status, keep every value it shows. Idempotent. */
    fun collapseStatus(): ShellPanelState =
        if (statusCollapsed) this else copy(statusCollapsed = true)

    /**
     * Up/down arrow on the status title line. While folded it unfolds the status and opens
     * the journal in one step; while unfolded it only toggles the journal list.
     */
    fun toggleJournal(): ShellPanelState =
        if (statusCollapsed) {
            copy(statusCollapsed = false, journalExpanded = true)
        } else {
            copy(journalExpanded = !journalExpanded)
        }

    fun openJournalScreen(): ShellPanelState = copy(journalScreenOpen = true)

    fun closeJournalScreen(): ShellPanelState = copy(journalScreenOpen = false)

    /** Clearing the log cannot leave an expanded journal over an empty list. */
    fun journalCleared(): ShellPanelState =
        if (journalExpanded) copy(journalExpanded = false) else this
}

/** Direction of a committed horizontal tab swipe, in reading order. */
internal enum class ShellSwipeDirection {
    /** Next tab in reading order: finger left in LTR, finger right in RTL. */
    Forward,

    /** Previous tab in reading order. */
    Backward,
}

internal object ShellSwipePolicy {
    /** Minimum horizontal travel for a deliberate tab change, not a nudge. */
    const val THRESHOLD_DP = 72f

    /**
     * A committed swipe must be horizontal: moving farther sideways than vertically and past
     * [thresholdPx]. A short or mostly vertical drag stays with whatever the tab shows.
     * [isRtl] flips which physical direction means "next tab", so the gesture matches the
     * order the navigation bar renders.
     */
    fun direction(
        totalX: Float,
        totalY: Float,
        thresholdPx: Float,
        isRtl: Boolean = false,
    ): ShellSwipeDirection? {
        if (thresholdPx <= 0f) return null
        if (abs(totalX) < thresholdPx) return null
        if (abs(totalX) <= abs(totalY)) return null
        val towardNext = if (isRtl) totalX > 0f else totalX < 0f
        return if (towardNext) ShellSwipeDirection.Forward else ShellSwipeDirection.Backward
    }

    /**
     * A gesture that starts inside the system back-gesture zone belongs to the system:
     * reacting to it would change tabs while Back is also being performed. Insets are
     * physical pixels; a zero inset means that side has no system gesture zone.
     */
    fun startsInSystemBackEdge(
        xPx: Float,
        widthPx: Float,
        leftInsetPx: Float,
        rightInsetPx: Float,
    ): Boolean {
        if (widthPx <= 0f) return false
        if (leftInsetPx > 0f && xPx < leftInsetPx) return true
        return rightInsetPx > 0f && xPx > widthPx - rightInsetPx
    }

    /** Adjacent tab in [tabs] order; null at either end, so the edge never wraps. */
    fun target(
        current: ShellTab,
        direction: ShellSwipeDirection,
        tabs: List<ShellTab> = ShellTab.entries,
    ): ShellTab? {
        val index = tabs.indexOf(current)
        if (index < 0) return null
        val next = when (direction) {
            ShellSwipeDirection.Forward -> index + 1
            ShellSwipeDirection.Backward -> index - 1
        }
        return tabs.getOrNull(next)
    }
}

/**
 * Pure arbitration between the shell swipe and whatever the tab shows.
 *
 * Watch order matters: children get the Main pass first, so a consumed change means a
 * range slider, a horizontally scrolled row or a vertical list owns the gesture and the
 * shell abandons it. Once the shell qualifies its own horizontal drag it consumes every
 * further change, which cancels a clickable row's tap instead of opening it; the tap
 * detector is therefore gone by the time the up arrives. A terminal up still claimed by
 * another handler is treated as a cancellation, never as a second action.
 */
internal class ShellSwipeRecognizer(
    private val thresholdPx: Float,
    private val isRtl: Boolean,
) {
    private enum class State { Idle, Tracking, Qualified, Aborted, Finished }

    private var state = State.Idle
    private var lastX = 0f
    private var lastY = 0f
    private var totalX = 0f
    private var totalY = 0f

    /** True once the gesture ended, either cancelled or completed; stop feeding events. */
    val isFinished: Boolean
        get() = state == State.Aborted || state == State.Finished

    val isAborted: Boolean
        get() = state == State.Aborted

    /**
     * Starts a gesture. [consumed] is accepted for call-site fidelity but deliberately
     * ignored: Compose `clickable` consumes every down, so down consumption says nothing
     * about drag ownership; only a consumed move does.
     */
    fun down(x: Float, y: Float, consumed: Boolean = false) {
        totalX = 0f
        totalY = 0f
        lastX = x
        lastY = y
        state = State.Tracking
    }

    /**
     * Feeds one pointer change. Returns true when the caller must consume it: once the
     * swipe is qualified that cancellation is what stops a clickable row from firing.
     */
    fun move(x: Float, y: Float, consumed: Boolean): Boolean {
        when (state) {
            State.Tracking -> {
                if (consumed) {
                    // A child took the gesture; its priority is absolute.
                    state = State.Aborted
                    return false
                }
                totalX += x - lastX
                totalY += y - lastY
                lastX = x
                lastY = y
                if (ShellSwipePolicy.direction(totalX, totalY, thresholdPx, isRtl) == null) {
                    return false
                }
                state = State.Qualified
                return true
            }
            State.Qualified -> {
                // Keep tracking distance and keep consuming: a clickable up arriving now
                // must not cancel the committed swipe.
                totalX += x - lastX
                totalY += y - lastY
                lastX = x
                lastY = y
                return true
            }
            State.Idle, State.Aborted, State.Finished -> return false
        }
    }

    /**
     * Ends a normal pointer sequence. [consumed] marks a terminal up that another handler
     * claimed (for example a system cancellation): dispatching then would act twice, so it
     * cancels instead. After qualification the shell consumes its moves, which cancels a
     * clickable tap before it can consume the up.
     */
    fun up(consumed: Boolean = false): ShellSwipeDirection? {
        val direction = if (state == State.Qualified && !consumed) {
            ShellSwipePolicy.direction(totalX, totalY, thresholdPx, isRtl)
        } else {
            null
        }
        state = State.Finished
        return direction
    }

    /** System back gesture or another cancellation: never dispatch a tab change. */
    fun cancel() {
        state = State.Finished
    }
}
