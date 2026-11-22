package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.ventoy.UnreadableProblem

/**
 * Phone import keeps three promises the UI relies on: the inspection is bound to the live
 * attachment, an existing target is never replaced without an explicit confirmation, and a
 * rejected document surfaces a typed problem instead of a half-imported file.
 */
class PhoneImportUiTest {
    private val device = DeviceIdentity(label = "Test Stick", capacityBytes = 8_000_000_000uL)

    private fun ready(identity: DeviceIdentity = device) = AppUiState(
        presence = DevicePresence.Ready,
        device = identity,
        partitions = listOf(
            PartitionTab(
                id = "1",
                name = "Ventoy",
                readOnly = false,
                startLba = 2048uL,
                sectorCount = 4096uL,
                filesystemId = FilesystemId.FAT,
            ),
        ),
        selectedPartitionId = "1",
        diskSectorSize = 512,
    )

    private fun imagePick(): Pair<AppUiState, String> {
        val opened = UiReducer.reduce(
            ready(),
            UiIntent.BeginFilesPick(
                purpose = FilesPickPurpose.WindowsImage,
                returnTab = ShellTab.Ventoy,
                initialPath = "/ISO",
            ),
        ).state.copy(filesListingState = FilesListingState.Loaded)
        return opened to requireNotNull(opened.filesPickMode).sessionId
    }

    private fun requestImage(state: AppUiState, pickSessionId: String): AppUiState =
        UiReducer.reduce(
            state,
            UiIntent.RequestImportWindowsIso(
                uri = URI,
                kind = WindowsImportKind.Image,
                directory = "/ISO",
                pickSessionId = pickSessionId,
            ),
        ).state

    private fun inspected(
        exists: Boolean,
        generation: Long,
        kind: WindowsImportKind = WindowsImportKind.Image,
        markers: List<String> = emptyList(),
        pickSessionId: String? = null,
        partitionStartLba: ULong? = 2048uL,
        partitionSectorCount: ULong? = 4096uL,
    ) = UiIntent.WindowsIsoImportInspected(
        uri = URI,
        name = if (kind == WindowsImportKind.XmlTemplate) "autounattend.xml" else "win11.iso",
        sizeBytes = 1024L,
        targetName = if (kind == WindowsImportKind.XmlTemplate) "autounattend.xml" else "win11.iso",
        exists = exists,
        generation = generation,
        kind = kind,
        templateMarkers = markers,
        directory = if (kind == WindowsImportKind.XmlTemplate) "/ventoy" else "/ISO",
        pickSessionId = pickSessionId,
        partitionStartLba = partitionStartLba,
        partitionSectorCount = partitionSectorCount,
    )

    @Test
    fun requestInspectsWithTheChosenFolderAndKind() {
        val result = UiReducer.reduce(
            ready(),
            UiIntent.RequestImportWindowsIso(URI, WindowsImportKind.XmlTemplate, "/ventoy"),
        )

        assertNull(result.state.pendingIsoImport)
        val effect = result.effects.single() as UiEffect.InspectIsoImport
        assertEquals(URI, effect.uri)
        assertEquals(WindowsImportKind.XmlTemplate, effect.importKind)
        assertEquals("/ventoy", effect.importDirectory)
        assertEquals(1L, effect.requestGeneration)
        assertEquals(1L, result.state.isoImportGeneration)
    }

    @Test
    fun staleXmlPickerContextAfterReplugIsRejected() {
        val base = ready()
        val result =
            UiReducer.reduce(
                base,
                UiIntent.RequestImportWindowsIso(
                    uri = URI,
                    kind = WindowsImportKind.XmlTemplate,
                    directory = "/ventoy",
                    expectedIdentity = base.device?.identity,
                    expectedAttachGeneration =
                        (base.device?.attachGeneration ?: 0L) + 1L,
                ),
            )

        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
        assertNull(result.state.isoImportSafInFlight)
    }

    @Test
    fun xmlInspectionIsDisownedWhenTheAttachmentChanges() {
        val base =
            ready().copy(
                isoImportSafInFlight = 7L,
                isoImportUsbInFlight = 7L,
                filesPickMode = null,
            )
        val replacement =
            device.copy(
                label = "Other Stick",
                serialNumber = "OTHER-1",
                attachGeneration = device.attachGeneration + 1L,
            )
        val result =
            UiReducer.reduce(
                base,
                UiIntent.DeviceChanged(presence = DevicePresence.Ready, device = replacement),
            )

        assertTrue(result.effects.contains(UiEffect.CancelIsoImportInspection))
        assertNull(result.state.isoImportSafInFlight)
        // The SAF ticket is disowned, but the USB worker may still be closing the disk:
        // the guard must survive until the matching Finished.
        assertEquals(7L, result.state.isoImportUsbInFlight)

        val finished =
            UiReducer.reduce(result.state, UiIntent.WindowsIsoImportFinished(7L))
        assertNull(finished.state.isoImportUsbInFlight)
    }

