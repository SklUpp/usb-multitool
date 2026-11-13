package usb.ventoy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Read/generate Windows unattend XML.
 *
 * Ventoy only transports the file. Windows Setup defines whether the content is valid;
 * without the Microsoft unattend XSD this layer reports `schemaChecked=false` and never
 * claims validity. Unknown existing elements are kept as DOM nodes, but serialization is
 * not guaranteed byte-identical. Disabled/null blocks are not generated. No default
 * password, no wipe, and no DiskConfiguration unless the caller explicitly asks for it.
 */
object Autounattend {
    private const val NS = "urn:schemas-microsoft-com:unattend"
    private const val MAX_PARTITION_LABEL_LENGTH = 32
    private const val MAX_LAYOUT_TOTAL_MB = 1L shl 40

    private val knownElements =
        setOf(
            "unattend", "settings", "component", "UserData", "AcceptEula", "FullName",
            "ComputerName", "UserAccounts", "LocalAccounts", "LocalAccount", "Name",
            "DisplayName", "Group", "Description", "Password", "Value", "PlainText",
            "International-Core", "InputLocale", "SystemLocale", "UILanguage", "UserLocale",
            "DiskConfiguration", "Disk", "WillWipeDisk", "CreatePartitions", "CreatePartition",
            "Order", "Type", "Size", "Label", "ModifyPartitions", "ModifyPartition",
            "PartitionID", "Format", "PartitionType", "Active", "Extend", "ImageInstall",
            "TypeID", "OSImage", "InstallFrom", "MetaData", "Key", "InstallTo", "DiskID",
            "WillShowUI", "DataImage", "InstallToAvailablePartition", "Credentials", "Username",
            "Domain", "Path", "File", "DoNotResize", "Letter",
        )

    private val knownPasses = setOf("windowsPE", "specialize", "oobeSystem", "auditSystem", "generalize")
    private val knownComponents =
        setOf("Microsoft-Windows-Setup", "Microsoft-Windows-Shell-Setup", "Microsoft-Windows-International-Core")
    private val allowedAttributes =
        mapOf(
            "settings" to setOf("pass"),
            "component" to setOf("name", "processorArchitecture", "publicKeyToken", "language", "versionScope"),
        )
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"
    private const val WCM = "http://schemas.microsoft.com/WMIConfig/2002/State"

    fun read(sourcePath: String, bytes: ByteArray?): AutounattendState {
        if (bytes == null) return AutounattendState.Missing
        if (bytes.isEmpty()) {
            return AutounattendState.Unreadable(
                UnreadableProblem.Empty,
                "$sourcePath is empty",
                sourcePath,
            )
        }
        val document =
            try {
                parse(bytes)
            } catch (failure: Throwable) {
                return AutounattendState.Unreadable(
                    if (failure is NotUnattendRootException) {
                        UnreadableProblem.NotUnattendRoot
                    } else {
                        UnreadableProblem.MalformedXml
                    },
                    failure.message ?: failure.javaClass.simpleName,
                    sourcePath,
                )
            }
        val root = document.documentElement
            ?: return AutounattendState.Unreadable(
                UnreadableProblem.MalformedXml,
                "XML has no root",
                sourcePath,
            )
        val unknown = ArrayList<UnknownElement>()
        collectUnknown(root, "", null, unknown::add)
        val existing = readParams(root)
        val markers = WindowsTemplate.inspect(bytes).destructiveMarkers
        return AutounattendState.Parsed(
            AutounattendDocument(
                existing = existing,
                unknownElements = unknown,
                boundImages = emptyList(), // filled by the host from ventoy.json
                sourcePath = sourcePath,
                destructiveMarkers = markers,
                templateSha256 = WindowsTemplate.sha256(bytes),
                layoutProblems =
                    if (hasDataImageInstallTo(root)) {
                        listOf(AutounattendProblem.LayoutImageInstallNotSupported)
                    } else {
                        emptyList()
                    },
            ),
        )
    }

    fun defaults(): AutounattendDefaults =
        AutounattendDefaults(
            languageTag = null,
            localeId = null,
            computerName = null,
            localAccount = null,
            note = "Absent fields are not written; Windows Setup/OOBE prompts or derives them.",
        )

