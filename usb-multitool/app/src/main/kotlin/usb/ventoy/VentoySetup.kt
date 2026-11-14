package usb.ventoy

import usb.requireLinkedEngine
import usb.device.BlockDevice
import usb.fs.BlockDeviceFatCallback
import usb.fs.FatFs
import usb.fs.UsbFsVolume
import usb.partition.VirtualPartition
import java.security.MessageDigest
import java.io.IOException

/**
 * Windows setup bindings on the real Ventoy data partition (partition 1).
 *
 * Ventoy's `auto_install` `image` field is a full path to an image file; it is not
 * restricted to `/ISO` or to the `.iso` extension, so any regular file on that partition
 * can be linked. The app-generated XML is the `template`. Linking and unlinking never
 * delete the source image or any XML file.
 */
object VentoySetup {
    const val ISO_DIR = "ISO"
    const val VENTOY_DIR = "ventoy"
    const val CONFIG_PATH = DEFAULT_VENTOY_CONFIG_PATH
    const val CONFIG_BACKUP_PATH = "/ventoy/ventoy_backup.json"

    /** Refuse to load an unreasonably large template as text. */
    const val MAX_TEMPLATE_BYTES: Long = 2L * 1024 * 1024

    /** ventoy.json is a small control file; this bound keeps a malformed volume from hanging. */
    const val MAX_SETTINGS_BYTES: Long = 2L * 1024 * 1024

    /** Volume-relative key for the read/write helpers; a leading slash hides it. */
    internal const val CONFIG_KEY = VENTOY_DIR + "/ventoy.json"

    data class LinkResult(val json: String, val templatePath: String)

    /**
     * Bind one or more existing image files on the data partition to a template that
     * already exists there. The template is read only: it is never rewritten, generated,
     * or deleted. A template that wipes/partitions a disk requires [allowDestructiveTemplate].
     * All-or-nothing conflict check happens before the first write.
     */
    fun linkImages(
        disk: BlockDevice,
        imagePaths: List<String>,
        templatePath: String,
        overwrite: Boolean = false,
        allowDestructiveTemplate: Boolean = false,
        expectedTemplateSha256: String? = null,
        onMutation: () -> Unit = {},
    ): LinkResult =
        withVolume(disk) { volume ->
            val normalized = imagePaths.map(WindowsSetup::normalizeImagePath).distinct()
            require(normalized.isNotEmpty()) { "no image selected" }
            for (path in normalized) {
                val entry = volume.statOrNull(path)
                    ?: throw NotOnVentoyPartitionException(
                        "\"$path\" is not on the Ventoy data partition; copy it there first",
                    )
                if (entry.isDirectory) {
                    throw NotOnVentoyPartitionException("\"$path\" is a directory, not an image file")
                }
            }
            val template = xmlTarget(templatePath)
            val templateEntry = volume.statOrNull(template)
                ?: throw NotOnVentoyPartitionException(
                    "\"$template\" XML template is not on the Ventoy data partition; import it first",
                )
            if (templateEntry.isDirectory) {
                throw NotOnVentoyPartitionException("\"$template\" is a directory, not an XML file")
            }
            if (templateEntry.size <= 0L) throw InvalidTemplateException("XML template is empty")
            if (templateEntry.size > MAX_TEMPLATE_BYTES) {
                throw InvalidTemplateException(
                    "XML template is ${templateEntry.size} bytes; limit is $MAX_TEMPLATE_BYTES",
                )
            }
            val templateBytes = volume.read(template, 0L, templateEntry.size.toInt())
            val inspection = WindowsTemplate.inspect(templateBytes, requireUnattendRoot = true)
            requireAcknowledgedTemplate(
                inspection = inspection,
                bytes = templateBytes,
                allowDestructive = allowDestructiveTemplate,
                expectedSha256 = expectedTemplateSha256,
            )

            val existingJson = readText(volume, CONFIG_PATH)
            val json = WindowsSetup.mergeAutoInstall(
                existingJson = existingJson,
                imagePaths = normalized,
                templatePath = template,
                options = SetupOptions(),
                overwrite = overwrite,
            )
            onMutation()
            ensureDirectory(volume, VENTOY_DIR)
            replaceFile(volume, CONFIG_PATH, json.toByteArray(Charsets.UTF_8))
            LinkResult(json = json, templatePath = template)
        }

    /** Existing raw auto_install entries on the data partition. */
    fun readLinks(disk: BlockDevice): List<AutoInstallLink> =
        withVolume(disk) { volume -> WindowsSetup.parseLinks(readText(volume, CONFIG_PATH)) }

    /** Raw global control entries, preserving unknown keys, sections, and exact raw values. */
    fun readGlobalSettings(disk: BlockDevice): VentoyGlobalSettings =
        readSettingsFile(disk, CONFIG_PATH, defaultPath = true)

    /**
     * Read one explicit settings file on the data partition. Unlike the default path, a
     * missing file is [VentoyGlobalSettings.Missing] and must never be created by a read;
     * a directory, an oversized file, or a missing Ventoy layout is a [VentoyGlobalSettings.ReadError].
     */
    fun readGlobalSettingsAt(disk: BlockDevice, path: String): VentoyGlobalSettings =
        readSettingsFile(disk, normalizeVentoyFilePath(path), defaultPath = false)

    private fun readSettingsFile(
        disk: BlockDevice,
        requestedPath: String,
        defaultPath: Boolean,
    ): VentoyGlobalSettings {
        return try {
            withVolume(disk) { volume ->
                readSettingsFile(volume, requestedPath, defaultPath)
            }
        } catch (failure: IncompatibleVentoyException) {
            if (!defaultPath) {
                return VentoyGlobalSettings.ReadError(
                    "the Ventoy data partition is not available; cannot read $requestedPath",
                )
            }
            val damaged =
                try {
                    VentoyLayout.looksLikeVentoyLayout(disk)
                } catch (inspectionFailure: Throwable) {
                    if (inspectionFailure.isFatalReadFailure()) throw inspectionFailure
                    return VentoyGlobalSettings.ReadError(
                        "Ventoy layout could not be inspected: " +
                            (inspectionFailure.message
                                ?: inspectionFailure.javaClass.simpleName),
                    )
                }
            if (damaged) {
                VentoyGlobalSettings.ReadError(
                    "Ventoy layout is present but invalid; ventoy.json cannot be read",
                    damagedLayout = true,
                )
            } else {
                VentoyGlobalSettings.Missing
            }
        }
    }

