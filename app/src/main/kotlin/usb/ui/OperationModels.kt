package usb.ui

import usb.service.OperationToken
import usb.operation.DiskOperationPhase

enum class OperationState {
    Queued,
    Running,
    Completed,
    Cancelled,
    Failed,
    Partial,
}


sealed interface OperationTitle {
    /** English technical string for journals and DiskOperation.name. Never localized. */
    val logLabel: String

    data class Destructive(val action: DestructiveAction) : OperationTitle {
        override val logLabel: String get() = action.displayName()
    }

    data object ProbeDevice : OperationTitle {
        override val logLabel: String get() = "Probe device"
    }

    data object ReadScan : OperationTitle {
        override val logLabel: String get() = "Read scan"
    }

    data object SmartRead : OperationTitle {
        override val logLabel: String get() = "SMART read"
    }


    data object RescueBackup : OperationTitle {
        override val logLabel: String get() = "Rescue backup"
    }

    data object GptCriticalBackup : OperationTitle {
        override val logLabel: String get() = "GPT critical backup"
    }

    data object RefreshPartitions : OperationTitle {
        override val logLabel: String get() = "Refresh partitions"
    }

    data object SelfTest : OperationTitle {
        override val logLabel: String get() = "Self-test"
    }

    /** Transient running banner before the terminal Self-test title. */
    data object SelfTestRunning : OperationTitle {
        override val logLabel: String get() = "Self-test: running..."
    }

    data object Upload : OperationTitle {
        override val logLabel: String get() = "Upload"
    }

    data object AutounattendXmlLink : OperationTitle {
        override val logLabel: String get() = "autounattend.xml link"
    }

    data object CheckRawRange : OperationTitle {
        override val logLabel: String get() = "Check raw range"
    }

    data object ListRawSources : OperationTitle {
        override val logLabel: String get() = "List raw sources"
    }

    data object ListDataPartitionFiles : OperationTitle {
        override val logLabel: String get() = "List data partition files"
    }

    data object ReadXmlBindings : OperationTitle {
        override val logLabel: String get() = "Read XML bindings"
    }

    data object RemoveXmlBindings : OperationTitle {
        override val logLabel: String get() = "Remove XML bindings"
    }

    data object SaveGlobalWindowsControls : OperationTitle {
        override val logLabel: String get() = "Save global Windows controls"
    }

    data object ReadUnattendXml : OperationTitle {
        override val logLabel: String get() = "Read unattend XML"
    }

    data object ListVentoyImages : OperationTitle {
        override val logLabel: String get() = "List Ventoy images"
    }

    data object GenerateUnattendXml : OperationTitle {
        override val logLabel: String get() = "Generate unattend XML"
    }

    data object WriteUnattendXml : OperationTitle {
        override val logLabel: String get() = "Write unattend XML"
    }

    data object ReadVentoyGlobalSettings : OperationTitle {
        override val logLabel: String get() = "Read Ventoy global settings"
    }

    data object ListVentoyFolders : OperationTitle {
        override val logLabel: String get() = "List Ventoy folders"
    }

    data object SetVentoyGlobalSetting : OperationTitle {
        override val logLabel: String get() = "Apply Ventoy global settings"
    }

    data object BindImagesToUnattendXml : OperationTitle {
        override val logLabel: String get() = "Bind images to unattend XML"
    }

    data object UnbindImageFromUnattendXml : OperationTitle {
        override val logLabel: String get() = "Unbind image from unattend XML"
    }

    data object EjectDevice : OperationTitle {
        override val logLabel: String get() = "Eject device"
    }

    data class ListPath(val path: String) : OperationTitle {
        override val logLabel: String get() = "List $path"
    }

    data class NewFolder(val name: String) : OperationTitle {
        override val logLabel: String get() = "New folder $name"
    }

    data class DeleteEntry(val name: String) : OperationTitle {
        override val logLabel: String get() = "Delete $name"
    }

    data class RenameEntry(val from: String) : OperationTitle {
        override val logLabel: String get() = "Rename $from"
    }

    data class DownloadEntry(val name: String) : OperationTitle {
        override val logLabel: String get() = "Download $name"
    }

    data class StageForRawWrite(val name: String) : OperationTitle {
        override val logLabel: String get() = "Stage $name for raw write"
    }