    /**
     * [requireDeclaredDiskSize] makes the editor refuse a layout whose sum cannot be checked.
     * The backend keeps the default: the declared size is not written to XML, so a caller
     * that does not need the editor check can still generate a valid Windows layout.
     */
    fun validate(
        params: AutounattendParams,
        requireDeclaredDiskSize: Boolean = false,
    ): AutounattendValidation {
        val problems = linkedSetOf<AutounattendProblem>()
        if (params.intents.language == XmlFieldIntent.Set) {
            params.languageTag?.let {
                if (!it.matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"))) {
                    problems.add(AutounattendProblem.BadLanguageTag)
                }
            }
        }
        if (params.intents.locale == XmlFieldIntent.Set) {
            params.localeId?.let {
                if (!it.matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"))) {
                    problems.add(AutounattendProblem.BadLanguageTag)
                }
            }
        }
        if (params.intents.computerName == XmlFieldIntent.Set) {
            params.computerName?.let {
                if (it.isEmpty() || it.length > 15 || !it.matches(Regex("^[A-Za-z0-9-]+$"))) {
                    problems.add(AutounattendProblem.BadComputerName)
                }
            }
        }
        if (params.intents.localAccount == XmlFieldIntent.Set) {
            params.localAccount?.let { account ->
                account.name?.let {
                    if (it.isEmpty() || it.length > 20 || it.any { c -> c in "\"\\/[]:;|=,+*?<>@" }) {
                        problems.add(AutounattendProblem.BadAccountName)
                    }
                }
                if (account.name == null && (account.password != null || account.inAdministrators != null)) {
                    problems.add(AutounattendProblem.BadAccountName)
                }
            }
        }
        if (params.intents.diskLayout == XmlFieldIntent.Set) params.diskLayout?.let { layout ->
            if (layout.writeLayout) {
                if (layout.diskId == null || layout.diskId < 0) {
                    problems.add(AutounattendProblem.LayoutIncomplete)
                }
                if (layout.partitions.isEmpty()) {
                    problems.add(AutounattendProblem.LayoutIncomplete)
                }
                if (layout.partitions.any { it.sizeMb <= 0 }) {
                    problems.add(AutounattendProblem.LayoutIncomplete)
                }
                if (layout.partitions.none { it.role == DiskPartitionRole.Primary }) {
                    problems.add(AutounattendProblem.LayoutIncomplete)
                }
                if (layout.partitions.firstOrNull()?.role != DiskPartitionRole.Efi) {
                    problems.add(AutounattendProblem.LayoutIncomplete)
                }
                layout.partitions.forEach { partition ->
                    val label = partition.label
                    if (label != null &&
                        (label.isBlank() ||
                            label.length > MAX_PARTITION_LABEL_LENGTH ||
                            label.any { it.isISOControl() })
                    ) {
                        problems.add(AutounattendProblem.BadPartitionLabel)
                    }
                }
                val total = diskLayoutTotalMb(layout.partitions)
                if (total == null) {
                    problems.add(AutounattendProblem.LayoutSizeOverflow)
                } else {
                    if (total > MAX_LAYOUT_TOTAL_MB) {
                        problems.add(AutounattendProblem.LayoutTooLarge)
                    }
                    when {
                        layout.declaredSizeMb == null ->
                            if (requireDeclaredDiskSize) {
                                problems.add(AutounattendProblem.LayoutDiskSizeMissing)
                            }
                        layout.declaredSizeMb <= 0 ->
                            problems.add(AutounattendProblem.LayoutIncomplete)
                        total > layout.declaredSizeMb ->
                            problems.add(AutounattendProblem.LayoutExceedsDiskSize)
                    }
                }
            }
        }
        return AutounattendValidation(
            schemaChecked = false,
            problems = problems.toList(),
            unavailableReason =
                "Microsoft unattend schema is not bundled; only well-formedness and app value " +
                    "constraints were checked. Windows Setup makes the final decision.",
        )
    }

    /** Generate bytes from an optional base document. Null/disabled blocks are omitted. */
    fun generate(baseXml: ByteArray?, params: AutounattendParams): ByteArray {
        val document =
            if (baseXml == null || baseXml.isEmpty()) {
                newDocument()
            } else {
                parse(baseXml)
            }
        val root = document.documentElement
        if (params.intents.language != XmlFieldIntent.Preserve || params.intents.locale != XmlFieldIntent.Preserve) {
            if (params.intents.language == XmlFieldIntent.Set && params.languageTag != null) {
                val component = ensureComponent(document, root, "oobeSystem", "Microsoft-Windows-International-Core")
                setText(document, component, "UILanguage", params.languageTag)
            } else if (params.intents.language == XmlFieldIntent.Remove) {
                findComponent(root, "oobeSystem", "Microsoft-Windows-International-Core")?.let {
                    removeChild(it, "UILanguage")
                }
            }
            if (params.intents.locale == XmlFieldIntent.Set && params.localeId != null) {
                val component = ensureComponent(document, root, "oobeSystem", "Microsoft-Windows-International-Core")
                setText(document, component, "UserLocale", params.localeId)
                setText(document, component, "SystemLocale", params.localeId)
            } else if (params.intents.locale == XmlFieldIntent.Remove) {
                findComponent(root, "oobeSystem", "Microsoft-Windows-International-Core")?.let {
                    removeChild(it, "UserLocale")
                    removeChild(it, "SystemLocale")
                }
            }
        }
        when (params.intents.computerName) {
            XmlFieldIntent.Set -> params.computerName?.let { name ->
                val component = ensureComponent(document, root, "specialize", "Microsoft-Windows-Shell-Setup")
                setText(document, component, "ComputerName", name)
            }
            XmlFieldIntent.Remove -> findComponent(root, "specialize", "Microsoft-Windows-Shell-Setup")?.let {
                removeChild(it, "ComputerName")
            }
            XmlFieldIntent.Preserve -> Unit
        }
        when (params.intents.localAccount) {
            XmlFieldIntent.Set -> params.localAccount?.takeIf { !it.name.isNullOrBlank() }?.let {
                applyLocalAccount(document, root, it)
            }
            XmlFieldIntent.Remove -> findComponent(root, "oobeSystem", "Microsoft-Windows-Shell-Setup")?.let { shell ->
                requireAtMostOne(shell, "UserAccounts", "oobeSystem/UserAccounts")
                childOrNull(shell, "UserAccounts")?.let { userAccounts ->
                    removeChild(userAccounts, "LocalAccounts")
                    if (elementChildren(userAccounts).isEmpty()) {
                        removeChild(shell, "UserAccounts")
                    }
                }
            }
            XmlFieldIntent.Preserve -> Unit
        }
        when (params.intents.diskLayout) {
            XmlFieldIntent.Set -> params.diskLayout?.takeIf { it.writeLayout }?.let {
                applyDiskLayout(document, root, it, params.originalDiskLayout)
            }
            XmlFieldIntent.Remove -> findComponent(root, "windowsPE", "Microsoft-Windows-Setup")?.let {
                removeChild(it, "DiskConfiguration")
            }
            XmlFieldIntent.Preserve -> Unit
        }
        return serialize(document)
    }

