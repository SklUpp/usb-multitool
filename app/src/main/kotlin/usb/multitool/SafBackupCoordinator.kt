package usb.multitool

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import usb.diagnostics.BackupArchive
import usb.diagnostics.RescueMap
import usb.saf.SafBackupFolder
import usb.saf.SafDocument
import usb.saf.SafTree
import usb.ui.AppUiState
import usb.ui.DevicePresence
import usb.ui.RestoreSourceInspector
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey
import usb.ui.UiReducer
import usb.ui.normalizeProviderText
import usb.ui.rescueImageLengthIssue

/**
 * Owns SAF backup-folder grant checks, GPT/rescue pick inspections, and pick-generation
 * fences. [MainViewModel] keeps destructive fencing (`startCreateWithBackupFolder`,
 * `requestBackupAction`, `expectedBackupPin`, `dispatch`).
 */
class SafBackupCoordinator(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    private val tree: (Uri) -> SafTree = { SafBackupFolder(resolver, it) },
    private val intents: (UiIntent) -> Unit,
    private val log: (String) -> Unit,
    private val prefs: SharedPreferences? = null,
    private val backupFolderUriKey: String = "backup_folder_uri",
    private val backupFolderNameKey: String = "backup_folder_name",
    /** Live UI state for main-hop attach fences; VM passes `{ state }`. */
    private val liveState: () -> AppUiState = { AppUiState() },
) {
    private val gptPickGeneration = AtomicLong(0)
    private val rescueArchivePickGeneration = AtomicLong(0)

    /** True when the persisted write grant still exists; the provider is checked on write. */
    fun hasUsableBackupFolder(state: AppUiState): Boolean {
        val uri = state.backupFolderUri ?: return false
        return state.backupFolderUsable && hasPersistedWrite(uri)
    }

    /**
     * Persist the user's tree choice. Returns false when the grant was not persisted or
     * the provider cannot describe the tree; the caller then keeps the picker flow open.
     */
    fun onFolderPicked(uri: Uri): Boolean {
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val persisted = hasPersistedWrite(uri.toString())
        val name = runCatching {
            tree(uri).displayName()
        }.getOrNull()
        val usable = persisted
        if (usable) {
            prefs?.edit()
                ?.putString(backupFolderUriKey, uri.toString())
                ?.putString(backupFolderNameKey, name)
                ?.apply()
        }
        intents(UiIntent.SetBackupFolder(uri.toString(), name, usable))
        log("picker.backup_folder uri=${uri} usable=$usable")
        intents(UiIntent.AppendLog(logLine("Backup folder: ${name ?: "unnamed"}")))
        // Choosing where backups are stored must not pre-arm a restore. Restore sources
        // change only through an explicit file or restore-folder pick.
        intents(
            UiIntent.SetBackupFile(
                null,
                null,
                gptPickGeneration.incrementAndGet(),
            ),
        )
        val clearedGeneration = rescueArchivePickGeneration.incrementAndGet()
        intents(UiIntent.SetRescueArchiveTree(null))
        intents(UiIntent.SetRescueImage(null, null, clearedGeneration))
        intents(UiIntent.SetRescueMap(null, null, clearedGeneration))
        return usable
    }

    fun onGptBackupPicked(uri: Uri, state: AppUiState) {
        val target = uri.toString()
        log("picker.gpt_backup uri=$target")
        val generation = gptPickGeneration.incrementAndGet()
        val expectedAttachGeneration = state.device?.attachGeneration
        val diskSectorSize =
            state.diskSectorSize.takeIf { state.presence == DevicePresence.Ready && state.device != null }
        scope.launch(Dispatchers.IO) {
            val (name, size) = contentInfo(resolver, uri)
            val inspection =
                try {
                    val input = resolver.openInputStream(uri)
                        ?: throw IllegalStateException("cannot open $target for reading")
                    val blob = input.use { RestoreSourceInspector.readBounded(it) }
                    RestoreSourceInspector.inspectGpt(
                        uri = target,
                        name = name,
                        sizeBytes = size,
                        blob = blob,
                        diskSectorSize = diskSectorSize,
                    )
                } catch (failure: Exception) {
                    usb.ui.GptBackupInspection(
                        uri = target,
                        name = name,
                        sizeBytes = size,
                        error = normalizeProviderText(
                            failure.message ?: failure.javaClass.simpleName,
                        ),
                    )
                }
            withContext(Dispatchers.Main.immediate) {
                if (generation == gptPickGeneration.get()) {
                    if (liveState().device?.attachGeneration != expectedAttachGeneration) {
                        // Capture is fixed at launch; live attach drift is the caller's
                        // responsibility via a fresh [state] on the next pick. Keep the
                        // same fence shape as MainViewModel: compare against the snapshot
                        // attach generation captured above.
                        intents(UiIntent.SetBackupFile(null, null, generation))
                        intents(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                    } else {
                        intents(UiIntent.AppendLog(logLine("GPT backup picked: ${name ?: "unnamed"}")))
                        intents(UiIntent.SetBackupFile(target, inspection, generation))
                    }
                }
            }
        }
    }

    /**
     * Manual restore-single-file picker. A `.map` name is decoded as a sector map; every
     * other document is inspected as a rescue image. The shared folder choice is cleared so
     * the visible path is the exact document the user picked.
     */
    fun onRescueFilePicked(uri: Uri, state: AppUiState, mapOnly: Boolean = false) {
        val target = uri.toString()
        log("picker.rescue_file uri=$target")
        val generation = rescueArchivePickGeneration.incrementAndGet()
        val expectedAttachGeneration = state.device?.attachGeneration
        intents(UiIntent.Status(UiMessage.Key(UiMessageKey.CheckingArchive)))
        scope.launch(Dispatchers.IO) {
            val (name, size) = contentInfo(resolver, uri)
            val lower = name?.lowercase(java.util.Locale.ROOT)
            val isMap = lower?.endsWith(".map") == true
            if (mapOnly && !isMap) {
                withContext(Dispatchers.Main.immediate) {
                    intents(UiIntent.Status(UiMessage.Key(UiMessageKey.RescueMapRequired)))
                }
                return@launch
            }
            var imageInspection: usb.ui.RescueImageInspection? = null
            var mapInspection: usb.ui.RescueMapInspection? = null
            var failure: String? = null
            try {
                if (isMap) {
                    val bytes = resolver.openInputStream(uri)?.use {
                        RestoreSourceInspector.readBounded(it, RescueMap.MAX_ENCODED_CHARS)
                    } ?: throw IllegalStateException("cannot open $target")
                    mapInspection = RestoreSourceInspector.inspectMap(
                        uri = target,
                        name = name,
                        sizeBytes = size,
                        text = bytes.toString(Charsets.UTF_8),
                    )
                } else {
                    val header = ByteArray(6)
                    var read = 0
                    resolver.openInputStream(uri)?.use { input ->
                        while (read < header.size) {
                            val n = input.read(header, read, header.size - read)
                            if (n <= 0) break
                            read += n
                        }
                    }
                    imageInspection = RestoreSourceInspector.inspectImage(
                        uri = target,
                        name = name,
                        sizeBytes = size,
                        format = usb.image.classifyImageContent(name, header.copyOf(read)),
                    )
                }
            } catch (error: Throwable) {
                val detail = normalizeProviderText(
                    error.message ?: error.javaClass.simpleName,
                )
                if (isMap) {
                    mapInspection = usb.ui.RescueMapInspection(
                        uri = target,
                        name = name,
                        sizeBytes = size,
                        error = detail,
                    )
                } else {
                    failure = detail
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != rescueArchivePickGeneration.get()) return@withContext
                if (expectedAttachGeneration != null &&
                    liveState().device?.attachGeneration != expectedAttachGeneration
                ) {
                    intents(UiIntent.SetRescueImage(null, null, generation))
                    intents(UiIntent.SetRescueMap(null, null, generation))
                    intents(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                    return@withContext
                }
                if (failure != null) {
                    intents(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.SourceInspectionRejected,
                                listOf(name ?: target, failure),
                            ),
                        ),
                    )
                    return@withContext
                }
                intents(UiIntent.SetRescueArchiveTree(null))
                mapInspection?.let { inspection ->
                    intents(UiIntent.SetRescueMap(target, inspection, generation))
                }
                imageInspection?.let { inspection ->
                    intents(UiIntent.SetRescueImage(target, inspection, generation))
                }
            }
        }
    }

    /**
     * A restore folder is the unit: exactly one `.img` and one `.map`. Listing, map decode
     * and image metadata happen off the main thread; a newer folder pick invalidates an
     * older result. On success the existing destructive confirmation opens with the same
     * inspection types as the old per-file pickers.
     */
    fun onRescueArchiveFolderPicked(uri: Uri, quiet: Boolean = false, state: AppUiState) {
        log("picker.rescue_folder uri=${uri}")
        if (!quiet) intents(UiIntent.AppendLog(logLine("Restore folder picked")))
        val generation = rescueArchivePickGeneration.incrementAndGet()
        val expectedAttachGeneration = state.device?.attachGeneration
        if (!quiet) intents(UiIntent.Status(UiMessage.Key(UiMessageKey.CheckingArchive)))
        scope.launch(Dispatchers.IO) {
            var invalid: BackupArchive.Selection.Invalid? = null
            var imageUri: String? = null
            var mapUri: String? = null
            var imageInspection: usb.ui.RescueImageInspection? = null
            var mapInspection: usb.ui.RescueMapInspection? = null
            var failureMessage: String? = null
            try {
                val folder = tree(uri)
                fun entriesOf(documents: List<SafDocument>) = documents.map {
                    BackupArchive.Entry(
                        name = it.name,
                        uri = it.uri.toString(),
                        isDirectory = it.isDirectory,
                        sizeBytes = it.sizeBytes,
                    )
                }
                val rootDocuments = folder.listRootFiles()
                // The shared folder may also hold GPT critical backups; they are not
                // part of the image+map pair and must not reject it as unexpected.
                val rootEntries = entriesOf(rootDocuments).filterNot { isGptBackupName(it.name) }
                // Image creation publishes `usb-rescue-*/disk.img`; scan those archives
                // too, newest first, so one folder still drives create and restore.
                val archives = rootDocuments
                    .filter {
                        it.isDirectory &&
                            it.name.startsWith("usb-rescue-", ignoreCase = true)
                    }
                    .sortedByDescending { it.name }
                    .map { entriesOf(folder.listChildren(it.uri)) }
                val candidates = listOf(rootEntries) + archives
                val selections = candidates.map { BackupArchive.select(it) }
                // A filename-complete but corrupt newest map must not hide an older
                // usable archive; validate the map before accepting the pair.
                val selection = selections
                    .filterIsInstance<BackupArchive.Selection.Ready>()
                    .firstOrNull { ready ->
                        ready.pair.maps.any { candidate ->
                            runCatching {
                                RestoreSourceInspector.inspectMap(
                                    uri = candidate.uri,
                                    name = candidate.name,
                                    sizeBytes = candidate.sizeBytes,
                                    text = folder.readText(
                                        Uri.parse(candidate.uri),
                                        RescueMap.MAX_ENCODED_CHARS,
                                    ),
                                )
                            }.getOrNull()?.let { inspection ->
                                val summary = inspection.summary
                                if (inspection.error != null || summary == null) {
                                    false
                                } else {
                                    val sectorSize =
                                        summary.sectorSize ?: state.diskSectorSize.takeIf { it > 0 }
                                    val format = classifyRescueImage(
                                        resolver,
                                        Uri.parse(ready.pair.image.uri),
                                        ready.pair.image.name,
                                    )
                                    (summary.kind != usb.diagnostics.MapKind.COPY ||
                                        summary.nonTriedSectors == 0UL) &&
                                        (sectorSize == null ||
                                            rescueImageLengthIssue(
                                                ready.pair.image.sizeBytes,
                                                summary.sectorCount,
                                                sectorSize,
                                                format,
                                            ) == null)
                                }
                            } == true
                        }
                    }
                    ?: selections
                        .filterIsInstance<BackupArchive.Selection.Invalid>()
                        .firstOrNull { it.problem != BackupArchive.Problem.Empty }
                    ?: BackupArchive.select(emptyList())
                when (selection) {
                    is BackupArchive.Selection.Invalid -> invalid = selection
                    is BackupArchive.Selection.Ready -> {
                        val pair = selection.pair
                        imageUri = pair.image.uri
                        val imageFormat = classifyRescueImage(
                            resolver,
                            Uri.parse(pair.image.uri),
                            pair.image.name,
                        )
                        imageInspection = RestoreSourceInspector.inspectImage(
                            uri = pair.image.uri,
                            name = pair.image.name,
                            sizeBytes = pair.image.sizeBytes,
                            format = imageFormat,
                            lastModified = contentLastModified(
                                resolver,
                                Uri.parse(pair.image.uri),
                            ),
                        )
                        val imageBytes = pair.image.sizeBytes
                        val rejections = mutableListOf<String>()
                        // Canonical first, then newest generation. A truncated/older map
                        // never hides a valid one; a partial COPY is not chosen.
                        for (candidate in pair.maps) {
                            val inspection = try {
                                RestoreSourceInspector.inspectMap(
                                    uri = candidate.uri,
                                    name = candidate.name,
                                    sizeBytes = candidate.sizeBytes,
                                    text = folder.readText(
                                        Uri.parse(candidate.uri),
                                        RescueMap.MAX_ENCODED_CHARS,
                                    ),
                                )
                            } catch (failure: Throwable) {
                                rejections += "${candidate.name}: " +
                                    normalizeProviderText(
                                        failure.message ?: failure.javaClass.simpleName,
                                    )
                                continue
                            }
                            val summary = inspection.summary
                            if (inspection.error != null || summary == null) {
                                rejections += "${candidate.name}: " +
                                    (inspection.error ?: "map details unavailable")
                                continue
                            }
                            if (summary.kind == usb.diagnostics.MapKind.COPY &&
                                summary.nonTriedSectors > 0UL
                            ) {
                                rejections += "${candidate.name}: incomplete checkpoint " +
                                    "(${summary.nonTriedSectors} nontried sectors)"
                                continue
                            }
                            val liveSectorSize = state.diskSectorSize.takeIf { it > 0 }
                            if (summary.sectorSize != null && liveSectorSize != null &&
                                summary.sectorSize != liveSectorSize
                            ) {
                                rejections += "${candidate.name}: sector size " +
                                    "${summary.sectorSize} != $liveSectorSize"
                                continue
                            }
                            if (liveSectorSize != null) {
                                val liveSectors = state.device?.capacityBytes?.let {
                                    it / liveSectorSize.toULong()
                                }
                                if (liveSectors != null && summary.sectorCount > liveSectors) {
                                    rejections += "${candidate.name}: covers " +
                                        "${summary.sectorCount} > $liveSectors sectors"
                                    continue
                                }
                            }
                            val sectorSize = summary.sectorSize
                                ?: state.diskSectorSize.takeIf { it > 0 }
                            if (sectorSize != null) {
                                when (
                                    rescueImageLengthIssue(
                                        imageBytes,
                                        summary.sectorCount,
                                        sectorSize,
                                        imageFormat,
                                    )
                                ) {
                                    usb.ui.RescueImageLengthIssue.SHORT -> {
                                        val expected = BackupArchive.expectedImageBytes(
                                            summary.sectorCount,
                                            sectorSize,
                                        )
                                        rejections += "${candidate.name}: image is " +
                                            "$imageBytes bytes; map needs at least $expected"
                                        continue
                                    }
                                    usb.ui.RescueImageLengthIssue.OVERFLOW -> {
                                        rejections += "${candidate.name}: geometry overflows"
                                        continue
                                    }
                                    null -> Unit
                                }
                            }
                            mapUri = candidate.uri
                            mapInspection = inspection
                            break
                        }
                        if (mapUri == null) {
                            failureMessage = rejections.joinToString("; ").ifEmpty {
                                "no readable map for ${pair.image.name}"
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                failureMessage = normalizeProviderText(
                    failure.message ?: failure.javaClass.simpleName,
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != rescueArchivePickGeneration.get()) return@withContext
                if (expectedAttachGeneration != null &&
                    liveState().device?.attachGeneration != expectedAttachGeneration
                ) {
                    intents(UiIntent.SetRescueArchiveTree(null))
                    intents(UiIntent.SetRescueImage(null, null, generation))
                    intents(UiIntent.SetRescueMap(null, null, generation))
                    intents(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                    return@withContext
                }
                val rejection = failureMessage
                if (rejection != null) {
                    intents(UiIntent.SetRescueArchiveTree(null))
                    intents(UiIntent.SetRescueImage(null, null, generation))
                    intents(UiIntent.SetRescueMap(null, null, generation))
                    if (!quiet) {
                        intents(
                            UiIntent.Status(
                                UiMessage.Key(
                                    UiMessageKey.ArchiveUnreadable,
                                    listOf(rejection),
                                ),
                            ),
                        )
                    }
                    return@withContext
                }
                invalid?.let {
                    intents(UiIntent.SetRescueArchiveTree(null))
                    intents(UiIntent.SetRescueImage(null, null, generation))
                    intents(UiIntent.SetRescueMap(null, null, generation))
                    if (!quiet) intents(UiIntent.Status(UiReducer.archiveInvalidMessage(it)))
                    return@withContext
                }
                intents(UiIntent.SetRescueArchiveTree(uri.toString()))
                intents(UiIntent.SetRescueImage(imageUri, imageInspection, generation))
                intents(UiIntent.SetRescueMap(mapUri, mapInspection, generation))
            }
        }
    }

    fun requireBackupFolder(state: AppUiState): SafTree {
        val uri = state.backupFolderUri
            ?: throw IllegalStateException("backup folder not selected")
        require(hasPersistedWrite(uri)) {
            "backup folder access was lost; choose the folder again"
        }
        return tree(Uri.parse(uri))
    }

    fun readBlob(uri: String, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "invalid GPT backup max size $maxBytes" }
        val input = resolver.openInputStream(Uri.parse(uri))
            ?: throw IllegalStateException("cannot open $uri for reading")
        return input.use { stream ->
            val buffer = ByteArray(maxBytes + 1)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            require(read <= maxBytes) {
                "selected file holds more than $maxBytes bytes (GPT critical backup cap)"
            }
            require(read > 0) {
                "selected file is empty; expected a GPT critical backup"
            }
            buffer.copyOf(read)
        }
    }

    /** Invalidate in-flight rescue inspections (ResetRescueSelection). */
    fun invalidateRescueSelection() {
        rescueArchivePickGeneration.incrementAndGet()
    }

    /** Invalidate in-flight GPT inspections (ResetGptSelection). */
    fun invalidateGptSelection() {
        gptPickGeneration.incrementAndGet()
    }

    private fun classifyRescueImage(
        resolver: ContentResolver,
        uri: Uri,
        name: String?,
    ): usb.image.ImageFormat? =
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(6)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) break
                    read += n
                }
                usb.image.classifyImageContent(name, header.copyOf(read))
            }
        }.getOrNull()

    private fun hasPersistedWrite(uriString: String): Boolean =
        runCatching {
            resolver.persistedUriPermissions.any {
                it.isWritePermission && it.uri.toString() == uriString
            }
        }.getOrDefault(false)

    private companion object {
        fun isGptBackupName(name: String): Boolean =
            name.startsWith("gpt-critical-", ignoreCase = true) &&
                name.endsWith(".bin", ignoreCase = true)

        fun contentLastModified(resolver: ContentResolver, uri: Uri): Long? =
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { query ->
                    if (!query.moveToFirst()) {
                        null
                    } else {
                        val index = query.getColumnIndex(
                            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        )
                        if (index >= 0 && !query.isNull(index)) query.getLong(index) else null
                    }
                }
            }.getOrNull()

        fun contentInfo(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val cursor = try {
                resolver.query(uri, columns, null, null, null)
            } catch (_: RuntimeException) {
                // Metadata is optional; the stream itself is still opened (and may fail loudly).
                null
            } ?: return Pair(null, null)
            return cursor.use { query ->
                if (!query.moveToFirst()) {
                    Pair(null, null)
                } else {
                    val nameIndex = query.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = query.getColumnIndex(OpenableColumns.SIZE)
                    val name =
                        if (nameIndex >= 0 && !query.isNull(nameIndex)) {
                            query.getString(nameIndex)
                        } else {
                            null
                        }
                    val size =
                        if (sizeIndex >= 0 && !query.isNull(sizeIndex)) {
                            query.getLong(sizeIndex)
                        } else {
                            null
                        }
                    Pair(name, size)
                }
            }
        }

        fun logLine(message: String): String {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            return "[$ts] $message"
        }
    }
}