    @Test
    fun imageImportWithoutActivePickIsRejected() {
        val result = UiReducer.reduce(ready(), UiIntent.RequestImportWindowsIso(URI))

        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
        assertNull(result.state.isoImportSafInFlight)
    }

    @Test
    fun existingTargetWaitsForConfirmationAndWritesNothing() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val result =
            UiReducer.reduce(
                requested,
                inspected(
                    exists = true,
                    generation = requested.isoImportGeneration,
                    pickSessionId = token,
                ),
            )

        assertTrue(result.effects.isEmpty())
        val proposal = requireNotNull(result.state.pendingIsoImport)
        assertEquals("/ISO/win11.iso", "${proposal.directory}/${proposal.targetName}")
        assertEquals(device.identity, proposal.identity)
    }

    @Test
    fun imageProposalKeepsPickSessionForHostFencing() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val result = UiReducer.reduce(
            requested,
            inspected(
                exists = false,
                generation = requested.isoImportGeneration,
                pickSessionId = token,
            ),
        )

        val effect = result.effects.single() as UiEffect.ImportIso
        assertEquals(token, effect.isoImport.pickSessionId)
    }

    @Test
    fun newTargetImportsWithoutConfirmation() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val result =
            UiReducer.reduce(
                requested,
                inspected(
                    exists = false,
                    generation = requested.isoImportGeneration,
                    pickSessionId = token,
                ),
            )

        assertNull(result.state.pendingIsoImport)
        val effect = result.effects.single() as UiEffect.ImportIso
        assertEquals(false, effect.overwrite)
        assertEquals("/ISO", effect.isoImport.directory)
    }

    @Test
    fun staleImageInspectionGenerationIsRejected() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val stale =
            UiReducer.reduce(
                requested,
                inspected(
                    exists = false,
                    generation = requested.isoImportGeneration - 1L,
                    pickSessionId = token,
                ),
            )

        assertNull(stale.state.pendingIsoImport)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun inspectProbeDisagreeingWithPickPinIsRejected() {
        // Stale UI may probe after a real disk change; the disagreement must fail
        // closed before any proposal or write, even though the session matches.
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val result = UiReducer.reduce(
            requested,
            inspected(
                exists = false,
                generation = requested.isoImportGeneration,
                pickSessionId = token,
                partitionStartLba = 999uL,
                partitionSectorCount = 4096uL,
            ),
        )

        assertNull(result.state.pendingIsoImport)
        // RequestImport parks an in-flight SAF read; the refusal must disown it so
        // a stuck provider cannot block a new import. USB-guard release still waits
        // for the host Finished after the disk close.
        assertEquals(
            listOf(UiEffect.CancelIsoImportInspection),
            result.effects,
        )
        assertEquals(requested.isoImportGeneration + 1, result.state.isoImportGeneration)
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun inspectProbeMatchingPickPinIsAccepted() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val result = UiReducer.reduce(
            requested,
            inspected(
                exists = false,
                generation = requested.isoImportGeneration,
                pickSessionId = token,
                partitionStartLba = 2048uL,
                partitionSectorCount = 4096uL,
            ),
        )

        val effect = result.effects.single() as UiEffect.ImportIso
        assertEquals(2048uL, effect.isoImport.partitionStartLba)
        assertEquals(4096uL, effect.isoImport.partitionSectorCount)
        assertEquals(token, effect.isoImport.pickSessionId)
    }

    @Test
    fun unconfirmedOverwriteNeverImports() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val pending =
            UiReducer.reduce(
                requested,
                inspected(
                    exists = true,
                    generation = requested.isoImportGeneration,
                    pickSessionId = token,
                ),
            )
        val result = UiReducer.reduce(pending.state, UiIntent.ConfirmWindowsIsoImport(false))

        assertTrue(result.effects.isEmpty())
        assertNull(result.state.pendingIsoImport)
    }

    @Test
    fun confirmedOverwriteImportsWithOverwriteFlag() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val pending =
            UiReducer.reduce(
                requested,
                inspected(
                    exists = true,
                    generation = requested.isoImportGeneration,
                    pickSessionId = token,
                ),
            )
        val result = UiReducer.reduce(pending.state, UiIntent.ConfirmWindowsIsoImport(true))

        assertNull(result.state.pendingIsoImport)
        val effect = result.effects.single() as UiEffect.ImportIso
        assertEquals(true, effect.overwrite)
        assertEquals("win11.iso", effect.isoImport.targetName)
    }

    @Test
    fun attachmentChangeDropsTheProposal() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val pending =
            UiReducer.reduce(
                requested,
                inspected(
                    exists = true,
                    generation = requested.isoImportGeneration,
                    pickSessionId = token,
                ),
            )
        val other = DeviceIdentity(label = "Other Stick", capacityBytes = 4_000_000_000uL)
        val swapped = pending.state.copy(device = other)
        val result = UiReducer.reduce(swapped, UiIntent.ConfirmWindowsIsoImport(true))

        assertTrue(result.effects.isEmpty())
        assertNull(result.state.pendingIsoImport)
    }

    @Test
    fun templateMarkersAreCarriedToTheConfirmation() {
        val requested = UiReducer.reduce(
            ready(),
            UiIntent.RequestImportWindowsIso(URI, WindowsImportKind.XmlTemplate, "/ventoy"),
        )
        val result = UiReducer.reduce(
            requested.state,
            inspected(
                exists = true,
                generation = 1L,
                kind = WindowsImportKind.XmlTemplate,
                markers = listOf("/unattend/settings/component/DiskConfiguration"),
            ),
        )

        val proposal = requireNotNull(result.state.pendingIsoImport)
        assertEquals(WindowsImportKind.XmlTemplate, proposal.kind)
        assertEquals(listOf("/unattend/settings/component/DiskConfiguration"), proposal.templateMarkers)
    }

    @Test
    fun folderDialogTemplateProposalCarriesInspectTimeGeometry() {
        // Folder dialog flows never create a Files pick; geometry comes from the host
        // probe at inspect time (WindowsIsoImportInspected.partitionStartLba).
        val requested = UiReducer.reduce(
            ready(),
            UiIntent.RequestImportWindowsIso(URI, WindowsImportKind.XmlTemplate, "/ventoy"),
        )
        val result = UiReducer.reduce(
            requested.state,
            inspected(
                exists = false,
                generation = 1L,
                kind = WindowsImportKind.XmlTemplate,
                partitionStartLba = 2048uL,
                partitionSectorCount = 4096uL,
            ),
        )
        val effect = result.effects.single() as UiEffect.ImportIso
        assertEquals(2048uL, effect.isoImport.partitionStartLba)
        assertEquals(4096uL, effect.isoImport.partitionSectorCount)
        assertEquals(device.identity, effect.isoImport.identity)
        assertEquals(device.attachGeneration, effect.isoImport.attachGeneration)
        assertNull(effect.isoImport.pickSessionId)
    }

    @Test
    fun staleInspectGeometryProposalRefusesImport() {
        val requested = UiReducer.reduce(
            ready(),
            UiIntent.RequestImportWindowsIso(URI, WindowsImportKind.XmlTemplate, "/ventoy"),
        )
        val pending = UiReducer.reduce(
            requested.state,
            inspected(
                exists = false,
                generation = 1L,
                kind = WindowsImportKind.XmlTemplate,
                partitionStartLba = 999uL,
                partitionSectorCount = 4096uL,
            ),
        )
        // Host ships the proposal pin into WindowsTemplateImported; the reducer must
        // refuse the stale geometry instead of reading another layout's bytes.
        val result = UiReducer.reduce(
            pending.state,
            UiIntent.WindowsTemplateImported(
                path = "/ventoy/autounattend.xml",
                identity = device.identity,
                attachGeneration = device.attachGeneration,
                partitionStartLba = 999uL,
                partitionSectorCount = 4096uL,
            ),
        )

        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun rejectedDocumentSurfacesTypedProblemAndKeepsNoProposal() {
        val (picked, token) = imagePick()
        val requested = requestImage(picked, token)
        val result = UiReducer.reduce(
            requested,
            UiIntent.WindowsIsoImportRejected(
                problem = UnreadableProblem.MalformedXml,
                generation = requested.isoImportGeneration,
            ),
        )

        assertEquals(UnreadableProblem.MalformedXml, result.state.isoImportError)
        assertNull(result.state.pendingIsoImport)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun listingFailureIsKeptForThePickerInsteadOfLookingEmpty() {
        val requested = UiReducer.reduce(ready(), UiIntent.LoadWindowsIsoCandidates)
        assertEquals(1L, (requested.effects.single() as UiEffect.LoadWindowsIsoCandidates).requestGeneration)
        val failed = UiReducer.reduce(
            requested.state,
            UiIntent.WindowsIsoCandidatesLoaded(
                generation = 1L,
                identity = device.identity,
                candidates = emptyList(),
                error = "3 folder(s) could not be read",
            ),
        )

        assertEquals("3 folder(s) could not be read", failed.state.windowsIsoCandidatesError)
        val retried = UiReducer.reduce(failed.state, UiIntent.LoadWindowsIsoCandidates)
        assertNull(retried.state.windowsIsoCandidatesError)
    }

    @Test
    fun bindCarriesTheAcknowledgementAndTheInspectedDigest() {
        val result = UiReducer.reduce(
            ready(),
            UiIntent.RequestBindAutounattend(
                xmlName = "/ventoy/autounattend.xml",
                imagePaths = listOf("/ISO/win11.iso"),
                allowDestructiveTemplate = true,
                expectedTemplateSha256 = DIGEST,
            ),
        )

        val effect = result.effects.single() as UiEffect.BindAutounattend
        assertEquals(true, effect.allowDestructiveTemplate)
        assertEquals(DIGEST, effect.expectedTemplateSha256)
        assertEquals(listOf("/ISO/win11.iso"), effect.imagePaths)
    }

    private companion object {
        const val URI = "content://com.android.providers.downloads.documents/document/42"
        const val DIGEST = "9f2c0d1e4b7a8c5d3e6f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d"
    }
}
