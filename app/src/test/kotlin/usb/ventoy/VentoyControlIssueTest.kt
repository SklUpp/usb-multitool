package usb.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Localized UI needs the typed issue, not the English sentence: the key, limit, expected
 * and received values must survive unchanged. The English text stays the stable log form.
 */
class VentoyControlIssueTest {
    @Test
    fun keyIssueKeepsEmptyLengthAndCharsetDistinct() {
        assertEquals(VentoyControlCatalog.Issue.EmptyKey, VentoyControlCatalog.keyIssue(" "))
        assertEquals(
            VentoyControlCatalog.Issue.KeyTooLong(VentoyControlCatalog.MAX_KEY_LENGTH),
            VentoyControlCatalog.keyIssue("K".repeat(VentoyControlCatalog.MAX_KEY_LENGTH + 1)),
        )
        assertEquals(VentoyControlCatalog.Issue.KeyCharset, VentoyControlCatalog.keyIssue("bad key"))
        assertNull(VentoyControlCatalog.keyIssue("CUSTOM_KEY"))
    }

    @Test
    fun valueIssueKeepsKeyExpectedAndActual() {
        assertEquals(
            VentoyControlCatalog.Issue.ValueNotAllowed(
                "VTOY_WIN11_BYPASS_CHECK",
                "0 or 1",
                "2",
            ),
            VentoyControlCatalog.valueIssue("VTOY_WIN11_BYPASS_CHECK", "2"),
        )
        assertEquals(
            VentoyControlCatalog.Issue.ValueTooLong(VentoyControlCatalog.MAX_VALUE_LENGTH),
            VentoyControlCatalog.valueIssue(
                "CUSTOM_KEY",
                "x".repeat(VentoyControlCatalog.MAX_VALUE_LENGTH + 1),
            ),
        )
        assertEquals(
            VentoyControlCatalog.Issue.ValueControlChars,
            VentoyControlCatalog.valueIssue("CUSTOM_KEY", "a\u0001b"),
        )
        assertNull(VentoyControlCatalog.valueIssue("CUSTOM_KEY", "kept"))
        assertNull("a cleared value is a removal", VentoyControlCatalog.valueIssue("CUSTOM_KEY", null))
    }

    @Test
    fun duplicateIssueNamesTheKeyAndEnglishTextIsStable() {
        val settings =
            listOf(
                VentoyControlSetting("A", "1"),
                VentoyControlSetting("A", "2"),
            )
        assertEquals(VentoyControlCatalog.Issue.DuplicateKey("A"), VentoyControlCatalog.issue(settings))
        assertEquals("duplicate key \"A\"", VentoyControlCatalog.problem(settings))
        assertEquals("key is empty", VentoyControlCatalog.keyProblem(""))
        assertEquals(
            "key is longer than ${VentoyControlCatalog.MAX_KEY_LENGTH} characters",
            VentoyControlCatalog.keyProblem("K".repeat(VentoyControlCatalog.MAX_KEY_LENGTH + 1)),
        )
    }
}
