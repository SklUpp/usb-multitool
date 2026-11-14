package usb.ui

import android.os.Bundle
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Deterministic tests for [WindowsImportPickerCoordinator] over the real main Looper/Handler,
 * Android [Bundle] state persistence, and [WindowsImportLaunchQueue] / [WindowsImportRequestCodes]:
 * - session-bound launch, result consumption, and session cancellation;
 * - 5-minute main-Looper timeout arming, pause/resume disarm+rearm, and destroy cleanup;
 * - Bundle save/restore round-trip, allocator high-water mark advance, and fail-closed corruption;
 * - stale attachment, attachGeneration, partition geometry, and pickSession invalidation on resume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WindowsImportPickerCoordinatorTest {
    private fun newCoordinator(
        storage: WindowsImportRequestCodes.Storage = InMemoryRequestCodeStorage(),
        onTimeout: (PendingWindowsImport) -> Unit = {},
        onInvalidated: (PendingWindowsImport) -> Unit = {},
    ): Pair<WindowsImportPickerCoordinator, WindowsImportRequestCodes> {
        val codes = WindowsImportRequestCodes(storage)
        val queue = WindowsImportLaunchQueue(codes)
        return WindowsImportPickerCoordinator(
            queue = queue,
            onTimeout = onTimeout,
            onInvalidated = onInvalidated,
        ) to codes
    }

    private fun startPinned(
        coordinator: WindowsImportPickerCoordinator,
        kind: WindowsImportKind = WindowsImportKind.Image,
        directory: String? = "/ISO",
        pickSessionId: String? = "session-1",
        identity: String? = "SanDisk#SN-1",
        attachGeneration: Long = 4L,
        partitionId: String? = "1",
        startLba: ULong? = 2048uL,
        sectorCount: ULong? = 8192uL,
        launchSource: FilesPickLaunchSource = FilesPickLaunchSource.Phone,
    ): PendingWindowsImport {
        val result = coordinator.begin(
            kind = kind,
            directory = directory,
            pickSessionId = pickSessionId,
            identity = identity,
            attachGeneration = attachGeneration,
            partitionId = partitionId,
            startLba = startLba,
            sectorCount = sectorCount,
            launchSource = launchSource,
        )
        return (result as WindowsImportLaunch.Starting).context
    }

    @Test
    fun sessionBoundLaunchConsumeAndCancelRespectOwnership() {
        val (coordinator, _) = newCoordinator()
        val first = startPinned(coordinator, pickSessionId = "session-a")

        // A second launch cannot hijack an active session-bound picker.
        val busy = coordinator.begin(
            kind = WindowsImportKind.Image,
            directory = "/ISO",
            pickSessionId = "session-b",
            identity = "SanDisk#SN-1",
            attachGeneration = 4L,
            partitionId = "1",
            startLba = 2048uL,
            sectorCount = 8192uL,
            launchSource = FilesPickLaunchSource.Files,
        )
        assertEquals(WindowsImportLaunch.Busy, busy)
        assertEquals(first, coordinator.pending)

        // Mismatched session or requestCode leaves the pending launch intact.
        assertFalse(coordinator.cancelByPickSession("session-b"))
        assertNull(coordinator.takeResult(first.requestCode + 1))
        assertEquals(first, coordinator.pending)

        // Exact requestCode consumes once; duplicate callback sees null.
        assertEquals(first, coordinator.takeResult(first.requestCode))
        assertNull(coordinator.pending)
        assertNull(coordinator.takeResult(first.requestCode))

        // Session cancel releases only the matching session.
        val second = startPinned(coordinator, pickSessionId = "session-b")
        assertFalse(coordinator.cancelByPickSession("session-a"))
        assertEquals(second, coordinator.pending)
        assertTrue(coordinator.cancelByPickSession("session-b"))
        assertNull(coordinator.pending)
    }

    @Test
    fun resumeArmsFiveMinuteTimeoutAndFiresAtExactBoundary() {
        val timedOut = mutableListOf<PendingWindowsImport>()
        val (coordinator, _) = newCoordinator(onTimeout = { timedOut += it })
        val ctx = startPinned(coordinator)

        coordinator.onResume(
            identity = ctx.identity,
            attachGeneration = ctx.attachGeneration,
            partitionId = ctx.partitionId,
            startLba = ctx.startLba,
            sectorCount = ctx.sectorCount,
            pickSessionId = ctx.pickSessionId,
        )

        ShadowLooper.idleMainLooper(299_000L, TimeUnit.MILLISECONDS)
        assertTrue(timedOut.isEmpty())
        assertEquals(ctx, coordinator.pending)

        ShadowLooper.idleMainLooper(1_000L, TimeUnit.MILLISECONDS)
        assertEquals(listOf(ctx), timedOut)
        assertNull(coordinator.pending)
    }

    @Test
    fun pauseDisarmsTimeoutWithoutDroppingPendingAndResumeRearmsFreshWindow() {
        val timedOut = mutableListOf<PendingWindowsImport>()
        val (coordinator, _) = newCoordinator(onTimeout = { timedOut += it })
        val ctx = startPinned(coordinator)

        coordinator.onResume(
            identity = ctx.identity,
            attachGeneration = ctx.attachGeneration,
            partitionId = ctx.partitionId,
            startLba = ctx.startLba,
            sectorCount = ctx.sectorCount,
            pickSessionId = ctx.pickSessionId,
        )
        ShadowLooper.idleMainLooper(3, TimeUnit.MINUTES)

        // System SAF picker is in foreground: pause disarms timer without dropping queue entry.
        coordinator.onPause()
        ShadowLooper.idleMainLooper(10, TimeUnit.MINUTES)
        assertTrue(timedOut.isEmpty())
        assertEquals(ctx, coordinator.pending)

        // Returning to app without result re-arms a fresh 5-minute countdown.
        coordinator.onResume(
            identity = ctx.identity,
            attachGeneration = ctx.attachGeneration,
            partitionId = ctx.partitionId,
            startLba = ctx.startLba,
            sectorCount = ctx.sectorCount,
            pickSessionId = ctx.pickSessionId,
        )
        ShadowLooper.idleMainLooper(299_000L, TimeUnit.MILLISECONDS)
        assertTrue(timedOut.isEmpty())

        ShadowLooper.idleMainLooper(1_000L, TimeUnit.MILLISECONDS)
        assertEquals(listOf(ctx), timedOut)
        assertNull(coordinator.pending)
    }

    @Test
    fun takeResultCancelLaunchFailedAndDestroyDisarmArmedTimeout() {
        val timedOut = mutableListOf<PendingWindowsImport>()
        val (coordinator, _) = newCoordinator(onTimeout = { timedOut += it })

        fun resumeMatching(ctx: PendingWindowsImport) {
            coordinator.onResume(
                identity = ctx.identity,
                attachGeneration = ctx.attachGeneration,
                partitionId = ctx.partitionId,
                startLba = ctx.startLba,
                sectorCount = ctx.sectorCount,
                pickSessionId = ctx.pickSessionId,
            )
        }

        val taken = startPinned(coordinator, pickSessionId = "s-take")
        resumeMatching(taken)
        assertEquals(taken, coordinator.takeResult(taken.requestCode))
        ShadowLooper.idleMainLooper(6, TimeUnit.MINUTES)
        assertTrue(timedOut.isEmpty())

        val cancelled = startPinned(coordinator, pickSessionId = "s-cancel")
        resumeMatching(cancelled)
        assertTrue(coordinator.cancelByPickSession("s-cancel"))
        ShadowLooper.idleMainLooper(6, TimeUnit.MINUTES)
        assertTrue(timedOut.isEmpty())

        val failed = startPinned(coordinator, pickSessionId = "s-fail")
        resumeMatching(failed)
        coordinator.launchFailed(failed.requestCode)
        assertNull(coordinator.pending)
        ShadowLooper.idleMainLooper(6, TimeUnit.MINUTES)
        assertTrue(timedOut.isEmpty())

        val destroyed = startPinned(coordinator, pickSessionId = "s-destroy")
        resumeMatching(destroyed)
        coordinator.onDestroy()
        ShadowLooper.idleMainLooper(6, TimeUnit.MINUTES)
        assertTrue(timedOut.isEmpty())
    }

    @Test
    fun saveAndRestoreRoundTripsAllFieldsAndAdvancesFreshAllocatorPastRestoredCode() {
        val storageA = InMemoryRequestCodeStorage(
            next = WindowsImportRequestCodes.FIRST_REQUEST_CODE + 15,
        )
        val (coordinatorA, _) = newCoordinator(storage = storageA)
        val original = startPinned(
            coordinator = coordinatorA,
            kind = WindowsImportKind.Image,
            directory = "/Windows/ISO",
            pickSessionId = "pick-99",
            identity = "Kingston#SER-77",
            attachGeneration = 12L,
            partitionId = "p2",
            startLba = 4096uL,
            sectorCount = 16384uL,
            launchSource = FilesPickLaunchSource.Phone,
        )

        val saved = Bundle()
        coordinatorA.saveState(saved)

        // Fresh storage at default base simulates process/storage recreation.
        val storageB = InMemoryRequestCodeStorage(next = WindowsImportRequestCodes.FIRST_REQUEST_CODE)
        val (coordinatorB, _) = newCoordinator(storage = storageB)

        val restored = coordinatorB.restore(saved)
        assertEquals(WindowsImportPickerCoordinator.RestoreResult.Restored, restored)
        assertEquals(original, coordinatorB.pending)

        // Consuming the restored launch and starting another must draw a strictly higher code.
        assertEquals(original, coordinatorB.takeResult(original.requestCode))
        val next = startPinned(coordinatorB, pickSessionId = "pick-100")
        assertTrue(next.requestCode > original.requestCode)
    }

    @Test
    fun restoreRejectsMissingCorruptOrUnsecuredStateAndDefaultsUnknownLaunchSource() {
        val (coordinator, _) = newCoordinator()
        assertEquals(WindowsImportPickerCoordinator.RestoreResult.Ignored, coordinator.restore(null))
        assertNull(coordinator.pending)

        // Empty bundle saved when nothing is pending restores as Ignored.
        val emptySaved = Bundle()
        coordinator.saveState(emptySaved)
        assertEquals(WindowsImportPickerCoordinator.RestoreResult.Ignored, coordinator.restore(emptySaved))

        // Capture a valid bundle and tamper with critical fields.
        val original = startPinned(coordinator)
        val validBundle = Bundle()
        coordinator.saveState(validBundle)

        val invalidKind = Bundle(validBundle).apply {
            putString("usb.multitool.PENDING_WINDOWS_KIND", "NotAKind")
        }
        val (cInvalidKind, _) = newCoordinator()
        assertEquals(WindowsImportPickerCoordinator.RestoreResult.Ignored, cInvalidKind.restore(invalidKind))
        assertNull(cInvalidKind.pending)

        val lowCode = Bundle(validBundle).apply {
            putInt("usb.multitool.PENDING_WINDOWS_REQUEST", WindowsImportRequestCodes.FIRST_REQUEST_CODE - 1)
        }
        val (cLowCode, _) = newCoordinator()
        assertEquals(WindowsImportPickerCoordinator.RestoreResult.Ignored, cLowCode.restore(lowCode))
        assertNull(cLowCode.pending)

        val highCode = Bundle(validBundle).apply {
            putInt("usb.multitool.PENDING_WINDOWS_REQUEST", WindowsImportRequestCodes.LAST_REQUEST_CODE + 1)
        }
        val (cHighCode, _) = newCoordinator()
        assertEquals(WindowsImportPickerCoordinator.RestoreResult.Ignored, cHighCode.restore(highCode))
        assertNull(cHighCode.pending)

        // Unknown launchSource falls back to Files while keeping the rest of the fenced context.
        val unknownSource = Bundle(validBundle).apply {
            putString("usb.multitool.PENDING_WINDOWS_LAUNCH_SOURCE", "LegacySource")
        }
        val (cFallback, _) = newCoordinator()
        assertEquals(WindowsImportPickerCoordinator.RestoreResult.Restored, cFallback.restore(unknownSource))
        assertEquals(
            original.copy(launchSource = FilesPickLaunchSource.Files),
            cFallback.pending,
        )

        // Storage write failure when securing the restored code must fail closed (Unavailable).
        val failingStorage = InMemoryRequestCodeStorage(
            next = WindowsImportRequestCodes.FIRST_REQUEST_CODE,
            failWrites = true,
        )
        val (cFailing, _) = newCoordinator(storage = failingStorage)
        assertEquals(
            WindowsImportPickerCoordinator.RestoreResult.Unavailable,
            cFailing.restore(validBundle),
        )
        assertNull(cFailing.pending)
    }

    @Test
    fun onResumeInvalidatesStaleAttachmentOrReplugAndDisarmsTimeout() {
        val timedOut = mutableListOf<PendingWindowsImport>()
        val invalidated = mutableListOf<PendingWindowsImport>()
        val (coordinator, _) = newCoordinator(
            onTimeout = { timedOut += it },
            onInvalidated = { invalidated += it },
        )

        // Different stick identity.
        val ctxIdentity = startPinned(coordinator, identity = "StickA#1", attachGeneration = 3L)
        coordinator.onResume(
            identity = "StickB#2",
            attachGeneration = 3L,
            partitionId = ctxIdentity.partitionId,
            startLba = ctxIdentity.startLba,
            sectorCount = ctxIdentity.sectorCount,
            pickSessionId = ctxIdentity.pickSessionId,
        )
        assertNull(coordinator.pending)
        assertEquals(listOf(ctxIdentity), invalidated)
        ShadowLooper.idleMainLooper(6, TimeUnit.MINUTES)
        assertTrue(timedOut.isEmpty())

        // Detached device (identity == null).
        invalidated.clear()
        val ctxDetached = startPinned(coordinator, identity = "StickA#1", attachGeneration = 3L)
        coordinator.onResume(
            identity = null,
            attachGeneration = 4L,
            partitionId = null,
            startLba = null,
            sectorCount = null,
            pickSessionId = null,
        )
        assertNull(coordinator.pending)
        assertEquals(listOf(ctxDetached), invalidated)

        // Same-identity replug (attachGeneration bumped).
        invalidated.clear()
        val ctxReplug = startPinned(coordinator, identity = "StickA#1", attachGeneration = 4L)
        coordinator.onResume(
            identity = "StickA#1",
            attachGeneration = 5L,
            partitionId = ctxReplug.partitionId,
            startLba = ctxReplug.startLba,
            sectorCount = ctxReplug.sectorCount,
            pickSessionId = ctxReplug.pickSessionId,
        )
        assertNull(coordinator.pending)
        assertEquals(listOf(ctxReplug), invalidated)
        ShadowLooper.idleMainLooper(6, TimeUnit.MINUTES)
        assertTrue(timedOut.isEmpty())
    }

    @Test
    fun onResumeInvalidatesChangedPartitionGeometryWhenPinnedButAllowsXmlFlow() {
        val timedOut = mutableListOf<PendingWindowsImport>()
        val invalidated = mutableListOf<PendingWindowsImport>()
        val (coordinator, _) = newCoordinator(
            onTimeout = { timedOut += it },
            onInvalidated = { invalidated += it },
        )

        // Partition id changed.
        val ctxPart = startPinned(coordinator, partitionId = "1", startLba = 2048uL, sectorCount = 8192uL)
        coordinator.onResume(
            identity = ctxPart.identity,
            attachGeneration = ctxPart.attachGeneration,
            partitionId = "2",
            startLba = 2048uL,
            sectorCount = 8192uL,
            pickSessionId = ctxPart.pickSessionId,
        )
        assertNull(coordinator.pending)
        assertEquals(listOf(ctxPart), invalidated)

        // startLba or sectorCount changed on same partition id.
        invalidated.clear()
        val ctxGeom = startPinned(coordinator, partitionId = "1", startLba = 2048uL, sectorCount = 8192uL)
        coordinator.onResume(
            identity = ctxGeom.identity,
            attachGeneration = ctxGeom.attachGeneration,
            partitionId = "1",
            startLba = 2048uL,
            sectorCount = 16384uL,
            pickSessionId = ctxGeom.pickSessionId,
        )
        assertNull(coordinator.pending)
        assertEquals(listOf(ctxGeom), invalidated)

        // Unpinned XML template flow (partitionId == null) survives partition selection changes.
        invalidated.clear()
        val ctxXml = startPinned(
            coordinator = coordinator,
            kind = WindowsImportKind.XmlTemplate,
            directory = null,
            pickSessionId = null,
            partitionId = null,
            startLba = null,
            sectorCount = null,
            launchSource = FilesPickLaunchSource.Files,
        )
        coordinator.onResume(
            identity = ctxXml.identity,
            attachGeneration = ctxXml.attachGeneration,
            partitionId = "2",
            startLba = 4096uL,
            sectorCount = 1000uL,
            pickSessionId = "other-files-pick",
        )
        assertEquals(ctxXml, coordinator.pending)
        assertTrue(invalidated.isEmpty())

        ShadowLooper.idleMainLooper(5, TimeUnit.MINUTES)
        assertEquals(listOf(ctxXml), timedOut)
    }

    @Test
    fun onResumeCancelsMismatchedPickSessionSilentlyAndInvalidatesStaleRestoredLaunch() {
        val timedOut = mutableListOf<PendingWindowsImport>()
        val invalidated = mutableListOf<PendingWindowsImport>()
        val (coordinator, _) = newCoordinator(
            onTimeout = { timedOut += it },
            onInvalidated = { invalidated += it },
        )

        val ctx = startPinned(coordinator, pickSessionId = "session-1")
        coordinator.onResume(
            identity = ctx.identity,
            attachGeneration = ctx.attachGeneration,
            partitionId = ctx.partitionId,
            startLba = ctx.startLba,
            sectorCount = ctx.sectorCount,
            pickSessionId = "session-2",
        )
        assertNull(coordinator.pending)
        assertTrue(invalidated.isEmpty())
        ShadowLooper.idleMainLooper(6, TimeUnit.MINUTES)
        assertTrue(timedOut.isEmpty())

        // Recreated coordinator restores bundle, then immediately invalidates when stick replugged.
        val original = startPinned(coordinator, identity = "Stick#1", attachGeneration = 2L)
        val bundle = Bundle()
        coordinator.saveState(bundle)

        val recreatedTimedOut = mutableListOf<PendingWindowsImport>()
        val recreatedInvalidated = mutableListOf<PendingWindowsImport>()
        val (recreated, _) = newCoordinator(
            onTimeout = { recreatedTimedOut += it },
            onInvalidated = { recreatedInvalidated += it },
        )
        assertEquals(WindowsImportPickerCoordinator.RestoreResult.Restored, recreated.restore(bundle))
        assertNotNull(recreated.pending)

        recreated.onResume(
            identity = "Stick#1",
            attachGeneration = 3L,
            partitionId = original.partitionId,
            startLba = original.startLba,
            sectorCount = original.sectorCount,
            pickSessionId = original.pickSessionId,
        )
        assertNull(recreated.pending)
        assertEquals(listOf(original), recreatedInvalidated)
        ShadowLooper.idleMainLooper(6, TimeUnit.MINUTES)
        assertTrue(recreatedTimedOut.isEmpty())
    }
}