    private fun applyLocalAccount(document: Document, root: Element, account: LocalAccount) {
        // LocalAccounts is valid in auditSystem/oobeSystem only, never specialize.
        val stale = findComponent(root, "specialize", "Microsoft-Windows-Shell-Setup")
        stale?.let { shell ->
            val staleAccounts = childElements(shell, "UserAccounts")
            staleAccounts.forEach { accountNode ->
                requireSupportedSubtree(accountNode, "specialize/UserAccounts")
                if (elementChildren(accountNode).isNotEmpty()) {
                    throw IllegalStateException(
                        "specialize/UserAccounts already has content this app cannot merge safely; " +
                            "refusing to delete it",
                    )
                }
            }
            staleAccounts.forEach { shell.removeChild(it) }
        }
        val shell = ensureComponent(document, root, "oobeSystem", "Microsoft-Windows-Shell-Setup")
        requireAtMostOne(shell, "UserAccounts", "oobeSystem/UserAccounts")
        val userAccounts = child(document, shell, "UserAccounts")
        requireAtMostOne(userAccounts, "LocalAccounts", "oobeSystem/UserAccounts/LocalAccounts")
        val localAccounts = child(document, userAccounts, "LocalAccounts")
        val existing = childElements(localAccounts, "LocalAccount")
        if (existing.size > 1) {
            throw IllegalStateException(
                "XML has ${existing.size} LocalAccount entries; refusing to guess which one to edit",
            )
        }
        val localAccount = existing.firstOrNull() ?: document.createElementNS(namespace(root), "LocalAccount").also {
            localAccounts.appendChild(it)
        }
        val currentAdministrators = directText(localAccount, "Group")?.equals("Administrators", true) == true
        val inferredGroupChange =
            account.groupIntent == XmlFieldIntent.Preserve &&
                account.inAdministrators != null && account.inAdministrators != currentAdministrators
        val nameChanged = account.name != directText(localAccount, "Name")
        if (!nameChanged && !inferredGroupChange && account.groupIntent == XmlFieldIntent.Preserve && account.passwordIntent == XmlFieldIntent.Preserve) return
        setText(document, localAccount, "Name", account.name ?: return)
        if (account.passwordIntent != XmlFieldIntent.Preserve) {
            when (account.passwordIntent) {
                XmlFieldIntent.Set -> account.password?.let { password ->
                    requireAtMostOne(localAccount, "Password", "oobeSystem/UserAccounts/LocalAccount/Password")
                    val passwordElement = child(document, localAccount, "Password")
                    val existingPlainText = directText(passwordElement, "PlainText")?.toBooleanStrictOrNull()
                    if (account.passwordPlainText == false || existingPlainText == false) {
                        throw IllegalStateException("${pathOf(passwordElement)} uses encoded password format")
                    }
                    setText(document, passwordElement, "Value", password)
                    setText(document, passwordElement, "PlainText", "true")
                }
                XmlFieldIntent.Remove -> {
                    requireAtMostOne(localAccount, "Password", "oobeSystem/UserAccounts/LocalAccount/Password")
                    removeChild(localAccount, "Password")
                }
                XmlFieldIntent.Preserve -> Unit
            }
        }
        val requestedGroupIntent = if (inferredGroupChange) XmlFieldIntent.Set else account.groupIntent
        val requestedGroup = if (inferredGroupChange) null else account.preservedGroup
        if (requestedGroupIntent != XmlFieldIntent.Preserve) {
            when (requestedGroupIntent) {
                XmlFieldIntent.Set -> when {
                    requestedGroup != null -> setText(document, localAccount, "Group", requestedGroup)
                    account.inAdministrators == true -> setText(document, localAccount, "Group", "Administrators")
                    account.inAdministrators == false -> removeChild(localAccount, "Group")
                    else -> Unit
                }
                XmlFieldIntent.Remove -> {
                    requireAtMostOne(localAccount, "Group", "oobeSystem/UserAccounts/LocalAccount/Group")
                    removeChild(localAccount, "Group")
                }
                XmlFieldIntent.Preserve -> Unit
            }
        }
    }

