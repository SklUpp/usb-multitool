package usb.multitool

import android.hardware.usb.UsbDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import usb.device.UsbExclusiveClaim
import usb.files.AttachmentChangedException
import usb.files.AttachmentPin
import usb.files.AttachmentState
import usb.files.FilesVolumeSession
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsageReason
import usb.fs.FilesystemUsageSnapshot
import usb.ui.AppUiState
import usb.ui.UiIntent
import usb.ui.UiReducer
import usb.ui.filesystemUsageKey
import usb.ui.scheduleFilesystemUsageBatchRequest

/**
 * Owns the one sequential free-space batch. Selected rows and the batch share this
 * coordinator, so two jobs can never race for the USB claim.
 *
 * A user operation cancels the batch and leaves unfinished partitions `Waiting`
 * (never `Unavailable`, never Busy-as-disconnect). A terminal write clears the plan,
 * so the next refresh measures it again; a paused batch resumes on a later
 * operation terminal or device refresh, avoiding a Busy retry loop.
 */
internal class FilesystemUsageCoordinator(
    private val scope: CoroutineScope,
    private val state: () -> AppUiState,
    private val updateState: (AppUiState) -> Unit,
    private val attachment: () -> AttachmentState.Snapshot<UsbDevice>,
    private val deviceKey: (UsbDevice?) -> String?,
    private val session: FilesVolumeSession,
    private val intents: (UiIntent) -> Unit,
    private val deferredIntents: (UiIntent) -> Unit,
) {
    /** Newest batch plan. An unchanged plan over a complete map is not measured again. */
    @Volatile private var lastBatchKeys: Set<FilesystemUsageKey>? = null
    private var job: Job? = null
    /** Token of the live batch job; a replaced job never clears the flag for its successor. */
    @Volatile private var jobToken = 0L
    @Volatile private var inFlight = false

    /** Forget the last plan (operation began, detach, phase drop). */
    fun resetPlan() {
        lastBatchKeys = null
    }

    fun refresh(intent: UiIntent) {
        if (intent is UiIntent.OperationBegan) return
        if (intent is UiIntent.DeviceChanged && state().actionsEnabled) {
            lastBatchKeys = null
        }
        // Events that drop parked phases must also forget the last plan, or resume is skipped.
        if (intent is UiIntent.DismissConfirmation ||
            intent is UiIntent.EjectResulted ||
            intent is UiIntent.SelectPartition
        ) {
            lastBatchKeys = null
        }
        val finished =
            intent is UiIntent.OperationUpdated &&
                intent.progress.state != usb.ui.OperationState.Running &&
                intent.progress.state != usb.ui.OperationState.Queued
        // Busy/confirmation/operation: no second claim. Phases stay Waiting.
        if (!state().actionsEnabled) return
        val scheduled = scheduleFilesystemUsageBatchRequest(
            state = state(),
            lastBatchKeys = lastBatchKeys,
            batchInFlight = inFlight,
            operationFinished = finished,
        ) ?: return
        lastBatchKeys = scheduled.steps.map { it.second }.toSet()
        updateState(scheduled.state)
        startBatch(scheduled.steps)
    }

    /**
     * Stop the one usage job. Cancellation does not interrupt a coroutine already inside
     * the session monitor (synchronized is not interruptible); the step refuses to open
     * a volume once it observes the cancellation, and the epoch is closed on the way out.
     * [markWaiting] turns Queued/Calculating into Waiting for the next batch pass.
     */
    fun cancel(markWaiting: Boolean) {
        job?.cancel()
        inFlight = false
        jobToken += 1L
        if (markWaiting) {
            updateState(UiReducer.reduce(state(), UiIntent.CancelFilesystemUsageBatch).state)
        }
    }

    private sealed interface UsageStepOutcome {
        data class Measured(val snapshot: FilesystemUsageSnapshot, val epoch: Long? = null) : UsageStepOutcome
        /** Exclusive claim or a user operation owns the session: pause, do not fail. */
        data object Deferred : UsageStepOutcome
        /** Attachment/geometry/epoch changed: drop this step, publish nothing. */
        data object Stale : UsageStepOutcome
    }

    /**
     * Sequential batch owner. Steps run one at a time: never two mounts, never a second
     * claim. Reading free space is one blocking engine call with no progress callback, so
     * phases stay ordinal (Queued/Waiting/Calculating) and never fake a percentage.
     *
     * `Waiting` is published before contending for the shared session lock; `Calculating`
     * only from inside the lock, immediately before the engine call. A step that observes
     * a cancellation, an attachment change, a write fence or a Busy claim publishes
     * nothing stale — the batch stops and unfinished partitions stay Waiting.
     */
    fun startBatch(steps: List<Pair<String, FilesystemUsageKey>>) {
        if (steps.isEmpty()) return
        val live = attachment()
        val expectedGeneration = live.generation
        val expectedIdentity = state().device?.identity
        val pin = live.device?.let { AttachmentPin(live.generation, deviceKey(it)!!) }
        val generation = state().filesystemUsageBatchGeneration
        // A cancelled synchronized call may still be running. Join it before opening
        // another volume; cancellation alone does not release the session monitor.
        val previousJob = job
        previousJob?.cancel()
        jobToken += 1L
        val batchToken = jobToken
        inFlight = true
        job = scope.launch(Dispatchers.IO) {
            try {
                previousJob?.join()
                for ((partitionId, key) in steps) {
                    val proceed = withContext(Dispatchers.Main.immediate) {
                        state().filesystemUsageBatchGeneration == generation &&
                            state().actionsEnabled
                    }
                    if (!proceed) {
                        withContext(Dispatchers.Main.immediate) {
                            if (state().filesystemUsageBatchGeneration == generation) {
                                intents(
                                    UiIntent.FilesystemUsageStepDeferred(generation, partitionId, key),
                                )
                            }
                        }
                        break
                    }
                    // Contending for the session monitor: Waiting, not Calculating.
                    deferredIntents(
                        UiIntent.FilesystemUsageStepWaiting(generation, partitionId, key),
                    )
                    var retries = 0
                    var outcome = measureStep(
                        generation = generation,
                        partitionId = partitionId,
                        key = key,
                        expectedGeneration = expectedGeneration,
                        expectedIdentity = expectedIdentity,
                        pin = pin,
                    )
                    while (outcome == UsageStepOutcome.Deferred && retries < 3) {
                        // No release notification exists. Bounded backoff avoids a retry
                        // storm; cancellation interrupts the delay before another open.
                        deferredIntents(UiIntent.FilesystemUsageStepWaiting(generation, partitionId, key))
                        delay(1000L)
                        val stillCurrent = withContext(Dispatchers.Main.immediate) {
                            state().actionsEnabled &&
                                state().filesystemUsageBatchGeneration == generation &&
                                state().partitions.firstOrNull { it.id == partitionId }
                                    ?.let { state().filesystemUsageKey(it) } == key
                        }
                        if (!stillCurrent) break
                        retries++
                        outcome = measureStep(
                            generation, partitionId, key, expectedGeneration, expectedIdentity, pin,
                        )
                    }
                    when (val result = outcome) {
                        UsageStepOutcome.Deferred -> {
                            withContext(Dispatchers.Main.immediate) {
                                intents(UiIntent.FilesystemUsageStepDeferred(generation, partitionId, key))
                            }
                            break
                        }
                        UsageStepOutcome.Stale -> {
                            // Attachment/geometry fence: park unfinished as Waiting.
                            // Do not clear phases or bump generation here — that would let
                            // finally reschedule immediately and thrash mount/close.
                            // Resume only on terminal / DeviceChanged / dismiss / reselect.
                            withContext(Dispatchers.Main.immediate) {
                                intents(UiIntent.FilesystemUsageStepDeferred(generation, partitionId, key))
                            }
                            break
                        }
                        is UsageStepOutcome.Measured -> {
                            try {
                                withContext(Dispatchers.Main.immediate) {
                                    intents(
                                        UiIntent.FilesystemUsageStepResulted(
                                            generation = generation,
                                            partitionId = partitionId,
                                            key = result.snapshot.key,
                                            usage = result.snapshot.usage,
                                            access = result.snapshot.access,
                                        ),
                                    )
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                result.epoch?.let { epoch ->
                                    session.withLock { session.closeIfEpochLocked(epoch) }
                                }
                                throw cancel
                            }
                        }
                    }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                    // A replacement job already raised the token; never clear its flag.
                    if (jobToken == batchToken) {
                        inFlight = false
                        job = null
                        if (state().filesystemUsageBatchGeneration != generation && state().actionsEnabled) {
                            lastBatchKeys = null
                            refresh(UiIntent.RequestFilesystemUsageBatch)
                        }
                    }
                }
            }
        }
    }

    /**
     * One step under the files session lock. A volume that will not open reports
     * "unavailable" instead of an invented value; a Busy claim/user operation defers the
     * step instead of failing it. The epoch is closed on cancel/failure so `onCleared`
     * cannot race past an empty session and leave the claim held.
     */
    private suspend fun measureStep(
        generation: Long,
        partitionId: String,
        key: FilesystemUsageKey,
        expectedGeneration: Long,
        expectedIdentity: String?,
        pin: AttachmentPin?,
    ): UsageStepOutcome {
        val jobContext = kotlinx.coroutines.currentCoroutineContext()
        val outcome = try {
            session.withLock {
                val live = attachment()
                when {
                    !jobContext.isActive -> UsageStepOutcome.Stale
                    live.generation != expectedGeneration ||
                        live.generation != key.attachGeneration ||
                        expectedIdentity == null ||
                        expectedIdentity != key.identity -> UsageStepOutcome.Stale
                    else -> {
                        var acquired: Long? = null
                        try {
                            // Lock held: the real engine call starts now.
                            deferredIntents(
                                UiIntent.FilesystemUsageStepCalculating(generation, partitionId, key),
                            )
                            val measured =
                                session.withAcquireRecorder({ epoch -> acquired = epoch }) {
                                    session.filesystemUsageForLocked(pin, partitionId, key) {
                                        jobContext.ensureActive()
                                    }
                                }
                            if (!jobContext.isActive) {
                                acquired?.let { session.closeIfEpochLocked(it) }
                                UsageStepOutcome.Stale
                            } else {
                                UsageStepOutcome.Measured(measured, acquired)
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            acquired?.let { session.closeIfEpochLocked(it) }
                            throw cancel
                        } catch (changed: AttachmentChangedException) {
                            acquired?.let { session.closeIfEpochLocked(it) }
                            UsageStepOutcome.Stale
                        } catch (busy: UsbExclusiveClaim.BusyException) {
                            // Another exclusive owner: pause, never report Unavailable.
                            acquired?.let { session.closeIfEpochLocked(it) }
                            UsageStepOutcome.Deferred
                        } catch (failure: Throwable) {
                            // A mounted-but-unmeasured volume must not retain the claim.
                            acquired?.let { session.closeIfEpochLocked(it) }
                            UsageStepOutcome.Measured(
                                FilesystemUsageSnapshot(
                                    key,
                                    FilesystemUsage.Unavailable(FilesystemUsageReason.IoFailure),
                                ),
                            )
                        }
                    }
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        }
        // Success keeps the RO mount cached for the next browse.
        return outcome
    }
}
