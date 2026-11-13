package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.image.RangeProbeAttachment
import usb.image.RangeProbeFinding
import usb.image.RangeProbeResult
import usb.partition.Partition
import usb.partition.PartitionTable
import usb.partition.RawObjectKind
import usb.partition.RawRangeGeometry
import usb.partition.RawWriteRange
import usb.partition.TableScheme

class RawRangeUiStateTest {
    private val device =
        DeviceIdentity(
            label = "Raw Stick",
            capacityBytes = 64UL * 1024UL * 1024UL,
            attachGeneration = 7L,
        )

    private val attachment = RangeProbeAttachment(device.identity, device.attachGeneration)

    private val geometry =
        RawRangeGeometry.from(
            PartitionTable(
                scheme = TableScheme.MBR,
                partitions = listOf(Partition(2048UL, 4096UL, name = "DATA")),
                freeRegions = emptyList(),
                diskSectorCount = 131_072UL,
                sectorSize = 512,
            ),
            diskSectorCount = 131_072UL,
            sectorSize = 512,
        )

    private fun ready(state: AppUiState = AppUiState()) =
        state.copy(
            presence = DevicePresence.Ready,
            device = device,
            rawGeometry = geometry,
            imageUri = "content://images/ubuntu.iso",
            imageName = "ubuntu.iso",
            // 4096 sectors of 512 bytes: one sector of headroom on the test disk.
            imageSizeBytes = 4096L * 512L,
            rawDecodedSizeBytes = 4096L * 512L,
            rawDecodedSizeSource = "content://images/ubuntu.iso",
            rawDecodedSha256 = "confirmed-digest",
            diskSectorSize = 512,
        )

    private fun probe(
        range: RawWriteRange,
        attach: RangeProbeAttachment? = attachment,
        requestId: Long = 0L,
    ) =
        RangeProbeResult(
            finding = RangeProbeFinding.DataPresent,
            range = range,
            diskSectorCount = geometry.diskSectorCount,
            sectorSize = geometry.sectorSize,
            sampledSectors = listOf(range.startLba),
            attachment = attach,
            requestId = requestId,
        )

    @Test
    fun explicitRangeReachesConfirmationWithAbsoluteStartAndSize() {
        val state = ready()
        val result =
            UiReducer.reduce(state, UiIntent.RequestRawWrite(2048UL))
        val pending = result.state.pendingConfirmation
        assertNotNull(pending)
        assertEquals(DestructiveAction.RAW_WRITE, pending!!.action)
        assertEquals(2048UL, pending.rawStartLba)
        assertEquals(4096UL, pending.rawSectorCount)
        assertEquals(geometry.diskSectorCount, pending.diskSectorCount)
        assertEquals(geometry.sectorSize, pending.diskSectorSize)
        // F1: source display name on the confirmation payload.
        assertEquals("ubuntu.iso", pending.rawSourceName)

        val confirmed = UiReducer.reduce(result.state, UiIntent.ConfirmDestructive)
        val effect = confirmed.effects.single() as UiEffect.SubmitDestructive
        assertEquals(2048UL, effect.confirmation.rawStartLba)
        assertEquals(4096UL, effect.confirmation.rawSectorCount)
        assertEquals("ubuntu.iso", effect.confirmation.rawSourceName)
    }

    @Test
    fun stagedLabelPreferredOverSafUriForRawSourceName() {
        val result =
            UiReducer.reduce(
                ready().copy(
                    stagedRawImagePath = "/data/staging/copy.bin",
                    stagedRawImageLabel = "phone-dump.img",
                    stagedRawImageSizeBytes = 64L * 512L,
                    rawDecodedSizeBytes = 64L * 512L,
                    rawDecodedSizeSource = "/data/staging/copy.bin",
                    rawDecodedSha256 = null,
                    imageUri = null,
                    imageName = null,
                ),
                UiIntent.RequestRawWrite(0UL),
            )
        assertEquals("phone-dump.img", result.state.pendingConfirmation!!.rawSourceName)
        assertEquals(64UL, result.state.pendingConfirmation!!.rawSectorCount)
    }

