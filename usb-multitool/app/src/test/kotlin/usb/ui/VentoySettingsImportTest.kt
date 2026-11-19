package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoySettingsSource

/**
 * The phone settings import: a SAF document is picked in MainActivity and handed to the
 * reducer as [UiIntent.SelectVentoySettingsDocument]. Reading it must not depend on the
 * stick's partition table (the document is read from the phone, the stick is only written on
 * Apply), and the read answer must reach [AppUiState.ventoyGlobalSettings].
 */
class VentoySettingsImportTest {
    private val stick = DeviceIdentity(
        label = "Ventoy Stick",
        capacityBytes = 32_000_000_000uL,
        serialNumber = "SN-1",
        attachGeneration = 4L,
    )

    private fun ready(): AppUiState = AppUiState(
        presence = DevicePresence.Ready,
        device = stick,
    )

    private val picked = VentoySettingsSource.Document(
        uri = "content://com.android.providers.downloads.documents/document/42",
        displayName = "ventoy.json",
    )

    @Test
    fun aPhoneDocumentIsReadEvenBeforeTheDataPartitionIsProbed() {
        val reduced = UiReducer.reduce(
            ready(),
            UiIntent.SelectVentoySettingsDocument(uri = picked.uri, displayName = picked.displayName),
        )

        assertEquals(picked, reduced.state.ventoySettingsSource)
        assertTrue(reduced.state.ventoySettingsSourceLoading)
        // Nothing to apply to yet is not a reason to discard the picked file.
        assertNull(reduced.state.statusMessage)
        assertEquals(
            listOf(UiEffect.LoadVentoyGlobalSettings(requestToken = 1L, source = picked)),
            reduced.effects,
        )
    }

    @Test
    fun theSticksOwnSettingsFileStillNeedsTheDataPartition() {
        // The relaxation above is document-only: a stick path cannot be read at all when
        // partition 1 is unknown.
        val reduced = UiReducer.reduce(ready(), UiIntent.UseDefaultVentoySettingsFile)

        assertEquals(
            UiMessage.Key(UiMessageKey.VentoyDataPartitionMissing),
            reduced.state.statusMessage,
        )
        assertTrue(reduced.effects.isEmpty())
        assertEquals(VentoySettingsSource.Default, reduced.state.ventoySettingsSource)
    }

    @Test
    fun theReadOfThePickedDocumentBecomesTheVisibleSettings() {
        val selected = UiReducer.reduce(
            ready(),
            UiIntent.SelectVentoySettingsDocument(uri = picked.uri, displayName = picked.displayName),
        ).state
        val answered = picked.copy(snapshotSha256 = "a".repeat(64))

        val landed = UiReducer.reduce(
            selected,
            UiIntent.VentoyGlobalSettingsLoaded(
                settings = VentoyGlobalSettings.Present(emptyList()),
                epoch = selected.ventoyCacheEpoch,
                requestToken = selected.ventoyGlobalSettingsRequest,
                source = answered,
            ),
        )

        assertEquals(VentoyGlobalSettings.Present(emptyList()), landed.state.ventoyGlobalSettings)
        assertEquals(answered, landed.state.ventoySettingsSource)
        assertFalse(landed.state.ventoySettingsSourceLoading)
    }

    @Test
    fun anImportedDocumentIsWritableWithoutStagedEdits() {
        val selected = UiReducer.reduce(
            ready(),
            UiIntent.SelectVentoySettingsDocument(uri = picked.uri, displayName = picked.displayName),
        ).state
        val loaded = UiReducer.reduce(
            selected,
            UiIntent.VentoyGlobalSettingsLoaded(
                settings = VentoyGlobalSettings.Present(emptyList()),
                epoch = selected.ventoyCacheEpoch,
                requestToken = selected.ventoyGlobalSettingsRequest,
                source = picked,
            ),
        ).state

        val apply = UiReducer.reduce(loaded, UiIntent.RequestApplyVentoyControls)

        val effect = apply.effects.single() as UiEffect.ApplyVentoyControls
        assertEquals(picked, effect.source)
        // The write is pinned to the attachment that accepted the pick.
        assertEquals(stick.identity, effect.identity)
        assertEquals(stick.attachGeneration, effect.attachGeneration)
    }

    @Test
    fun aFailedDocumentReadIsVisibleAsAnErrorInsteadOfSilence() {
        val selected = UiReducer.reduce(
            ready(),
            UiIntent.SelectVentoySettingsDocument(uri = picked.uri, displayName = picked.displayName),
        ).state
        val failure = VentoyGlobalSettings.ReadError(
            message = "no permission to read the selected settings document",
        )

        val landed = UiReducer.reduce(
            selected,
            UiIntent.VentoyGlobalSettingsLoaded(
                settings = failure,
                epoch = selected.ventoyCacheEpoch,
                requestToken = selected.ventoyGlobalSettingsRequest,
                source = picked,
            ),
        )

        assertEquals(failure, landed.state.ventoyGlobalSettings)
        assertFalse(landed.state.ventoySettingsSourceLoading)
        assertEquals(UiMessage.Raw(failure.message), landed.state.statusMessage)
    }

    @Test
    fun aReadAnswerForAnotherDocumentIsDroppedInsteadOfShown() {
        val selected = UiReducer.reduce(
            ready(),
            UiIntent.SelectVentoySettingsDocument(uri = picked.uri, displayName = picked.displayName),
        ).state

        val other = VentoySettingsSource.Document(uri = "content://other/document/7")
        val landed = UiReducer.reduce(
            selected,
            UiIntent.VentoyGlobalSettingsLoaded(
                settings = VentoyGlobalSettings.Present(emptyList()),
                epoch = selected.ventoyCacheEpoch,
                requestToken = selected.ventoyGlobalSettingsRequest,
                source = other,
            ),
        )

        assertNull(landed.state.ventoyGlobalSettings)
        assertEquals(picked, landed.state.ventoySettingsSource)
        assertTrue(landed.state.ventoySettingsSourceLoading)
    }

    @Test
    fun aPickWhileUsbWorkIsNotAllowedIsRefusedVisiblyAndChangesNothing() {
        val busy = ready().copy(presence = DevicePresence.Busy)

        val reduced = UiReducer.reduce(
            busy,
            UiIntent.SelectVentoySettingsDocument(uri = picked.uri, displayName = picked.displayName),
        )

        assertNotNull(reduced.state.statusMessage)
        assertTrue(reduced.effects.isEmpty())
        assertEquals(VentoySettingsSource.Default, reduced.state.ventoySettingsSource)
        assertFalse(reduced.state.ventoySettingsSourceLoading)
    }
}
