package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.fs.FilesystemSpace
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsageReason
import usb.fs.FilesystemUsageSnapshot
import usb.partition.TableScheme

/**
 * Free-space wiring: the reducer asks for a measurement bound to the live attachment and
 * geometry, publishes it only while that binding still holds, and drops stale answers.
 */
class FilesystemUsageUiTest {
    private val device = DeviceIdentity(label = "Test Stick", capacityBytes = 8_000_000_000uL)

    private val data = PartitionTab(
        id = "1",
        name = "DATA",
        readOnly = false,
        startLba = 2048uL,
        sectorCount = 4096uL,
        filesystemId = FilesystemId.FAT,
    )
    private val other = PartitionTab(
        id = "2",
        name = "OTHER",
        readOnly = false,
        startLba = 8192uL,
        sectorCount = 2048uL,
        filesystemId = FilesystemId.NTFS,
    )

    private fun ready(
        selected: String = "1",
        partitions: List<PartitionTab> = listOf(data, other),
        sectorSize: Int = 512,
    ) = AppUiState(
        presence = DevicePresence.Ready,
        device = device,
        partitions = partitions,
        selectedPartitionId = selected,
        diskSectorSize = sectorSize,
        partitionScheme = TableScheme.GPT,
    )

    private fun keyFor(state: AppUiState, partition: PartitionTab): FilesystemUsageKey =
        requireNotNull(state.filesystemUsageKey(partition))

    private fun space(free: ULong, total: ULong) =
        FilesystemUsage.Known(FilesystemSpace(totalBytes = total, freeBytes = free), measuredAtMillis = 7L)

    @Test
    fun requestBindsTheEffectToTheLiveKeyAndGeneration() {
        val state = ready()
        val result = UiReducer.reduce(state, UiIntent.RequestFilesystemUsage)
        val effect = result.effects.single()
        assertEquals(UiEffect.Kind.LoadFilesystemUsage, effect.kind)
        assertEquals("1", effect.partitionId)
        assertEquals(1L, effect.usageGeneration)
        assertEquals(keyFor(state, data), effect.usageKey)
        assertEquals(1L, result.state.filesystemUsageGeneration)
        assertNull(result.state.filesystemUsage)
    }

    @Test
    fun keyCarriesAttachmentGeometryAndFilesystem() {
        val key = keyFor(ready(sectorSize = 4096), data)
        assertEquals(device.attachGeneration, key.attachGeneration)
        assertEquals(device.identity, key.identity)
        assertEquals(2048uL, key.partitionStartLba)
        assertEquals(4096uL, key.partitionSectorCount)
        assertEquals(4096, key.sectorSize)
        assertEquals(FilesystemId.FAT, key.filesystem)
    }

    @Test
    fun unknownGeometryOrFilesystemAsksForNothing() {
        val noFilesystem = data.copy(filesystemId = null)
        val noGeometry = data.copy(startLba = null)
        for (partition in listOf(noFilesystem, noGeometry)) {
            val state = ready(partitions = listOf(partition))
            assertNull(state.filesystemUsageKey(partition))
            val result = UiReducer.reduce(state, UiIntent.RequestFilesystemUsage)
            assertTrue(result.effects.isEmpty())
            assertNull(result.state.filesystemUsage)
        }
    }

