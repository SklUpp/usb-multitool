package usb.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Owns Windows SAF picker state that must stay together across Activity callbacks and
 * recreation. [MainActivity] remains responsible for launching the Intent and applying the
 * selected URI; this class fences lifecycle events around one [PendingWindowsImport].
 */
class WindowsImportPickerCoordinator(
    private val queue: WindowsImportLaunchQueue,
    private val onTimeout: (PendingWindowsImport) -> Unit,
    private val onInvalidated: (PendingWindowsImport) -> Unit = {},
) {
    sealed interface RestoreResult {
        data object Ignored : RestoreResult
        data object Restored : RestoreResult
        data object Unavailable : RestoreResult
    }

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutCode: Int? = null
    private val timeoutRunnable =
        Runnable {
            val code = timeoutCode ?: return@Runnable
            timeoutCode = null
            val context = queue.pending?.takeIf { it.requestCode == code } ?: return@Runnable
            if (queue.resetByRequestCode(code)) onTimeout(context)
        }

    val pending: PendingWindowsImport?
        get() = queue.pending

    fun begin(
        kind: WindowsImportKind,
        directory: String?,
        pickSessionId: String?,
        identity: String?,
        attachGeneration: Long,
        partitionId: String? = null,
        startLba: ULong? = null,
        sectorCount: ULong? = null,
        launchSource: FilesPickLaunchSource = FilesPickLaunchSource.Files,
    ): WindowsImportLaunch =
        queue.begin(
            kind,
            directory,
            pickSessionId,
            identity,
            attachGeneration,
            partitionId,
            startLba,
            sectorCount,
            launchSource,
        )

    /** Consume only matching result, retiring its timeout with its context. */
    fun takeResult(requestCode: Int): PendingWindowsImport? {
        val context = queue.take(requestCode) ?: return null
        disarm()
        return context
    }

    /** Intent launch failed before the system picker took ownership. */
    fun launchFailed(requestCode: Int) {
        disarm()
        queue.resetByRequestCode(requestCode)
    }

    /** Restore state only when all identifying fields and a valid legacy code are present. */
    fun restore(state: Bundle?): RestoreResult {
        val kind =
            state
                ?.getString(STATE_KIND)
                ?.let { stored -> runCatching { WindowsImportKind.valueOf(stored) }.getOrNull() }
        val requestCode = state?.getInt(STATE_REQUEST, -1) ?: -1
        val validRange =
            requestCode in
                WindowsImportRequestCodes.FIRST_REQUEST_CODE..WindowsImportRequestCodes.LAST_REQUEST_CODE
        if (state == null || kind == null || !validRange) return RestoreResult.Ignored

        val context =
            PendingWindowsImport(
                kind = kind,
                directory = state.getString(STATE_DIRECTORY),
                pickSessionId = state.getString(STATE_PICK_SESSION),
                launchSource = state.getString(STATE_LAUNCH_SOURCE)
                    ?.let { runCatching { FilesPickLaunchSource.valueOf(it) }.getOrNull() }
                    ?: FilesPickLaunchSource.Files,
                identity = state.getString(STATE_IDENTITY),
                attachGeneration = state.getLong(STATE_ATTACH, -1L),
                partitionId = state.getString(STATE_PARTITION),
                startLba = state.getString(STATE_START_LBA)?.toULongOrNull(),
                sectorCount = state.getString(STATE_SECTOR_COUNT)?.toULongOrNull(),
                requestCode = requestCode,
            )
        return if (queue.restore(context)) RestoreResult.Restored else RestoreResult.Unavailable
    }

    fun saveState(outState: Bundle) {
        queue.pending?.let { context ->
            outState.putString(STATE_KIND, context.kind.name)
            context.directory?.let { outState.putString(STATE_DIRECTORY, it) }
            context.pickSessionId?.let { outState.putString(STATE_PICK_SESSION, it) }
            outState.putString(STATE_LAUNCH_SOURCE, context.launchSource.name)
            context.identity?.let { outState.putString(STATE_IDENTITY, it) }
            outState.putLong(STATE_ATTACH, context.attachGeneration)
            context.partitionId?.let { outState.putString(STATE_PARTITION, it) }
            context.startLba?.let { outState.putString(STATE_START_LBA, it.toString()) }
            context.sectorCount?.let { outState.putString(STATE_SECTOR_COUNT, it.toString()) }
            outState.putInt(STATE_REQUEST, context.requestCode)
        }
    }

    /** Reconcile restored/returned picker with live attachment and files-pick session. */
    fun onResume(
        identity: String?,
        attachGeneration: Long,
        pickSessionId: String?,
        partitionId: String? = null,
        startLba: ULong? = null,
        sectorCount: ULong? = null,
    ) {
        val context = queue.pending ?: return
        if (context.identity != identity || context.attachGeneration != attachGeneration) {
            cancelByRequestCode(context.requestCode)
            onInvalidated(context)
            return
        }
        if (context.partitionId != null &&
            (context.partitionId != partitionId ||
                context.startLba != startLba ||
                context.sectorCount != sectorCount)
        ) {
            cancelByRequestCode(context.requestCode)
            onInvalidated(context)
            return
        }
        val expectedSession = context.pickSessionId
        if (expectedSession != null && pickSessionId != expectedSession) {
            cancelByPickSession(expectedSession)
            return
        }
        arm(context.requestCode)
    }

    fun cancelByPickSession(pickSessionId: String): Boolean {
        val cancelled = queue.resetByPickSession(pickSessionId)
        if (cancelled) disarm()
        return cancelled
    }

    fun onPause() = disarm()

    fun onDestroy() {
        disarm()
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }

    private fun cancelByRequestCode(requestCode: Int): Boolean {
        val cancelled = queue.resetByRequestCode(requestCode)
        if (cancelled) disarm()
        return cancelled
    }

    private fun arm(requestCode: Int) {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutCode = requestCode
        timeoutHandler.postDelayed(timeoutRunnable, WINDOWS_IMPORT_TIMEOUT_MS)
    }

    private fun disarm() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutCode = null
    }

    private companion object {
        const val STATE_KIND = "usb.multitool.PENDING_WINDOWS_KIND"
        const val STATE_DIRECTORY = "usb.multitool.PENDING_WINDOWS_DIRECTORY"
        const val STATE_PICK_SESSION = "usb.multitool.PENDING_WINDOWS_PICK_SESSION"
        const val STATE_LAUNCH_SOURCE = "usb.multitool.PENDING_WINDOWS_LAUNCH_SOURCE"
        const val STATE_IDENTITY = "usb.multitool.PENDING_WINDOWS_IDENTITY"
        const val STATE_ATTACH = "usb.multitool.PENDING_WINDOWS_ATTACH"
        const val STATE_PARTITION = "usb.multitool.PENDING_WINDOWS_PARTITION"
        const val STATE_START_LBA = "usb.multitool.PENDING_WINDOWS_START_LBA"
        const val STATE_SECTOR_COUNT = "usb.multitool.PENDING_WINDOWS_SECTOR_COUNT"
        const val STATE_REQUEST = "usb.multitool.PENDING_WINDOWS_REQUEST"
        const val WINDOWS_IMPORT_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