    @Test
    fun compressedSourceWaitsForMeasuredSizeThenConfirms() {
        val measuring =
            ready().copy(
                imageName = "ubuntu.img.gz",
                imageSizeBytes = 1024L,
                rawDecodedSizeBytes = null,
                rawSizeMeasuring = true,
                rawSizeGeneration = 5L,
            )
        val waiting = UiReducer.reduce(measuring, UiIntent.RequestRawWrite(0UL))
        assertNull(waiting.state.pendingConfirmation)

        val measured =
            UiReducer.reduce(
                waiting.state,
                UiIntent.RawSourceMeasured(
                    generation = 5L,
                    source = measuring.imageUri,
                    bytes = 4096L * 512L,
                    sha256 = "measured-digest",
                ),
            )
        assertEquals(4096L * 512L, measured.state.rawWriteDecodedBytes())
        val confirmed = UiReducer.reduce(measured.state, UiIntent.RequestRawWrite(0UL))
        assertNotNull(confirmed.state.pendingConfirmation)
        assertEquals(4096UL, confirmed.state.pendingConfirmation!!.rawSectorCount)
        assertEquals(4096L * 512L, confirmed.state.pendingConfirmation!!.rawDecodedBytes)
        assertEquals("measured-digest", confirmed.state.pendingConfirmation!!.rawSourceSha256)
    }
    @Test
    fun replacementMeasurementWaitsWithoutAcceptingStaleProviderResult() {
        val first =
            ready().copy(
                imageName = "old.iso",
                imageUri = "content://images/old.iso",
                rawDecodedSizeBytes = null,
                rawDecodedSizeSource = "content://images/old.iso",
                rawSizeMeasuring = true,
                rawSizeGeneration = 10L,
            )
        val replacement =
            first.copy(
                imageName = "new.iso",
                imageUri = "content://images/new.iso",
                rawDecodedSizeSource = "content://images/new.iso",
                rawSizeMeasuring = true,
                rawSizeGeneration = 11L,
            )
        val waiting =
            UiReducer.reduce(
                replacement,
                UiIntent.RawSourceMeasurementWaiting(11L, "content://images/new.iso"),
            )
        assertTrue(waiting.state.rawMeasurementWaitingForPrevious)
        assertFalse(waiting.state.rawMeasurementPending)
        val stale =
            UiReducer.reduce(
                waiting.state,
                UiIntent.RawSourceMeasured(
                    generation = 10L,
                    source = "content://images/old.iso",
                    bytes = 512L,
                    sha256 = "stale",
                ),
            )
        assertNull(stale.state.rawDecodedSizeBytes)
        assertTrue(stale.state.rawMeasurementWaitingForPrevious)
        val started =
            UiReducer.reduce(
                stale.state,
                UiIntent.RawSourceMeasurementStarted(11L, "content://images/new.iso"),
            )
        assertTrue(started.state.rawSizeMeasuring)
        assertFalse(started.state.rawMeasurementWaitingForPrevious)
    }

    @Test
    fun queuedMeasurementTokenCannotReviveAfterSourceCancel() {
        val waiting =
            ready().copy(
                rawSizeGeneration = 4L,
                rawMeasurementPending = true,
            )
        val cancelled = UiReducer.reduce(waiting, UiIntent.CancelRawSourceMeasurement)
        val lateStart =
            UiReducer.reduce(
                cancelled.state,
                UiIntent.RawSourceMeasurementStarted(4L, waiting.imageUri),
            )
        assertFalse(lateStart.state.rawSizeMeasuring)
        assertFalse(lateStart.state.rawMeasurementPending)
        assertNull(lateStart.state.rawDecodedSizeBytes)
    }

    @Test
    fun unnamedUnknownSourceIsMeasuredRatherThanRejectedByName() {
        val measuring =
            ready().copy(
                imageName = null,
                imageSizeBytes = null,
                rawDecodedSizeBytes = null,
                rawSizeMeasuring = true,
                rawSizeGeneration = 6L,
            )
        val measured =
            UiReducer.reduce(
                measuring,
                UiIntent.RawSourceMeasured(
                    generation = 6L,
                    source = measuring.imageUri,
                    bytes = 4096L * 512L,
                    sha256 = "measured-digest",
                ),
            )
        val confirmed = UiReducer.reduce(measured.state, UiIntent.RequestRawWrite(0UL))
        assertEquals(4096UL, confirmed.state.pendingConfirmation!!.rawSectorCount)
    }