    data class FormatTarget(val target: String) : OperationTitle {
        override val logLabel: String get() = "Format $target"
    }

    data class ImportTarget(val targetName: String) : OperationTitle {
        override val logLabel: String get() = "Import $targetName"
    }

    data class DownloadBatch(val count: Int) : OperationTitle {
        override val logLabel: String get() = "Download $count entries"
    }

    data class UploadBatch(val count: Int) : OperationTitle {
        override val logLabel: String get() = "Upload $count entries"
    }

    data class DeleteBatch(val count: Int) : OperationTitle {
        override val logLabel: String get() = "Delete $count entries"
    }

    /** True when [OperationProgress] bytes are per current file, not the whole batch. */
    fun reportsCurrentFileBytes(): Boolean =
        this is DownloadBatch || this is UploadBatch
}

/** Tab whose section owns [this] operation; null for global-only actions. */
fun OperationTitle.originTab(): ShellTab? =
    when (this) {
        is OperationTitle.ListPath,
        is OperationTitle.NewFolder,
        is OperationTitle.DeleteEntry,
        is OperationTitle.RenameEntry,
        is OperationTitle.DownloadEntry,
        is OperationTitle.StageForRawWrite,
        is OperationTitle.FormatTarget,
        is OperationTitle.ImportTarget,
        is OperationTitle.DownloadBatch,
        is OperationTitle.UploadBatch,
        is OperationTitle.DeleteBatch,
        OperationTitle.Upload,
        OperationTitle.ListDataPartitionFiles,
        -> ShellTab.Files

        OperationTitle.AutounattendXmlLink,
        OperationTitle.ReadXmlBindings,
        OperationTitle.RemoveXmlBindings,
        OperationTitle.SaveGlobalWindowsControls,
        OperationTitle.ReadUnattendXml,
        OperationTitle.ListVentoyImages,
        OperationTitle.GenerateUnattendXml,
        OperationTitle.WriteUnattendXml,
        OperationTitle.ReadVentoyGlobalSettings,
        OperationTitle.ListVentoyFolders,
        OperationTitle.SetVentoyGlobalSetting,
        OperationTitle.BindImagesToUnattendXml,
        OperationTitle.UnbindImageFromUnattendXml,
        -> ShellTab.Ventoy

        OperationTitle.ReadScan,
        OperationTitle.SmartRead,
        OperationTitle.RescueBackup,
        OperationTitle.GptCriticalBackup,
        OperationTitle.SelfTest,
        OperationTitle.SelfTestRunning,
        -> ShellTab.Diagnostics

        OperationTitle.ProbeDevice,
        OperationTitle.RefreshPartitions,
        OperationTitle.CheckRawRange,
        OperationTitle.ListRawSources,
        -> ShellTab.Partitions

        OperationTitle.EjectDevice -> null

        is OperationTitle.Destructive ->
            when (action) {
                DestructiveAction.VENTOY_INSTALL,
                DestructiveAction.VENTOY_UPDATE,
                DestructiveAction.VENTOY_LINK_XML,
                DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
                DestructiveAction.VENTOY_UNATTEND_DISK_LAYOUT,
                -> ShellTab.Ventoy

                DestructiveAction.GPT_RESTORE,
                DestructiveAction.RESCUE_RESTORE,
                -> ShellTab.Diagnostics

                DestructiveAction.WIPE,
                DestructiveAction.RAW_WRITE,
                DestructiveAction.FORMAT_PARTITION,
                DestructiveAction.DELETE_PARTITION,
                DestructiveAction.CREATE_PARTITION,
                DestructiveAction.GPT_REPAIR,
                DestructiveAction.SET_PARTITION_ROLE,
                DestructiveAction.SET_PARTITION_BOOTABLE,
                DestructiveAction.RENAME_PARTITION,
                DestructiveAction.RENAME_VOLUME_LABEL,
                DestructiveAction.INITIALIZE_PARTITION_TABLE,
                -> ShellTab.Partitions
            }
    }

