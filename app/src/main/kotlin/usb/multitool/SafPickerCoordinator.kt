package usb.multitool

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import usb.ui.AppUiState
import usb.ui.FilesBatchItem
import usb.ui.FilesBatchSelection
import usb.ui.FilesDownloadBinding
import usb.ui.PickerSlot
import usb.ui.PickerVerdict
import usb.ui.UiAttachmentPin
import usb.ui.UiEffect
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey
import usb.ui.UploadTarget
import usb.ui.resultVerdict
import usb.ui.stageVerdict

/**
 * Owns download/batch picker slots, effect channel, and Bundle save/restore.
 * Activity keeps launchers and Activity Result callbacks.
 */
class SafPickerCoordinator(
    private val resolver: ContentResolver,
    private val intents: (UiIntent) -> Unit,
) {
    private val _effects = Channel<UiEffect>(Channel.UNLIMITED)
    val effects: Flow<UiEffect> = _effects.receiveAsFlow()

    val download = PickerSlot<FilesDownloadBinding>()
    val batchDownload = PickerSlot<FilesBatchSelection>()
    val batchUpload = PickerSlot<UploadTarget>()

    val pendingDownloadBinding: FilesDownloadBinding?
        get() = download.pending
    val pendingDownloadRequestId: Long
        get() = download.pendingRequestId

    fun stageDownload(binding: FilesDownloadBinding, state: AppUiState): Long? {
        if (download.pending != null) {
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickActive)))
            return null
        }
        if (binding.stageVerdict(state) != PickerVerdict.Ok) {
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return null
        }
        val requestId =
            download.stage(binding) ?: run {
                intents(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickActive)))
                return null
            }
        _effects.trySend(UiEffect.DownloadPickerReady(binding, binding.name, requestId))
        return requestId
    }

    fun claimLaunch(requestId: Long, binding: FilesDownloadBinding): Boolean =
        download.claimLaunch(requestId) && download.pending == binding

    fun launchFailed(binding: FilesDownloadBinding, requestId: Long = pendingDownloadRequestId) {
        if (download.pending != binding) return
        download.clearIf(requestId)
    }

    fun submitResult(binding: FilesDownloadBinding, uri: Uri, state: AppUiState): Boolean {
        if (download.pending != binding) {
            discard(uri)
            return false
        }
        if (binding.resultVerdict(state) != PickerVerdict.Ok) {
            download.clear()
            discard(uri)
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return false
        }
        val requestId = download.pendingRequestId
        intents(UiIntent.DownloadFile(binding, uri.toString(), fromPicker = true))
        download.clearIf(requestId)
        return true
    }

    fun restorePending(binding: FilesDownloadBinding): Boolean = download.restore(binding)

    fun discard(uri: Uri) {
        val deleted =
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        if (!deleted) runCatching { resolver.delete(uri, null, null) }
    }

    fun clearDownload() {
        download.clear()
    }

    fun closeEffects() {
        _effects.close()
    }

    fun trySend(effect: UiEffect): Boolean = _effects.trySend(effect).isSuccess

    fun save(out: Bundle) {
        download.pending?.let { binding ->
            out.putString(STATE_PENDING_DOWNLOAD_DIRECTORY, binding.directory)
            out.putString(STATE_PENDING_DOWNLOAD_NAME, binding.name)
            out.putString(STATE_PENDING_DOWNLOAD_SIZE, binding.sizeBytes.toString())
            out.putString(STATE_PENDING_DOWNLOAD_IDENTITY, binding.attachment.identity)
            out.putLong(STATE_PENDING_DOWNLOAD_ATTACH, binding.attachment.attachGeneration)
            out.putString(STATE_PENDING_DOWNLOAD_PARTITION, binding.attachment.partitionId)
            binding.attachment.startLba?.let {
                out.putString(STATE_PENDING_DOWNLOAD_START, it.toString())
            }
            binding.attachment.sectorCount?.let {
                out.putString(STATE_PENDING_DOWNLOAD_COUNT, it.toString())
            }
        }
        batchDownload.pending?.let { selection ->
            out.putString(STATE_PENDING_BATCH_DIRECTORY, selection.directory)
            out.putStringArray(
                STATE_PENDING_BATCH_NAMES,
                selection.items.map { it.name }.toTypedArray(),
            )
            out.putBooleanArray(
                STATE_PENDING_BATCH_DIRS,
                selection.items.map { it.isDirectory }.toBooleanArray(),
            )
            out.putStringArray(
                STATE_PENDING_BATCH_SIZES,
                selection.items.map { it.sizeBytes.toString() }.toTypedArray(),
            )
            out.putString(STATE_PENDING_BATCH_IDENTITY, selection.identity)
            out.putLong(STATE_PENDING_BATCH_ATTACH, selection.attachGeneration)
            out.putString(STATE_PENDING_BATCH_PARTITION, selection.partitionId)
            selection.startLba?.let { out.putString(STATE_PENDING_BATCH_START, it.toString()) }
            selection.sectorCount?.let {
                out.putString(STATE_PENDING_BATCH_COUNT, it.toString())
            }
        }
        batchUpload.pending?.let { target ->
            out.putString(STATE_PENDING_BATCH_UPLOAD_DIRECTORY, target.directory)
            out.putString(STATE_PENDING_BATCH_UPLOAD_IDENTITY, target.attachment.identity)
            out.putLong(STATE_PENDING_BATCH_UPLOAD_ATTACH, target.attachment.attachGeneration)
            out.putString(STATE_PENDING_BATCH_UPLOAD_PARTITION, target.attachment.partitionId)
            out.putString(
                STATE_PENDING_BATCH_UPLOAD_START,
                target.attachment.startLba?.toString(),
            )
            out.putString(
                STATE_PENDING_BATCH_UPLOAD_COUNT,
                target.attachment.sectorCount?.toString(),
            )
        }
    }

    /** Restores slots from process-death Bundle. Returns true when a stale download name was present without a full binding. */
    fun restore(saved: Bundle?): Boolean {
        if (saved == null) return false
        var staleDownloadName = false
        if (download.pending == null) {
            val savedPickerRequest = saved.getString(STATE_PENDING_DOWNLOAD_NAME)
            val binding =
                run {
                    val directory = saved.getString(STATE_PENDING_DOWNLOAD_DIRECTORY)
                    val name = saved.getString(STATE_PENDING_DOWNLOAD_NAME)
                    val size = saved.getString(STATE_PENDING_DOWNLOAD_SIZE)?.toULongOrNull()
                    val identity = saved.getString(STATE_PENDING_DOWNLOAD_IDENTITY)
                    val partition = saved.getString(STATE_PENDING_DOWNLOAD_PARTITION)
                    val start = saved.getString(STATE_PENDING_DOWNLOAD_START)?.toULongOrNull()
                    val count = saved.getString(STATE_PENDING_DOWNLOAD_COUNT)?.toULongOrNull()
                    val generation = saved.getLong(STATE_PENDING_DOWNLOAD_ATTACH, Long.MIN_VALUE)
                    if (directory != null &&
                        name != null &&
                        size != null &&
                        identity != null &&
                        partition != null &&
                        generation != Long.MIN_VALUE
                    ) {
                        FilesDownloadBinding(
                            directory,
                            name,
                            size,
                            UiAttachmentPin(identity, generation, partition, start, count),
                        )
                    } else {
                        null
                    }
                }
            if (binding != null) {
                download.restore(binding)
            } else if (savedPickerRequest != null) {
                staleDownloadName = true
            }
        }
        run {
            val directory = saved.getString(STATE_PENDING_BATCH_DIRECTORY) ?: return@run
            val identity = saved.getString(STATE_PENDING_BATCH_IDENTITY) ?: return@run
            val partition = saved.getString(STATE_PENDING_BATCH_PARTITION) ?: return@run
            val generation = saved.getLong(STATE_PENDING_BATCH_ATTACH, Long.MIN_VALUE)
            val names = saved.getStringArray(STATE_PENDING_BATCH_NAMES) ?: return@run
            val dirs = saved.getBooleanArray(STATE_PENDING_BATCH_DIRS) ?: return@run
            val sizes = saved.getStringArray(STATE_PENDING_BATCH_SIZES) ?: return@run
            if (generation == Long.MIN_VALUE ||
                names.size != dirs.size ||
                names.size != sizes.size
            ) {
                return@run
            }
            val items =
                names.indices.map { index ->
                    FilesBatchItem(
                        names[index],
                        dirs[index],
                        sizes[index].toULongOrNull() ?: return@run,
                    )
                }
            batchDownload.restore(
                FilesBatchSelection(
                    directory = directory,
                    items = items,
                    identity = identity,
                    attachGeneration = generation,
                    partitionId = partition,
                    startLba = saved.getString(STATE_PENDING_BATCH_START)?.toULongOrNull(),
                    sectorCount = saved.getString(STATE_PENDING_BATCH_COUNT)?.toULongOrNull(),
                ),
            )
        }
        run {
            val directory = saved.getString(STATE_PENDING_BATCH_UPLOAD_DIRECTORY)
            val identity = saved.getString(STATE_PENDING_BATCH_UPLOAD_IDENTITY)
            val partition = saved.getString(STATE_PENDING_BATCH_UPLOAD_PARTITION)
            val start = saved.getString(STATE_PENDING_BATCH_UPLOAD_START)?.toULongOrNull()
            val count = saved.getString(STATE_PENDING_BATCH_UPLOAD_COUNT)?.toULongOrNull()
            val attach = saved.getLong(STATE_PENDING_BATCH_UPLOAD_ATTACH, Long.MIN_VALUE)
            if (directory != null &&
                identity != null &&
                partition != null &&
                attach != Long.MIN_VALUE
            ) {
                batchUpload.restore(
                    UploadTarget(
                        directory = directory,
                        attachment = UiAttachmentPin(identity, attach, partition, start, count),
                    ),
                )
            }
        }
        return staleDownloadName
    }

    companion object {
        const val STATE_PENDING_DOWNLOAD_DIRECTORY = "usb.multitool.PENDING_DOWNLOAD_DIRECTORY"
        const val STATE_PENDING_DOWNLOAD_NAME = "usb.multitool.PENDING_DOWNLOAD_NAME"
        const val STATE_PENDING_DOWNLOAD_SIZE = "usb.multitool.PENDING_DOWNLOAD_SIZE"
        const val STATE_PENDING_DOWNLOAD_IDENTITY = "usb.multitool.PENDING_DOWNLOAD_IDENTITY"
        const val STATE_PENDING_DOWNLOAD_ATTACH = "usb.multitool.PENDING_DOWNLOAD_ATTACH"
        const val STATE_PENDING_DOWNLOAD_PARTITION = "usb.multitool.PENDING_DOWNLOAD_PARTITION"
        const val STATE_PENDING_DOWNLOAD_START = "usb.multitool.PENDING_DOWNLOAD_START"
        const val STATE_PENDING_DOWNLOAD_COUNT = "usb.multitool.PENDING_DOWNLOAD_COUNT"
        const val STATE_PENDING_BATCH_DIRECTORY = "usb.multitool.PENDING_BATCH_DIRECTORY"
        const val STATE_PENDING_BATCH_NAMES = "usb.multitool.PENDING_BATCH_NAMES"
        const val STATE_PENDING_BATCH_DIRS = "usb.multitool.PENDING_BATCH_DIRS"
        const val STATE_PENDING_BATCH_SIZES = "usb.multitool.PENDING_BATCH_SIZES"
        const val STATE_PENDING_BATCH_IDENTITY = "usb.multitool.PENDING_BATCH_IDENTITY"
        const val STATE_PENDING_BATCH_ATTACH = "usb.multitool.PENDING_BATCH_ATTACH"
        const val STATE_PENDING_BATCH_PARTITION = "usb.multitool.PENDING_BATCH_PARTITION"
        const val STATE_PENDING_BATCH_START = "usb.multitool.PENDING_BATCH_START"
        const val STATE_PENDING_BATCH_COUNT = "usb.multitool.PENDING_BATCH_COUNT"
        const val STATE_PENDING_BATCH_UPLOAD_DIRECTORY =
            "usb.multitool.PENDING_BATCH_UPLOAD_DIRECTORY"
        const val STATE_PENDING_BATCH_UPLOAD_IDENTITY =
            "usb.multitool.PENDING_BATCH_UPLOAD_IDENTITY"
        const val STATE_PENDING_BATCH_UPLOAD_ATTACH = "usb.multitool.PENDING_BATCH_UPLOAD_ATTACH"
        const val STATE_PENDING_BATCH_UPLOAD_PARTITION =
            "usb.multitool.PENDING_BATCH_UPLOAD_PARTITION"
        const val STATE_PENDING_BATCH_UPLOAD_START = "usb.multitool.PENDING_BATCH_UPLOAD_START"
        const val STATE_PENDING_BATCH_UPLOAD_COUNT = "usb.multitool.PENDING_BATCH_UPLOAD_COUNT"
    }
}