    @Test
    fun noRequestWhileAnOperationRunsOrAConfirmationIsOpen() {
        val running = ready().copy(operation = OperationProgress(state = OperationState.Running, label = "Format"))
        assertTrue(UiReducer.reduce(running, UiIntent.RequestFilesystemUsage).effects.isEmpty())
        val queued = ready().copy(operation = OperationProgress(state = OperationState.Queued, label = "Create"))
        assertTrue(UiReducer.reduce(queued, UiIntent.RequestFilesystemUsage).effects.isEmpty())
        val absent = ready().copy(presence = DevicePresence.Disconnected)
        assertTrue(UiReducer.reduce(absent, UiIntent.RequestFilesystemUsage).effects.isEmpty())
        val confirming = requireNotNull(
            UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.WIPE))
                .state.pendingConfirmation,
        )
        val blocked = UiReducer.reduce(
            ready().copy(pendingConfirmation = confirming),
            UiIntent.RequestFilesystemUsage,
        )
        assertTrue(blocked.effects.isEmpty())
        assertNull(blocked.state.filesystemUsage)
    }

    @Test
    fun matchingAnswerIsPublished() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull(requested.effects.single().usageKey)
        val published = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(1L, key, space(free = 1024uL, total = 4096uL)),
        )
        val snapshot = requireNotNull(published.state.filesystemUsage)
        assertEquals(key, snapshot.key)
        val known = snapshot.usage as FilesystemUsage.Known
        assertEquals(1024uL, known.space.freeBytes)
        assertEquals(4096uL, known.space.totalBytes)
        assertEquals(3072uL, known.space.usedBytes)
        assertTrue(snapshot.matches(keyFor(published.state, data)))
    }

    @Test
    fun unavailableAnswerIsStoredAsUnknownNotZero() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull(requested.effects.single().usageKey)
        val published = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(
                1L,
                key,
                FilesystemUsage.Unavailable(FilesystemUsageReason.EngineMissing),
            ),
        )
        val usage = requireNotNull(published.state.filesystemUsage).usage
        assertEquals(FilesystemUsageReason.EngineMissing, (usage as FilesystemUsage.Unavailable).reason)
    }

    @Test
    fun answerForAnOlderRequestIsDropped() {
        val first = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val firstKey = requireNotNull(first.effects.single().usageKey)
        val second = UiReducer.reduce(first.state, UiIntent.RequestFilesystemUsage)
        assertEquals(2L, second.state.filesystemUsageGeneration)
        val stale = UiReducer.reduce(
            second.state,
            UiIntent.FilesystemUsageResulted(1L, firstKey, space(1uL, 2uL)),
        )
        assertNull(stale.state.filesystemUsage)
    }

    @Test
    fun answerForAnotherAttachmentOrGeometryIsDropped() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull(requested.effects.single().usageKey)
        val otherIdentity = key.copy(identity = "another-stick")
        val otherGeometry = key.copy(partitionSectorCount = key.partitionSectorCount + 1uL)
        val otherSectorSize = key.copy(sectorSize = 4096)
        val otherAttachment = key.copy(attachGeneration = key.attachGeneration + 1L)
        val otherFilesystem = key.copy(filesystem = FilesystemId.NTFS)
        for (stale in listOf(otherIdentity, otherGeometry, otherSectorSize, otherAttachment, otherFilesystem)) {
            val result = UiReducer.reduce(
                requested.state,
                UiIntent.FilesystemUsageResulted(1L, stale, space(1uL, 2uL)),
            )
            assertNull("stale key $stale must not be published", result.state.filesystemUsage)
        }
    }

    @Test
    fun answerForAnotherSelectionIsDropped() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val otherKey = keyFor(requested.state, other)
        val result = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(1L, otherKey, space(1uL, 2uL)),
        )
        assertNull(result.state.filesystemUsage)
    }

    @Test
    fun selectionChangeInvalidatesThePublishedValue() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull(requested.effects.single().usageKey)
        val published = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(1L, key, space(1uL, 2uL)),
        )
        assertNull(UiReducer.reduce(published.state, UiIntent.SelectPartition("2")).state.filesystemUsage)
    }

    @Test
    fun attachmentRefreshInvalidatesThePublishedValue() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull(requested.effects.single().usageKey)
        val published = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(1L, key, space(1uL, 2uL)),
        )
        val refreshed = UiReducer.reduce(
            published.state,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device.copy(attachGeneration = device.attachGeneration + 1L),
                partitions = listOf(data, other),
                sectorSize = 512,
            ),
        )
        assertNull(refreshed.state.filesystemUsage)
    }

    @Test
    fun finishedOperationInvalidatesButRunningDoesNot() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull(requested.effects.single().usageKey)
        val published = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(1L, key, space(1uL, 2uL)),
        )
        val running = UiReducer.reduce(
            published.state,
            UiIntent.OperationUpdated(OperationProgress(state = OperationState.Running, label = "Upload")),
        )
        assertEquals(key, requireNotNull(running.state.filesystemUsage).key)
        for (terminal in listOf(
            OperationState.Completed,
            OperationState.Failed,
            OperationState.Partial,
            OperationState.Cancelled,
        )) {
            val finished = UiReducer.reduce(
                published.state,
                UiIntent.OperationUpdated(OperationProgress(state = terminal, label = "Upload")),
            )
            assertNull("$terminal must invalidate the measured value", finished.state.filesystemUsage)
        }
    }

    @Test
    fun snapshotFromAnotherKeyIsNotRendered() {
        val key = keyFor(ready(), data)
        val snapshot = FilesystemUsageSnapshot(key, space(1uL, 2uL))
        assertTrue(snapshot.matches(key))
        assertTrue(!snapshot.matches(key.copy(partitionStartLba = key.partitionStartLba + 8uL)))
    }

    @Test
    fun sameKeyAttachmentRefreshAsksAgain() {
        val key = keyFor(ready(), data)
        val measured = ready().copy(filesystemUsage = FilesystemUsageSnapshot(key, space(1uL, 2uL)))
        // Fast path: the state still holds a measurement for the key already requested.
        assertTrue(!measured.needsFilesystemUsageRequest(key, key, null, operationFinished = false))
        // A resume/partition refresh publishes the same attachment and geometry again...
        val refreshed = UiReducer.reduce(
            measured,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = measured.device,
                partitions = measured.partitions,
                sectorSize = measured.diskSectorSize,
            ),
        )
        assertNull(refreshed.state.filesystemUsage)
        assertEquals(key, refreshed.state.filesystemUsageKey(data))
        // ...and drops the snapshot, so an identical key is no proof the value is current.
        assertTrue(refreshed.state.needsFilesystemUsageRequest(key, key, null, operationFinished = false))
    }

    @Test
    fun sameKeySelectionInvalidationAsksAgain() {
        val key = keyFor(ready(), data)
        val measured = ready().copy(filesystemUsage = FilesystemUsageSnapshot(key, space(1uL, 2uL)))
        val reselected = UiReducer.reduce(measured, UiIntent.SelectPartition("1"))
        assertNull(reselected.state.filesystemUsage)
        assertTrue(reselected.state.needsFilesystemUsageRequest(key, key, null, operationFinished = false))
    }

    @Test
    fun pendingRequestIsNotDuplicated() {
        val key = keyFor(ready(), data)
        val pending = ready() // request emitted, answer not published yet
        assertTrue(!pending.needsFilesystemUsageRequest(key, key, key, operationFinished = false))
        // A pending request for another key does not suppress the current one.
        assertTrue(pending.needsFilesystemUsageRequest(key, key, key.copy(sectorSize = 4096), false))
        assertTrue(pending.needsFilesystemUsageRequest(key, null, key, false))
    }

    @Test
    fun anotherKeyAlwaysAsksEvenWithAStaleSnapshot() {
        val stale = keyFor(ready(), data)
        val current = keyFor(ready(), other)
        val measured = ready().copy(filesystemUsage = FilesystemUsageSnapshot(stale, space(1uL, 2uL)))
        assertTrue(measured.needsFilesystemUsageRequest(current, stale, null, operationFinished = false))
    }

    @Test
    fun finishedOperationAsksAgainEvenWhenMeasured() {
        val key = keyFor(ready(), data)
        val measured = ready().copy(filesystemUsage = FilesystemUsageSnapshot(key, space(1uL, 2uL)))
        assertTrue(measured.needsFilesystemUsageRequest(key, key, key, operationFinished = true))
    }

    @Test
    fun noMeasurableSelectionAsksNothing() {
        assertTrue(!ready().needsFilesystemUsageRequest(null, null, null, operationFinished = false))
        assertTrue(!ready().needsFilesystemUsageRequest(null, null, null, operationFinished = true))
    }

    @Test
    fun refreshCycleConvergesToOneRequestPerInvalidation() {
        // Mirrors MainActivity.refreshFilesystemUsage: exactly one request per key change
        // or invalidation, never one per incoming intent, and no loop after an answer.
        var current = ready()
        val key = keyFor(current, data)
        var lastRequested: FilesystemUsageKey? = null
        var inFlight: FilesystemUsageKey? = null
        var requests = 0
        fun refresh(finished: Boolean = false) {
            if (current.needsFilesystemUsageRequest(key, lastRequested, inFlight, finished)) {
                requests++
                lastRequested = key
                inFlight = key
                current = UiReducer.reduce(current, UiIntent.RequestFilesystemUsage).state
            }
        }
        fun publish(usage: FilesystemUsage) {
            val generation = current.filesystemUsageGeneration
            current = UiReducer.reduce(
                current,
                UiIntent.FilesystemUsageResulted(generation, key, usage),
            ).state
            if (generation == current.filesystemUsageGeneration) inFlight = null
        }
        refresh()
        repeat(5) { refresh() } // any number of intents while the answer is still pending
        assertEquals(1, requests)
        publish(space(free = 10uL, total = 20uL))
        repeat(5) { refresh() } // steady state: the snapshot matches, nothing to ask
        assertEquals(1, requests)
        current = UiReducer.reduce(current, UiIntent.SelectPartition("1")).state // same key
        assertNull(current.filesystemUsage)
        refresh()
        assertEquals(2, requests)
        publish(FilesystemUsage.Unavailable(FilesystemUsageReason.IoFailure))
        repeat(5) { refresh() } // Unavailable also matches its key: no loop
        assertEquals(2, requests)
        refresh(finished = true) // a finished operation always re-measures
        assertEquals(3, requests)
    }
}