    private fun readSettingsFile(
        volume: UsbFsVolume,
        requestedPath: String,
        defaultPath: Boolean,
    ): VentoyGlobalSettings {
        val entry = volume.statOrNull(requestedPath)
        if (entry == null) {
            if (!defaultPath) return VentoyGlobalSettings.Missing
            // Absence of the default path is decided by stat; any list/stat failure
            // propagates and the host reports ReadError instead of "no settings".
            val ventoyDir = volume.statOrNull(VENTOY_DIR)
                ?: return VentoyGlobalSettings.Missing
            if (!ventoyDir.isDirectory) {
                return VentoyGlobalSettings.ReadError("$VENTOY_DIR exists but is not a directory")
            }
            return VentoyGlobalSettings.Missing
        }
        if (entry.isDirectory) {
            return VentoyGlobalSettings.ReadError("$requestedPath is a directory, not a file")
        }
        if (entry.size > MAX_SETTINGS_BYTES) {
            return VentoyGlobalSettings.ReadError(
                "$requestedPath is ${entry.size} bytes; limit is $MAX_SETTINGS_BYTES",
            )
        }
        val json =
            if (entry.size <= 0L) {
                ""
            } else {
                String(volume.read(requestedPath, 0L, entry.size.toInt()), Charsets.UTF_8)
            }
        return parseGlobalSettings(json)
    }

    /** Cancellation and JVM/linkage faults must never be turned into a Missing/ReadError result. */
    private fun Throwable.isFatalReadFailure(): Boolean =
        this is Error || this is java.util.concurrent.CancellationException

    /**
     * Parse an already-read file. Missing is decided by the caller (no file); this keeps
     * empty, unparseable, and populated results distinct.
     */
    internal fun parseGlobalSettings(json: String): VentoyGlobalSettings {
        if (json.isBlank()) return VentoyGlobalSettings.Empty
        val root =
            try {
                MiniJson.parse(json) as? Map<*, *>
            } catch (failure: Throwable) {
                return VentoyGlobalSettings.ReadError(
                    "ventoy.json is not valid JSON: " +
                        (failure.message ?: failure.javaClass.simpleName),
                )
            } ?: return VentoyGlobalSettings.ReadError("ventoy.json root is not an object")
        val rawControl = root["control"] ?: return VentoyGlobalSettings.Empty
        val control =
            rawControl as? List<*>
                ?: return VentoyGlobalSettings.ReadError("\"control\" is not a JSON array")
        val entries = ArrayList<VentoySetting>()
        for (item in control) {
            val map = item as? Map<*, *> ?: continue
            for ((rawKey, rawValue) in map) {
                val key = rawKey?.toString() ?: continue
                val scalar =
                    rawValue is String || rawValue is Number || rawValue is Boolean
                val rawText =
                    if (scalar) {
                        rawValue.toString()
                    } else {
                        MiniJson.stringify(rawValue)
                    }
                val known = VentoyControlCatalog.known(key)
                val status =
                    when {
                        known != null && scalar ->
                            SettingStatus.Known(
                                valid = rawText in known.allowed,
                                expected = known.expected,
                            )
                        known != null -> SettingStatus.Known(valid = false, expected = known.expected)
                        scalar -> SettingStatus.UnknownKey
                        else -> SettingStatus.Unreadable
                    }
                entries.add(
                    VentoySetting(
                        key = key,
                        rawValue = rawText,
                        status = status,
                    ),
                )
            }
        }
        return if (entries.isEmpty()) {
            if (control.isEmpty()) {
                VentoyGlobalSettings.Empty
            } else {
                VentoyGlobalSettings.ReadError(
                    "\"control\" has no key/value entries this app can read",
                )
            }
        } else {
            VentoyGlobalSettings.Present(entries)
        }
    }
    data class SettingsSnapshot(val settings: VentoyGlobalSettings, val sha256: String)

    /** Read one bounded USB file snapshot; parsed settings and hash share identical bytes. */
    fun readGlobalSettingsSnapshotAt(disk: BlockDevice, path: String): SettingsSnapshot {
        val normalized = normalizeVentoyFilePath(path)
        return withVolume(disk) { volume ->
            val entry = volume.statOrNull(normalized)
                ?: throw IllegalStateException("$normalized no longer exists")
            require(!entry.isDirectory) { "$normalized is a directory, not a file" }
            require(entry.size <= MAX_SETTINGS_BYTES) { "$normalized exceeds $MAX_SETTINGS_BYTES bytes" }
            val bytes = if (entry.size == 0L) ByteArray(0) else volume.read(normalized, 0L, entry.size.toInt())
            SettingsSnapshot(parseGlobalSettings(strictUtf8(bytes)), sha256(bytes))
        }
    }

    /**
     * Write explicitly chosen control values in one read-merge-write. Unknown JSON sections
     * and all other keys are preserved; a null value removes just that key.
     * Returns false when the merge changes nothing, in which case no file is written.
     *
     * A missing default file may be created, matching install/update behaviour. A file chosen
     * by the user must already exist: [applyGlobalControlsAt] refuses to create it.
     */
    fun applyGlobalControls(
        disk: BlockDevice,
        settings: List<VentoyControlSetting>,
        onMutation: () -> Unit = {},
    ): Boolean = applySettingsFile(disk, CONFIG_PATH, settings, allowCreate = true, onMutation)

    /**
     * Read-merge-write exactly [path] on the data partition. The file is never copied to the
     * default path or to another file. Absence, a directory, or an oversized file fails closed
     * before any write; unknown sections and unrelated keys are preserved.
     */
    fun applyGlobalControlsAt(
        disk: BlockDevice,
        path: String,
        settings: List<VentoyControlSetting>,
        onMutation: () -> Unit = {},
    ): Boolean =
        applySettingsFile(
            disk,
            normalizeVentoyFilePath(path),
            settings,
            allowCreate = false,
            onMutation,
        )

