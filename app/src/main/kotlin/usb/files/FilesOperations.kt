package usb.files

import android.content.ContentResolver
import android.net.Uri
import usb.files.transfer.BatchTransferService
import usb.files.transfer.DownloadCursor
import usb.files.transfer.DownloadService
import usb.files.transfer.TransferConflict
import usb.files.transfer.TransferDecision
import usb.files.transfer.UploadService
import usb.files.transfer.VolumeMutationService
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.saf.SafBackupFolder
import usb.saf.SafDocument

/**
 * Compatibility facade over volume / download / upload / batch transfer services.
 * Prefer the services directly for new call sites.
 */
@Deprecated("Use usb.files.transfer.DownloadCursor", ReplaceWith("DownloadCursor", "usb.files.transfer.DownloadCursor"))
typealias BatchDownloadCursor = DownloadCursor

object FilesOperations {
    fun list(volume: UsbFsVolume, path: String): List<UsbFsDirEntry> =
        VolumeMutationService.list(volume, path)

    fun delete(volume: UsbFsVolume, target: String) =
        VolumeMutationService.delete(volume, target)

    fun rename(volume: UsbFsVolume, from: String, to: String) =
        VolumeMutationService.rename(volume, from, to)

    fun deleteRecursively(
        volume: UsbFsVolume,
        target: String,
        operation: DiskOperation,
        onProgress: ((String) -> Unit)? = null,
    ) = VolumeMutationService.deleteRecursively(volume, target, operation, onProgress)

    fun upload(
        resolver: ContentResolver,
        uri: Uri,
        target: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        onCleanupFailure: (Throwable) -> Unit = {},
    ) = UploadService.upload(
        resolver, uri, target, volume, operation, chunkBytes, onCleanupFailure,
    )

    internal fun upload(
        input: java.io.InputStream,
        target: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        knownSizeBytes: Long? = null,
        onCleanupFailure: (Throwable) -> Unit = {},
        targetAlreadyOwned: Boolean = false,
    ) = UploadService.upload(
        input, target, volume, operation, chunkBytes, knownSizeBytes, onCleanupFailure, targetAlreadyOwned,
    )

    fun download(
        resolver: ContentResolver,
        target: String,
        destination: Uri,
        volume: UsbFsVolume,
        operation: DiskOperation,
        expectedSize: Long? = null,
    ) = DownloadService.download(resolver, target, destination, volume, operation, expectedSize)

    fun downloadTree(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        destTree: SafBackupFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
        onProgress: ((String) -> Unit)? = null,
    ) = DownloadService.downloadTree(
        resolver, volume, sourcePath, destTree, destParent, operation, conflict, onProgress,
    )

    internal fun downloadTree(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        sourcePath: String,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
        onProgress: ((String) -> Unit)? = null,
    ) = DownloadService.downloadTree(
        resolver, volume, sourcePath, destTree, destParent, operation, conflict, onProgress,
    )

    fun downloadChildren(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        parentPath: String,
        names: List<String>,
        destTree: SafBackupFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
        onProgress: ((String) -> Unit)? = null,
        resume: DownloadCursor? = null,
        onCursor: (DownloadCursor) -> Unit = {},
    ) = BatchTransferService.downloadChildren(
        resolver, volume, parentPath, names, destTree, destParent, operation, conflict,
        onProgress, resume, onCursor,
    )

    internal fun downloadChildren(
        resolver: ContentResolver,
        volume: UsbFsVolume,
        parentPath: String,
        names: List<String>,
        destTree: BatchSafFolder,
        destParent: Uri,
        operation: DiskOperation,
        conflict: (TransferConflict) -> TransferDecision,
        onProgress: ((String) -> Unit)? = null,
        resume: DownloadCursor? = null,
        onCursor: (DownloadCursor) -> Unit = {},
    ) = BatchTransferService.downloadChildren(
        resolver, volume, parentPath, names, destTree, destParent, operation, conflict,
        onProgress, resume, onCursor,
    )

    fun uploadTree(
        resolver: ContentResolver,
        sources: List<SafDocument>,
        sourceTree: SafBackupFolder,
        destDir: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        conflict: (TransferConflict) -> TransferDecision,
        onCleanupFailure: (Throwable) -> Unit = {},
        onProgress: ((String) -> Unit)? = null,
    ) = UploadService.uploadTree(
        resolver, sources, sourceTree, destDir, volume, operation, chunkBytes, conflict,
        onCleanupFailure, onProgress,
    )

    internal fun uploadTree(
        resolver: ContentResolver,
        sources: List<SafDocument>,
        sourceTree: BatchSafFolder,
        destDir: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        conflict: (TransferConflict) -> TransferDecision,
        onCleanupFailure: (Throwable) -> Unit = {},
        onProgress: ((String) -> Unit)? = null,
    ) = UploadService.uploadTree(
        resolver, sources, sourceTree, destDir, volume, operation, chunkBytes, conflict,
        onCleanupFailure, onProgress,
    )

    fun uploadDocuments(
        resolver: ContentResolver,
        uris: List<Uri>,
        destDir: String,
        volume: UsbFsVolume,
        operation: DiskOperation,
        chunkBytes: Int,
        conflict: (TransferConflict) -> TransferDecision,
        onCleanupFailure: (Throwable) -> Unit = {},
        onProgress: ((String) -> Unit)? = null,
        onDocumentSettled: ((Uri) -> Unit)? = null,
    ) = BatchTransferService.uploadDocuments(
        resolver, uris, destDir, volume, operation, chunkBytes, conflict,
        onCleanupFailure, onProgress, onDocumentSettled,
    )

    internal fun openableSizeBytes(resolver: ContentResolver, uri: Uri): Long? =
        OpenableSafHelpers.openableSizeBytes(resolver, uri)

    internal fun openableDisplayName(resolver: ContentResolver, uri: Uri): String? =
        OpenableSafHelpers.openableDisplayName(resolver, uri)

    internal fun requireFreeSpaceBeforeWrite(volume: UsbFsVolume, needBytes: Long?) =
        OpenableSafHelpers.requireFreeSpaceBeforeWrite(volume, needBytes)
}
