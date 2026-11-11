package usb.ventoy

import usb.requireLinkedEngine
import usb.device.BlockDevice
import usb.fs.BlockDeviceFatCallback
import usb.fs.FatFs
import usb.fs.UsbFsVolume
import usb.partition.VirtualPartition

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
    const val CONFIG_PATH = "/ventoy/ventoy.json"

    /** Refuse to load an unreasonably large template as text. */
    const val MAX_TEMPLATE_BYTES: Long = 2L * 1024 * 1024

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

    /** Raw global control entries, preserving unknown keys and exact raw values. */
    fun readGlobalSettings(disk: BlockDevice): VentoyGlobalSettings =
        withVolume(disk) { volume ->
            val json = readText(volume, CONFIG_PATH)
            if (json.isNullOrBlank()) {
                VentoyGlobalSettings(emptyList())
            } else {
                val root =
                    try {
                        MiniJson.parse(json) as? Map<*, *>
                    } catch (failure: Throwable) {
                        throw IllegalStateException(
                            "ventoy.json is not valid JSON: ${failure.message}",
                            failure,
                        )
                    } ?: throw IllegalStateException("ventoy.json root is not an object")
                val control = root["control"] as? List<*> ?: emptyList<Any?>()
                val entries =
                    control.mapNotNull { item ->
                        val map = item as? Map<*, *> ?: return@mapNotNull null
                        val entry = map.entries.firstOrNull() ?: return@mapNotNull null
                        val key = entry.key?.toString() ?: return@mapNotNull null
                        val raw = entry.value?.toString() ?: ""
                        val status =
                            if (key == "VTOY_WIN11_BYPASS_CHECK" || key == "VTOY_WIN11_BYPASS_NRO") {
                                SettingStatus.Known(
                                    valid = raw == "0" || raw == "1",
                                    expected = "0 or 1",
                                )
                            } else {
                                SettingStatus.UnknownKey
                            }
                        VentoySetting(key, raw, status)
                    }
                VentoyGlobalSettings(entries)
            }
        }

    fun setGlobalSetting(
        disk: BlockDevice,
        key: String,
        value: String?,
        onMutation: () -> Unit = {},
    ) = withVolume(disk) { volume ->
        val existingJson = readText(volume, CONFIG_PATH)
        val updated = WindowsSetup.setControl(existingJson, key, value)
        onMutation()
        ensureDirectory(volume, VENTOY_DIR)
        replaceFile(volume, CONFIG_PATH, updated.toByteArray(Charsets.UTF_8))
        updated
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

    fun listDirectories(disk: BlockDevice): List<String> =
        withVolume(disk) { volume ->
            val result = linkedSetOf("/")
            fun walk(parent: String, depth: Int) {
                if (depth > 6 || result.size >= 512) return
                for (entry in volume.list(parent)) {
                    if (!entry.isDirectory) continue
                    val path = if (parent == "/") "/${entry.name}" else "$parent/${entry.name}"
                    if (result.add(path)) walk(path, depth + 1)
                    if (result.size >= 512) return
                }
            }
            walk("/", 0)
            result.sorted()
        }

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
        options: SetupOptions = SetupOptions(),
        onMutation: () -> Unit = {},
    ): GenerateResult =
        withVolume(disk) { volume ->
            val path = requireXmlName(xmlName)
            val validation = Autounattend.validate(params)
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
            val existingJson = readText(volume, CONFIG_PATH)
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
            if (existing != null && existing.isNotEmpty()) {
                // Never overwrite a previous backup; pick an unused sibling name.
                replaceFile(volume, uniqueBackupName(volume, path), existing)
            }
            replaceFile(volume, path, generated)
            if (mergedJson != null) {
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
            GenerateResult(path.substringAfterLast('/'), validation, bindings)
        }

    class XmlOverwriteRequiredException(message: String) : Exception(message)
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

    /** Write [bytes] through a hidden temp file; restore the previous file on failure. */
    private fun replaceFile(volume: UsbFsVolume, path: String, bytes: ByteArray) {
        val parent = path.substringBeforeLast('/', "/")
        val name = path.substringAfterLast('/')
        val nonce = "${System.currentTimeMillis()}-${System.nanoTime()}"
        val temp = "$parent/.$name.tmp-$nonce"
        val backup = "$parent/.$name.bak-$nonce"
        volume.write(path = temp, data = bytes, create = true)
        volume.sync()
        var backedUp = false
        var moved = false
        try {
            if (volume.statOrNull(path) != null) {
                volume.rename(path, backup)
                backedUp = true
            }
            volume.rename(temp, path)
            moved = true
            volume.sync()
        } catch (failure: Throwable) {
            if (moved) runCatching { volume.delete(path) }
            if (backedUp) runCatching { volume.rename(backup, path) }
            runCatching { if (volume.statOrNull(temp) != null) volume.delete(temp) }
            throw failure
        }
        if (backedUp) {
            runCatching { volume.delete(backup); volume.sync() }
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

    private fun xmlTarget(input: String): String = XmlPath.resolve(input)

    private fun uniqueBackupName(volume: UsbFsVolume, path: String): String {
        val nonce = "${System.currentTimeMillis()}-${System.nanoTime()}"
        var candidate = "$path.bak-$nonce"
        var suffix = 0
        while (volume.statOrNull(candidate) != null) {
            suffix++
            candidate = "$path.bak-$nonce-$suffix"
        }
        return candidate
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

    private fun readText(volume: UsbFsVolume, path: String): String? {
        val entry = findEntry(volume, path) ?: return null
        if (entry.size <= 0L) return ""
        if (entry.size > Int.MAX_VALUE) {
            throw IllegalStateException("$path is ${entry.size} bytes; refusing to load it as text")
        }
        return String(volume.read(path, 0L, entry.size.toInt()), Charsets.UTF_8)
    }
}
