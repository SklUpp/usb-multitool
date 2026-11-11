package usb.ui

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.diagnostics.MapKind
import usb.diagnostics.RescueMap
import usb.partition.GptBackup
import usb.partition.SyntheticGpt

/**
 * Behavioral coverage for the pre-confirmation inspection: bounded reads, honest map/GPT
 * summaries, stale-selection drops and the refusal gates that keep a failed inspection
 * away from the destructive confirmation.
 */
class RestoreInspectionTest {
    private val device =
        DeviceIdentity(label = "Inspection stick", capacityBytes = 2048UL * 512UL, attachGeneration = 7)

    private fun ready(): AppUiState =
        AppUiState(
            presence = DevicePresence.Ready,
            device = device,
            partitions = listOf(PartitionTab("1", "DATA", readOnly = false, startLba = 34UL, sectorCount = 1000UL)),
            diskSectorSize = 512,
            tableValid = true,
        )

    @Test
    fun imageInspectionCarriesSafMetadataWithoutTouchingTheImage() {
        val inspection = RestoreSourceInspector.inspectImage("content://docs/stick.img", "stick.img", 4096L)
        assertEquals("content://docs/stick.img", inspection.uri)
        assertEquals("stick.img", inspection.name)
        assertEquals(4096L, inspection.sizeBytes)
        assertNull(RestoreSourceInspector.inspectImage("content://docs/x", null, null).sizeBytes)
    }

    @Test
    fun mapInspectionReportsKindRangesAndStates() {
        val map = RescueMap.forRestore(2048UL, sectorSize = 512)
        map.markCopied(0UL, 100UL)
        map.markFailed(100UL, 4UL)
        val inspection =
            RestoreSourceInspector.inspectMap(
                "content://docs/stick.map",
                "stick.map",
                80L,
                map.encode(),
            )
        assertNull(inspection.error)
        val summary = inspection.summary!!
        assertEquals(MapKind.RESTORE, summary.kind)
        assertEquals(2048UL, summary.sectorCount)
        assertEquals(512, summary.sectorSize)
        assertEquals(listOf(0UL until 100UL), summary.copiedRanges)
        assertEquals(listOf(100UL until 104UL), summary.failedRanges)
        assertEquals(4UL, summary.failedSectors)
        assertEquals(2048UL - 104UL, summary.nonTriedSectors)
    }

    @Test
    fun malformedMapBecomesAnExplicitInspectionError() {
        val inspection =
            RestoreSourceInspector.inspectMap("content://docs/bad.map", "bad.map", null, "not a map")
        assertNull(inspection.summary)
        assertNotNull(inspection.error)
    }

