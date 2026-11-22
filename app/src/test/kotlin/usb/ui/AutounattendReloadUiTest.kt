package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.ventoy.AutounattendDocument
import usb.ventoy.AutounattendState

/**
 * The XML document and the whole-partition image list are two reads of one state. They must
 * share one generation, and every path that can change the file must ask for a fresh read even
 * when no composition key changes.
 */
class AutounattendReloadUiTest {
    private val device = DeviceIdentity(label = "Test Stick", capacityBytes = 8_000_000_000uL)
    private val template = "/ventoy/autounattend.xml"

    private fun readyWithTemplate() = AppUiState(
        presence = DevicePresence.Ready,
        device = device,
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
        windowsTemplatePath = template,
        // Provenance is mandatory since the atomic-consume cutover: a path
        // without a matching live pin is refused, never retained.
        windowsTemplatePin = FilesPickSelection(
            purpose = FilesPickPurpose.WindowsTemplate,
            path = template,
            name = "autounattend.xml",
            sizeBytes = 64uL,
            isDirectory = false,
            identity = device.identity,
            attachGeneration = device.attachGeneration,
            partitionId = "1",
            startLba = 2048uL,
            sectorCount = 4096uL,
        ),
        windowsIsoCandidates = listOf(
            RawSourceCandidate(path = template, name = "autounattend.xml", sizeBytes = 64L, partitionId = "1"),
        ),
    )

    private fun document() = AutounattendDocument(
        existing = null,
        unknownElements = emptyList(),
        boundImages = listOf("/ISO/win11.iso"),
        sourcePath = template,
        destructiveMarkers = emptyList(),
        templateSha256 = "0f00",
    )

    @Test
    fun loadAsksForDocumentAndCandidatesUnderOneGeneration() {
        val result = UiReducer.reduce(readyWithTemplate(), UiIntent.LoadAutounattend(template))

        assertEquals(2, result.effects.size)
        val reload = result.effects.filterIsInstance<UiEffect.LoadAutounattend>().single()
        val candidatesReload =
            result.effects.filterIsInstance<UiEffect.LoadAutounattendCandidates>().single()
        // Both reads of one state must carry the same generation; only these two effect
        // types expose requestGeneration, so it is read from them, not from UiEffect.
        val generation = reload.requestGeneration
        assertEquals(
            "LoadAutounattend must publish one shared requestGeneration",
            generation,
            candidatesReload.requestGeneration,
        )
        assertEquals(generation, result.state.autounattendRequestGeneration)
        assertEquals(
            setOf(UiEffect.LoadAutounattend::class, UiEffect.LoadAutounattendCandidates::class),
            result.effects.map { it::class }.toSet(),
        )

        // Both answers of that one generation must be accepted, not just the newer one.
        val loaded = UiReducer.reduce(
            result.state,
            UiIntent.AutounattendLoaded(
                xmlName = template,
                state = AutounattendState.Parsed(document()),
                generation = generation,
            ),
        )
        assertTrue(loaded.state.autounattendState is AutounattendState.Parsed)

        val candidates = UiReducer.reduce(
            result.state,
            UiIntent.AutounattendCandidatesLoaded(
                xmlName = template,
                candidates = AutounattendImageCandidates(
                    images = emptyList(),
                    searchScope = emptyList(),
                    scopeLimitedBy = null,
                ),
                generation = generation,
            ),
        )
        assertTrue(candidates.state.autounattendCandidates != null)
    }

    @Test
    fun staleGenerationIsStillDropped() {
        val loaded = UiReducer.reduce(readyWithTemplate(), UiIntent.LoadAutounattend(template))
        val stale = UiReducer.reduce(
            loaded.state,
            UiIntent.AutounattendLoaded(
                xmlName = template,
                state = AutounattendState.Parsed(document()),
                generation = loaded.state.autounattendRequestGeneration - 1,
            ),
        )

        assertEquals(AutounattendState.Missing, stale.state.autounattendState)
    }

    private fun confirmedPick(
        path: String = template,
        identity: String? = device.identity,
        attachGeneration: Long = device.attachGeneration,
        partitionId: String = "1",
        startLba: ULong? = 2048uL,
        sectorCount: ULong? = 4096uL,
        isDirectory: Boolean = false,
    ) = FilesPickSelection(
        purpose = FilesPickPurpose.WindowsTemplate,
        path = path,
        name = path.substringAfterLast('/'),
        sizeBytes = 1_024uL,
        isDirectory = isDirectory,
        identity = identity,
        attachGeneration = attachGeneration,
        partitionId = partitionId,
        startLba = startLba,
        sectorCount = sectorCount,
    )

