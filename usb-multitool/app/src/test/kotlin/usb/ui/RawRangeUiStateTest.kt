package usb.ui

import org.junit.Assert.assertEquals
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
            UiReducer.reduce(state, UiIntent.RequestWriteRawRange(2048UL, 4096UL))
        val pending = result.state.pendingConfirmation
        assertNotNull(pending)
        assertEquals(DestructiveAction.RAW_WRITE, pending!!.action)
        assertEquals(2048UL, pending.rawStartLba)
        assertEquals(4096UL, pending.rawSectorCount)
        assertEquals(geometry.diskSectorCount, pending.diskSectorCount)
        assertEquals(geometry.sectorSize, pending.diskSectorSize)

        val confirmed = UiReducer.reduce(result.state, UiIntent.ConfirmDestructive)
        val effect = confirmed.effects.single()
        assertEquals(UiEffect.Kind.SubmitDestructive, effect.kind)
        assertEquals(2048UL, effect.confirmation!!.rawStartLba)
        assertEquals(4096UL, effect.confirmation!!.rawSectorCount)
    }

    @Test
    fun impossibleRangeIsRejectedBeforeConfirmation() {
        val result =
            UiReducer.reduce(
                ready(),
                UiIntent.RequestWriteRawRange(131_072UL, 2UL),
            )
        assertNull(result.state.pendingConfirmation)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun missingSourceOrGeometryBlocksConfirmation() {
        val noSource = UiReducer.reduce(ready().copy(imageUri = null), UiIntent.RequestWriteRawRange(0UL, 1UL))
        assertNull(noSource.state.pendingConfirmation)

        val noGeometry = UiReducer.reduce(ready().copy(rawGeometry = null), UiIntent.RequestWriteRawRange(0UL, 1UL))
        assertNull(noGeometry.state.pendingConfirmation)
    }

    @Test
    fun wholeMediumDefaultIsStartZeroAndFullDisk() {
        val result =
            UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.RAW_WRITE))
        val pending = result.state.pendingConfirmation!!
        assertEquals(0UL, pending.rawStartLba)
        assertEquals(geometry.diskSectorCount, pending.rawSectorCount)
    }

    @Test
    fun probeRequestEmitsEffectAndClearsStaleResult() {
        val stale = ready().copy(rangeProbe = probe(RawWriteRange(0UL, 10UL)))
        val result = UiReducer.reduce(stale, UiIntent.RequestRangeProbe(2048UL, 4096UL))
        assertNull(result.state.rangeProbe)
        assertEquals(RawWriteRange(2048UL, 4096UL), result.state.rangeProbeRequestedRange)
        val effect = result.effects.single()
        assertEquals(UiEffect.Kind.RangeProbe, effect.kind)
        assertEquals(2048UL, effect.startLba)
        assertEquals(4096UL, effect.sectorCount)
        assertEquals(result.state.rangeProbeRequestId, effect.probeRequestId)
        assertNotNull(effect.probeRequestId)
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