    @Test
    fun measurementDoesNotStartWithoutPositiveCapacityOrGeometry() {
        val noBound =
            ready().copy(
                device = device.copy(capacityBytes = 0UL),
                rawGeometry = null,
                diskSectorSize = 0,
                imageUri = null,
                rawDecodedSizeBytes = null,
            )
        val result =
            UiReducer.reduce(
                noBound,
                UiIntent.SetImage(
                    uri = "content://images/ubuntu.img.gz",
                    name = "ubuntu.img.gz",
                    sizeBytes = 1024L,
                ),
            )
        assertTrue(result.state.rawMeasurementPending)
        assertFalse(result.state.rawSizeMeasuring)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun measurementResumesWhenGeometryBecomesAvailable() {
        val noBound =
            ready().copy(
                device = device.copy(capacityBytes = 0UL),
                rawGeometry = null,
                diskSectorSize = 0,
                imageUri = null,
                rawDecodedSizeBytes = null,
            )
        val waiting =
            UiReducer.reduce(
                noBound,
                UiIntent.SetImage(
                    uri = "content://images/ubuntu.img.gz",
                    name = "ubuntu.img.gz",
                    sizeBytes = 1024L,
                ),
            )
        val resumed =
            UiReducer.reduce(
                waiting.state,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device,
                    sectorSize = 512,
                    rawGeometry = geometry,
                ),
            )
        assertFalse(resumed.state.rawMeasurementPending)
        assertTrue(resumed.state.rawSizeMeasuring)
        val measure = resumed.effects.filterIsInstance<UiEffect.MeasureRawSource>().single()
        assertEquals("content://images/ubuntu.img.gz", measure.source)
        assertNotNull(measure.limitBytes)
    }

    @Test
    fun stagedCompressedMagicRequestsMeasurementEvenWithIsoName() {
        val result =
            UiReducer.reduce(
                ready(),
                UiIntent.SetStagedRawImage(
                    path = "/data/staging/copy.iso",
                    label = "backup.iso",
                    sizeBytes = 1000L,
                    compressedMagic = true,
                ),
            )
        assertTrue(result.state.rawSizeMeasuring)
        assertNull(result.state.rawWriteDecodedBytes())
        val effect = result.effects.single() as UiEffect.MeasureRawSource
        assertTrue(effect.staged)
        assertEquals("/data/staging/copy.iso", effect.source)
    }

    @Test
    fun stagedPlainImageUsesFileSizeWithoutMeasurement() {
        val result =
            UiReducer.reduce(
                ready(),
                UiIntent.SetStagedRawImage(
                    path = "/data/staging/copy.img",
                    label = "backup.img",
                    sizeBytes = 2_097_152L,
                ),
            )
        assertEquals(2_097_152L, result.state.rawWriteDecodedBytes())
        assertFalse(result.state.rawSizeMeasuring)
        assertTrue(result.effects.none { it is UiEffect.MeasureRawSource })
    }

    @Test
    fun explicitCancelStopsMeasurementAndDropsLateResult() {
        val measuring =
            ready().copy(
                rawDecodedSizeBytes = null,
                rawDecodedSha256 = null,
                rawSizeMeasuring = true,
                rawSizeGeneration = 11L,
            )
        val cancelled =
            UiReducer.reduce(measuring, UiIntent.CancelRawSourceMeasurement)
        assertFalse(cancelled.state.rawSizeMeasuring)
        assertNull(cancelled.state.rawWriteDecodedBytes())
        assertTrue(cancelled.effects.single() is UiEffect.CancelRawSourceMeasure)
        assertTrue(cancelled.state.rawSizeGeneration > 11L)

        val late =
            UiReducer.reduce(
                cancelled.state,
                UiIntent.RawSourceMeasured(
                    generation = 11L,
                    source = measuring.imageUri,
                    bytes = 1024L,
                    sha256 = "late",
                ),
            )
        assertNull(late.state.rawWriteDecodedBytes())
    }

    @Test
    fun staleOrFailedMeasurementNeverSizesARange() {
        val measuring =
            ready().copy(
                rawDecodedSizeBytes = null,
                rawSizeMeasuring = true,
                rawSizeGeneration = 9L,
            )
        val stale =
            UiReducer.reduce(
                measuring,
                UiIntent.RawSourceMeasured(
                    generation = 8L,
                    source = measuring.imageUri,
                    bytes = 4096L * 512L,
                ),
            )
        assertNull(stale.state.rawDecodedSizeBytes)
        assertNull(UiReducer.reduce(stale.state, UiIntent.RequestRawWrite(0UL)).state.pendingConfirmation)

        val otherSource =
            UiReducer.reduce(
                measuring,
                UiIntent.RawSourceMeasured(
                    generation = 9L,
                    source = "content://other.iso",
                    bytes = 4096L * 512L,
                ),
            )
        assertNull(otherSource.state.rawDecodedSizeBytes)

        val failed =
            UiReducer.reduce(
                measuring,
                UiIntent.RawSourceMeasured(
                    generation = 9L,
                    source = measuring.imageUri,
                    bytes = null,
                    error = "decoder I/O",
                ),
            )
        assertEquals("decoder I/O", failed.state.rawDecodedSizeError)
        assertEquals(RawMeasureError.Failed, failed.state.rawDecodedErrorKind)
        assertNull(UiReducer.reduce(failed.state, UiIntent.RequestRawWrite(0UL)).state.pendingConfirmation)
    }