data class OperationProgress(
    val state: OperationState,
    val title: OperationTitle,
    val token: OperationToken,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val bytesPerSecond: Long = 0L,
    /** Current batch member, e.g. the SAF name being uploaded. Null for non-file ops. */
    val currentFile: String? = null,
    val failure: OperationFailure? = null,
    /** Concrete outcome (paths, counts) shown instead of the bare "Success" label. */
    val resultMessage: String? = null,
    /**
     * Engine outcome of the last raw write, including the mandatory readback verdict.
     * Null for every other operation; a Partial state here must be surfaced as Partial.
     */
    val rawWriteResult: usb.image.RawWriteResult? = null,
    /**
     * Structured read-scan numbers (sectors, failed ranges, average successful-read rate).
     * Null for every other operation. Compose formats via string resources so a language
     * switch does not leave a frozen EN/RU sentence.
     */
    val readScanResult: ReadScanOutcome? = null,
    /**
     * Structured SMART / IDENTIFY facts from the last manual probe.
     * Null for every other operation. Compose formats via string resources.
     */
    val smartResult: SmartOutcome? = null,

    val cancelable: Boolean = true,
    /**
     * Raw-write streaming phase mirrored from [usb.operation.DiskOperation.phase].
     * Null for every other operation and after readback completes.
     */
    val phase: DiskOperationPhase? = null,
) {
    /**
     * Stable English technical label for journals and DiskOperation.name.
     * UI must not parse this; use [title] through [OperationPresentation.label].
     */
    val label: String get() = title.logLabel

    val fraction: Float
        get() =
            if (bytesTotal <= 0L) {
                0f
            } else {
                (bytesDone.toDouble() / bytesTotal.toDouble()).toFloat().coerceIn(0f, 1f)
            }
}

/**
 * Numbers from [usb.diagnostics.ScanReport] for UI. Rate null → "unavailable" string resource.
 */
data class ReadScanOutcome(
    val sectorsRead: ULong,
    val totalSectors: ULong,
    val unreadableSectors: ULong,
    val failedRangeCount: Int,
    /** Null when successful bytes or monotonic elapsed make rate undefined. */
    val averageBytesPerSecond: Long?,
) {
    /** English technical line for file/log only; UI uses string resources. */
    fun toLogLine(): String {
        val speed = averageBytesPerSecond?.let { usb.formatBinaryBytesPerSecond(it) }
            ?: "unavailable"
        return "Read scan: $sectorsRead of $totalSectors sectors read, " +
            "$unreadableSectors unreadable in $failedRangeCount range(s), avg read $speed"
    }

    companion object {
        fun from(report: usb.diagnostics.ScanReport, totalSectors: ULong): ReadScanOutcome =
            ReadScanOutcome(
                sectorsRead = report.sectorsRead,
                totalSectors = totalSectors,
                unreadableSectors = report.failed.sumOf { it.last - it.first + 1UL },
                failedRangeCount = report.failed.size,
                averageBytesPerSecond = report.averageBytesPerSecond,
            )
    }
}

/**
 * UI-facing SMART probe numbers/strings. Never invents temperature/wear/health.
 * [reallocatedRaw] null means attribute 0x05 absent, not zero.
 */
data class SmartOutcome(
    val availability: usb.device.SmartAvailability,
    val model: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val attributeCount: Int = 0,
    val reallocatedRaw: Long? = null,
    val checksumOk: Boolean? = null,
    val detail: String? = null,
) {
    fun toLogLine(): String {
        val modelPart = model?.let { " model=$it" } ?: ""
        val realloc = reallocatedRaw?.let { " realloc=$it" } ?: " realloc=absent"
        val sum = when (checksumOk) {
            true -> " checksum=ok"
            false -> " checksum=mismatch"
            null -> ""
        }
        return "SMART ${availability.name.lowercase()}$modelPart attrs=$attributeCount$realloc$sum" +
            (detail?.let { " ($it)" } ?: "")
    }

    companion object {
        fun from(telemetry: usb.device.SmartTelemetry): SmartOutcome {
            val realloc = telemetry.attributes.firstOrNull { it.id == 0x05 }?.raw
            return SmartOutcome(
                availability = telemetry.availability,
                model = telemetry.identify?.model,
                serial = telemetry.identify?.serial,
                firmware = telemetry.identify?.firmware,
                attributeCount = telemetry.attributes.size,
                reallocatedRaw = realloc,
                checksumOk = telemetry.checksumOk,
                detail = telemetry.detail,
            )
        }
    }
}


