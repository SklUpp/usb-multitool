package usb.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import usb.EngineMissingException

/**
 * Runs the read-only filesystem space query.
 *
 * The caller must already own [volume] through the serialized mount/operation
 * lock (MainActivity's files lock). This object never opens, remounts or closes
 * anything, so it cannot race a mount or break operation ordering.
 */
object FilesystemUsageProbe {
    /**
     * Measures [volume] on [io], never on the caller's main thread. Returns
     * [FilesystemUsage.Unavailable] instead of throwing. Do not call this from
     * inside a non-suspending `synchronized` block: the coroutine may resume on
     * another thread and break monitor ownership.
     */
    suspend fun measure(
        volume: UsbFsVolume,
        key: FilesystemUsageKey,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): FilesystemUsageSnapshot = withContext(io) { measureLocked(volume, key) }

    /**
     * Blocking variant for a caller that already runs on an IO thread and holds
     * the serialized volume lock. Never call it on the main thread. Returns
     * [FilesystemUsage.Unavailable] instead of throwing.
     */
    fun measureLocked(volume: UsbFsVolume, key: FilesystemUsageKey): FilesystemUsageSnapshot {
        val usage = when (volume) {
            is UsbFsUsageVolume -> measureUsage(volume)
            else -> FilesystemUsage.Unavailable(FilesystemUsageReason.UnsupportedFilesystem)
        }
        return FilesystemUsageSnapshot(key, usage)
    }

    private fun measureUsage(volume: UsbFsUsageVolume): FilesystemUsage =
        try {
            FilesystemUsage.Known(volume.space(), System.currentTimeMillis())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: EngineMissingException) {
            FilesystemUsage.Unavailable(FilesystemUsageReason.EngineMissing)
        } catch (_: UnsatisfiedLinkError) {
            FilesystemUsage.Unavailable(FilesystemUsageReason.EngineMissing)
        } catch (closed: IllegalStateException) {
            if (closed.message.orEmpty().contains("closed")) {
                FilesystemUsage.Unavailable(FilesystemUsageReason.Closed)
            } else {
                FilesystemUsage.Unavailable(FilesystemUsageReason.IoFailure)
            }
        } catch (_: Throwable) {
            FilesystemUsage.Unavailable(FilesystemUsageReason.IoFailure)
        }
}
