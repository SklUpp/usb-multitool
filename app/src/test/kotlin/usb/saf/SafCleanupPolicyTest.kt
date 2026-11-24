package usb.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafCleanupPolicyTest {
    data class EmptyDestinationCase(
        val desc: String,
        val offset: Long,
        val reportedSize: Long?,
        val hasData: Boolean?,
        val expectedEmpty: Boolean,
    )

    @Test
    fun isEmptyDestinationTableDriven() {
        val cases = listOf(
            EmptyDestinationCase(
                desc = "zero offset is always empty",
                offset = 0L,
                reportedSize = null,
                hasData = null,
                expectedEmpty = true,
            ),
            EmptyDestinationCase(
                desc = "zero offset is empty even if reportedSize non-zero",
                offset = 0L,
                reportedSize = 100L,
                hasData = true,
                expectedEmpty = true,
            ),
            EmptyDestinationCase(
                desc = "positive offset, zero reported size and confirmed no data",
                offset = 50L,
                reportedSize = 0L,
                hasData = false,
                expectedEmpty = true,
            ),
            EmptyDestinationCase(
                desc = "positive offset, zero reported size but has data",
                offset = 50L,
                reportedSize = 0L,
                hasData = true,
                expectedEmpty = false,
            ),
            EmptyDestinationCase(
                desc = "positive offset, zero reported size but data check failed",
                offset = 50L,
                reportedSize = 0L,
                hasData = null,
                expectedEmpty = false,
            ),
            EmptyDestinationCase(
                desc = "positive offset, positive reported size",
                offset = 50L,
                reportedSize = 50L,
                hasData = true,
                expectedEmpty = false,
            ),
            EmptyDestinationCase(
                desc = "positive offset, unknown reported size",
                offset = 50L,
                reportedSize = null,
                hasData = false,
                expectedEmpty = false,
            ),
        )

        for (case in cases) {
            val actual = SafCleanupPolicy.isEmptyDestination(
                offset = case.offset,
                reportedSize = case.reportedSize,
                hasData = case.hasData,
            )
            assertEquals("Failed case: ${case.desc}", case.expectedEmpty, actual)
        }
    }

    @Test
    fun emptyAndCreatedNotesStayDistinct() {
        assertEquals("empty destination deleted", SafCleanupPolicy.emptyDestinationNote(true))
        assertEquals("destination retained", SafCleanupPolicy.emptyDestinationNote(false))
        assertEquals("partial destination deleted", SafCleanupPolicy.createdDestinationNote(true))
        assertEquals("destination retained", SafCleanupPolicy.createdDestinationNote(false))
    }

    @Test
    fun createdOnlyPolicyIgnoresPreexistingTargets() {
        assertTrue(SafCleanupPolicy.shouldDeleteCreated(true))
        assertFalse(SafCleanupPolicy.shouldDeleteCreated(false))
    }
}