    private fun applyDiskLayout(
        document: Document,
        root: Element,
        layout: DiskLayout,
        original: DiskLayout?,
    ) {
        val setup = ensureComponent(document, root, "windowsPE", "Microsoft-Windows-Setup")
        val oldConfiguration = childOrNull(setup, "DiskConfiguration")
        if (childElements(setup, "DiskConfiguration").size > 1) {
            throw IllegalStateException("Multiple DiskConfiguration blocks; refusing to replace them")
        }
        if (childElements(setup, "ImageInstall").size > 1) {
            throw IllegalStateException("Multiple ImageInstall blocks; refusing to edit them")
        }
        val willShowUi = oldConfiguration?.let { directText(it, "WillShowUI") }
        oldConfiguration?.let { configuration ->
            requireSupportedSubtree(configuration, "DiskConfiguration", replacing = true)
            requireLayoutReplacementPreserves(configuration, layout, original)
            val disks = childElements(configuration, "Disk")
            if (disks.size > 1) {
                throw IllegalStateException(
                    "DiskConfiguration has ${disks.size} disks; refusing to replace an ambiguous layout",
                )
            }
        }
        val existingImageInstall = childOrNull(setup, "ImageInstall")
        existingImageInstall?.let { install ->
            requireSupportedSubtree(install, "ImageInstall")
            val images = childElements(install, "OSImage")
            if (images.size > 1) {
                throw IllegalStateException(
                    "ImageInstall has ${images.size} OSImage entries; refusing to guess the target",
                )
            }
            if (childElements(install, "InstallToAvailablePartition").isNotEmpty()) {
                throw IllegalStateException(
                    "ImageInstall/InstallToAvailablePartition is in the wrong place; refusing to edit it",
                )
            }
            images.firstOrNull()?.let { image ->
                if (childElements(image, "InstallTo").size > 1) {
                    throw IllegalStateException(
                        "ImageInstall/OSImage has multiple InstallTo entries",
                    )
                }
            }
        }
        // Explicit opt-in: replace only the partition layout; InstallFrom/MetaData stay.
        removeChild(setup, "DiskConfiguration")
        val diskConfiguration = child(document, setup, "DiskConfiguration")
        willShowUi?.let { setText(document, diskConfiguration, "WillShowUI", it) }
        val disk = child(document, diskConfiguration, "Disk")
        setActionAdd(document, root, disk)
        setText(document, disk, "DiskID", (layout.diskId ?: 0).toString())
        setText(document, disk, "WillWipeDisk", layout.wipe.toString())
        val create = child(document, disk, "CreatePartitions")
        val modify = child(document, disk, "ModifyPartitions")
        var modifyOrder = 0
        layout.partitions.forEachIndexed { index, partition ->
            val createPartition = document.createElementNS(namespace(root), "CreatePartition")
            create.appendChild(createPartition)
            setActionAdd(document, root, createPartition)
            setText(document, createPartition, "Order", (index + 1).toString())
            setText(document, createPartition, "Type", partitionType(partition.role))
            setText(document, createPartition, "Size", partition.sizeMb.toString())
            partition.label?.let { setText(document, createPartition, "Label", it) }

            if (partition.role != DiskPartitionRole.Msr) {
                modifyOrder++
                val modifyPartition = document.createElementNS(namespace(root), "ModifyPartition")
                modify.appendChild(modifyPartition)
                setActionAdd(document, root, modifyPartition)
                setText(document, modifyPartition, "Order", modifyOrder.toString())
                setText(document, modifyPartition, "PartitionID", (index + 1).toString())
                setText(document, modifyPartition, "Format", format(partition.role))
                if (partition.role == DiskPartitionRole.Recovery) {
                    setText(
                        document,
                        modifyPartition,
                        "TypeID",
                        "de94bba4-06d1-4d40-a16a-bfd50179d6ac",
                    )
                }
                partition.label?.let { setText(document, modifyPartition, "Label", it) }
            }
        }
        val imageInstall = child(document, setup, "ImageInstall")
        childElements(imageInstall, "DataImage").forEach { dataImage ->
            if (childOrNull(dataImage, "InstallTo") != null) {
                throw IllegalStateException(
                    "DataImage has an InstallTo target that cannot be mapped to the new layout safely",
                )
            }
        }
        val osImage = child(document, imageInstall, "OSImage")
        removeChild(osImage, "InstallToAvailablePartition")
        val installTo = child(document, osImage, "InstallTo")
        setText(document, installTo, "DiskID", (layout.diskId ?: 0).toString())
        val primaryIndex = layout.partitions.indexOfFirst { it.role == DiskPartitionRole.Primary }
        setText(document, installTo, "PartitionID", (primaryIndex + 1).toString())
    }

    private fun partitionType(role: DiskPartitionRole): String =
        when (role) {
            DiskPartitionRole.Efi -> "EFI"
            DiskPartitionRole.Msr -> "MSR"
            DiskPartitionRole.Primary -> "Primary"
            DiskPartitionRole.Recovery -> "Primary"
        }

    private fun format(role: DiskPartitionRole): String =
        when (role) {
            DiskPartitionRole.Efi -> "FAT32"
            DiskPartitionRole.Recovery -> "NTFS"
            else -> "NTFS"
        }

    private fun readParams(root: Element): AutounattendParams? {
        val international = findComponentForRead(root, "oobeSystem", "Microsoft-Windows-International-Core")
        val language = international?.let { directText(it, "UILanguage") }
        val locale = international?.let { directText(it, "UserLocale") }

        val specializeShell = findComponentForRead(root, "specialize", "Microsoft-Windows-Shell-Setup")
        val computer = specializeShell?.let { directText(it, "ComputerName") }

        val accountShell = findComponentForRead(root, "oobeSystem", "Microsoft-Windows-Shell-Setup")
        val accountContainers = accountShell?.let { childElements(it, "UserAccounts") }.orEmpty()
        val localAccountContainers =
            if (accountContainers.size == 1) childElements(accountContainers.single(), "LocalAccounts") else emptyList()
        val accountNodes =
            if (localAccountContainers.size == 1) {
                childElements(localAccountContainers.single(), "LocalAccount")
            } else {
                emptyList()
            }
        val account =
            if (accountNodes.size == 1) {
                val node = accountNodes.single()
                directText(node, "Name")?.let { name ->
                    val passwordNode = childOrNull(node, "Password")
                    val plainText = passwordNode?.let { directText(it, "PlainText")?.toBooleanStrictOrNull() }
                    val group = directText(node, "Group")
                    LocalAccount(
                        name = name,
                        password = if (plainText == false) null else passwordNode?.let { directText(it, "Value") },
                        inAdministrators = group?.equals("Administrators", true) == true,
                        preservedGroup = group?.takeUnless { it.equals("Administrators", true) },
                        passwordPlainText = plainText,
                        passwordIntent = XmlFieldIntent.Preserve,
                        groupIntent = XmlFieldIntent.Preserve,
                    )
                }
            } else {
                null
            }

        val setup = findComponentForRead(root, "windowsPE", "Microsoft-Windows-Setup")
        val configurations = setup?.let { childElements(it, "DiskConfiguration") }.orEmpty()
        val layout =
            if (configurations.size == 1) {
                val disks = childElements(configurations.single(), "Disk")
                if (disks.size == 1) readDiskLayout(disks.single()) else null
            } else {
                null
            }
        if (language == null && locale == null && computer == null && account == null && layout == null) {
            return null
        }
        return AutounattendParams(
            languageTag = language,
            localeId = locale,
            computerName = computer,
            localAccount = account?.copy(
                passwordIntent = XmlFieldIntent.Preserve,
                groupIntent = XmlFieldIntent.Preserve,
            ),
            diskLayout = layout,
            intents = AutounattendIntents(
                language = XmlFieldIntent.Preserve,
                locale = XmlFieldIntent.Preserve,
                computerName = XmlFieldIntent.Preserve,
                localAccount = XmlFieldIntent.Preserve,
                diskLayout = XmlFieldIntent.Preserve,
            ),
            originalDiskLayout = layout,
        )
    }

