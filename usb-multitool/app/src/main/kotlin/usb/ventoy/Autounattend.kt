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

    private val knownElements =
        setOf(
            "unattend", "settings", "component", "UserData", "AcceptEula", "FullName",
            "ComputerName", "UserAccounts", "LocalAccounts", "LocalAccount", "Name",
            "DisplayName", "Group", "Description", "Password", "Value", "PlainText",
            "International-Core", "InputLocale", "SystemLocale", "UILanguage", "UserLocale",
            "DiskConfiguration", "Disk", "WillWipeDisk", "CreatePartitions", "CreatePartition",
            "Order", "Type", "Size", "Label", "ModifyPartitions", "ModifyPartition",
            "PartitionID", "Format", "PartitionType", "Active", "Extend", "ImageInstall",
            "TypeID",
            "OSImage", "InstallFrom", "MetaData", "Key", "InstallTo", "DiskID", "WillShowUI",
            "DataImage", "InstallToAvailablePartition",
            "Credentials", "Username", "Domain", "Path", "File", "DoNotResize", "Letter",
        )

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
        collectUnknown(root, "", unknown::add)
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

    fun validate(params: AutounattendParams): AutounattendValidation {
        val problems = linkedSetOf<AutounattendProblem>()
        params.languageTag?.let {
            if (!it.matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"))) {
                problems.add(AutounattendProblem.BadLanguageTag)
            }
        }
        params.localeId?.let {
            if (!it.matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"))) {
                problems.add(AutounattendProblem.BadLanguageTag)
            }
        }
        params.computerName?.let {
            if (it.isEmpty() || it.length > 15 || !it.matches(Regex("^[A-Za-z0-9-]+$"))) {
                problems.add(AutounattendProblem.BadComputerName)
            }
        }
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
        params.diskLayout?.let { layout ->
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
                val total = layout.partitions.fold(0L) { sum, p -> sum + p.sizeMb }
                if (total <= 0 || total > 1L shl 40) {
                    problems.add(AutounattendProblem.LayoutTooLarge)
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
        if (params.languageTag != null || params.localeId != null) {
            val component = ensureComponent(document, root, "oobeSystem", "Microsoft-Windows-International-Core")
            params.languageTag?.let { setText(document, component, "UILanguage", it) }
            params.localeId?.let {
                setText(document, component, "UserLocale", it)
                setText(document, component, "SystemLocale", it)
            }
        }
        params.computerName?.let { name ->
            val component = ensureComponent(document, root, "specialize", "Microsoft-Windows-Shell-Setup")
            setText(document, component, "ComputerName", name)
        }
        params.localAccount?.let { account ->
            if (!account.name.isNullOrBlank()) {
                applyLocalAccount(document, root, account)
            }
        }
        params.diskLayout?.let { layout ->
            if (layout.writeLayout) applyDiskLayout(document, root, layout)
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
        val userAccounts = child(document, shell, "UserAccounts")
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
        setText(document, localAccount, "Name", account.name ?: return)
        account.password?.let { password ->
            val passwordElement = child(document, localAccount, "Password")
            setText(document, passwordElement, "Value", password)
            setText(document, passwordElement, "PlainText", "true")
        }
        when (account.inAdministrators) {
            true -> setText(document, localAccount, "Group", "Administrators")
            false -> removeChild(localAccount, "Group")
            null -> Unit
        }
    }

    private fun applyDiskLayout(document: Document, root: Element, layout: DiskLayout) {
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
            requireSupportedSubtree(configuration, "DiskConfiguration")
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
        var language: String? = null
        var locale: String? = null
        var computer: String? = null
        var account: LocalAccount? = null
        var accountCount = 0
        var layout: DiskLayout? = null
        walk(root) { element ->
            when (element.localName ?: element.tagName) {
                "UILanguage" -> language = element.textContent?.trim()
                "UserLocale" -> locale = element.textContent?.trim()
                "ComputerName" -> computer = element.textContent?.trim()
                "LocalAccount" -> {
                    accountCount++
                    val name = directText(element, "Name")
                    val password = directText(element, "Password", "Value")
                    val group = directText(element, "Group")
                    if (name != null) {
                        account = LocalAccount(name, password, group?.equals("Administrators", true))
                    }
                }
                "DiskConfiguration" -> {
                    val disk = childOrNull(element, "Disk") ?: return@walk
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
                                .map { p ->
                                    val id = directText(p, "Order")?.toIntOrNull() ?: 0
                                    LayoutPartition(
                                        sizeMb = directText(p, "Size")?.toLongOrNull() ?: 0L,
                                        role =
                                            when {
                                                id in recoveryIds -> DiskPartitionRole.Recovery
                                                directText(p, "Type")?.lowercase() == "efi" ->
                                                    DiskPartitionRole.Efi
                                                directText(p, "Type")?.lowercase() == "msr" ->
                                                    DiskPartitionRole.Msr
                                                else -> DiskPartitionRole.Primary
                                            },
                                        label = directText(p, "Label"),
                                    )
                                }
                        } ?: emptyList()
                    layout =
                        DiskLayout(
                            writeLayout = true,
                            diskId = directText(disk, "DiskID")?.toLongOrNull(),
                            wipe = directText(disk, "WillWipeDisk")?.toBooleanStrictOrNull() ?: false,
                            partitions = partitions,
                        )
                }
            }
        }
        if (accountCount > 1) account = null
        if (language == null && locale == null && computer == null && account == null && layout == null) {
            return null
        }
        return AutounattendParams(
            languageTag = language,
            localeId = locale,
            computerName = computer,
            localAccount = account,
            diskLayout = layout,
        )
    }

    private fun collectUnknown(element: Element, path: String, add: (UnknownElement) -> Unit) {
        val name = element.localName ?: element.tagName
        val currentPath = if (path.isEmpty()) name else "$path/$name"
        if (name !in knownElements) add(UnknownElement(name, path.ifEmpty { null }))
        var child = element.firstChild
        while (child != null) {
            if (child is Element) collectUnknown(child, currentPath, add)
            child = child.nextSibling
        }
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

    private fun findComponent(root: Element, pass: String, componentName: String): Element? =
        childElements(root, "settings")
            .firstOrNull { it.getAttribute("pass") == pass }
            ?.let { settings ->
                childElements(settings, "component")
                    .firstOrNull { it.getAttribute("name") == componentName }
            }

    private fun ensureComponent(
        document: Document,
        root: Element,
        pass: String,
        componentName: String,
    ): Element {
        val settings = ensureSettings(document, root, pass)
        childElements(settings, "component")
            .firstOrNull { it.getAttribute("name") == componentName }
            ?.let { return it }
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
        childElements(root, "settings").firstOrNull { it.getAttribute("pass") == pass }?.let { return it }
        val settings = document.createElementNS(namespace(root), "settings")
        settings.setAttribute("pass", pass)
        root.appendChild(settings)
        return settings
    }

    private fun requireSupportedSubtree(parent: Element, context: String) {
        val namespace = parent.namespaceURI
        if (namespace != NS) {
            throw IllegalStateException(
                "$context contains an element without the unattend namespace; refusing to delete it",
            )
        }
        val name = parent.localName ?: parent.tagName
        if (name !in knownElements) {
            throw IllegalStateException(
                "$context contains unknown element <$name>; refusing to delete it",
            )
        }
        val attributes = parent.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val attributeNamespace = attribute.namespaceURI
            val attributeName = attribute.localName ?: attribute.nodeName
            val allowed =
                attributeName == "xmlns" ||
                    attributeNamespace == "http://www.w3.org/2000/xmlns/" ||
                    (attributeNamespace == "http://schemas.microsoft.com/WMIConfig/2002/State" &&
                        attributeName == "action")
            if (!allowed) {
                throw IllegalStateException(
                    "$context contains unsupported attribute $attributeName; refusing to delete it",
                )
            }
        }
        elementChildren(parent).forEach { requireSupportedSubtree(it, context) }
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
            if (child is Element && (child.localName ?: child.tagName) == name) result.add(child)
            child = child.nextSibling
        }
        return result
    }

    private fun setText(document: Document, parent: Element, name: String, value: String) {
        val element = child(document, parent, name)
        element.textContent = value
    }

    private fun removeChild(parent: Element, name: String) {
        childElements(parent, name).forEach { parent.removeChild(it) }
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
