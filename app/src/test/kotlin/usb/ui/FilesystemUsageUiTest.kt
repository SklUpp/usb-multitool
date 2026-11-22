package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.FilesystemId
import usb.fs.FilesystemSpace
import usb.fs.FilesystemAccess
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsagePhase
import usb.fs.FilesystemUsageReason
import usb.fs.FilesystemUsageSnapshot
import usb.partition.TableScheme

import usb.service.OperationTokenGenerator
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
        val effect = result.effects.single() as UiEffect.LoadFilesystemUsage
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
        val running = ready().copy(operation = OperationProgress(state = OperationState.Running, title = OperationTitle.FormatTarget("p1"), token = OperationTokenGenerator.next()))
        assertTrue(UiReducer.reduce(running, UiIntent.RequestFilesystemUsage).effects.isEmpty())
        val queued = ready().copy(operation = OperationProgress(state = OperationState.Queued, title = OperationTitle.Destructive(DestructiveAction.CREATE_PARTITION), token = OperationTokenGenerator.next()))
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
        val key = requireNotNull((requested.effects.single() as UiEffect.LoadFilesystemUsage).usageKey)
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
        val key = requireNotNull((requested.effects.single() as UiEffect.LoadFilesystemUsage).usageKey)
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
    fun matchingAnswerCarriesDriverAccessIntoState() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull((requested.effects.single() as UiEffect.LoadFilesystemUsage).usageKey)
        val published = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(
                1L,
                key,
                space(free = 1uL, total = 2uL),
                FilesystemAccess.ReadWrite,
            ),
        )
        assertEquals(FilesystemAccess.ReadWrite, requireNotNull(published.state.filesystemUsage).access)
    }

    @Test
    fun answerForAnOlderRequestIsDropped() {
        val first = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val firstKey = requireNotNull((first.effects.single() as UiEffect.LoadFilesystemUsage).usageKey)
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
        val key = requireNotNull((requested.effects.single() as UiEffect.LoadFilesystemUsage).usageKey)
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
        val key = requireNotNull((requested.effects.single() as UiEffect.LoadFilesystemUsage).usageKey)
        val published = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(1L, key, space(1uL, 2uL)),
        )
        assertNull(UiReducer.reduce(published.state, UiIntent.SelectPartition("2")).state.filesystemUsage)
    }

    @Test
    fun attachmentRefreshInvalidatesThePublishedValue() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull((requested.effects.single() as UiEffect.LoadFilesystemUsage).usageKey)
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
    fun writeInvalidatesAtStartAndAtEveryTerminalState() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsage)
        val key = requireNotNull((requested.effects.single() as UiEffect.LoadFilesystemUsage).usageKey)
        val published = UiReducer.reduce(
            requested.state,
            UiIntent.FilesystemUsageResulted(1L, key, space(1uL, 2uL)),
        )
        val uploadToken = OperationTokenGenerator.next()
        val running = UiReducer.reduce(
            published.state,
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Running,
                    title = OperationTitle.Upload,
                    token = uploadToken,
                ),
            ),
        )
        assertNull(running.state.filesystemUsage)
        assertTrue(running.state.partitionUsage.isEmpty())
        for (terminal in listOf(
            OperationState.Completed,
            OperationState.Failed,
            OperationState.Partial,
            OperationState.Cancelled,
        )) {
            val finished = UiReducer.reduce(
                running.state,
                UiIntent.OperationUpdated(
                    OperationProgress(state = terminal, title = OperationTitle.Upload, token = uploadToken),
                ),
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

    @Test
    fun failedProbeBusyTransitionDropsStaleGeometry() {
        val current = ready().copy(selectedPartitionId = "1")
        val busy = UiReducer.reduce(
            current,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Busy,
                device = device,
                partitions = emptyList(),
            ),
        )
        assertEquals(listOf(data, other), busy.state.partitions)
        val fenced = UiReducer.reduce(
            busy.state,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Busy,
                device = device,
                partitions = emptyList(),
                retainExisting = false,
                tableValid = false,
            ),
        )
        assertTrue(fenced.state.partitions.isEmpty())
        assertNull(fenced.state.selectedPartitionId)
        assertEquals(DevicePresence.Busy, fenced.state.presence)
    }

    @Test
    fun deviceReadyProductionSchedulerEmitsUsageEffectAfterConsumerClaimRelease() {
        val readyResult = UiReducer.reduce(
            AppUiState(),
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device,
                partitions = listOf(data, other),
                sectorSize = 512,
            ),
        )
        val readyState = readyResult.state
        assertEquals("1", readyState.selectedPartitionId)
        val scheduled = requireNotNull(
            scheduleFilesystemUsageRequest(readyState, null, null, operationFinished = false),
        )
        assertEquals(UiEffect.LoadFilesystemUsage::class, scheduled.effect::class)
        assertEquals("1", scheduled.effect.partitionId)
        assertEquals(scheduled.key, scheduled.effect.usageKey)
    }

    // ---- Sequential batch: ordering, fencing, park/resume, write invalidation ----

    private fun batchState(): AppUiState =
        UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsageBatch).state

    private fun AppUiState.measure(
        partitionId: String,
        free: ULong = 1uL,
        total: ULong = 4uL,
    ): AppUiState {
        val partition = partitions.first { it.id == partitionId }
        return UiReducer.reduce(
            this,
            UiIntent.FilesystemUsageStepResulted(
                filesystemUsageBatchGeneration,
                partitionId,
                requireNotNull(filesystemUsageKey(partition)),
                space(free, total),
            ),
        ).state
    }

    private fun AppUiState.written(): AppUiState {
        val token = OperationTokenGenerator.next()
        val running = UiReducer.reduce(
            this,
            UiIntent.OperationBegan(
                OperationProgress(state = OperationState.Running, title = OperationTitle.Upload, token = token),
            ),
        ).state
        return UiReducer.reduce(
            running,
            UiIntent.OperationUpdated(
                OperationProgress(state = OperationState.Completed, title = OperationTitle.Upload, token = token),
            ),
        ).state
    }

    @Test
    fun batchPlanPutsTheSelectedPartitionFirstAndSkipsUnmeasurableOnes() {
        assertEquals(listOf("1", "2"), ready().measurableFilesystemUsageKeys().map { it.first })
        assertEquals(keyFor(ready(), data), ready().measurableFilesystemUsageKeys().first().second)
        // Selected last still wins the first slot.
        val selectedOther = ready(selected = "2")
        assertEquals(listOf("2", "1"), selectedOther.measurableFilesystemUsageKeys().map { it.first })
        val partial = ready(partitions = listOf(data.copy(filesystemId = null), other))
        assertEquals(listOf("2"), partial.measurableFilesystemUsageKeys().map { it.first })
    }

    @Test
    fun batchRequestQueuesEveryMeasurablePartitionAndEmitsOneSequentialEffect() {
        val result = UiReducer.reduce(ready(), UiIntent.RequestFilesystemUsageBatch)
        val effect = result.effects.single() as UiEffect.LoadFilesystemUsageBatch
        assertEquals(1L, effect.usageGeneration)
        assertEquals(1L, result.state.filesystemUsageBatchGeneration)
        assertEquals(listOf("1", "2"), effect.steps.map { it.first })
        assertEquals(
            mapOf("1" to FilesystemUsagePhase.Queued, "2" to FilesystemUsagePhase.Queued),
            result.state.partitionUsagePhases,
        )
        assertTrue(result.state.partitionUsage.isEmpty())
        // Nothing is measured while a user operation owns the session: wait, do not fail.
        val running = ready().copy(
            operation = OperationProgress(
                state = OperationState.Running,
                title = OperationTitle.Upload,
                token = OperationTokenGenerator.next(),
            ),
        )
        val blocked = UiReducer.reduce(running, UiIntent.RequestFilesystemUsageBatch)
        assertTrue(blocked.effects.isEmpty())
        assertTrue(blocked.state.partitionUsagePhases.values.all { it == FilesystemUsagePhase.Waiting })
    }

    @Test
    fun sequentialStepsAccumulateResultsAndClearOnlyTheirOwnPhase() {
        val requested = batchState()
        val afterOther = requested.measure("2", free = 2uL, total = 8uL)
        assertEquals(1, afterOther.partitionUsage.size)
        // The selected row still belongs to partition 1; another partition's result never
        // overwrites it.
        assertNull(afterOther.filesystemUsage)
        assertNull(afterOther.partitionUsagePhase(other))
        assertEquals(FilesystemUsagePhase.Queued, afterOther.partitionUsagePhase(data))
        val afterData = afterOther.measure("1")
        assertEquals(2, afterData.partitionUsage.size)
        assertEquals(keyFor(afterData, data), requireNotNull(afterData.filesystemUsage).key)
        assertTrue(afterData.partitionUsagePhases.isEmpty())
        assertEquals(keyFor(afterData, other), requireNotNull(afterData.partitionUsageSnapshot(other)).key)
    }

    @Test
    fun stepAnswerFromAnOlderBatchGenerationIsDropped() {
        val first = batchState()
        val second = UiReducer.reduce(first, UiIntent.RequestFilesystemUsageBatch).state
        assertEquals(2L, second.filesystemUsageBatchGeneration)
        val stale = UiReducer.reduce(
            second,
            UiIntent.FilesystemUsageStepResulted(1L, "1", keyFor(second, data), space(1uL, 2uL)),
        ).state
        assertTrue(stale.partitionUsage.isEmpty())
        assertNull(stale.filesystemUsage)
    }

    @Test
    fun stepAnswerForChangedGeometryOrFilesystemIsDropped() {
        val requested = batchState()
        val live = keyFor(requested, data)
        val moved = live.copy(partitionStartLba = 4096uL)
        val resized = live.copy(partitionSectorCount = live.partitionSectorCount + 1uL)
        val otherFs = live.copy(filesystem = FilesystemId.NTFS)
        for (stale in listOf(moved, resized, otherFs)) {
            val dropped = UiReducer.reduce(
                requested,
                UiIntent.FilesystemUsageStepResulted(1L, "1", stale, space(1uL, 2uL)),
            ).state
            assertTrue("stale key $stale must not be stored", dropped.partitionUsage.isEmpty())
            assertNull(dropped.filesystemUsage)
        }
    }

    @Test
    fun selectingAnotherPartitionKeepsMeasuredMapAndClearsLegacySelectedSlot() {
        val measured = batchState().measure("1", free = 1uL, total = 4uL).measure("2", free = 2uL, total = 8uL)
        val switched = UiReducer.reduce(measured, UiIntent.SelectPartition("2")).state
        assertEquals(2, switched.partitionUsage.size)
        // Legacy selected-only callers still see a cleared slot and request again.
        assertNull(switched.filesystemUsage)
        assertTrue(switched.partitionUsagePhases.isEmpty())
        // Batch UI reads the durable map through helpers, never the cleared slot.
        assertEquals(keyFor(switched, other), requireNotNull(switched.partitionUsageSnapshot(other)).key)
        assertEquals(keyFor(switched, data), requireNotNull(switched.partitionUsageSnapshot(data)).key)
        val back = UiReducer.reduce(switched, UiIntent.SelectPartition("1")).state
        assertEquals(2, back.partitionUsage.size)
        assertNull(back.filesystemUsage)
        assertEquals(1uL, ((back.partitionUsageSnapshot(data)?.usage) as FilesystemUsage.Known).space.freeBytes)
    }

    @Test
    fun deferredStepShowsWaitingAndStoresNoUnavailableValue() {
        val requested = batchState()
        val waiting = UiReducer.reduce(
            requested,
            UiIntent.FilesystemUsageStepDeferred(1L, "1", keyFor(requested, data)),
        ).state
        assertEquals(FilesystemUsagePhase.Waiting, waiting.partitionUsagePhase(data))
        // The stopped batch parks every remaining step as Waiting, not failed.
        assertEquals(FilesystemUsagePhase.Waiting, waiting.partitionUsagePhase(other))
        assertTrue(waiting.partitionUsage.isEmpty())
        assertNull(waiting.filesystemUsage)
    }

    @Test
    fun waitingIsPublishedBeforeTheLockAndCalculatingOnlyAfterwards() {
        val requested = batchState()
        val waiting = UiReducer.reduce(
            requested,
            UiIntent.FilesystemUsageStepWaiting(1L, "1", keyFor(requested, data)),
        ).state
        assertEquals(FilesystemUsagePhase.Waiting, waiting.partitionUsagePhase(data))
        val calculating = UiReducer.reduce(
            waiting,
            UiIntent.FilesystemUsageStepCalculating(1L, "1", keyFor(waiting, data)),
        ).state
        assertEquals(FilesystemUsagePhase.Calculating, calculating.partitionUsagePhase(data))
        assertEquals(FilesystemUsagePhase.Queued, calculating.partitionUsagePhase(other))
    }

    @Test
    fun cancelledBatchParksEveryUnfinishedPartitionAndKeepsResults() {
        val partial = batchState().measure("1").let {
            UiReducer.reduce(
                it,
                UiIntent.FilesystemUsageStepCalculating(1L, "2", keyFor(it, other)),
            ).state
        }
        val cancelled = UiReducer.reduce(partial, UiIntent.CancelFilesystemUsageBatch).state
        assertEquals(2L, cancelled.filesystemUsageBatchGeneration)
        assertEquals(FilesystemUsagePhase.Waiting, cancelled.partitionUsagePhase(other))
        assertEquals(1, cancelled.partitionUsage.size)
        // A Calculating queued by a coroutine still inside the (non-interruptible) monitor
        // cannot revive the cancelled batch.
        val late = UiReducer.reduce(
            cancelled,
            UiIntent.FilesystemUsageStepCalculating(1L, "2", keyFor(cancelled, other)),
        ).state
        assertEquals(FilesystemUsagePhase.Waiting, late.partitionUsagePhase(other))
    }

    @Test
    fun finishedWriteClearsTheMapAndDropsInFlightSteps() {
        val measured = batchState().measure("1")
        val finished = measured.written()
        assertTrue(finished.partitionUsage.isEmpty())
        assertNull(finished.filesystemUsage)
        assertTrue(finished.filesystemUsageBatchGeneration > measured.filesystemUsageBatchGeneration)
        // A value measured before the write can never surface after it.
        val late = UiReducer.reduce(
            finished,
            UiIntent.FilesystemUsageStepResulted(
                measured.filesystemUsageBatchGeneration,
                "1",
                keyFor(finished, data),
                space(1uL, 4uL),
            ),
        ).state
        assertTrue(late.partitionUsage.isEmpty())
        assertNull(late.filesystemUsage)
    }

    @Test
    fun readOnlyTerminalKeepsTheMapSoRowsStillShowSpace() {
        val measured = batchState().measure("1")
        val token = OperationTokenGenerator.next()
        val running = UiReducer.reduce(
            measured,
            UiIntent.OperationBegan(
                OperationProgress(state = OperationState.Running, title = OperationTitle.ReadScan, token = token),
            ),
        ).state
        val finished = UiReducer.reduce(
            running,
            UiIntent.OperationUpdated(
                OperationProgress(state = OperationState.Completed, title = OperationTitle.ReadScan, token = token),
            ),
        ).state
        assertEquals(1, finished.partitionUsage.size)
        // Read-only terminal rebinds the selected slot from the durable map.
        assertEquals(keyFor(finished, data), requireNotNull(finished.filesystemUsage).key)
        assertEquals(keyFor(finished, data), requireNotNull(finished.partitionUsageSnapshot(data)).key)
        assertTrue(finished.partitionUsagePhases.isEmpty())
        // Geometry change on the same attachment drops keys that no longer match.
        val resized = listOf(data.copy(sectorCount = requireNotNull(data.sectorCount) + 8uL), other)
        val refreshed = UiReducer.reduce(
            finished,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Busy,
                device = device,
                partitions = resized,
            ),
        ).state
        assertTrue(refreshed.partitionUsage.isEmpty())
    }

    @Test
    fun pausedBatchIsNotRescheduledInALoop() {
        val requested = batchState()
        val deferred = UiReducer.reduce(
            requested,
            UiIntent.FilesystemUsageStepDeferred(1L, "1", keyFor(requested, data)),
        ).state
        // Waiting phases pause the plan; a refresh must not restart it (Busy retry loop).
        assertNull(
            scheduleFilesystemUsageBatchRequest(
                deferred,
                lastBatchKeys = requested.measurableFilesystemUsageKeys().map { it.second }.toSet(),
                batchInFlight = false,
                operationFinished = false,
            ),
        )
        // While any batch job is live nothing else may open a second claim.
        assertNull(
            scheduleFilesystemUsageBatchRequest(
                batchState(),
                lastBatchKeys = null,
                batchInFlight = true,
                operationFinished = false,
            ),
        )
        // A write terminal clears the paused phases; the next refresh measures once.
        val scheduled = requireNotNull(
            scheduleFilesystemUsageBatchRequest(
                deferred.written(),
                lastBatchKeys = deferred.measurableFilesystemUsageKeys().map { it.second }.toSet(),
                batchInFlight = false,
                operationFinished = true,
            ),
        )
        assertEquals(listOf("1", "2"), scheduled.steps.map { it.first })
        // Steady state after every step answered: nothing is asked again.
        val complete = scheduled.state.measure("1").measure("2")
        assertNull(
            scheduleFilesystemUsageBatchRequest(
                complete,
                lastBatchKeys = scheduled.steps.map { it.second }.toSet(),
                batchInFlight = false,
                operationFinished = false,
            ),
        )
    }

    @Test
    fun staleDeferredParksWaitingWithoutImmediateReschedule() {
        val requested = batchState()
        val generation = requested.filesystemUsageBatchGeneration
        // Host maps Stale -> FilesystemUsageStepDeferred; park, never bump.
        val parked = UiReducer.reduce(
            requested,
            UiIntent.FilesystemUsageStepDeferred(generation, "1", keyFor(requested, data)),
        ).state
        assertEquals(generation, parked.filesystemUsageBatchGeneration)
        assertEquals(FilesystemUsagePhase.Waiting, parked.partitionUsagePhase(data))
        assertEquals(FilesystemUsagePhase.Waiting, parked.partitionUsagePhase(other))
        // Phases still present: scheduler must not open another mount/close loop.
        assertNull(
            scheduleFilesystemUsageBatchRequest(
                parked,
                lastBatchKeys = requested.measurableFilesystemUsageKeys().map { it.second }.toSet(),
                batchInFlight = false,
                operationFinished = false,
            ),
        )
        // Meaningful event clears phases; only then may missing keys resume.
        val dismissed = UiReducer.reduce(
            parked.copy(
                pendingConfirmation = requireNotNull(
                    UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.WIPE))
                        .state.pendingConfirmation,
                ),
            ),
            UiIntent.DismissConfirmation,
        ).state
        assertTrue(dismissed.partitionUsagePhases.isEmpty())
        assertTrue(dismissed.filesystemUsageBatchGeneration > generation)
        val scheduled = requireNotNull(
            scheduleFilesystemUsageBatchRequest(dismissed, lastBatchKeys = null, batchInFlight = false, operationFinished = false),
        )
        assertEquals(listOf("1", "2"), scheduled.steps.map { it.first })
    }

    @Test
    fun filesystemSpaceReadsDurableMapAfterSelectClearsLegacySlot() {
        val measured = batchState().measure("1", free = 3uL, total = 9uL).measure("2", free = 4uL, total = 8uL)
        val switched = UiReducer.reduce(measured, UiIntent.SelectPartition("2")).state
        assertNull(switched.filesystemUsage)
        // AppShell helper must still show map-backed space for every measured row.
        assertEquals(4uL, requireNotNull(switched.filesystemSpace(other)).freeBytes)
        assertEquals(3uL, requireNotNull(switched.filesystemSpace(data)).freeBytes)
    }

    @Test
    fun readyRefreshRestartsPausedBatchAndRejectsOldAttachmentResult() {
        val requested = batchState()
        val key = keyFor(requested, data)
        val deferred = UiReducer.reduce(
            requested, UiIntent.FilesystemUsageStepDeferred(1L, "1", key),
        ).state
        val refreshed = UiReducer.reduce(
            deferred,
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = device.copy(attachGeneration = device.attachGeneration + 1L),
                partitions = listOf(data, other),
                sectorSize = 512,
            ),
        ).state
        assertTrue(refreshed.partitionUsagePhases.isEmpty())
        assertTrue(refreshed.partitionUsage.isEmpty())
        val scheduled = requireNotNull(scheduleFilesystemUsageBatchRequest(refreshed, null, false, false))
        val stale = UiReducer.reduce(
            scheduled.state, UiIntent.FilesystemUsageStepResulted(1L, "1", key, space(1uL, 2uL)),
        ).state
        assertTrue(stale.partitionUsage.isEmpty())
    }

    @Test
    fun readOnlyTerminalResumesOnlyUnfinishedPartitions() {
        val partial = batchState().measure("1")
        val deferred = UiReducer.reduce(
            partial, UiIntent.FilesystemUsageStepDeferred(1L, "2", keyFor(partial, other)),
        ).state
        val token = OperationTokenGenerator.next()
        val running = UiReducer.reduce(
            deferred, UiIntent.OperationBegan(
                OperationProgress(state = OperationState.Running, title = OperationTitle.ReadScan, token = token),
            ),
        ).state
        val terminal = UiReducer.reduce(
            running, UiIntent.OperationUpdated(
                OperationProgress(state = OperationState.Completed, title = OperationTitle.ReadScan, token = token),
            ),
        ).state
        assertTrue(terminal.partitionUsagePhases.isEmpty())
        // Selected snapshot rebinds from the durable map; write ops alone wipe it.
        assertEquals(keyFor(terminal, data), requireNotNull(terminal.filesystemUsage).key)
        assertEquals(keyFor(terminal, data), requireNotNull(terminal.partitionUsageSnapshot(data)).key)
        val scheduled = requireNotNull(scheduleFilesystemUsageBatchRequest(terminal, null, false, true))
        assertEquals(listOf("2"), scheduled.steps.map { it.first })
        assertEquals(1, scheduled.state.partitionUsage.size)
    }

    @Test
    fun dismissConfirmationClearsPhasesSoMissingKeysCanResume() {
        val confirming = requireNotNull(
            UiReducer.reduce(ready(), UiIntent.RequestDestructive(DestructiveAction.WIPE))
                .state.pendingConfirmation,
        )
        val parked = batchState().copy(
            pendingConfirmation = confirming,
            partitionUsagePhases = mapOf(
                "1" to FilesystemUsagePhase.Waiting,
                "2" to FilesystemUsagePhase.Waiting,
            ),
        )
        val dismissed = UiReducer.reduce(parked, UiIntent.DismissConfirmation).state
        assertNull(dismissed.pendingConfirmation)
        assertTrue(dismissed.partitionUsagePhases.isEmpty())
        assertTrue(dismissed.filesystemUsageBatchGeneration > parked.filesystemUsageBatchGeneration)
        val scheduled = requireNotNull(
            scheduleFilesystemUsageBatchRequest(dismissed, lastBatchKeys = null, batchInFlight = false, operationFinished = false),
        )
        assertEquals(listOf("1", "2"), scheduled.steps.map { it.first })
    }

    @Test
    fun ejectFailureAndBusyClearPhasesForResume() {
        val requested = UiReducer.reduce(ready(), UiIntent.RequestEject).state
        val withPhases = requested.copy(
            partitionUsagePhases = mapOf("1" to FilesystemUsagePhase.Waiting, "2" to FilesystemUsagePhase.Queued),
        )
        val failed = UiReducer.reduce(
            withPhases,
            UiIntent.EjectResulted(
                ejectGeneration = withPhases.ejectGeneration,
                identity = device.identity,
                attachGeneration = device.attachGeneration,
                succeeded = false,
                message = "SYNCHRONIZE CACHE failed",
            ),
        ).state
        assertTrue(failed.actionsEnabled)
        assertTrue(failed.partitionUsagePhases.isEmpty())
        val busyParked = withPhases.copy(ejectInFlight = true)
        val busy = UiReducer.reduce(
            busyParked,
            UiIntent.EjectResulted(
                ejectGeneration = busyParked.ejectGeneration,
                identity = device.identity,
                attachGeneration = device.attachGeneration,
                succeeded = false,
                busy = true,
            ),
        ).state
        assertTrue(busy.partitionUsagePhases.isEmpty())
        assertTrue(busy.actionsEnabled)
        val scheduled = requireNotNull(
            scheduleFilesystemUsageBatchRequest(failed, lastBatchKeys = null, batchInFlight = false, operationFinished = false),
        )
        assertEquals(listOf("1", "2"), scheduled.steps.map { it.first })
    }

}