    private fun findComponentForRead(root: Element, pass: String, componentName: String): Element? {
        val settings = childElements(root, "settings").filter {
            it.namespaceURI == NS && it.getAttribute("pass") == pass
        }
        if (settings.size != 1) return null
        val components = childElements(settings.single(), "component").filter {
            it.namespaceURI == NS && it.getAttribute("name") == componentName
        }
        return components.singleOrNull()
    }

    private fun readDiskLayout(disk: Element): DiskLayout {
        val recoveryIds =
            childOrNull(disk, "ModifyPartitions")?.let { modify ->
                childElements(modify, "ModifyPartition")
                    .filter {
                        directText(it, "TypeID")?.equals(
                            "de94bba4-06d1-4d40-a16a-bfd50179d6ac",
                            ignoreCase = true,
                        ) == true
                    }
                    .mapNotNull { directText(it, "PartitionID")?.toIntOrNull() }
                    .toSet()
            } ?: emptySet()
        val partitions =
            childOrNull(disk, "CreatePartitions")?.let { creates ->
                childElements(creates, "CreatePartition")
                    .sortedBy { directText(it, "Order")?.toIntOrNull() ?: Int.MAX_VALUE }
                    .map { partition ->
                        val id = directText(partition, "Order")?.toIntOrNull() ?: 0
                        LayoutPartition(
                            sizeMb = directText(partition, "Size")?.toLongOrNull() ?: 0L,
                            role = when {
                                id in recoveryIds -> DiskPartitionRole.Recovery
                                directText(partition, "Type")?.equals("efi", true) == true -> DiskPartitionRole.Efi
                                directText(partition, "Type")?.equals("msr", true) == true -> DiskPartitionRole.Msr
                                else -> DiskPartitionRole.Primary
                            },
                            label = directText(partition, "Label"),
                        )
                    }
            } ?: emptyList()
        return DiskLayout(
            writeLayout = true,
            diskId = directText(disk, "DiskID")?.toLongOrNull(),
            wipe = directText(disk, "WillWipeDisk")?.toBooleanStrictOrNull() ?: false,
            partitions = partitions,
        )
    }

    private fun collectUnknown(
        element: Element,
        path: String,
        parent: Element?,
        add: (UnknownElement) -> Unit,
    ) {
        val name = element.localName ?: element.tagName
        val currentPath = if (path.isEmpty()) name else "$path/$name"
        val attributes = unsupportedAttributes(element)
        val unsupportedPassword =
            name == "Password" && directText(element, "PlainText")?.toBooleanStrictOrNull() == false
        if (!isEditableElement(element, parent) || attributes.isNotEmpty() || unsupportedPassword) {
            add(
                UnknownElement(
                    name,
                    currentPath,
                    attributes,
                    detail = if (unsupportedPassword) "encoded password format" else null,
                ),
            )
        }
        var child = element.firstChild
        while (child != null) {
            when {
                child is Element -> collectUnknown(child, currentPath, element, add)
                child.nodeType == Node.COMMENT_NODE ->
                    add(UnknownElement("#comment", "$currentPath/#comment", detail = "comment content"))
                child.nodeType == Node.PROCESSING_INSTRUCTION_NODE ->
                    add(UnknownElement("#processing-instruction", "$currentPath/#processing-instruction", detail = "processing instruction"))
                child.nodeType == Node.CDATA_SECTION_NODE ->
                    add(UnknownElement("#cdata", "$currentPath/#cdata", detail = "CDATA content"))
                child.nodeType == Node.TEXT_NODE && !child.nodeValue.isNullOrBlank() && elementChildren(element).isNotEmpty() ->
                    add(UnknownElement("#text", "$currentPath/#text", detail = "mixed text content"))
            }
            child = child.nextSibling
        }
    }

    private fun isEditableElement(element: Element, parent: Element?): Boolean {
        if (!isRecognizedElement(element, parent)) return false
        if (parent == null || parent.localName == "unattend" || parent.localName == "settings") return true
        if (parent.localName == "component") return isEditableComponentChild(element, parent)
        if (!isEditableElement(parent, parent.parentNode as? Element)) return false
        return (element.localName ?: element.tagName) in editableChildren[parent.localName].orEmpty()
    }

    private fun isEditableComponentChild(element: Element, component: Element): Boolean {
        val pass = (component.parentNode as? Element)?.getAttribute("pass")
        return when (component.getAttribute("name") to pass) {
            "Microsoft-Windows-Setup" to "windowsPE" -> element.localName == "DiskConfiguration"
            "Microsoft-Windows-Shell-Setup" to "specialize" -> element.localName == "ComputerName"
            "Microsoft-Windows-Shell-Setup" to "oobeSystem" -> element.localName == "UserAccounts"
            "Microsoft-Windows-International-Core" to "oobeSystem" ->
                element.localName in setOf("UILanguage", "SystemLocale", "UserLocale")
            else -> false
        }
    }