    private fun applySettingsFile(
        disk: BlockDevice,
        path: String,
        settings: List<VentoyControlSetting>,
        allowCreate: Boolean,
        onMutation: () -> Unit,
    ): Boolean {
        val changed =
            withVolume(disk) { volume ->
                require(settings.isNotEmpty()) { "no Ventoy control setting to apply" }
                VentoyControlCatalog.problem(settings)?.let {
                    throw IllegalArgumentException(it)
                }
                val entry = volume.statOrNull(path)
                if (entry?.isDirectory == true) {
                    throw IllegalStateException(
                        "$path is a directory; refusing to overwrite it",
                    )
                }
                if (entry == null && !allowCreate) {
                    throw IllegalStateException(
                        "$path no longer exists; refusing to create a chosen settings file",
                    )
                }
                if (entry != null && entry.size > MAX_SETTINGS_BYTES) {
                    throw IllegalStateException(
                        "$path is ${entry.size} bytes; limit is $MAX_SETTINGS_BYTES",
                    )
                }
                val existingJson =
                    when {
                        entry == null -> null
                        entry.size <= 0L -> ""
                        else -> String(
                            volume.read(path, 0L, entry.size.toInt()),
                            Charsets.UTF_8,
                        )
                    }
                val merged = WindowsSetup.applyControls(existingJson, settings)
                    ?: return@withVolume false
                onMutation()
                if (allowCreate) ensureDirectory(volume, VENTOY_DIR)
                replaceFile(volume, path, merged.toByteArray(Charsets.UTF_8))
                true
            }
        // The FAT volume is closed; flush the block device so the reported success is durable.
        if (changed) disk.sync()
        return changed
    }
    /**
     * Publish validated phone-imported JSON to standard config. Existing config is copied to
     * a unique backup first; an existing backup is never overwritten. Phone URI is never opened.
     */
    fun applyImportedDocument(
        disk: BlockDevice,
        importedJson: String,
        settings: List<VentoyControlSetting> = emptyList(),
        onMutation: () -> Unit = {},
    ): Boolean {
        require(importedJson.isNotBlank()) {
            "imported settings document is empty or whitespace-only"
        }
        require(importedJson.toByteArray(Charsets.UTF_8).size <= MAX_SETTINGS_BYTES) {
            "imported settings exceed $MAX_SETTINGS_BYTES bytes"
        }
        val parsed = parseGlobalSettings(importedJson)
        require(parsed !is VentoyGlobalSettings.ReadError) {
            (parsed as VentoyGlobalSettings.ReadError).message
        }
        val finalJson =
            if (settings.isEmpty()) {
                importedJson
            } else {
                WindowsSetup.applyControls(importedJson, settings) ?: importedJson
            }
        val changed = withVolume(disk) { volume ->
            val target = volume.statOrNull(CONFIG_PATH)
            if (target?.isDirectory == true) {
                throw IllegalStateException("$CONFIG_PATH is a directory; refusing to overwrite it")
            }
            val previous = target?.let { entry ->
                require(entry.size <= MAX_SETTINGS_BYTES) {
                    "$CONFIG_PATH is ${entry.size} bytes; limit is $MAX_SETTINGS_BYTES"
                }
                if (entry.size == 0L) ByteArray(0) else volume.read(CONFIG_PATH, 0L, entry.size.toInt())
            }
            if (previous != null && previous.contentEquals(finalJson.toByteArray(Charsets.UTF_8))) {
                false
            } else {
                onMutation()
                ensureDirectory(volume, VENTOY_DIR)
                previous?.let { backupBeforeReplace(volume, CONFIG_PATH, it) }
                replaceFile(volume, CONFIG_PATH, finalJson.toByteArray(Charsets.UTF_8))
                true
            }
        }
        if (changed) disk.sync()
        return changed
    }
    fun applyImportedFile(
        disk: BlockDevice,
        sourcePath: String,
        expectedSha256: String? = null,
        settings: List<VentoyControlSetting> = emptyList(),
        onMutation: () -> Unit = {},
    ): Boolean {
        val changed = withVolume(disk) { volume ->
            val source = normalizeVentoyFilePath(sourcePath)
            val sourceEntry = volume.statOrNull(source)
                ?: throw IllegalStateException("$source no longer exists; choose it again")
            require(!sourceEntry.isDirectory) { "$source is a directory, not a file" }
            require(sourceEntry.size <= MAX_SETTINGS_BYTES) { "$source exceeds $MAX_SETTINGS_BYTES bytes" }
            val bytes = if (sourceEntry.size == 0L) ByteArray(0) else volume.read(source, 0L, sourceEntry.size.toInt())
            val imported = strictUtf8(bytes)
            require(imported.isNotBlank()) { "$source is empty or whitespace-only" }
            require(expectedSha256 == null || expectedSha256 == sha256(bytes)) {
                "$source changed after validation; choose it again"
            }
            val parsed = parseGlobalSettings(imported)
            require(parsed !is VentoyGlobalSettings.ReadError) {
                (parsed as VentoyGlobalSettings.ReadError).message
            }
            val finalJson = if (settings.isEmpty()) imported else {
                WindowsSetup.applyControls(imported, settings) ?: imported
            }
            val target = volume.statOrNull(CONFIG_PATH)
            require(target?.isDirectory != true) { "$CONFIG_PATH is a directory" }
            val previous = target?.let {
                require(it.size <= MAX_SETTINGS_BYTES) { "$CONFIG_PATH exceeds $MAX_SETTINGS_BYTES bytes" }
                if (it.size == 0L) ByteArray(0) else volume.read(CONFIG_PATH, 0L, it.size.toInt())
            }
            val outputBytes = finalJson.toByteArray(Charsets.UTF_8)
            if (previous != null && previous.contentEquals(outputBytes)) return@withVolume false
            onMutation()
            ensureDirectory(volume, VENTOY_DIR)
            previous?.let { backupBeforeReplace(volume, CONFIG_PATH, it) }
            replaceFile(volume, CONFIG_PATH, outputBytes)
            true
        }
        if (changed) disk.sync()
        return changed
    }

    data class UnattendRead(
        val state: AutounattendState,
        val bindings: List<AutounattendBinding>,
        val searchScope: List<String> = listOf("/"),
        val scopeLimitedBy: String? = null,
        val templateInSearchScope: Boolean? = null,
    )

    /** Effective roots and the real setting that narrows discovery (never a guessed default). */
    fun readSearchScope(volume: UsbFsVolume): Pair<List<String>, String?> {
        val json = readText(volume, CONFIG_PATH) ?: return listOf("/") to null
        val root =
            try {
                MiniJson.parse(json) as? Map<*, *>
            } catch (failure: Throwable) {
                throw IllegalStateException("ventoy.json is not valid JSON: ${failure.message}", failure)
            } ?: throw IllegalStateException("ventoy.json root is not an object")
        val control = root["control"] as? List<*> ?: emptyList<Any?>()
        val entries =
            control.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                map.entries.firstOrNull()?.let { it.key?.toString() to it.value?.toString() }
            }
        val rootValue = entries.firstOrNull { it.first == "VTOY_DEFAULT_SEARCH_ROOT" }?.second
        val roots =
            rootValue
                ?.split(';', ',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { if (it.startsWith("/")) it else "/$it" }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("/")
        val limits = ArrayList<String>()
        if (rootValue != null) limits.add("VTOY_DEFAULT_SEARCH_ROOT=$rootValue")
        val maxLevelEntry = entries.firstOrNull { it.first == "VTOY_MAX_SEARCH_LEVEL" }
        val maxLevel = maxLevelEntry?.second?.toIntOrNull()
        if (maxLevelEntry != null) {
            if (maxLevel == null || maxLevel < 0) {
                limits.add("VTOY_MAX_SEARCH_LEVEL=unknown")
            } else {
                limits.add("VTOY_MAX_SEARCH_LEVEL=$maxLevel")
            }
        }
        return roots to limits.joinToString("; ").takeIf { it.isNotEmpty() }
    }

