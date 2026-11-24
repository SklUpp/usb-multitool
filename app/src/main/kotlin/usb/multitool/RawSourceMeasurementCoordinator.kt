package usb.multitool

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import usb.image.CloseCapacityException
import usb.image.ImageSource
import usb.image.MeasurementHandle
import usb.ui.AppUiState
import usb.ui.RawMeasureError
import usb.ui.rawSourceKey
import usb.ui.UiEffect
import usb.ui.UiIntent

/**
 * Owns the single decode-count pass for the selected raw source. Kept outside the
 * ViewModel because the provider stream can stay blocked long after the ViewModel is
 * gone; this object owns the handle, the pending newest request and the completion hop.
 */
internal class RawSourceMeasurementCoordinator(
    private val scope: CoroutineScope,
    private val resolver: () -> ContentResolver,
    private val state: () -> AppUiState,
    private val onIntent: (UiIntent) -> Unit,
) {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var job: Job? = null
    private var handle: MeasurementHandle? = null
    private var running = false
    /** Latest source waiting for a blocked provider pass to return; never starts in parallel. */
    private var pending: UiEffect.MeasureRawSource? = null

    /**
     * Read-only decode-count pass for the raw source. No temporary uncompressed file:
     * bytes are discarded as they are decoded, and cancellation is checked between
     * chunks. The result is bound to the reducer generation; a stale pass is ignored.
     */
    fun measure(effect: UiEffect.MeasureRawSource) {
        MeasurementHandle.retryDeferredCloses()
        if (running) {
            // Keep newest source pending. The old provider read remains guarded until its
            // coroutine returns; cancellation only asks the provider stream to close.
            pending = effect
            onIntent(
                UiIntent.RawSourceMeasurementWaiting(
                    generation = effect.generation,
                    source = effect.source,
                ),
            )
            handle?.cancel()
            job?.cancel()
            return
        }
        start(effect)
    }

    /** Close the tracked provider stream, if any. No new pass is queued. */
    fun cancel() {
        pending = null
        handle?.cancel()
        handle = null
        job?.cancel()
        job = null
    }

    private fun startPending() {
        val effect = pending ?: return
        pending = null
        // A newer pick/cancel may have superseded this request while the provider was
        // blocked. Never revive a stale URI or generation.
        if (state().rawSizeGeneration != effect.generation ||
            state().rawSourceKey != effect.source
        ) {
            return
        }
        onIntent(
            UiIntent.RawSourceMeasurementStarted(
                generation = effect.generation,
                source = effect.source,
            ),
        )
        start(effect)
    }

    private fun start(effect: UiEffect.MeasureRawSource) {
        handle?.cancel()
        val handle = try {
            MeasurementHandle()
        } catch (_: CloseCapacityException) {
            onIntent(measureError(effect, RawMeasureError.CloseCapacity))
            return
        }
        this.handle = handle
        running = true
        val source = resolver()
        val job =
            try {
                scope.launch(Dispatchers.IO) {
                    val measurementScope = this
                    val outcome = runCatching {
                        val image =
                            if (effect.staged) {
                                ImageSource.fromFile(java.io.File(effect.source))
                            } else {
                                ImageSource.fromContentUri(Uri.parse(effect.source), source)
                            }
                        image.measureDecoded(
                            isActive = { measurementScope.isActive },
                            limitBytes = effect.limitBytes,
                            handle = handle,
                        )
                    }
                    val failure = outcome.exceptionOrNull()
                    if (failure !is java.util.concurrent.CancellationException &&
                        measurementScope.isActive
                    ) {
                        val detail = failure?.let { it.message ?: it.javaClass.simpleName }
                        val measured = outcome.getOrNull()
                        withContext(Dispatchers.Main.immediate) {
                            onIntent(
                                UiIntent.RawSourceMeasured(
                                    generation = effect.generation,
                                    source = effect.source,
                                    bytes = measured?.bytes,
                                    sha256 = measured?.sha256,
                                    error = detail,
                                    errorKind =
                                        if (failure == null) null else RawMeasureError.Failed,
                                ),
                            )
                        }
                    }
                }
            } catch (failure: Throwable) {
                handle.cancel()
                handle.abandon()
                running = false
                this.handle = null
                this.job = null
                throw failure
            }
        this.job = job
        job.invokeOnCompletion {
            // Runs even if the coroutine was cancelled before its body started; for a
            // blocked provider read this is delayed until the read actually returns.
            // Handle release must not depend on [scope], which is cancelled on destroy.
            handle.cancel()
            handle.abandon()
            mainHandler.post {
                running = false
                if (this.job === job) this.job = null
                if (this.handle === handle) this.handle = null
                startPending()
            }
        }
    }

    private fun measureError(
        effect: UiEffect.MeasureRawSource,
        kind: RawMeasureError,
    ): UiIntent.RawSourceMeasured =
        UiIntent.RawSourceMeasured(
            generation = effect.generation,
            source = effect.source,
            bytes = null,
            errorKind = kind,
        )
}