    @Test
    fun emptyMeasuredSourceIsRejected() {
        val measured =
            UiReducer.reduce(
                ready().copy(rawSizeGeneration = 7L),
                UiIntent.RawSourceMeasured(
                    generation = 7L,
                    source = "content://images/ubuntu.iso",
                    bytes = 0L,
                    sha256 = "empty-digest",
                ),
            )
        assertNull(UiReducer.reduce(measured.state, UiIntent.RequestRawWrite(0UL)).state.pendingConfirmation)
    }

    @Test
    fun compressedPickRequestsMeasurement() {
        val result =
            UiReducer.reduce(
                ready().copy(imageUri = null, rawDecodedSizeBytes = null),
                UiIntent.SetImage(
                    uri = "content://images/ubuntu.img.gz",
                    name = "ubuntu.img.gz",
                    sizeBytes = 1024L,
                ),
            )
        val effect = result.effects.single() as UiEffect.MeasureRawSource
        assertEquals("content://images/ubuntu.img.gz", effect.source)
        assertEquals(false, effect.staged)
        assertTrue(result.state.rawSizeMeasuring)
        assertNull(result.state.rawWriteDecodedBytes())
    }

    @Test
    fun imageThatDoesNotFitFromStartIsRejectedBeforeConfirmation() {
        val result = UiReducer.reduce(ready(), UiIntent.RequestRawWrite(130_000UL))
        assertNull(result.state.pendingConfirmation)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun impossibleRangeIsRejectedBeforeConfirmation() {
        val result =
            UiReducer.reduce(
                ready(),
                UiIntent.RequestRawWrite(131_072UL),
            )
        assertNull(result.state.pendingConfirmation)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun missingSourceOrGeometryBlocksConfirmation() {
        val noSource = UiReducer.reduce(
            ready().copy(imageUri = null, stagedRawImagePath = null),
            UiIntent.RequestRawWrite(0UL),
        )
        assertNull(noSource.state.pendingConfirmation)

        val noGeometry = UiReducer.reduce(ready().copy(rawGeometry = null), UiIntent.RequestRawWrite(0UL))
        assertNull(noGeometry.state.pendingConfirmation)
    }

    @Test
    fun rawWriteCannotBypassTheDerivedRange() {
        val result =
            UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE))
        assertNull(result.state.pendingConfirmation)
        assertEquals(UiMessage.Key(UiMessageKey.RawRangeInvalid), result.state.statusMessage)
    }

    @Test
    fun probeRequestEmitsEffectAndClearsStaleResult() {
        val stale = ready().copy(rangeProbe = probe(RawWriteRange(0UL, 10UL)))
        val result = UiReducer.reduce(stale, UiIntent.RequestRangeProbe(2048UL, 4096UL))
        assertNull(result.state.rangeProbe)
        assertEquals(RawWriteRange(2048UL, 4096UL), result.state.rangeProbeRequestedRange)
        val effect = result.effects.single() as UiEffect.RangeProbe
        assertEquals(2048UL, effect.startLba)
        assertEquals(4096UL, effect.sectorCount)
        assertEquals(result.state.rangeProbeRequestId, effect.probeRequestId)
    }

    @Test
    fun probeResultIsAcceptedOnlyForTheSameAttachmentGeometryAndRange() {
        val requested =
            UiReducer.reduce(ready(), UiIntent.RequestRangeProbe(2048UL, 4096UL)).state
        val requestId = requested.rangeProbeRequestId!!
        fun result(range: RawWriteRange = RawWriteRange(2048UL, 4096UL)) =
            probe(range, requestId = requestId)

        val accepted =
            UiReducer.reduce(requested, UiIntent.RangeProbeResulted(result()))
        assertEquals(RangeProbeFinding.DataPresent, accepted.state.rangeProbe?.finding)

        val otherGeneration =
            UiReducer.reduce(
                requested,
                UiIntent.RangeProbeResulted(
                    probe(
                        RawWriteRange(2048UL, 4096UL),
                        attach = attachment.copy(attachGeneration = 8L),
                        requestId = requestId,
                    ),
                ),
            )
        assertNull(otherGeneration.state.rangeProbe)

        val otherGeometry =
            UiReducer.reduce(
                requested,
                UiIntent.RangeProbeResulted(result().copy(diskSectorCount = 999UL)),
            )
        assertNull(otherGeometry.state.rangeProbe)

        // The requested range is part of the gate: a probe for another draft is stale.
        val otherRange =
            UiReducer.reduce(
                requested,
                UiIntent.RangeProbeResulted(result(RawWriteRange(0UL, 1UL))),
            )
        assertNull(otherRange.state.rangeProbe)

        // An older request id must not overwrite the current draft.
        val oldRequest =
            UiReducer.reduce(
                requested,
                UiIntent.RangeProbeResulted(result().copy(requestId = requestId - 1L)),
            )
        assertNull(oldRequest.state.rangeProbe)
    }

