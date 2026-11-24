package usb.multitool

import android.app.Application
import android.hardware.usb.UsbDevice
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import usb.files.AttachmentChangedException
import usb.files.AttachmentState
import usb.image.ImageSource
import usb.image.ImageStaging
import usb.image.RawWriter
import usb.operation.DiskOperation
import usb.service.OperationHandle
import usb.service.OperationToken
import usb.operation.syncDestructiveDisk
import usb.partition.GptBackup
import usb.partition.PartitionEditor
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.PartitionWriter
import usb.partition.VirtualPartition
import usb.partition.isProtectedSystemPartition
import usb.ui.AppUiState
import usb.service.DiskController
import usb.device.withWriteTracking
import usb.ui.DestructiveAction
import usb.ui.OperationProgress
import usb.ui.OperationState
import usb.ui.OperationTitle
import usb.ui.PartitionRef
import usb.ui.PartitionRole
import usb.ui.toTableScheme
import usb.ui.UiEffect
import usb.ui.UiIntent
import usb.ui.toOperationResult
import usb.fs.FormatCatalog
import usb.fs.Formatter
import usb.fs.Filesystem
import usb.diagnostics.Diagnostics
import usb.diagnostics.RescueMap
import usb.diagnostics.RescueOperations
import usb.diagnostics.RescueRestorePrep
import usb.ventoy.VentoyAssets
import usb.ventoy.VentoyOperations
import java.io.File

/**
 * Builds and owns one confirmed destructive disk operation. Confirmation identity,
 * geometry and source hashes are re-checked against the live disk inside the worker;
 * the UI only receives terminal progress through [intents].
 */
