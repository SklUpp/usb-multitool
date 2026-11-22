package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutounattendRawWriteUiTest {
    private val device =
        DeviceIdentity(label = "Kingston DataTraveler", capacityBytes = 15_728_640_000uL)

    private val partitions =
        listOf(
            PartitionTab("1", "VENTOY", readOnly = false, startLba = 0uL, sectorCount = 2048uL),
        )

    private fun ready(): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = partitions,
            selectedPartitionId = "1",
            vtoyBypassChecks = true,
        )

    private fun parsedDoc(
        sourcePath: String = "/ventoy/autounattend.xml",
        sha: String? = "abc123",
    ) = usb.ventoy.AutounattendDocument(
        existing = null,
        unknownElements = emptyList(),
        boundImages = emptyList(),
        sourcePath = sourcePath,
        templateSha256 = sha,
        sourceText = "<unattend xmlns=\"urn:schemas-microsoft-com:unattend\"/>",
        sourceCharsetName = "UTF-8",
    )

    private fun rawBytes(): ByteArray =
        """
        <unattend xmlns="urn:schemas-microsoft-com:unattend">
          <!-- keep-me -->
        </unattend>
        """.trimIndent().toByteArray()

    @Test
    fun dismissClearsPendingRawWriteWithoutEffect() {
        val asked =
            UiReducer.reduce(
                ready().copy(autounattendState = usb.ventoy.AutounattendState.Parsed(parsedDoc())),
                UiIntent.RequestWriteAutounattendRaw(
                    xmlName = "autounattend.xml",
                    rawBytes = rawBytes(),
                    overwriteXml = true,
                    expectedTemplateSha256 = "abc123",
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                ),
            )
        assertTrue(asked.effects.isEmpty())
        assertNotNull(asked.state.pendingAutounattendRawWrite)
        assertEquals(
            DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
            asked.state.pendingConfirmation!!.action,
        )

        val dismissed = UiReducer.reduce(asked.state, UiIntent.DismissConfirmation)
        assertTrue(dismissed.effects.none { it is UiEffect.WriteAutounattendRaw })
        assertNull(dismissed.state.pendingConfirmation)
        assertNull(dismissed.state.pendingAutounattendRawWrite)
    }

    @Test
    fun overwriteConfirmEmitsWriteAutounattendRaw() {
        val bytes = rawBytes()
        val asked =
            UiReducer.reduce(
                ready().copy(autounattendState = usb.ventoy.AutounattendState.Parsed(parsedDoc())),
                UiIntent.RequestWriteAutounattendRaw(
                    xmlName = "autounattend.xml",
                    rawBytes = bytes,
                    overwriteXml = true,
                    allowDestructiveTemplate = false,
                    expectedTemplateSha256 = "abc123",
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                ),
            )
        assertTrue(asked.effects.isEmpty())
        assertNull(asked.state.pendingAutounattendGeneration)

        val confirmed = UiReducer.reduce(asked.state, UiIntent.ConfirmDestructive)
        val effect = confirmed.effects.single() as UiEffect.WriteAutounattendRaw
        assertEquals("autounattend.xml", effect.xmlName)
        assertTrue(effect.rawBytes.contentEquals(bytes))
        assertTrue(effect.overwriteXml)
        assertEquals("abc123", effect.expectedTemplateSha256)
        assertNull(confirmed.state.pendingAutounattendRawWrite)
        assertNull(confirmed.state.pendingConfirmation)
    }

    @Test
    fun staleIdentityYieldsTargetChanged() {
        val refused =
            UiReducer.reduce(
                ready().copy(autounattendState = usb.ventoy.AutounattendState.Parsed(parsedDoc())),
                UiIntent.RequestWriteAutounattendRaw(
                    xmlName = "autounattend.xml",
                    rawBytes = rawBytes(),
                    overwriteXml = true,
                    expectedTemplateSha256 = "abc123",
                    identity = "other-stick",
                    attachGeneration = device.attachGeneration,
                ),
            )
        assertTrue(refused.effects.isEmpty())
        assertNull(refused.state.pendingAutounattendRawWrite)
        assertNull(refused.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.TargetChanged), refused.state.statusMessage)
    }

    @Test
    fun cancelPathIsReducerOnlyNoWriteEffect() {
        // Request then Dismiss never emits WriteAutounattendRaw.
        val asked =
            UiReducer.reduce(
                ready().copy(autounattendState = usb.ventoy.AutounattendState.Parsed(parsedDoc())),
                UiIntent.RequestWriteAutounattendRaw(
                    xmlName = "autounattend.xml",
                    rawBytes = rawBytes(),
                    overwriteXml = true,
                    expectedTemplateSha256 = "abc123",
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                ),
            )
        val dismissed = UiReducer.reduce(asked.state, UiIntent.DismissConfirmation)
        assertTrue(dismissed.effects.none { it is UiEffect.WriteAutounattendRaw })
        assertTrue(asked.effects.none { it is UiEffect.WriteAutounattendRaw })
    }

    @Test
    fun generationOverwriteClearsStaleRawPending() {
        val asked =
            UiReducer.reduce(
                ready().copy(autounattendState = usb.ventoy.AutounattendState.Parsed(parsedDoc())),
                UiIntent.RequestWriteAutounattendRaw(
                    xmlName = "autounattend.xml",
                    rawBytes = rawBytes(),
                    overwriteXml = true,
                    expectedTemplateSha256 = "abc123",
                    identity = device.identity,
                    attachGeneration = device.attachGeneration,
                ),
            )
        assertNotNull(asked.state.pendingAutounattendRawWrite)
        // Confirmation blocks actionsEnabled; keep stale raw pending with confirmation cleared.
        val stale =
            asked.state.copy(
                pendingConfirmation = null,
                pendingAutounattendRawWrite = asked.state.pendingAutounattendRawWrite,
            )

        val generateAsked =
            UiReducer.reduce(
                stale,
                UiIntent.RequestGenerateAutounattend(
                    xmlName = "autounattend.xml",
                    params = usb.ventoy.AutounattendParams(languageTag = "en-US"),
                    overwriteXml = true,
                    expectedTemplateSha256 = "abc123",
                ),
            )
        assertNull(generateAsked.state.pendingAutounattendRawWrite)
        assertNotNull(generateAsked.state.pendingAutounattendGeneration)

        val confirmed = UiReducer.reduce(generateAsked.state, UiIntent.ConfirmDestructive)
        assertTrue(confirmed.effects.single() is UiEffect.GenerateAutounattend)
        assertTrue(confirmed.effects.none { it is UiEffect.WriteAutounattendRaw })
    }

}