    /** null when the scope cannot be trusted (unknown max level), never a false "inside". */
    fun pathInSearchScope(
        path: String,
        roots: List<String>,
        scopeLimitedBy: String?,
    ): Boolean? {
        val normalized = WindowsSetup.normalizeImagePath(path)
        val matchingRoot =
            roots.firstOrNull { root ->
                val normalizedRoot = if (root.endsWith("/")) root else "$root/"
                normalized.equals(root, ignoreCase = true) ||
                    normalized.startsWith(normalizedRoot, ignoreCase = true)
            } ?: return false
        if (scopeLimitedBy == null) return true
        val rawLevel =
            scopeLimitedBy
                .split(';')
                .map { it.trim() }
                .firstOrNull { it.startsWith("VTOY_MAX_SEARCH_LEVEL=") }
                ?.substringAfter('=')
                ?: return true
        val level = rawLevel.toIntOrNull() ?: return null
        val relative =
            if (matchingRoot == "/") normalized else normalized.removePrefix(matchingRoot).ifEmpty { "/" }
        val relativeDepth = relative.trim('/').count { it == '/' }
        return relativeDepth <= level
    }

    /**
     * Direct child directories of one existing folder on the data partition.
     *
     * One level per call: navigation supplies the folder, so no depth/entry cap can hide a
     * destination. A missing path throws [FolderUnavailableException] instead of returning
     * an empty list, because "gone" and "has no subfolders" need different UI answers.
     */
    fun listSubdirectories(disk: BlockDevice, path: String): List<String> =
        withVolume(disk) { volume -> listSubdirectories(volume, path) }

    /** Volume-level variant so host tests can drive the listing without a block device. */
    internal fun listSubdirectories(volume: UsbFsVolume, path: String): List<String> {
        val directory = normalizeFolderPath(path)
        val entry =
            volume.statOrNull(directory)
                ?: throw FolderUnavailableException(
                    FolderReadProblem.Missing,
                    "$directory is no longer on the Ventoy data partition",
                )
        if (!entry.isDirectory) {
            throw FolderUnavailableException(
                FolderReadProblem.NotDirectory,
                "$directory is not a directory",
            )
        }
        return volume
            .list(directory)
            .asSequence()
            .filter { it.isDirectory }
            .map { child ->
                if (directory == "/") "/${child.name}" else "$directory/${child.name}"
            }
            .distinct()
            .sorted()
            .toList()
    }

    class FolderUnavailableException(
        val problem: FolderReadProblem,
        message: String,
    ) : Exception(message)

    fun readAutounattend(disk: BlockDevice, xmlName: String): UnattendRead =
        withVolume(disk) { volume ->
            val path = requireXmlName(xmlName)
            val bytes = readBytes(volume, path)
            val baseState = Autounattend.read(path, bytes)
            val links = WindowsSetup.parseLinks(readText(volume, CONFIG_PATH))
            val bindings =
                links
                    .filter { link -> link.templates.any { it.equals(path, ignoreCase = true) } }
                    .map { link ->
                        AutounattendBinding(
                            imagePath = link.image,
                            bound = volume.statOrNull(link.image) != null,
                            problem =
                                if (volume.statOrNull(link.image) == null) {
                                    BindProblem.NotOnPartition
                                } else {
                                    null
                                },
                        )
                    }
            val (roots, limitedBy) = readSearchScope(volume)
            val inScope = pathInSearchScope(path, roots, limitedBy)
            val state =
                if (baseState is AutounattendState.Parsed) {
                    AutounattendState.Parsed(
                        baseState.document.copy(templateInSearchScope = inScope),
                    )
                } else {
                    baseState
                }
            UnattendRead(state, bindings, roots, limitedBy, inScope)
        }

    data class GenerateResult(
        val xmlName: String,
        val validation: AutounattendValidation,
        val bindings: List<AutounattendBinding>,
    )

    /**
     * Generate XML from an existing base (or a safe skeleton) and optionally bind images.
     * A previous XML is kept as `.bak` before a confirmed rewrite. Disabled blocks are
     * omitted. No XML is written when validation finds problems or a binding conflicts.
     * [requireDeclaredDiskSize] is the editor path: it refuses a layout whose declared disk
     * size is missing, so the user-visible sum/excess check cannot be bypassed. Direct
     * non-editor callers keep the default and the XML contract unchanged.
     */
    fun generateAutounattend(
        disk: BlockDevice,
        xmlName: String,
        params: AutounattendParams,
        imagePaths: List<String> = emptyList(),
        overwriteXml: Boolean = false,
        allowDestructiveTemplate: Boolean = false,
        expectedTemplateSha256: String? = null,
        expectedPlanSha256: String? = null,
        requireDeclaredDiskSize: Boolean = false,
        options: SetupOptions = SetupOptions(),
        onMutation: () -> Unit = {},
    ): GenerateResult =
        withVolume(disk) { volume ->
            generateAutounattend(
                volume = volume,
                xmlName = xmlName,
                params = params,
                imagePaths = imagePaths,
                overwriteXml = overwriteXml,
                allowDestructiveTemplate = allowDestructiveTemplate,
                expectedTemplateSha256 = expectedTemplateSha256,
                expectedPlanSha256 = expectedPlanSha256,
                requireDeclaredDiskSize = requireDeclaredDiskSize,
                options = options,
                onMutation = onMutation,
            )
        }

