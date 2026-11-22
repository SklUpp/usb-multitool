package usb.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShellTabPersistenceTest {
    @Test
    fun encodeDecodeRoundTripsEveryTab() {
        for (tab in ShellTab.entries) {
            assertEquals(tab, ShellTabPersistence.decode(ShellTabPersistence.encode(tab)))
        }
    }

    @Test
    fun unknownOrBlankNamesAreRejected() {
        assertNull(ShellTabPersistence.decode(null))
        assertNull(ShellTabPersistence.decode(""))
        assertNull(ShellTabPersistence.decode("NotATab"))
        assertNull(ShellTabPersistence.decode("files"))
    }

    @Test
    fun savedStateHandleStoresOnlyTabName() {
        val handle = SavedStateHandle()
        ShellTabPersistence.write(handle, ShellTab.Diagnostics)
        assertEquals(ShellTab.Diagnostics, ShellTabPersistence.read(handle))
        assertEquals(setOf(ShellTabPersistence.KEY), handle.keys())
        assertEquals("Diagnostics", handle.get<String>(ShellTabPersistence.KEY))
    }

    @Test
    fun corruptSavedValueReadsAsAbsent() {
        val handle = SavedStateHandle(mapOf(ShellTabPersistence.KEY to "legacy-tab"))
        assertNull(ShellTabPersistence.read(handle))
    }

    @Test
    fun wrongTypeSavedValueReadsAsAbsent() {
        val handle = SavedStateHandle(mapOf(ShellTabPersistence.KEY to 42))
        assertNull(ShellTabPersistence.read(handle))
    }
}