    @Test
    fun twoProbesOfTheSameRangeDoNotRaceLastResult() {
        val first = UiReducer.reduce(ready(), UiIntent.RequestRangeProbe(2048UL, 4096UL)).state
        val second =
            UiReducer.reduce(first, UiIntent.RequestRangeProbe(2048UL, 4096UL)).state
        val secondId = second.rangeProbeRequestId!!
        assertEquals(secondId - 1L, first.rangeProbeRequestId)

        val firstResult = probe(RawWriteRange(2048UL, 4096UL), requestId = first.rangeProbeRequestId!!)
        val stale = UiReducer.reduce(second, UiIntent.RangeProbeResulted(firstResult))
        assertNull(stale.state.rangeProbe)

        val fresh = probe(RawWriteRange(2048UL, 4096UL), requestId = secondId)
        val accepted = UiReducer.reduce(second, UiIntent.RangeProbeResulted(fresh))
        assertEquals(RangeProbeFinding.DataPresent, accepted.state.rangeProbe?.finding)
    }

    @Test
    fun staleAnswerDoesNotClearANewerAcceptedProbe() {
        val first = UiReducer.reduce(ready(), UiIntent.RequestRangeProbe(2048UL, 4096UL)).state
        val firstId = first.rangeProbeRequestId!!
        val second = UiReducer.reduce(first, UiIntent.RequestRangeProbe(2048UL, 4096UL)).state
        val secondId = second.rangeProbeRequestId!!
        val accepted =
            UiReducer.reduce(
                second,
                UiIntent.RangeProbeResulted(probe(RawWriteRange(2048UL, 4096UL), requestId = secondId)),
            ).state
        assertNotNull(accepted.rangeProbe)

        // The answer to the superseded request arrives late: it must not clear the fresh one.
        val lateFirst =
            UiReducer.reduce(
                accepted,
                UiIntent.RangeProbeResulted(probe(RawWriteRange(2048UL, 4096UL), requestId = firstId)),
            ).state
        assertEquals(secondId, lateFirst.rangeProbe?.requestId)
        assertEquals(RangeProbeFinding.DataPresent, lateFirst.rangeProbe?.finding)

        // Same request id but another attachment/geometry is stale too, and equally harmless.
        val lateGeometry =
            UiReducer.reduce(
                accepted,
                UiIntent.RangeProbeResulted(
                    probe(RawWriteRange(2048UL, 4096UL), requestId = secondId)
                        .copy(diskSectorCount = 999UL),
                ),
            ).state
        assertEquals(secondId, lateGeometry.rangeProbe?.requestId)
        val lateAttach =
            UiReducer.reduce(
                accepted,
                UiIntent.RangeProbeResulted(
                    probe(
                        RawWriteRange(2048UL, 4096UL),
                        attach = attachment.copy(attachGeneration = 8L),
                        requestId = secondId,
                    ),
                ),
            ).state
        assertEquals(secondId, lateAttach.rangeProbe?.requestId)
    }

    @Test
    fun attachChangeDropsProbeAndReplacesGeometry() {
        val withProbe = ready().copy(rangeProbe = probe(RawWriteRange(0UL, 1UL)))
        val changed =
            UiReducer.reduce(
                withProbe,
                UiIntent.DeviceChanged(
                    presence = DevicePresence.Ready,
                    device = device.copy(attachGeneration = 9L),
                    rawGeometry = geometry,
                ),
            )
        assertNull(changed.state.rangeProbe)
        assertEquals(geometry, changed.state.rawGeometry)

        val detached =
            UiReducer.reduce(
                withProbe,
                UiIntent.DeviceChanged(presence = DevicePresence.Disconnected),
            )
        assertNull(detached.state.rangeProbe)
        assertNull(detached.state.rawGeometry)
    }

    @Test
    fun tableZonesAreDistinctFromPartitions() {
        assertTrue(geometry.objects.any { it.kind == RawObjectKind.BootArea })
        assertTrue(geometry.objects.any { it.kind == RawObjectKind.Partition })
        assertEquals(listOf(0UL, 2048UL, 6144UL, 131_072UL), geometry.snapPoints)
    }
}