    /** Volume-level generate for host tests without mounting FatFs. */
    internal fun generateAutounattend(
        volume: UsbFsVolume,
        xmlName: String,
        params: AutounattendParams,
        imagePaths: List<String> = emptyList(),
        overwriteXml: Boolean = false,
        allowDestructiveTemplate: Boolean = false,
        expectedTemplateSha256: String? = null,
        expectedPlanSha256: String? = null,
        requireDeclaredDiskSize: Boolean = false,
        options: SetupOptions = SetupOptions(),
        onMutation: () -> Unit = {},
    ): GenerateResult {
        val path = requireXmlName(xmlName)
        val validation =
            Autounattend.validate(
                params,
                requireDeclaredDiskSize = requireDeclaredDiskSize &&
                    params.intents.diskLayout == XmlFieldIntent.Set,
            )
        if (validation.problems.isNotEmpty()) {
            throw UnattendValidationException(validation)
        }
        val existing = readBytes(volume, path)
        if (existing != null && !overwriteXml) {
            throw XmlOverwriteRequiredException(
                "$path already exists; the XML write needs its own confirmation",
            )
        }
        val existingInspection =
            existing?.takeIf { it.isNotEmpty() }?.let { WindowsTemplate.inspect(it) }
        if (existingInspection != null) {
            requireAcknowledgedTemplate(
                inspection = existingInspection,
                bytes = existing,
                allowDestructive = allowDestructiveTemplate,
                expectedSha256 = expectedTemplateSha256,
            )
        } else if (expectedTemplateSha256 != null) {
            throw TemplateChangedException(
                "The XML was removed after inspection; re-open it before writing",
            )
        }
        val actualPlanSha256 = autounattendPlanSha256(xmlName, params, imagePaths)
        if (expectedPlanSha256 != null && expectedPlanSha256 != actualPlanSha256) {
            throw PlanChangedException("The generation plan changed; confirm it again")
        }
        for (image in imagePaths) {
            val entry = volume.statOrNull(image)
                ?: throw NotOnVentoyPartitionException(
                    "\"$image\" is not on the Ventoy data partition",
                )
            if (entry.isDirectory) throw NotOnVentoyPartitionException("\"$image\" is a directory")
        }
        val existingJsonBytes: ByteArray?
        val existingJson: String?
        if (imagePaths.isEmpty()) {
            existingJsonBytes = null
            existingJson = readText(volume, CONFIG_PATH)
        } else {
            existingJsonBytes = readTextBytes(volume, CONFIG_PATH)
            existingJson = existingJsonBytes?.let { String(it, Charsets.UTF_8) }
        }
        val mergedJson =
            if (imagePaths.isEmpty()) {
                existingJson
            } else {
                WindowsSetup.mergeAutoInstall(
                    existingJson = existingJson,
                    imagePaths = imagePaths,
                    templatePath = path,
                    options = options,
                    overwrite = false,
                )
            }
        val generated = Autounattend.generate(existing, params)
        val markers = WindowsTemplate.inspect(generated).destructiveMarkers
        if (markers.isNotEmpty()) {
            if (expectedPlanSha256 == null) {
                throw PlanChangedException(
                    "Destructive XML needs an acknowledged plan hash before writing",
                )
            }
            val requestedLayout = params.diskLayout
            val layoutMarkers = mutableSetOf<String>()
            if (requestedLayout?.writeLayout == true) {
                layoutMarkers.add("DiskConfiguration")
                layoutMarkers.add("CreatePartitions")
                layoutMarkers.add("ModifyPartitions")
                layoutMarkers.add("ImageInstall")
                if (requestedLayout.wipe) layoutMarkers.add("WillWipeDisk")
            }
            val alreadyAcknowledged =
                existingInspection?.destructiveMarkers.orEmpty().toSet()
            val unexpected =
                markers.filterNot { it in alreadyAcknowledged || it in layoutMarkers }
            if (unexpected.isNotEmpty()) {
                throw DestructiveTemplateRequiresOptInException(
                    unexpected,
                    "Generated XML gained destructive sections not covered by the " +
                        "acknowledged template or layout: " + unexpected.joinToString(", "),
                )
            }
        }
        onMutation()
        ensureDirectory(volume, VENTOY_DIR)
        ensurePathDirectory(volume, path.substringBeforeLast('/', "/"))
        backupGenerationFiles(
            volume = volume,
            xmlPath = path,
            existingXml = existing,
            imagePaths = imagePaths,
            existingJsonBytes = existingJsonBytes,
        )
        if (existing == null) {
            createNewFile(volume, path, generated)
        } else {
            replaceFile(volume, path, generated)
        }
        if (imagePaths.isNotEmpty() && mergedJson != null) {
            replaceFile(volume, CONFIG_PATH, mergedJson.toByteArray(Charsets.UTF_8))
        }
        val links = WindowsSetup.parseLinks(mergedJson)
        val bindings =
            links
                .filter { link -> link.templates.any { it.equals(path, ignoreCase = true) } }
                .map { link ->
                    AutounattendBinding(
                        imagePath = link.image,
                        bound = volume.statOrNull(link.image) != null,
                    )
                }
        return GenerateResult(path.substringAfterLast('/'), validation, bindings)
    }



    data class RawWriteResult(
        val xmlName: String,
        val path: String,
        val sha256: String,
        val destructiveMarkers: List<String>,
    )

    /**
     * Write exact raw XML bytes for the answer file. Validates well-formed unattend XML,
     * refuses DTD/ENTITY, rechecks source digest and overwrite confirmation, backs up the
     * previous file, then replaces bytes without DOM reserialization.
     */
    fun writeAutounattendRaw(
        disk: BlockDevice,
        xmlName: String,
        rawBytes: ByteArray,
        overwriteXml: Boolean = false,
        allowDestructiveTemplate: Boolean = false,
        expectedTemplateSha256: String? = null,
        onMutation: () -> Unit = {},
    ): RawWriteResult =
        withVolume(disk) { volume ->
            writeAutounattendRaw(
                volume = volume,
                xmlName = xmlName,
                rawBytes = rawBytes,
                overwriteXml = overwriteXml,
                allowDestructiveTemplate = allowDestructiveTemplate,
                expectedTemplateSha256 = expectedTemplateSha256,
                onMutation = onMutation,
            )
        }

    /** Volume-level raw write for host tests without mounting FatFs. */
    internal fun writeAutounattendRaw(
        volume: UsbFsVolume,
        xmlName: String,
        rawBytes: ByteArray,
        overwriteXml: Boolean = false,
        allowDestructiveTemplate: Boolean = false,
        expectedTemplateSha256: String? = null,
        onMutation: () -> Unit = {},
    ): RawWriteResult {
        require(rawBytes.isNotEmpty()) { "XML body is empty" }
        require(rawBytes.size.toLong() <= MAX_TEMPLATE_BYTES) {
            "XML is ${rawBytes.size} bytes; limit is $MAX_TEMPLATE_BYTES"
        }
        val path = requireXmlName(xmlName)
        val inspection = WindowsTemplate.inspect(rawBytes, requireUnattendRoot = true)
        WindowsTemplate.requireAllowed(inspection, allowDestructiveTemplate)
        val existing = readBytes(volume, path)
        if (existing != null && !overwriteXml) {
            throw XmlOverwriteRequiredException(
                "$path already exists; the XML write needs its own confirmation",
            )
        }
        if (existing != null) {
            val existingInspection =
                if (existing.isEmpty()) {
                    TemplateInspection(emptyList())
                } else {
                    WindowsTemplate.inspect(existing)
                }
            requireAcknowledgedTemplate(
                inspection = existingInspection,
                bytes = existing,
                allowDestructive = allowDestructiveTemplate,
                expectedSha256 = expectedTemplateSha256,
            )
        } else if (expectedTemplateSha256 != null) {
            throw TemplateChangedException(
                "The XML was removed after inspection; re-open it before writing",
            )
        }
        onMutation()
        ensureDirectory(volume, VENTOY_DIR)
        ensurePathDirectory(volume, path.substringBeforeLast('/', "/"))
        if (existing != null) {
            backupBeforeReplace(volume, path, existing)
            replaceFile(volume, path, rawBytes)
        } else {
            createNewFile(volume, path, rawBytes)
        }
        return RawWriteResult(
            xmlName = path.substringAfterLast('/'),
            path = path,
            sha256 = WindowsTemplate.sha256(rawBytes),
            destructiveMarkers = inspection.destructiveMarkers,
        )
    }


