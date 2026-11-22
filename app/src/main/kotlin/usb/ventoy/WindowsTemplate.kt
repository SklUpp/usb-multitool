package usb.ventoy

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

class DestructiveTemplateRequiresOptInException(
    val markers: List<String>,
    message: String,
) : Exception(message)

open class InvalidTemplateException(message: String, cause: Throwable? = null) : Exception(message, cause)
class NotUnattendRootException(message: String) : InvalidTemplateException(message)
class TemplateTooLargeException(message: String) : InvalidTemplateException(message)
class EmptyTemplateException(message: String) : InvalidTemplateException(message)

data class TemplateInspection(
    /** Stable marker names found in the XML; empty means no disk/user destructive section. */
    val destructiveMarkers: List<String>,
)

/**
 * Read-only inspection of a user-selected Windows unattend template.
 *
 * The template bytes are never rewritten. Parsing disables DTDs and external entities;
 * the result is used only to require a separate opt-in before a template that wipes or
 * partitions a disk is linked. This is not a full Windows schema validator.
 */
object WindowsTemplate {
    fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun inspect(bytes: ByteArray, requireUnattendRoot: Boolean = false): TemplateInspection {
        // Android's parser rejects several standard JAXP hardening features. Rejecting any
        // DOCTYPE/ENTITY declaration up front is portable; supported features are applied
        // best-effort and an empty EntityResolver blocks external fetches either way.
        if (containsDoctypeOrEntity(bytes)) {
            throw InvalidTemplateException("XML templates with DOCTYPE or ENTITY declarations are refused")
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { factory.setXIncludeAware(false) }
        runCatching { factory.isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        val document =
            try {
                builder.parse(ByteArrayInputStream(bytes))
            } catch (failure: Throwable) {
                throw InvalidTemplateException(
                    "XML template is not well-formed: ${failure.message ?: failure.javaClass.simpleName}",
                    failure,
                )
            }
        if (requireUnattendRoot) {
            val root = document.documentElement
            if (root == null ||
                root.localName != "unattend" ||
                root.namespaceURI != "urn:schemas-microsoft-com:unattend"
            ) {
                throw NotUnattendRootException("XML root is not an unattend document")
            }
        }
        val markers = linkedSetOf<String>()
        walk(document.documentElement, markers)
        return TemplateInspection(destructiveMarkers = markers.toList())
    }

    internal fun containsDoctypeOrEntity(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        // XML entity declarations require a DOCTYPE. Check UTF-8 and both UTF-16 byte
        // orders; rejecting before parse is the fail-closed path on Android, whose parser
        // does not support every standard JAXP hardening feature.
        val texts =
            listOf(
                String(bytes, Charsets.UTF_8),
                String(bytes, Charsets.UTF_16LE),
                String(bytes, Charsets.UTF_16BE),
            )
        return texts.any { text ->
            val lower = text.lowercase()
            lower.contains("<!doctype") || lower.contains("<!entity")
        }
    }

    fun requireAllowed(inspection: TemplateInspection, allowDestructive: Boolean) {
        if (inspection.destructiveMarkers.isNotEmpty() && !allowDestructive) {
            throw DestructiveTemplateRequiresOptInException(
                markers = inspection.destructiveMarkers,
                message = "Template contains destructive sections " +
                    inspection.destructiveMarkers.joinToString(", ") +
                    "; confirm the disk configuration explicitly before linking",
            )
        }
    }

    private fun walk(element: Element, markers: MutableSet<String>) {
        when (element.localName ?: element.tagName) {
            "DiskConfiguration" -> markers.add("DiskConfiguration")
            "CreatePartitions" -> markers.add("CreatePartitions")
            "ModifyPartitions" -> markers.add("ModifyPartitions")
            "ImageInstall" -> markers.add("ImageInstall")
            "WillWipeDisk" -> if (element.textContent?.trim().equals("true", ignoreCase = true)) {
                markers.add("WillWipeDisk")
            }
        }
        var child: Node? = element.firstChild
        while (child != null) {
            if (child is Element) walk(child, markers)
            child = child.nextSibling
        }
    }
}