    private fun withConfirmedPick(
        state: AppUiState = readyWithTemplate(),
        selection: FilesPickSelection = confirmedPick(),
    ) = state.copy(filesPickSelections = mapOf(FilesPickPurpose.WindowsTemplate to selection))

    @Test
    fun consumingConfirmedPickSelectsTemplateAndClearsSingleUseToken() {
        val result = UiReducer.reduce(withConfirmedPick(), UiIntent.SelectWindowsTemplate)

        assertEquals(2, result.effects.size)
        assertEquals(template, result.state.windowsTemplatePath)
        assertEquals(confirmedPick(), result.state.windowsTemplatePin)
        assertNull(result.state.filesPickSelection(FilesPickPurpose.WindowsTemplate))
        assertEquals(AutounattendState.Missing, result.state.autounattendState)
    }

    @Test
    fun replayWithoutFreshConfirmRefuses() {
        val consumed = UiReducer.reduce(withConfirmedPick(), UiIntent.SelectWindowsTemplate).state
        val replay = UiReducer.reduce(consumed, UiIntent.SelectWindowsTemplate)

        assertEquals(template, replay.state.windowsTemplatePath)
        assertTrue(replay.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            replay.state.statusMessage,
        )
    }

    @Test
    fun missingConfirmedPickRefuses() {
        val result = UiReducer.reduce(readyWithTemplate(), UiIntent.SelectWindowsTemplate)

        assertEquals(template, result.state.windowsTemplatePath)
        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun importedTemplateReloadsTheDocument() {
        val result = UiReducer.reduce(
            readyWithTemplate(),
            UiIntent.WindowsTemplateImported(
                path = template,
                identity = device.identity,
                attachGeneration = device.attachGeneration,
                partitionStartLba = 2048uL,
                partitionSectorCount = 4096uL,
            ),
        )

        assertEquals(2, result.effects.size)
        assertEquals(
            setOf(UiEffect.LoadAutounattend::class, UiEffect.LoadAutounattendCandidates::class),
            result.effects.map { it::class }.toSet(),
        )
        assertEquals(
            template,
            result.effects.filterIsInstance<UiEffect.LoadAutounattend>().single().xmlName,
        )
    }

    @Test
    fun staleImportPinRefusesTemplate() {
        val result = UiReducer.reduce(
            readyWithTemplate(),
            UiIntent.WindowsTemplateImported(
                path = template,
                identity = "other-stick",
                attachGeneration = device.attachGeneration,
                partitionStartLba = 2048uL,
                partitionSectorCount = 4096uL,
            ),
        )

        assertEquals(template, result.state.windowsTemplatePath)
        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun forgedSelectionPinRefusesTemplate() {
        val result = UiReducer.reduce(
            withConfirmedPick(selection = confirmedPick(identity = "other-stick")),
            UiIntent.SelectWindowsTemplate,
        )

        assertEquals(template, result.state.windowsTemplatePath)
        assertEquals(AutounattendState.Missing, result.state.autounattendState)
        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun changedPartitionGeometryRefusesTemplate() {
        val result = UiReducer.reduce(
            withConfirmedPick(selection = confirmedPick(startLba = 999uL)),
            UiIntent.SelectWindowsTemplate,
        )

        assertEquals(template, result.state.windowsTemplatePath)
        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun nonFirstPartitionNeverSelectsTemplate() {
        val twoPartitions = readyWithTemplate().copy(
            partitions = readyWithTemplate().partitions + PartitionTab(
                id = "2",
                name = "Other",
                readOnly = false,
                startLba = 8192uL,
                sectorCount = 4096uL,
                filesystemId = FilesystemId.FAT,
            ),
        )
        val result = UiReducer.reduce(
            withConfirmedPick(
                state = twoPartitions,
                selection = confirmedPick(
                    path = "/other/template.xml",
                    partitionId = "2",
                    startLba = 8192uL,
                ),
            ),
            UiIntent.SelectWindowsTemplate,
        )

        assertEquals(template, result.state.windowsTemplatePath)
        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun traversingPathNeverSelectsTemplate() {
        val result = UiReducer.reduce(
            withConfirmedPick(selection = confirmedPick(path = "/ventoy/../evil.xml")),
            UiIntent.SelectWindowsTemplate,
        )

        assertEquals(template, result.state.windowsTemplatePath)
        assertTrue(result.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
            result.state.statusMessage,
        )
    }

    @Test
    fun geometryChangeAfterConsumeDropsTemplateAndDisownsReads() {
        val consumed = UiReducer.reduce(withConfirmedPick(), UiIntent.SelectWindowsTemplate).state
        val changed = consumed.copy(
            partitions = listOf(
                PartitionTab(
                    id = "1",
                    name = "Ventoy",
                    readOnly = false,
                    startLba = 999uL,
                    sectorCount = 4096uL,
                    filesystemId = FilesystemId.FAT,
                ),
            ),
        )
        val result = UiReducer.reduce(
            changed,
            UiIntent.DeviceChanged(presence = DevicePresence.Ready),
        )

        assertNull(result.state.windowsTemplatePath)
        assertNull(result.state.windowsTemplatePin)
        assertFalse(result.state.autounattendLoadInFlight)
    }

    @Test
    fun detachAfterConsumeDropsTemplate() {
        val consumed = UiReducer.reduce(withConfirmedPick(), UiIntent.SelectWindowsTemplate).state
        val result = UiReducer.reduce(
            consumed,
            UiIntent.DeviceChanged(presence = DevicePresence.Absent),
        )

        assertNull(result.state.windowsTemplatePath)
        assertNull(result.state.windowsTemplatePin)
    }

    @Test
    fun detachAfterConsumeDropsUnlinkAndLinkCandidates() {
        val consumed = UiReducer.reduce(withConfirmedPick(), UiIntent.SelectWindowsTemplate).state
        val armed = consumed.copy(
            pendingWindowsUnlink = listOf("/ISO/win11.iso"),
            linkOverwriteCandidatePaths = listOf("/ISO/win11.iso"),
            linkOverwriteCandidateTemplatePath = template,
        )
        val result = UiReducer.reduce(
            armed,
            UiIntent.DeviceChanged(presence = DevicePresence.Absent),
        )

        assertNull(result.state.pendingWindowsUnlink)
        assertEquals(emptyList<String>(), result.state.linkOverwriteCandidatePaths)
        assertNull(result.state.linkOverwriteCandidateTemplatePath)
        assertNull(result.state.windowsTemplatePath)
        assertNull(result.state.windowsTemplatePin)
    }

    @Test
    fun importOriginTemplateSurvivesSameGeometryChange() {
        val imported = UiReducer.reduce(
            readyWithTemplate(),
            UiIntent.WindowsTemplateImported(
                path = template,
                identity = device.identity,
                attachGeneration = device.attachGeneration,
                partitionStartLba = 2048uL,
                partitionSectorCount = 4096uL,
            ),
        ).state
        val result = UiReducer.reduce(
            imported,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device,
                partitions = imported.partitions,
            ),
        )

        assertEquals(template, result.state.windowsTemplatePath)
        assertEquals(imported.windowsTemplatePin, result.state.windowsTemplatePin)
    }

    @Test
    fun importOriginTemplateDropsOnGeometryChange() {
        val imported = UiReducer.reduce(
            readyWithTemplate(),
            UiIntent.WindowsTemplateImported(
                path = template,
                identity = device.identity,
                attachGeneration = device.attachGeneration,
                partitionStartLba = 2048uL,
                partitionSectorCount = 4096uL,
            ),
        ).state
        val changed = imported.copy(
            partitions = listOf(
                PartitionTab(
                    id = "1",
                    name = "Ventoy",
                    readOnly = false,
                    startLba = 999uL,
                    sectorCount = 4096uL,
                    filesystemId = FilesystemId.FAT,
                ),
            ),
        )
        val result = UiReducer.reduce(
            changed,
            UiIntent.DeviceChanged(presence = DevicePresence.Ready),
        )

        assertNull(result.state.windowsTemplatePath)
        assertNull(result.state.windowsTemplatePin)
        assertFalse(result.state.autounattendLoadInFlight)
    }

    @Test
    fun deviceChangeDropsTheListingError() {
        val failed = readyWithTemplate().copy(windowsIsoCandidatesError = "3 folder(s) could not be read")
        val swapped = UiReducer.reduce(
            failed,
            UiIntent.DeviceChanged(presence = DevicePresence.Absent),
        )

        assertNull(swapped.state.windowsIsoCandidatesError)
        assertNull(swapped.state.windowsIsoCandidates)
    }
}
