package usb.ui

import org.junit.Assert.assertEquals
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
        val generation = requireNotNull(
            result.effects.map { it.requestGeneration }.distinct().single(),
        ) { "LoadAutounattend must publish one shared requestGeneration" }
        assertEquals(generation, result.state.autounattendRequestGeneration)
        assertEquals(
            setOf(UiEffect.Kind.LoadAutounattend, UiEffect.Kind.LoadAutounattendCandidates),
            result.effects.map { it.kind }.toSet(),
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

    @Test
    fun selectingTheSameTemplateReloadsInsteadOfClearing() {
        val result = UiReducer.reduce(readyWithTemplate(), UiIntent.SelectWindowsTemplate(template))

        assertEquals(2, result.effects.size)
        assertEquals(template, result.state.windowsTemplatePath)
        assertEquals(AutounattendState.Missing, result.state.autounattendState)
    }

    @Test
    fun importedTemplateReloadsTheDocument() {
        val result = UiReducer.reduce(readyWithTemplate(), UiIntent.WindowsTemplateImported(template))

        assertEquals(2, result.effects.size)
        assertEquals(
            setOf(UiEffect.Kind.LoadAutounattend, UiEffect.Kind.LoadAutounattendCandidates),
            result.effects.map { it.kind }.toSet(),
        )
        assertEquals(template, result.effects.first().xmlName)
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