    private fun isRecognizedElement(element: Element, parent: Element?): Boolean {
        if (element.namespaceURI != NS) return false
        val name = element.localName ?: element.tagName
        if (name !in knownElements) return false
        if (parent == null) return name == "unattend"
        val parentName = parent.localName ?: parent.tagName
        if (parentName == "unattend" && name == "settings") {
            return element.getAttribute("pass") in knownPasses
        }
        if (parentName == "settings" && name == "component") {
            return parent.getAttribute("pass") in knownPasses &&
                element.getAttribute("name") in knownComponents
        }
        if (parentName == "component") {
            val pass = (parent.parentNode as? Element)?.getAttribute("pass")
            val componentName = parent.getAttribute("name")
            val children =
                when (componentName to pass) {
                    "Microsoft-Windows-Setup" to "windowsPE" -> setOf("DiskConfiguration", "ImageInstall")
                    "Microsoft-Windows-Shell-Setup" to "specialize" -> setOf("ComputerName")
                    "Microsoft-Windows-Shell-Setup" to "oobeSystem" -> setOf("UserAccounts")
                    "Microsoft-Windows-International-Core" to "oobeSystem" ->
                        setOf("UILanguage", "SystemLocale", "UserLocale", "InputLocale")
                    else -> emptySet()
                }
            return name in children
        }
        val allowed = allowedChildren[parentName] ?: return false
        return name in allowed
    }

    private val editableChildren =
        mapOf(
            "UserAccounts" to setOf("LocalAccounts"),
            "LocalAccounts" to setOf("LocalAccount"),
            "LocalAccount" to setOf("Name", "Group", "Password"),
            "Password" to setOf("Value", "PlainText"),
            "DiskConfiguration" to setOf("WillShowUI", "Disk"),
            "Disk" to setOf("DiskID", "WillWipeDisk", "CreatePartitions", "ModifyPartitions"),
            "CreatePartitions" to setOf("CreatePartition"),
            "CreatePartition" to setOf("Order", "Type", "Size", "Label"),
            "ModifyPartitions" to setOf("ModifyPartition"),
            "ModifyPartition" to setOf("Order", "PartitionID", "Format", "TypeID", "Label"),
            "ImageInstall" to setOf("OSImage", "DataImage"),
            "OSImage" to setOf("InstallFrom", "InstallTo", "InstallToAvailablePartition"),
            "InstallFrom" to setOf("MetaData"),
            "MetaData" to setOf("Key", "Value"),
            "InstallTo" to setOf("DiskID", "PartitionID"),
            "DataImage" to setOf("InstallTo"),
        )

    private val allowedChildren =
        mapOf(
            "settings" to setOf("component"),
            "component" to knownElements - setOf("unattend", "settings", "component"),
            "UserData" to setOf("AcceptEula", "FullName"),
            "UserAccounts" to setOf("LocalAccounts"),
            "LocalAccounts" to setOf("LocalAccount"),
            "LocalAccount" to setOf("Name", "DisplayName", "Group", "Description", "Password"),
            "Password" to setOf("Value", "PlainText"),
            "DiskConfiguration" to setOf("WillShowUI", "Disk"),
            "Disk" to setOf("DiskID", "WillWipeDisk", "CreatePartitions", "ModifyPartitions"),
            "CreatePartitions" to setOf("CreatePartition"),
            "CreatePartition" to setOf("Order", "Type", "Size", "Label"),
            "ModifyPartitions" to setOf("ModifyPartition"),
            "ModifyPartition" to setOf("Order", "PartitionID", "Format", "PartitionType", "Active", "Extend", "Label", "TypeID"),
            "ImageInstall" to setOf("OSImage", "DataImage"),
            "OSImage" to setOf("InstallFrom", "InstallTo", "InstallToAvailablePartition"),
            "DataImage" to setOf("Path", "InstallTo", "DoNotResize"),
            "InstallFrom" to setOf("MetaData"),
            "MetaData" to setOf("Key", "Value"),
            "InstallTo" to setOf("DiskID", "PartitionID"),
        )