    @Test
    fun boundedReaderRejectsDocumentsAtTheBoundary() {
        val exactly = RestoreSourceInspector.readBounded(ByteArrayInputStream(ByteArray(8)), 8)
        assertEquals(8, exactly.size)
        val failure =
            runCatching {
                RestoreSourceInspector.readBounded(ByteArrayInputStream(ByteArray(9)), 8)
            }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun gptInspectionValidatesHeadersGeometryAndPartitionCount() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL, sectorSize = 512)
        val blob = GptBackup.backupCriticalSectors(disk)
        val inspection =
            RestoreSourceInspector.inspectGpt(
                uri = "content://docs/gpt.bin",
                name = "gpt.bin",
                sizeBytes = blob.size.toLong(),
                blob = blob,
                diskSectorSize = 512,
            )
        assertNull(inspection.error)
        val summary = inspection.summary!!
        assertEquals(512, summary.sectorSize)
        assertEquals(GptBackup.primarySectors(512), summary.primarySectors)
        assertEquals(GptBackup.backupSectors(512), summary.backupSectors)
        assertEquals(2048UL, summary.originalSectorCount)
        assertEquals(2, summary.partitions)
        assertTrue(summary.firstUsable <= summary.lastUsable)
    }

    @Test
    fun gptInspectionDetects1024And2048SectorSizes() {
        for (sectorSize in listOf(1024, 2048)) {
            val disk = SyntheticGpt.build(sectorCount = 4096UL, sectorSize = sectorSize)
            val blob = GptBackup.backupCriticalSectors(disk)
            val inspection =
                RestoreSourceInspector.inspectGpt(
                    uri = "content://docs/gpt-$sectorSize.bin",
                    name = null,
                    sizeBytes = blob.size.toLong(),
                    blob = blob,
                    diskSectorSize = null,
                )
            assertNull(inspection.error)
            assertEquals(sectorSize, inspection.summary!!.sectorSize)
            assertEquals(2, inspection.summary.partitions)
        }
    }

    @Test
    fun gptInspectionDoesNotTrustSafSizeMetadata() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL, sectorSize = 512)
        val blob = GptBackup.backupCriticalSectors(disk)
        // A provider can report 0 or a stale encoded length; only the bounded read is validated.
        val inspection =
            RestoreSourceInspector.inspectGpt(
                uri = "content://docs/gpt.bin",
                name = "gpt.bin",
                sizeBytes = 1L,
                blob = blob,
                diskSectorSize = 512,
            )
        assertNull(inspection.error)
        assertEquals(1L, inspection.sizeBytes)
        assertEquals(blob.size, inspection.summary!!.blobBytes)
    }

    @Test
    fun gptInspectionRejectsWrongGeometryAndCorruption() {
        val disk = SyntheticGpt.build(sectorCount = 2048UL, sectorSize = 512)
        val blob = GptBackup.backupCriticalSectors(disk)
        val wrongSector =
            RestoreSourceInspector.inspectGpt("content://docs/gpt.bin", null, null, blob, 4096)
        assertNotNull(wrongSector.error)
        assertNull(wrongSector.summary)

        val corrupt = blob.copyOf()
        corrupt[512 + 16] = (corrupt[512 + 16].toInt() xor 0xFF).toByte()
        val corrupted =
            RestoreSourceInspector.inspectGpt("content://docs/gpt.bin", null, null, corrupt, 512)
        assertNotNull(corrupted.error)
        assertTrue(corrupted.error!!.contains("CRC"))
    }

    @Test
    fun failedGptInspectionBlocksTheDestructiveConfirmation() {
        val uri = "content://docs/gpt.bin"
        val state =
            ready().copy(
                backupUri = uri,
                backupInspection =
                    GptBackupInspection(uri = uri, name = "gpt.bin", error = "bad CRC"),
            )
        val result = UiReducer.reduce(state, UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
        assertNull(result.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf("gpt.bin", "bad CRC"),
            ),
            result.state.statusMessage,
        )
    }

    @Test
    fun gptInspectionFromAnotherGeometryBlocksTheConfirmation() {
        val uri = "content://docs/gpt.bin"
        val summary =
            GptBackupSummary(
                sectorSize = 512,
                primarySectors = 34,
                backupSectors = 33,
                blobBytes = 34304,
                partitions = 2,
                firstUsable = 34UL,
                lastUsable = 2014UL,
                originalSectorCount = 4096UL,
            )
        val state =
            ready().copy(
                backupUri = uri,
                backupInspection = GptBackupInspection(uri, "gpt.bin", 34304L, summary),
            )
        val result = UiReducer.reduce(state, UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
        assertNull(result.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(
                UiMessageKey.GptBackupDiskMismatch,
                listOf("4096", "2048"),
            ),
            result.state.statusMessage,
        )
    }

    @Test
    fun validGptInspectionIsSnapshottedIntoTheConfirmation() {
        val uri = "content://docs/gpt.bin"
        val summary =
            GptBackupSummary(
                sectorSize = 512,
                primarySectors = 34,
                backupSectors = 33,
                blobBytes = 34304,
                partitions = 2,
                firstUsable = 34UL,
                lastUsable = 2014UL,
                originalSectorCount = 2048UL,
            )
        val inspection = GptBackupInspection(uri, "gpt.bin", 34304L, summary)
        val state = ready().copy(backupUri = uri, backupInspection = inspection)
        val result = UiReducer.reduce(state, UiIntent.RequestDestructive(DestructiveAction.GPT_RESTORE))
        assertEquals(inspection, result.state.pendingConfirmation?.gptBackupInspection)
    }

    @Test
    fun failedInspectionReportsTheReasonAndARetryReplacesIt() {
        val gptUri = "content://docs/gpt.bin"
        val failed = GptBackupInspection(gptUri, "gpt.bin", error = "bad CRC")
        val afterFailure =
            UiReducer.reduce(
                ready(),
                UiIntent.SetBackupFile(gptUri, failed, requestId = 1L),
            ).state
        assertEquals(
            UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf("gpt.bin", "bad CRC"),
            ),
            afterFailure.statusMessage,
        )
        assertEquals(failed, afterFailure.backupInspection)

        val valid =
            GptBackupInspection(
                uri = gptUri,
                name = "gpt.bin",
                sizeBytes = 34304L,
                summary =
                    GptBackupSummary(
                        sectorSize = 512,
                        primarySectors = 34,
                        backupSectors = 33,
                        blobBytes = 34304,
                        partitions = 2,
                        firstUsable = 34UL,
                        lastUsable = 2014UL,
                        originalSectorCount = 2048UL,
                    ),
            )
        val retried =
            UiReducer.reduce(
                afterFailure,
                UiIntent.SetBackupFile(gptUri, valid, requestId = 2L),
            ).state
        assertEquals(
            UiMessage.Key(UiMessageKey.BackupReady, listOf("gpt.bin")),
            retried.statusMessage,
        )
        assertEquals(valid, retried.backupInspection)

        val mapUri = "content://docs/stick.map"
        val mapFailure =
            UiReducer.reduce(
                ready(),
                UiIntent.SetRescueMap(
                    mapUri,
                    RescueMapInspection(mapUri, "stick.map", error = "bad map"),
                    requestId = 1L,
                ),
            ).state
        assertEquals(
            UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf("stick.map", "bad map"),
            ),
            mapFailure.statusMessage,
        )
        val decoded = RescueMap.forRestore(2048UL, sectorSize = 512)
        val mapRetry =
            UiReducer.reduce(
                mapFailure,
                UiIntent.SetRescueMap(
                    mapUri,
                    RescueMapInspection(mapUri, "stick.map", null, decoded.summary()),
                    requestId = 2L,
                ),
            ).state
        assertEquals(
            UiMessage.Key(UiMessageKey.RescueMapReady, listOf("stick.map")),
            mapRetry.statusMessage,
        )
    }

    @Test
    fun malformedMapAndPartialCopyBlockTheRescueConfirmation() {
        val imageUri = "content://docs/stick.img"
        val mapUri = "content://docs/stick.map"
        val image = RescueImageInspection(imageUri, "stick.img", 1_048_576L)
        val malformed =
            ready().copy(
                rescueImageUri = imageUri,
                rescueMapUri = mapUri,
                rescueImageInspection = image,
                rescueMapInspection = RescueMapInspection(mapUri, "stick.map", null, error = "bad map"),
            )
        val rejected = UiReducer.reduce(malformed, UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE))
        assertNull(rejected.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf("stick.map", "bad map"),
            ),
            rejected.state.statusMessage,
        )

        val copy = RescueMap.forCopy(2048UL, sectorSize = 512)
        copy.markCopied(0UL, 10UL)
        val partial =
            ready().copy(
                rescueImageUri = imageUri,
                rescueMapUri = mapUri,
                rescueImageInspection = image,
                rescueMapInspection = RescueMapInspection(mapUri, "stick.map", null, copy.summary()),
            )
        val partialResult = UiReducer.reduce(partial, UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE))
        assertNull(partialResult.state.pendingConfirmation)
        assertEquals(
            UiMessage.Key(UiMessageKey.RescueMapPartialCopy, listOf("2038")),
            partialResult.state.statusMessage,
        )
    }

    @Test
    fun compressedSourceSizeIsNotComparedWithTheDecodedMapRequirement() {
        val imageUri = "content://docs/stick.img.gz"
        val mapUri = "content://docs/stick.map"
        val map = RescueMap.forRestore(2048UL, sectorSize = 512)
        val state =
            ready().copy(
                rescueImageUri = imageUri,
                rescueMapUri = mapUri,
                // Encoded gzip/xz source is far smaller than the decoded image; the map's
                // decoded byte requirement must not reject it before the stream is read.
                rescueImageInspection = RescueImageInspection(imageUri, "stick.img.gz", 1000L),
                rescueMapInspection = RescueMapInspection(mapUri, "stick.map", 80L, map.summary()),
            )
        val result = UiReducer.reduce(state, UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE))
        assertNotNull(result.state.pendingConfirmation)
        assertEquals(1000L, result.state.pendingConfirmation!!.rescueImageInspection?.sizeBytes)
    }

    @Test
    fun validRescueInspectionsAreSnapshottedIntoTheConfirmation() {
        val imageUri = "content://docs/stick.img"
        val mapUri = "content://docs/stick.map"
        val map = RescueMap.forRestore(2048UL, sectorSize = 512)
        map.markCopied(0UL, 2048UL)
        val image = RescueImageInspection(imageUri, "stick.img", 1_048_576L)
        val mapInspection = RescueMapInspection(mapUri, "stick.map", 80L, map.summary())
        val state =
            ready().copy(
                rescueImageUri = imageUri,
                rescueMapUri = mapUri,
                rescueImageInspection = image,
                rescueMapInspection = mapInspection,
            )
        val result = UiReducer.reduce(state, UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE))
        val pending = result.state.pendingConfirmation
        assertEquals(image, pending?.rescueImageInspection)
        assertEquals(mapInspection, pending?.rescueMapInspection)
    }

    @Test
    fun stalePickResultCannotReplaceANewerSelection() {
        val state = ready().copy(backupSelectionGeneration = 2L, backupUri = "content://docs/B.bin")
        val stale =
            UiReducer.reduce(
                state,
                UiIntent.SetBackupFile(
                    uri = "content://docs/A.bin",
                    inspection = GptBackupInspection("content://docs/A.bin"),
                    requestId = 1L,
                ),
            )
        assertEquals("content://docs/B.bin", stale.state.backupUri)
        assertEquals(2L, stale.state.backupSelectionGeneration)
    }
}