internal class DestructiveOperationCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val state: () -> AppUiState,
    private val attachGeneration: () -> Long,
    private val openRawDisk: ((AttachmentState.Snapshot<UsbDevice>) -> UsbDevice?) -> UsbOpenBoundary.RawUsbSession?,
    private val intents: (UiIntent) -> Unit,
    private val logLine: (String) -> String,
    private val nextToken: () -> OperationToken,
    private val activeHandle: () -> OperationHandle?,
    private val setActiveHandle: (OperationHandle?) -> Unit,
    private val activeHandles: MutableSet<OperationHandle>,
    private val refreshPartitions: () -> Unit,
    private val readBlob: (String, Int) -> ByteArray,
    private val readText: (String, Int) -> String,
    private val contentInfo: (android.content.ContentResolver, Uri) -> Pair<String?, Long?>,
    private val contentLastModified: (android.content.ContentResolver, Uri) -> Long?,
    private val backupDirectory: () -> File,
    private val timestamp: () -> String,
    private val ventoyAssets: (usb.ventoy.VentoyReleaseSource?) -> VentoyAssets,
    private val settingsSuffix: (Int, Boolean) -> String,
) {
    private var stagedConsumedPath: String? = null

    /** Run one already-confirmed destructive action. */
    fun run(effect: UiEffect.SubmitDestructive) {
                val action = effect.action
                val opTitle = OperationTitle.Destructive(action)
                val actionName = opTitle.logLabel
                val uiState = state()
                val confirmed = effect.confirmation
                val confirmedFormatId = confirmed.formatId
                var rawWriteOutcome: usb.image.RawWriteResult? = null
                val op = object : DiskOperation(name = actionName) {
                    /** Concrete outcome surfaced instead of the bare "Success" label. */
                    @Volatile var result: String? = null

                    override suspend fun run() {
                        // Close the FAT session, revalidate the confirmed attachment against
                        // the live snapshot, and take the exclusive USB claim in one step.
                        val raw = openRawDisk { live ->
                            require(live.generation == confirmed.identity.attachGeneration) {
                                "USB attach generation changed"
                            }
                            live.device ?: throw usb.device.DiskException.Disconnected()
                        } ?: throw AttachmentChangedException()
                        val dev = raw.device
                        val disk = raw.disk
                        try {
                            // Confirmation uses DeviceIdentity.identity (label#serial). RESTORE
                            // resume also requires the checkpoint disk identity to match the
                            // live stick (serial required) plus matching geometry.
                            val identity = confirmed.identity.identity
                            require(confirmed.identity.attachGeneration == attachGeneration()) { "USB attach generation changed" }
                            require(confirmed.identity.capacityBytes == disk.geometry.capacityBytes) { "confirmed capacity does not match live device" }
                            val confirmation = usb.diagnostics.DestructiveConfirmation(
                                identity = identity,
                                capacityBytes = confirmed.identity.capacityBytes,
                            )
                            if (action == DestructiveAction.RESCUE_RESTORE) {
                                // Without a stable serial, RescueRestorePrep discards any
                                // inherited resume progress and rewrites every sector. Only
                                // compare serials when the confirmed stick exposed one.
                                val serial = uiState.device?.serialNumber?.trim().orEmpty()
                                if (serial.isNotEmpty()) {
                                    val liveSerial = runCatching {
                                        dev.serialNumber?.trim().orEmpty()
                                    }.getOrNull().orEmpty()
                                    require(liveSerial.isNotEmpty() && liveSerial == serial) {
                                        "live USB serial '$liveSerial' does not match confirmed serial '$serial'"
                                    }
                                }
                            }
                            val table = runCatching { PartitionTable.read(disk) }.getOrNull()
                            if (action == DestructiveAction.RAW_WRITE) {
                                confirmed.diskSectorCount?.let {
                                    require(it == disk.geometry.sectorCount) {
                                        "confirmed sector count $it does not match live ${disk.geometry.sectorCount}"
                                    }
                                }
                                confirmed.diskSectorSize?.let {
                                    require(it == disk.geometry.sectorSize) {
                                        "confirmed sector size $it does not match live ${disk.geometry.sectorSize}"
                                    }
                                }
                            }
                            // Both Long and ULong write overloads — Ventoy uses Long LBA.
                            val partitionDisk =
                                disk.withWriteTracking { mutationAttempted = true }
                            when (action) {
                                DestructiveAction.WIPE -> {
                                    Diagnostics.wipe(
                                        disk = disk,
                                        identity = identity,
                                        confirmation = confirmation,
                                        operation = this,
                                    )
                                }
                                DestructiveAction.GPT_REPAIR -> {
                                    // Moves the backup entries + header to the current disk end,
                                    // fixes backupLba/lastUsable and recomputes both CRCs.
                                    val expected = confirmed.initialScheme
                                        ?: throw IllegalStateException(
                                            "initial scheme not confirmed for GPT repair",
                                        )
                                    val current = table
                                        ?: throw IllegalStateException("no partition table")
                                    if (current.scheme != expected.toTableScheme()) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition scheme changed since the confirmation " +
                                                "(${current.scheme} != ${expected.toTableScheme()})",
                                        )
                                    }
                                    val liveSnapshot = current.partitions.map {
                                        PartitionRef(it.startLba, it.sectorCount)
                                    }
                                    if (liveSnapshot != confirmed.initialPartitions) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition layout changed since the confirmation",
                                        )
                                    }
                                    val repaired = GptBackup.relocateBackup(partitionDisk, disk.geometry.sectorCount)
                                    result = "GPT backup relocated to LBA ${disk.geometry.sectorCount - 1UL}: " +
                                        "${repaired.partitions.size} partitions kept"
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.DELETE_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val index = current.partitions.indexOf(target)
                                        require(index >= 0) { "confirmed partition geometry missing" }
                                        PartitionWriter.commit(partitionDisk, PartitionEditor.delete(current, index))
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.SET_PARTITION_ROLE -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val index = current.partitions.indexOf(target)
                                        require(index >= 0) { "confirmed partition geometry missing" }
                                        val role = confirmed.partitionRole
                                            ?: throw IllegalStateException("partition role missing")
                                        val type = when (role) {
                                            PartitionRole.Data -> PartitionType.BASIC
                                            PartitionRole.Efi -> PartitionType.EFI
                                        }
                                        PartitionWriter.commit(
                                            partitionDisk,
                                            PartitionEditor.setType(current, index, type),
                                        )
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.SET_PARTITION_BOOTABLE -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val index = current.partitions.indexOf(target)
                                        require(index >= 0) { "confirmed partition geometry missing" }
                                        val bootable = confirmed.partitionBootable
                                            ?: throw IllegalStateException("bootable flag missing")
                                        PartitionWriter.commit(
                                            partitionDisk,
                                            PartitionEditor.setFlags(current, index, bootable = bootable),
                                        )
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.CREATE_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    current.requireUnambiguousLayout()
                                    val start = confirmed.createStartLba ?: throw IllegalStateException("partition start not confirmed")
                                    val size = confirmed.createSectorCount ?: throw IllegalStateException("partition size not confirmed")
                                    val created = PartitionEditor.create(
                                        current,
                                        size,
                                        PartitionType.BASIC,
                                        start,
                                        name = confirmed.createName,
                                    )
                                    PartitionWriter.commit(partitionDisk, created)
                                    val formatId = confirmed.createFormatId
                                    if (formatId != null) {
                                        val supported = Formatter.requireSupported(formatId)
                                        // Format the exact confirmed range, not a re-probed one.
                                        val partition = VirtualPartition(start, size, partitionDisk)
                                        try {
                                            Formatter.format(
                                                partition,
                                                supported,
                                                label = confirmed.volumeLabel,
                                            )
                                        } catch (formatFailure: Throwable) {
                                            // The entry is already committed: never report "failed"
                                            // as if nothing was created.
                                            result = "Partition created at LBA $start ($size sectors); " +
                                                "formatting as ${FormatCatalog.label(supported)} failed: " +
                                                (formatFailure.message ?: formatFailure.javaClass.simpleName) +
                                                ". The partition exists but is unformatted."
                                            state = usb.operation.OperationState.Partial
                                            failureCause = formatFailure
                                            throw formatFailure
                                        }
                                        result = "Created partition at LBA $start ($size sectors), " +
                                            "formatted as ${FormatCatalog.label(supported)}" +
                                            if (confirmed.volumeLabel.isNotEmpty()) {
                                                ", label '${confirmed.volumeLabel}'"
                                            } else {
                                                ""
                                            }
                                    } else {
                                        result = "Created partition at LBA $start ($size sectors), unformatted"
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.RENAME_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    current.requireUnambiguousLayout()
                                    val name = confirmed.partitionName
                                        ?: throw IllegalStateException("partition name not confirmed")
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        // An ESP/VTOYEFI rename would drop the name that protects it;
                                        // the EFI write override never lifts this.
                                        require(!isProtectedSystemPartition(target)) {
                                            "${target.name} is a protected system partition and cannot be renamed"
                                        }
                                        val index = current.partitions.indexOf(target)
                                        require(index >= 0) { "confirmed partition geometry missing" }
                                        PartitionWriter.commit(
                                            partitionDisk,
                                            PartitionEditor.rename(current, index, name),
                                        )
                                    }
                                    result = "Renamed partition at LBA ${confirmed.partitionStartLba} to '$name'"
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.RENAME_VOLUME_LABEL -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    current.requireUnambiguousLayout()
                                    val label = confirmed.volumeLabel
                                    require(label.isNotEmpty()) { "volume label not confirmed" }
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val partition = VirtualPartition(
                                            startLba = target.startLba,
                                            sectorCount = target.sectorCount,
                                            disk = partitionDisk,
                                        )
                                        val filesystemId = Filesystem.probe(partition)
                                        Formatter.setVolumeLabel(partition, filesystemId, label)
                                        result = "Volume label of ${target.name} changed to '$label' " +
                                            "(${FormatCatalog.label(filesystemId)})"
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.INITIALIZE_PARTITION_TABLE -> {
                                    val target = confirmed.targetScheme
                                        ?: throw IllegalStateException("target scheme not confirmed")
                                    val expected = confirmed.initialScheme
                                        ?: throw IllegalStateException("initial scheme not confirmed")
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    current.requireInitializationLayout()
                                    require(!current.gptPrimaryDamaged) {
                                        "GPT primary is damaged; restore a matching GPT backup first"
                                    }
                                    require(current.recognized == confirmed.initialTableRecognized) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition-table recognition changed since confirmation",
                                        )
                                    }
                                    require(disk.geometry.sectorSize == confirmed.initialDiskSectorSize) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "sector size changed since confirmation",
                                        )
                                    }
                                    require(disk.geometry.sectorCount == confirmed.initialDiskSectorCount) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "disk geometry changed since confirmation",
                                        )
                                    }
                                    if (!confirmed.initialTableRecognized) {
                                        if (current.recognized) {
                                            throw usb.diagnostics.ConfirmationMismatchException(
                                                "unrecognized table changed before initialization",
                                            )
                                        }
                                    } else if (current.scheme != expected.toTableScheme()) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition scheme changed since the confirmation " +
                                                "(${current.scheme} != ${expected.toTableScheme()})",
                                        )
                                    }
                                    val liveSnapshot =
                                        current.partitions.map { PartitionRef(it.startLba, it.sectorCount) }
                                    if (liveSnapshot != confirmed.initialPartitions) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition layout changed since the confirmation",
                                        )
                                    }
                                    result = usb.partition.TableInitializer.initializeConfirmed(
                                        disk = partitionDisk,
                                        table = current,
                                        target = target.toTableScheme(),
                                        expectedCurrent = confirmed.initialMetadataFingerprint
                                            ?: throw usb.diagnostics.ConfirmationMismatchException(
                                                "current metadata fingerprint missing",
                                            ),
                                        expectedTarget = confirmed.initialInitializationMetadataFingerprint
                                            ?: throw usb.diagnostics.ConfirmationMismatchException(
                                                "target metadata fingerprint missing",
                                            ),
                                    )
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.FORMAT_PARTITION -> {
                                    val current = table ?: throw usb.diagnostics.ConfirmationMismatchException("confirmed partition geometry missing")
                                    current.requireUnambiguousLayout()
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val partition = VirtualPartition(
                                            startLba = target.startLba,
                                            sectorCount = target.sectorCount,
                                            disk = partitionDisk,
                                        )
                                        // FS pinned in the confirmation payload (effect.formatId).
                                        val formatId = Formatter.requireSupported(
                                            confirmedFormatId
                                                ?: throw IllegalStateException("format confirmation missing filesystem id"),
                                        )
                                        Formatter.format(partition, formatId)
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.GPT_RESTORE -> {
                                    // Snapshot on confirmation — never a later uiState.backupUri.
                                    val uri = confirmed.gptBackupUri
                                        ?: throw IllegalStateException("no GPT backup URI in confirmation")
                                    require(uiState.backupUri == null || uiState.backupUri == uri) {
                                        "GPT backup URI changed after confirmation"
                                    }
                                    val blob = readBlob(uri, GptCriticalBackup.MAX_BLOB_BYTES)
                                    confirmed.gptBackupInspection?.sha256?.let { expected ->
                                        require(usb.ui.RestoreSourceInspector.sha256(blob) == expected) {
                                            "GPT backup source changed after confirmation"
                                        }
                                    }
                                    bytesTotal = blob.size.toLong()
                                    val restored = GptCriticalBackup.restore(
                                        disk = disk,
                                        blob = blob,
                                        operation = this,
                                    )
                                    report(blob.size.toLong())
                                    result = "Restored ${restored.sectorsRestored} GPT critical " +
                                        "sectors from ${uri.substringAfterLast('/')}: " +
                                        "${restored.partitions} partitions, backup at LBA " +
                                        "${restored.backup.currentLba} verified"
                                }
                                DestructiveAction.RESCUE_RESTORE -> {
                                    val imageUri = confirmed.rescueImageUri
                                        ?: throw IllegalStateException("no rescue image URI in confirmation")
                                    val mapUri = confirmed.rescueMapUri
                                        ?: throw IllegalStateException("no rescue map URI in confirmation")
                                    require(
                                        (uiState.rescueImageUri == null || uiState.rescueImageUri == imageUri) &&
                                            (uiState.rescueMapUri == null || uiState.rescueMapUri == mapUri),
                                    ) {
                                        "rescue source URI changed after confirmation"
                                    }
                                    val mapText = readText(
                                        mapUri,
                                        RescueMap.MAX_ENCODED_CHARS,
                                    )
                                    confirmed.rescueMapInspection?.sha256?.let { expected ->
                                        require(usb.ui.RestoreSourceInspector.sha256Text(mapText) == expected) {
                                            "rescue map changed after confirmation"
                                        }
                                    }
                                    val loaded = RescueMap.decode(mapText)
                                    val liveId = identity
                                    require(loaded.sectorSize != null) {
                                        "rescue map has no sector-size header; refusing to guess"
                                    }
                                    if (loaded.sectorSize != disk.sectorSize) {
                                        throw IllegalStateException(
                                            "rescue map sectorSize ${loaded.sectorSize} != disk ${disk.sectorSize}",
                                        )
                                    }
                                    require(loaded.sectorCount <= disk.geometry.sectorCount) {
                                        "rescue map covers ${loaded.sectorCount} sectors; disk has ${disk.geometry.sectorCount}"
                                    }
                                    // Policy: partial COPY is refused (NON_TRIED → zeros). RESTORE
                                    // resumes only when the checkpoint disk identity matches the live
                                    // stick (serial required) and geometry matches. After admission,
                                    // every copied range is re-read and rewritten unless it matches
                                    // the current image data. Do not change the image until the
                                    // operation finishes: it is read as-is, a change can leave mixed
                                    // data, and no snapshot/immutability or change detection is
                                    // promised. No image hash is involved.
                                    val liveHasSerial = uiState.device?.hasStableSerial == true
                                    val (restoreMap, prepNote) =
                                        RescueRestorePrep.prepare(
                                            loaded = loaded,
                                            liveIdentity = liveId,
                                            sectorSize = disk.sectorSize,
                                            liveHasSerial = liveHasSerial,
                                        )
                                    require(restoreMap.sectorCount <= (Long.MAX_VALUE.toULong() / disk.sectorSize.toULong())) {
                                        "rescue image size exceeds signed progress range"
                                    }
                                    val expected = (restoreMap.sectorCount * disk.sectorSize.toULong()).toLong()
                                    bytesTotal = expected
                                    val resolver = application.contentResolver
                                    val liveImage = contentInfo(resolver, Uri.parse(imageUri))
                                    confirmed.rescueImageInspection?.sizeBytes?.let { expected ->
                                        require(liveImage.second == expected) {
                                            "rescue image size changed after confirmation"
                                        }
                                    }
                                    confirmed.rescueImageInspection?.lastModified?.let { expected ->
                                        require(contentLastModified(resolver, Uri.parse(imageUri)) == expected) {
                                            "rescue image changed after confirmation"
                                        }
                                    }
                                    val source =
                                        ImageSource.fromContentUri(
                                            Uri.parse(imageUri),
                                            resolver,
                                        )
                                    val imageName = confirmed.rescueImageName
                                        ?: imageUri.substringAfterLast('/')
                                    val imageStem = imageName.substringBeforeLast('.')
                                        .ifBlank { "rescue" }
                                    val checkpointFolder = confirmed.rescueArchiveTreeUri?.let { tree ->
                                        runCatching {
                                            usb.saf.SafBackupFolder(
                                                application.contentResolver,
                                                Uri.parse(tree),
                                            )
                                        }.getOrNull()
                                    }
                                    val checkpointPublisher = checkpointFolder?.checkpointPublisher(
                                        baseName = "$imageStem.map",
                                        startGeneration = (System.currentTimeMillis() / 1000L)
                                            .toInt()
                                            .coerceAtLeast(1),
                                    )
                                    val checkpointFile =
                                        if (checkpointPublisher == null) {
                                            File(
                                                backupDirectory(),
                                                "rescue-restore-${timestamp()}-${System.nanoTime()}.map",
                                            )
                                        } else {
                                            null
                                        }
                                    val rescueResult = RescueOperations.restore(
                                        source = source,
                                        disk = disk,
                                        map = restoreMap,
                                        identity = liveId,
                                        confirmation = confirmation,
                                        checkpointFile = checkpointFile,
                                        checkpoint = checkpointPublisher?.let { publisher ->
                                            { mapToSave -> publisher.publish(mapToSave) }
                                        },
                                        operation = this,
                                    )
                                    if (!rescueResult.partial && checkpointFile != null) {
                                        runCatching { checkpointFile.delete() }
                                    }
                                    if (rescueResult.partial && checkpointPublisher != null) {
                                        val latest = checkpointPublisher.latestDocument
                                        if (latest != null) {
                                            val latestUri = Uri.parse(latest)
                                            val latestText = runCatching {
                                                checkpointFolder.readText(
                                                    latestUri,
                                                    RescueMap.MAX_ENCODED_CHARS,
                                                )
                                            }.getOrNull()
                                            val inspection = latestText?.let {
                                                usb.ui.RestoreSourceInspector.inspectMap(
                                                    uri = latest,
                                                    name = latestUri.lastPathSegment,
                                                    sizeBytes = null,
                                                    text = it,
                                                )
                                            }
                                            if (inspection != null) {
                                                intents(
                                                    UiIntent.SetRescueMap(
                                                        latest,
                                                        inspection,
                                                        uiState.rescueMapSelectionGeneration + 1,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    val checkpointLabel =
                                        checkpointPublisher?.latestDocument?.substringAfterLast('/')
                                            ?: checkpointFile?.name
                                            ?: "none"
                                    result =
                                        "Rescue restore: " +
                                            "${restoreMap.copied.sumOf { it.last - it.first + 1UL }} copied, " +
                                            "${restoreMap.failed.sumOf { it.last - it.first + 1UL }} failed of " +
                                            "${restoreMap.sectorCount} sectors" +
                                            if (rescueResult.partial) {
                                                " (partial — checkpoint $checkpointLabel)"
                                            } else {
                                                ""
                                            } +
                                            (prepNote?.let { " — $it" } ?: "")
                                }
                                DestructiveAction.VENTOY_INSTALL -> {
                                    val assets = ventoyAssets(confirmed.ventoyRelease)
                                    // First disk.write marks mutationAttempted (partitionDisk wrap).
                                    val settingsWritten =
                                        VentoyOperations.install(
                                            partitionDisk,
                                            assets,
                                            this,
                                            controls = confirmed.ventoyControls,
                                        )
                                    result =
                                        "Ventoy installed" +
                                            settingsSuffix(
                                                confirmed.ventoyControls.size,
                                                settingsWritten,
                                            )
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.VENTOY_UPDATE -> {
                                    val assets = ventoyAssets(confirmed.ventoyRelease)
                                    val settingsWritten =
                                        VentoyOperations.update(
                                            partitionDisk,
                                            assets,
                                            this,
                                            controls = confirmed.ventoyControls,
                                        )
                                    result =
                                        "Ventoy updated" +
                                            if (confirmed.ventoyControls.isEmpty()) {
                                                "; existing global settings preserved"
                                            } else {
                                                settingsSuffix(
                                                    confirmed.ventoyControls.size,
                                                    settingsWritten,
                                                )
                                            }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.RAW_WRITE -> {
                                    val stagedPath = uiState.stagedRawImagePath
                                    val source =
                                        if (stagedPath != null) {
                                            val filesDir = application.filesDir
                                            require(ImageStaging.isManaged(filesDir, stagedPath)) {
                                                "refusing raw write: $stagedPath is not a staging file " +
                                                    "created in this app session (re-stage the image)"
                                            }
                                            val file = java.io.File(stagedPath)
                                            require(file.isFile) {
                                                "staged image missing: $stagedPath"
                                            }
                                            ImageSource.fromFile(file)
                                        } else {
                                            val uri = uiState.imageUri
                                                ?: throw IllegalStateException(
                                                    "no image selected for raw write",
                                                )
                                            ImageSource.fromContentUri(
                                                Uri.parse(uri),
                                                application.contentResolver,
                                            )
                                        }
                                    // confirmation already bound to identity/capacity at dialog time;
                                    // requireMatch inside RawWriter re-checks the live disk.
                                    val range =
                                        usb.partition.RawWriteRange(
                                            startLba = confirmed.rawStartLba
                                                ?: throw IllegalStateException(
                                                    "raw write range start missing from confirmation",
                                                ),
                                            sectorCount = confirmed.rawSectorCount
                                                ?: throw IllegalStateException(
                                                    "raw write range size missing from confirmation",
                                                ),
                                        )
                                    rawWriteOutcome = RawWriter.write(
                                        source = source,
                                        disk = partitionDisk,
                                        identity = identity,
                                        confirmation = confirmation,
                                        overwrite = true,
                                        range = range,
                                        expectedDecodedBytes = confirmed.rawDecodedBytes,
                                        expectedSha256 = confirmed.rawSourceSha256,
                                        verifyReadback = confirmed.rawVerifyReadback,
                                        operation = this,
                                    )
                                    if (stagedPath != null) {
                                        // Defer delete until terminal Completed (Partial/Failed keep file).
                                        stagedConsumedPath = stagedPath
                                    }
                                }
                                DestructiveAction.VENTOY_LINK_XML ->
                                    throw IllegalStateException("Ventoy XML links use dedicated link flow")
                                // Confirmation-only gates; execution is GenerateAutounattend /
                                // ConfirmDiskLayout effects, never this destructive runner.
                                DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
                                DestructiveAction.VENTOY_UNATTEND_DISK_LAYOUT ->
                                    throw IllegalStateException(
                                        "Unattend XML write/layout uses dedicated generate flow",
                                    )
                            }
                            if (action == DestructiveAction.RAW_WRITE) {
                                // RawWriter already synced the medium after the last write
                                // and folded any sync failure into its readback verdict; a
                                // second sync would only duplicate that checked call.
                                if (state == usb.operation.OperationState.Completed) commitReached = true
                            } else {
                                syncDestructiveDisk(disk)
                            }
                        } finally {
                            disk.close()
                        }
                    }
                }
                val isCooperative = action.cooperative
                val token = nextToken()
                val handle = DiskController.get().submit(op, token)
                if (isCooperative) setActiveHandle(handle)
                activeHandles.add(handle)
                intents(
                    UiIntent.OperationBegan(
                        OperationProgress(
                            state = OperationState.Queued,
                            title = opTitle,
                            token = token,
                            cancelable = isCooperative,
                        ),
                    ),
                )
                scope.launch {
                    val ticker = launch {
                        while (true) {
                            delay(LOG_TICK_MS)
                            val sample = op.progressSnapshot()
                            val done = sample.bytesDone
                            val total = sample.bytesTotal
                            if (handle.state == usb.operation.OperationState.Running) {
                                intents(UiIntent.OperationUpdated(OperationProgress(
                                    state = OperationState.Running,
                                    title = opTitle,
                                    token = token,
                                    bytesDone = done,
                                    bytesTotal = total,
                                    bytesPerSecond = sample.bytesPerSecond,
                                    cancelable = isCooperative,
                                    phase = sample.phase,
                                )))
                                val phaseName = sample.phase?.let { " [${it.name}]" }.orEmpty()
                                val progress = if (total > 0) " ${(done.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else " $done bytes"
                                val message = "$actionName$phaseName$progress"
                                if (state().operation?.token == token) {
                                    android.util.Log.i("UsbMultitool", message)
                                    intents(UiIntent.AppendLog(logLine(message)))
                                }
                            }
                        }
                    }
                    try {
                        val outcome = handle.await()
                        ticker.cancel()
                        val result = outcome.toOperationResult(op.failureCause)
                        intents(
                            UiIntent.OperationUpdated(
                                result.toProgress(
                                    token = token,
                                    title = opTitle,
                                    cancelable = isCooperative,
                                    resultMessage = op.result,
                                    rawWriteResult = rawWriteOutcome,
                                ),
                            ),
                        )
                        if (result.state == OperationState.Completed) {
                            val consumed = stagedConsumedPath
                            stagedConsumedPath = null
                            if (consumed != null && state().stagedRawImagePath == consumed) {
                                // Terminal Completed — delete managed staging + clear UI.
                                intents(UiIntent.ClearStagedRawImage(deleteFile = true))
                            }
                        } else {
                            // Partial/Failed/Cancelled: keep staging for retry; forget consume mark.
                            stagedConsumedPath = null
                        }
                        when (action) {
                            DestructiveAction.DELETE_PARTITION,
                            DestructiveAction.CREATE_PARTITION,
                            DestructiveAction.FORMAT_PARTITION,
                            DestructiveAction.GPT_REPAIR,
                            DestructiveAction.GPT_RESTORE,
                            DestructiveAction.RESCUE_RESTORE,
                            DestructiveAction.VENTOY_INSTALL,
                            DestructiveAction.VENTOY_UPDATE,
                            DestructiveAction.SET_PARTITION_ROLE,
                            DestructiveAction.SET_PARTITION_BOOTABLE,
                            DestructiveAction.RENAME_PARTITION,
                            DestructiveAction.RENAME_VOLUME_LABEL,
                            DestructiveAction.INITIALIZE_PARTITION_TABLE,
                            DestructiveAction.WIPE,
                            DestructiveAction.RAW_WRITE -> refreshPartitions()
                            else -> Unit
                        }
                    } finally {
                        ticker.cancel()
                        if (activeHandle() === handle) setActiveHandle(null)
                        activeHandles.remove(handle)
                    }
                }
            }
    private companion object {
        const val LOG_TICK_MS = 1_000L
    }
}