    private fun unsupportedAttributes(element: Element): List<String> {
        val name = element.localName ?: element.tagName
        val allowed = allowedAttributes[name].orEmpty()
        val result = ArrayList<String>()
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val attributeName = attribute.localName ?: attribute.nodeName
            val namespace = attribute.namespaceURI
            val isXmlns = attributeName == "xmlns" || namespace == XMLNS
            val isUnqualifiedAllowed = namespace.isNullOrEmpty() && attributeName in allowed
            val isWcmAction = attributeName == "action" && namespace == WCM &&
                name in setOf("Disk", "CreatePartition", "ModifyPartition") && attribute.nodeValue == "add"
            if (!isXmlns && !isUnqualifiedAllowed && !isWcmAction) result.add(attributeName)
        }
        return result
    }

    private fun newDocument(): Document {
        val factory = secureFactory()
        val document = factory.newDocumentBuilder().newDocument()
        val root = document.createElementNS(NS, "unattend")
        document.appendChild(root)
        return document
    }

    private fun parse(bytes: ByteArray): Document {
        require(!WindowsTemplate.containsDoctypeOrEntity(bytes)) {
            "XML with DOCTYPE or ENTITY declarations is refused"
        }
        return secureFactory().newDocumentBuilder()
            .apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }
            .parse(ByteArrayInputStream(bytes))
            .also { document ->
                val root = document.documentElement
                    ?: throw IllegalArgumentException("XML has no root element")
                if (root.localName != "unattend" ||
                    root.namespaceURI != NS
                ) {
                    throw NotUnattendRootException("XML root is not an unattend document")
                }
            }
    }

    private class NotUnattendRootException(message: String) : IllegalArgumentException(message)

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }

    private fun findComponent(root: Element, pass: String, componentName: String): Element? {
        val settings = childElements(root, "settings").filter {
            it.namespaceURI == NS && it.getAttribute("pass") == pass
        }
        if (settings.size > 1) {
            throw IllegalStateException("unattend/$pass has ${settings.size} settings blocks; refusing to guess")
        }
        val components = settings.firstOrNull()?.let { settingsNode ->
            childElements(settingsNode, "component").filter {
                it.namespaceURI == NS && it.getAttribute("name") == componentName
            }
        }.orEmpty()
        if (components.size > 1) {
            throw IllegalStateException("$pass/$componentName has ${components.size} components; refusing to guess")
        }
        return components.firstOrNull()
    }

    private fun ensureComponent(
        document: Document,
        root: Element,
        pass: String,
        componentName: String,
    ): Element {
        val settings = ensureSettings(document, root, pass)
        val components = childElements(settings, "component").filter {
            it.namespaceURI == NS && it.getAttribute("name") == componentName
        }
        if (components.size > 1) {
            throw IllegalStateException("$pass/$componentName has ${components.size} components; refusing to guess")
        }
        components.firstOrNull()?.let { return it }
        val component = document.createElementNS(namespace(root), "component")
        component.setAttribute("name", componentName)
        component.setAttribute("processorArchitecture", "amd64")
        component.setAttribute("publicKeyToken", "31bf3856ad364e35")
        component.setAttribute("language", "neutral")
        component.setAttribute("versionScope", "nonSxS")
        settings.appendChild(component)
        return component
    }

    private fun ensureSettings(document: Document, root: Element, pass: String): Element {
        val settings = childElements(root, "settings").filter {
            it.namespaceURI == NS && it.getAttribute("pass") == pass
        }
        if (settings.size > 1) {
            throw IllegalStateException("unattend/$pass has ${settings.size} settings blocks; refusing to guess")
        }
        settings.firstOrNull()?.let { return it }
        val created = document.createElementNS(namespace(root), "settings")
        created.setAttribute("pass", pass)
        root.appendChild(created)
        return created
    }

    private fun requireSupportedSubtree(
        parent: Element,
        context: String,
        replacing: Boolean = false,
    ) {
        fun check(element: Element, owner: Element?) {
            val name = element.localName ?: element.tagName
            if (!isRecognizedElement(element, owner)) {
                throw IllegalStateException(
                    "$context contains unsupported element at ${pathOf(element)} <$name>; refusing to rewrite it",
                )
            }
            if (replacing && owner?.localName == "ModifyPartition" &&
                name !in setOf("Order", "PartitionID", "Format", "Label", "TypeID")
            ) {
                throw IllegalStateException(
                    "$context contains non-round-trippable field at ${pathOf(element)} <$name>; refusing to replace it",
                )
            }
            requireSafeNonElementContent(element, context)
            if (replacing && !isEditableElement(element, owner)) {
                throw IllegalStateException(
                    "$context contains unsupported editable field at ${pathOf(element)} <$name>; refusing to replace it",
                )
            }
            val unsupported = unsupportedAttributes(element)
            if (unsupported.isNotEmpty()) {
                throw IllegalStateException(
                    "$context contains unsupported attribute(s) at ${pathOf(element)}: " +
                        unsupported.joinToString(", "),
                )
            }
            val children = elementChildren(element)
            val repeatable = repeatableChildren[element.localName ?: element.tagName].orEmpty()
            children.groupingBy { it.localName ?: it.tagName }.eachCount().forEach { (name, count) ->
                if (count > 1 && name !in repeatable) {
                    throw IllegalStateException(
                        "$context contains duplicate <$name> at ${pathOf(element)}; refusing to guess",
                    )
                }
            }
            children.forEach { check(it, element) }
        }
        check(parent, parent.parentNode as? Element)
    }

    private val repeatableChildren =
        mapOf(
            "unattend" to setOf("settings"),
            "settings" to setOf("component"),
            "CreatePartitions" to setOf("CreatePartition"),
            "ModifyPartitions" to setOf("ModifyPartition"),
            "ImageInstall" to setOf("DataImage"),
        )

    private fun requireLayoutReplacementPreserves(
        configuration: Element,
        layout: DiskLayout,
        original: DiskLayout?,
    ) {
        if (original == null) throw IllegalStateException("Existing layout snapshot missing")
        val disk = childElements(configuration, "Disk").singleOrNull()
            ?: throw IllegalStateException("Existing DiskConfiguration has no unique Disk")
        val create = childOrNull(disk, "CreatePartitions")
            ?: throw IllegalStateException("Existing DiskConfiguration has no CreatePartitions")
        val parts = childElements(create, "CreatePartition")
        if (parts.size != original.partitions.size) throw IllegalStateException("Existing partition mapping changed")
        val orders = parts.mapNotNull { directText(it, "Order")?.toIntOrNull() }
        if (orders.size != parts.size || orders.toSet().size != orders.size) {
            throw IllegalStateException("Existing CreatePartition orders are ambiguous")
        }
        parts.sortedBy { directText(it, "Order")?.toIntOrNull() ?: Int.MAX_VALUE }
            .zip(original.partitions).forEachIndexed { index, (element, source) ->
                if (directText(element, "Order")?.toIntOrNull() != index + 1 ||
                    directText(element, "Size")?.toLongOrNull() != source.sizeMb ||
                    directText(element, "Type")?.equals(partitionType(source.role), true) != true ||
                    directText(element, "Label") != source.label
                ) throw IllegalStateException("Existing layout is not fully represented")
            }
        val fields = childOrNull(disk, "ModifyPartitions")?.let { childElements(it, "ModifyPartition") }.orEmpty()
        val ids = fields.mapNotNull { directText(it, "PartitionID")?.toIntOrNull() }
        if (ids.size != fields.size || ids.toSet().size != ids.size) {
            throw IllegalStateException("Existing ModifyPartition IDs are ambiguous")
        }
        val modifyOrders = fields.mapNotNull { directText(it, "Order")?.toIntOrNull() }
        if (modifyOrders.size != fields.size || modifyOrders.any { it <= 0 } || modifyOrders.toSet().size != modifyOrders.size) {
            throw IllegalStateException("Existing ModifyPartition orders are ambiguous")
        }
        val expected = original.partitions.count { it.role != DiskPartitionRole.Msr }
        if (fields.size != expected) throw IllegalStateException("Existing modify mapping is ambiguous")
        fields.forEach { field ->
            val id = directText(field, "PartitionID")?.toIntOrNull()
            val source = id?.let { original.partitions.getOrNull(it - 1) }
                ?: throw IllegalStateException("Existing ModifyPartition reference is invalid")
            if (source.role == DiskPartitionRole.Msr || directText(field, "Format") != format(source.role) ||
                directText(field, "Label") != source.label
            ) throw IllegalStateException("Existing ModifyPartition values are not represented")
            val typeId = directText(field, "TypeID")
            if (source.role == DiskPartitionRole.Recovery &&
                !typeId.equals("de94bba4-06d1-4d40-a16a-bfd50179d6ac", true)
            ) throw IllegalStateException("Existing recovery TypeID is not represented")
            if (source.role != DiskPartitionRole.Recovery && typeId != null) {
                throw IllegalStateException("Existing TypeID is not represented")
            }
        }
        if (layout.partitions.isEmpty()) throw IllegalStateException("Requested layout is empty")
    }
    private fun pathOf(element: Element): String {
        val names = ArrayList<String>()
        var current: Element? = element
        while (current != null) {
            names.add(current.localName ?: current.tagName)
            current = current.parentNode as? Element
        }
        return names.asReversed().joinToString("/")
    }

    private fun child(document: Document, parent: Element, name: String): Element =
        childOrNull(parent, name) ?: document.createElementNS(namespace(parent), name).also {
            parent.appendChild(it)
        }

    private fun childOrNull(parent: Element, name: String): Element? =
        childElements(parent, name).firstOrNull()

    private fun elementChildren(parent: Element): List<Element> {
        val result = ArrayList<Element>()
        var child = parent.firstChild
        while (child != null) {
            if (child is Element) result.add(child)
            child = child.nextSibling
        }
        return result
    }

    private fun childElements(parent: Element, name: String): List<Element> {
        val result = ArrayList<Element>()
        var child = parent.firstChild
        while (child != null) {
            if (child is Element && child.namespaceURI == NS &&
                (child.localName ?: child.tagName) == name
            ) result.add(child)
            child = child.nextSibling
        }
        return result
    }

    private fun requireAtMostOne(parent: Element, name: String, context: String) {
        val count = childElements(parent, name).size
        if (count > 1) {
            throw IllegalStateException(
                "$context has $count <$name> elements; refusing to guess which one to edit",
            )
        }
    }

    private fun setText(document: Document, parent: Element, name: String, value: String) {
        requireAtMostOne(parent, name, "${pathOf(parent)}/$name")
        val element = child(document, parent, name)
        val unsupported = unsupportedAttributes(element)
        if (unsupported.isNotEmpty()) {
            throw IllegalStateException(
                "${pathOf(element)} contains unsupported attribute(s): ${unsupported.joinToString(", ")}",
            )
        }
        requireSafeNonElementContent(element, "${pathOf(element)}")
        if (elementChildren(element).isNotEmpty()) {
            throw IllegalStateException(
                "${pathOf(element)} has child elements; refusing to replace text and lose structure",
            )
        }
        element.textContent = value
    }

    private fun removeChild(parent: Element, name: String) {
        val children = childElements(parent, name)
        if (children.size > 1) {
            throw IllegalStateException("${pathOf(parent)} has duplicate <$name>; refusing removal")
        }
        children.singleOrNull()?.let { child ->
            requireSupportedSubtree(child, "${pathOf(parent)}/$name", replacing = true)
            requireSafeNonElementContent(child, "${pathOf(parent)}/$name")
            parent.removeChild(child)
        }
    }

    private fun requireSafeNonElementContent(element: Element, context: String) {
        var child = element.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.COMMENT_NODE, Node.PROCESSING_INSTRUCTION_NODE, Node.CDATA_SECTION_NODE ->
                    throw IllegalStateException("$context contains non-element XML content; refusing mutation")
                Node.TEXT_NODE -> if (elementChildren(element).isNotEmpty() && !child.nodeValue.isNullOrBlank()) {
                    throw IllegalStateException("$context contains mixed text content; refusing mutation")
                }
            }
            child = child.nextSibling
        }
    }

    private fun setActionAdd(document: Document, root: Element, element: Element) {
        val wcmNamespace = "http://schemas.microsoft.com/WMIConfig/2002/State"
        val xmlNs = "http://www.w3.org/2000/xmlns/"
        if (root.getAttributeNS(xmlNs, "wcm").isEmpty()) {
            root.setAttributeNS(xmlNs, "xmlns:wcm", wcmNamespace)
        }
        val attribute = document.createAttributeNS(wcmNamespace, "wcm:action")
        attribute.value = "add"
        element.setAttributeNodeNS(attribute)
    }

    private fun directText(parent: Element, vararg path: String): String? {
        var current: Element = parent
        for (name in path) current = childOrNull(current, name) ?: return null
        return current.textContent?.trim()
    }

    private fun hasDataImageInstallTo(root: Element): Boolean {
        var found = false
        walk(root) { element ->
            if ((element.localName ?: element.tagName) == "DataImage" &&
                childOrNull(element, "InstallTo") != null
            ) {
                found = true
            }
        }
        return found
    }

    private fun walk(element: Element, visit: (Element) -> Unit) {
        visit(element)
        var child = element.firstChild
        while (child != null) {
            if (child is Element) walk(child, visit)
            child = child.nextSibling
        }
    }

    private fun namespace(element: Element): String =
        element.namespaceURI?.takeIf { it.isNotEmpty() } ?: NS

    private fun serialize(document: Document): ByteArray {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        val output = ByteArrayOutputStream()
        transformer.transform(DOMSource(document), StreamResult(output))
        return output.toByteArray()
    }
}