    class XmlOverwriteRequiredException(message: String) : Exception(message)
    class XmlNameRaceException(message: String, cause: Throwable? = null) : Exception(message, cause)
    class XmlNameExhaustedException(message: String) : Exception(message)
    class TemplateChangedException(message: String) : Exception(message)
    class PlanChangedException(message: String) : Exception(message)
    class TemplateTooLargeException(message: String) : Exception(message)

    internal fun requireAcknowledgedTemplate(
        inspection: TemplateInspection,
        bytes: ByteArray,
        allowDestructive: Boolean,
        expectedSha256: String?,
    ) {
        WindowsTemplate.requireAllowed(inspection, allowDestructive)
        if (expectedSha256 != null) {
            val actual = WindowsTemplate.sha256(bytes)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw TemplateChangedException(
                    "The XML changed after inspection; review its destructive sections again",
                )
            }
        } else if (inspection.destructiveMarkers.isNotEmpty()) {
            throw TemplateChangedException(
                "Destructive XML needs the SHA-256 captured at inspection before writing",
            )
        }
    }

    /** Bind existing images to an XML without rewriting the XML; conflicts fail closed. */
    fun bindImages(
        disk: BlockDevice,
        xmlName: String,
        imagePaths: List<String>,
        options: SetupOptions = SetupOptions(),
        allowDestructiveTemplate: Boolean = false,
        expectedTemplateSha256: String? = null,
        onMutation: () -> Unit = {},
    ): List<AutounattendBinding> =
        withVolume(disk) { volume ->
            val templatePath = requireXmlName(xmlName)
            val templateEntry = volume.statOrNull(templatePath)
                ?: throw NotOnVentoyPartitionException("XML template $templatePath is not on the stick")
            if (templateEntry.isDirectory) {
                throw NotOnVentoyPartitionException("$templatePath is a directory")
            }
            if (templateEntry.size <= 0L) throw InvalidTemplateException("XML template is empty")
            if (templateEntry.size > MAX_TEMPLATE_BYTES) {
                throw InvalidTemplateException(
                    "XML template is ${templateEntry.size} bytes; limit is $MAX_TEMPLATE_BYTES",
                )
            }
            val templateBytes = volume.read(templatePath, 0L, templateEntry.size.toInt())
            requireAcknowledgedTemplate(
                inspection = WindowsTemplate.inspect(templateBytes, requireUnattendRoot = true),
                bytes = templateBytes,
                allowDestructive = allowDestructiveTemplate,
                expectedSha256 = expectedTemplateSha256,
            )
            if (imagePaths.isEmpty()) throw IllegalArgumentException("no image selected")
            val normalizedImages = imagePaths.map { WindowsSetup.normalizeImagePath(it) }
            for (image in normalizedImages) {
                val entry = volume.statOrNull(image)
                    ?: throw NotOnVentoyPartitionException("\"$image\" is not on the Ventoy data partition")
                if (entry.isDirectory) throw NotOnVentoyPartitionException("\"$image\" is a directory")
            }
            val existingJson = readText(volume, CONFIG_PATH)
            val updated =
                WindowsSetup.mergeAutoInstall(
                    existingJson = existingJson,
                    imagePaths = normalizedImages,
                    templatePath = templatePath,
                    options = options,
                    overwrite = false,
                )
            onMutation()
            ensureDirectory(volume, VENTOY_DIR)
            replaceFile(volume, CONFIG_PATH, updated.toByteArray(Charsets.UTF_8))
            WindowsSetup.parseLinks(updated)
                .filter { link -> link.templates.any { it.equals(templatePath, true) } }
                .map { link ->
                    AutounattendBinding(
                        imagePath = link.image,
                        bound = volume.statOrNull(link.image) != null,
                    )
                }
        }

    /** Remove only image→this-XML entries; other templates and files are kept. */
    fun unbindImage(
        disk: BlockDevice,
        xmlName: String,
        imagePath: String,
        onMutation: () -> Unit = {},
    ): Boolean =
        withVolume(disk) { volume ->
            val templatePath = requireXmlName(xmlName)
            val existingJson = readText(volume, CONFIG_PATH) ?: return@withVolume false
            val updated =
                WindowsSetup.removeLinkForTemplate(existingJson, imagePath, templatePath)
                    ?: return@withVolume false
            onMutation()
            replaceFile(volume, CONFIG_PATH, updated.toByteArray(Charsets.UTF_8))
            true
        }

    class UnattendValidationException(val validation: AutounattendValidation) :
        Exception(
            "Invalid unattend parameters: " +
                validation.problems.joinToString(", "),
        )


    /**
     * Persist only the official global control flags. Other JSON keys, auto_install
     * entries, images, and XML files are preserved. These flags are global, not per-image.
     */
    fun saveControls(
        disk: BlockDevice,
        options: SetupOptions,
        onMutation: () -> Unit = {},
    ): String =
        withVolume(disk) { volume ->
            val existingJson = readText(volume, CONFIG_PATH)
            val updated = WindowsSetup.mergeControlOptions(existingJson, options)
            onMutation()
            ensureDirectory(volume, VENTOY_DIR)
            replaceFile(volume, CONFIG_PATH, updated.toByteArray(Charsets.UTF_8))
            updated
        }

    /**
     * Remove only the auto_install entries whose image path matches. Images, XML files,
     * unrelated entries, and every other JSON key stay untouched.
     * @return true when ventoy.json changed.
     */
    fun unlinkImages(
        disk: BlockDevice,
        imagePaths: List<String>,
        onMutation: () -> Unit = {},
    ): Boolean =
        withVolume(disk) { volume ->
            require(imagePaths.isNotEmpty()) { "no image selected" }
            val existingJson = readText(volume, CONFIG_PATH) ?: return@withVolume false
            val updated = WindowsSetup.removeLinks(existingJson, imagePaths) ?: return@withVolume false
            onMutation()
            replaceFile(volume, CONFIG_PATH, updated.toByteArray(Charsets.UTF_8))
            true
        }

    class NotOnVentoyPartitionException(message: String) : Exception(message)

    /**
     * Root map retained only for legacy tests that exercise JSON merge separately from
     * volume I/O. Production link/generate paths read the real file.
     */
    internal fun configRoot(existingJson: String?): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>().also { root ->
            if (existingJson != null) root[CONFIG_KEY] = existingJson
        }

    /**
     * Mount the real Ventoy data partition (partition 1) read/write for one operation.
     * The caller keeps [disk] open; the volume closes before this returns.
     */
    internal fun <T> withDataVolume(disk: BlockDevice, block: (UsbFsVolume) -> T): T =
        withVolume(disk, block)

    private inline fun <T> withVolume(disk: BlockDevice, block: (UsbFsVolume) -> T): T {
        val probe =
            VentoyLayout.probe(disk)
                ?: throw IncompatibleVentoyException(
                    "No Ventoy layout on this disk; install Ventoy before editing setup files",
                )
        val partition = VirtualPartition(probe.part1StartLba, probe.part1SectorCount, disk)
        val volume =
            requireLinkedEngine("FatFs") {
                FatFs.mount(
                    BlockDeviceFatCallback(partition),
                    partition.sectorSize,
                    partition.sectorCount.toLong(),
                )
            }
        return volume.use(block)
    }

    /**
     * Fixed-length unique sibling under [parent]. Never embeds the target basename so
     * near-255 LFN stems still get a valid temp/backup name on FAT.
     */
    private fun uniqueShortSibling(
        volume: UsbFsVolume,
        parent: String,
        kind: String,
    ): String {
        val nonce = "${System.currentTimeMillis().toString(36)}-${System.nanoTime().toString(36)}"
        // ".x<kind>-<nonce>" stays well under 255 UTF-16 units regardless of target name.
        val base = ".x$kind-$nonce"
        fun path(suffix: String): String =
            if (parent == "/") "/$base$suffix" else "$parent/$base$suffix"
        var candidate = path("")
        var n = 0
        while (pathOccupied(volume, candidate)) {
            n++
            if (n > 16) throw IOException("cannot allocate a temporary name under $parent")
            candidate = path("-$n")
        }
        return candidate
    }

    /**
     * Write [bytes] only when [path] is absent. Temp file + rename; never moves an
     * existing target aside. rename fails closed if the destination appears (race).
     */
    private fun createNewFile(volume: UsbFsVolume, path: String, bytes: ByteArray) {
        if (pathOccupied(volume, path)) {
            // Caller already chose create-new; occupancy here is a race, not a confirm prompt.
            throw XmlNameRaceException(
                "$path appeared before create; refusing to overwrite",
            )
        }
        val parent = path.substringBeforeLast('/', "/")
        val temp = uniqueShortSibling(volume, parent, "t")
        var tempOwned = false
        var renamed = false
        try {
            // Phase 1: FA_CREATE_NEW empty — ownership only after this returns.
            // FR_EXIST / FileExistsException means a peer owns [temp]; never delete it.
            volume.write(
                path = temp,
                data = ByteArray(0),
                create = true,
                exclusiveCreate = true,
            )
            tempOwned = true
            // Phase 2: fill our owned temp (open-existing; no truncate race).
            if (bytes.isNotEmpty()) {
                volume.write(path = temp, data = bytes, offset = 0L, create = false)
            }
            volume.sync()
            // Recheck under the same volume session before the exclusive rename.
            if (pathOccupied(volume, path)) {
                throw XmlNameRaceException(
                    "$path appeared before create; refusing to overwrite",
                )
            }
            volume.rename(temp, path)
            renamed = true
            volume.sync()
        } catch (failure: Throwable) {
            if (!renamed && tempOwned) {
                // Owned temp only — never delete a peer. Surface cleanup faults.
                runCatching {
                    if (volume.statOrNull(temp) != null) volume.delete(temp)
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            if (failure is XmlNameRaceException || failure is XmlOverwriteRequiredException) {
                throw failure
            }
            if (!renamed && isExclusiveCreateConflict(failure)) {
                // Peer owns the temp (or destination); do not delete peer bytes.
                throw XmlNameRaceException(
                    "temporary name for $path already claimed; refusing to overwrite",
                    failure,
                )
            }
            if (!renamed && pathOccupied(volume, path)) {
                throw XmlNameRaceException(
                    "$path appeared before create; refusing to overwrite",
                    failure,
                )
            }
            // After rename the bytes are at [path]; a sync failure is durability, not a race.
            throw failure
        }
    }

    private fun isExclusiveCreateConflict(failure: Throwable): Boolean {
        val msg = failure.message.orEmpty()
        return msg.contains("exclusive create failed", ignoreCase = true) ||
            (
                msg.contains("already exists", ignoreCase = true) &&
                    failure::class.java.simpleName.contains("Exists", ignoreCase = true)
            )
    }

    /** Case-insensitive occupancy (FAT); true for file or directory. */
    private fun pathOccupied(volume: UsbFsVolume, path: String): Boolean {
        // Prefer f_stat first: FatFs list returns LFN `fname` only, so an 8.3
        // short-alias collision would look free on list alone.
        if (volume.statOrNull(path) != null) return true
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', "/")
        if (parent != "/") {
            val parentEntry = volume.statOrNull(parent) ?: return false
            if (!parentEntry.isDirectory) return true
        }
        return try {
            volume.list(parent).any { it.name.equals(name, ignoreCase = true) }
        } catch (_: Throwable) {
            false
        }
    }

    /** Write [bytes] through a hidden temp file; restore the previous file on failure. */
    private fun replaceFile(volume: UsbFsVolume, path: String, bytes: ByteArray) {
        val parent = path.substringBeforeLast('/', "/")
        val temp = uniqueShortSibling(volume, parent, "t")
        val backup = uniqueShortSibling(volume, parent, "b")
        var tempOwned = false
        var backedUp = false
        var moved = false
        try {
            volume.write(
                path = temp,
                data = ByteArray(0),
                create = true,
                exclusiveCreate = true,
            )
            tempOwned = true
            if (bytes.isNotEmpty()) {
                volume.write(path = temp, data = bytes, offset = 0L, create = false)
            }
            volume.sync()
            if (volume.statOrNull(path) != null) {
                volume.rename(path, backup)
                backedUp = true
            }
            volume.rename(temp, path)
            moved = true
            volume.sync()
        } catch (failure: Throwable) {
            if (moved) {
                runCatching { volume.delete(path) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            if (backedUp) {
                runCatching { volume.rename(backup, path) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            if (tempOwned) {
                runCatching { if (volume.statOrNull(temp) != null) volume.delete(temp) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        }
        if (backedUp) {
            // Primary write succeeded; leftover backup must not be silent — surface for Partial.
            try {
                volume.delete(backup)
                volume.sync()
            } catch (cleanup: Throwable) {
                throw IOException(
                    "wrote $path but failed to remove backup $backup; disk may need cleanup",
                    cleanup,
                )
            }
        }
    }

    private fun ensureDirectory(volume: UsbFsVolume, name: String) {
        val exists = volume.list("/").any { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        if (!exists) volume.mkdir("/$name")
    }

    private fun ensurePathDirectory(volume: UsbFsVolume, path: String) {
        if (path == "/" || path.isEmpty()) return
        var current = ""
        for (segment in path.trim('/').split('/')) {
            if (segment.isEmpty()) continue
            current = "$current/$segment"
            val exists =
                volume.statOrNull(current)?.isDirectory == true
            if (!exists) volume.mkdir(current)
        }
    }

    /** Public resolver: bare name keeps legacy `/ventoy/<name>`; a path stays where it is. */
    fun xmlPath(xmlName: String): String = XmlPath.resolve(xmlName)

    /** Same resolver under an explicit name for UI display before writing. */
    fun resolveXmlPath(xmlName: String): String = XmlPath.resolve(xmlName)

    /**
     * WIN-06: first free `<dir>/<image-stem>.xml` / `_N.xml` on the volume.
     * Default dir is `/ventoy`. Probe only — write must still create-new; a later
     * race throws [XmlNameRaceException] (or import already-exists) instead of overwriting.
     */
    fun allocateXmlPath(
        disk: BlockDevice,
        imagePath: String,
        directory: String = "/$VENTOY_DIR",
    ): String =
        withVolume(disk) { volume -> allocateXmlPath(volume, imagePath, directory) }

    /** Volume-level allocator for host tests without mounting FatFs. */
    internal fun allocateXmlPath(
        volume: UsbFsVolume,
        imagePath: String,
        directory: String = "/$VENTOY_DIR",
    ): String {
        val dir = VentoyIsoLibrary.normalizeDirectory(directory)
        val stem = XmlPath.imageStem(imagePath)
        for (name in XmlPath.numberedXmlNames(stem)) {
            val candidate = if (dir == "/") "/$name" else "$dir/$name"
            require(!candidate.equals(CONFIG_PATH, ignoreCase = true)) {
                "XML target must not be the Ventoy config file $CONFIG_PATH"
            }
            if (!pathOccupied(volume, candidate)) return candidate
        }
        throw XmlNameExhaustedException(
            "no free XML name under $dir for stem \"$stem\" " +
                "(filesystem name length exhausted at ${XmlPath.MAX_LFN_UNITS} UTF-16 units)",
        )
    }

    /** Pure first candidate from an image path; does not inspect the volume. */
    fun suggestedXmlPath(
        imagePath: String,
        directory: String = "/$VENTOY_DIR",
    ): String {
        val dir = VentoyIsoLibrary.normalizeDirectory(directory)
        val name = "${XmlPath.imageStem(imagePath)}.xml"
        val candidate = if (dir == "/") "/$name" else "$dir/$name"
        require(!candidate.equals(CONFIG_PATH, ignoreCase = true)) {
            "XML target must not be the Ventoy config file $CONFIG_PATH"
        }
        return candidate
    }


    private fun xmlTarget(input: String): String = XmlPath.resolve(input)

    internal fun backupGenerationFiles(
        volume: UsbFsVolume,
        xmlPath: String,
        existingXml: ByteArray?,
        imagePaths: List<String>,
        existingJsonBytes: ByteArray?,
    ) {
        if (existingXml != null) backupBeforeReplace(volume, xmlPath, existingXml)
        if (imagePaths.isNotEmpty()) {
            existingJsonBytes?.let { backupBeforeReplace(volume, CONFIG_PATH, it) }
        }
    }

    /** Preserve an existing target before replacement; never overwrite an earlier backup. */
    internal fun backupBeforeReplace(volume: UsbFsVolume, path: String, bytes: ByteArray): String {
        val backupPath =
            if (path.equals(CONFIG_PATH, ignoreCase = true)) {
                uniqueVentoyConfigBackupName(volume)
            } else {
                uniqueBackupName(volume, path)
            }
        replaceFile(volume, backupPath, bytes)
        return backupPath
    }

    private fun uniqueVentoyConfigBackupName(volume: UsbFsVolume): String =
        if (volume.statOrNull(CONFIG_BACKUP_PATH) == null) {
            CONFIG_BACKUP_PATH
        } else {
            uniqueBackupName(volume, CONFIG_BACKUP_PATH)
        }

    /**
     * Prefer `<basename>.bak-<nonce>` so recovery stays associated with the source.
     * Fall back to a fixed-length `.xk-*` sibling only when that preferred name would
     * exceed [XmlPath.MAX_LFN_UNITS] (near-limit generated XML stems).
     */
    private fun uniqueBackupName(volume: UsbFsVolume, path: String): String {
        val parent = path.substringBeforeLast('/', "/")
        val base = path.substringAfterLast('/')
        val nonce = "${System.currentTimeMillis().toString(36)}-${System.nanoTime().toString(36)}"
        val preferredName = "$base.bak-$nonce"
        if (preferredName.length <= XmlPath.MAX_LFN_UNITS) {
            fun child(suffix: String): String =
                if (parent == "/") "/$preferredName$suffix" else "$parent/$preferredName$suffix"
            var candidate = child("")
            var n = 0
            while (pathOccupied(volume, candidate)) {
                n++
                if (n > 16) break
                candidate = child("-$n")
            }
            if (n <= 16 &&
                candidate.substringAfterLast('/').length <= XmlPath.MAX_LFN_UNITS &&
                !pathOccupied(volume, candidate)
            ) {
                return candidate
            }
        }
        return uniqueShortSibling(volume, parent, "k")
    }

    private fun requireXmlName(name: String): String = XmlPath.resolve(name)

    private fun findEntry(volume: UsbFsVolume, path: String): usb.fs.UsbFsDirEntry? {
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', "/")
        if (parent != "/") {
            val parentEntry = volume.statOrNull(parent) ?: return null
            if (!parentEntry.isDirectory) {
                throw IllegalStateException("$parent is not a directory")
            }
        }
        return volume.list(parent).firstOrNull {
            !it.isDirectory && it.name.equals(name, ignoreCase = true)
        }
    }

    private fun readBytes(volume: UsbFsVolume, path: String): ByteArray? {
        val entry = findEntry(volume, path) ?: return null
        if (entry.size > MAX_TEMPLATE_BYTES) {
            throw TemplateTooLargeException(
                "$path is ${entry.size} bytes; limit is $MAX_TEMPLATE_BYTES",
            )
        }
        return volume.read(path, 0L, entry.size.toInt())
    }

    internal fun readTextBytes(volume: UsbFsVolume, path: String): ByteArray? {
        val entry = findEntry(volume, path) ?: return null
        if (entry.size <= 0L) return ByteArray(0)
        if (entry.size > Int.MAX_VALUE) {
            throw IllegalStateException("$path is ${entry.size} bytes; refusing to load it as text")
        }
        return volume.read(path, 0L, entry.size.toInt())
    }

    private fun readText(volume: UsbFsVolume, path: String): String? {
        val bytes = readTextBytes(volume, path) ?: return null
        return strictUtf8(bytes)
    }
    private fun strictUtf8(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("settings document is not valid UTF-8", failure)
        }
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
