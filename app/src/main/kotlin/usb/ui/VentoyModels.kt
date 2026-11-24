package usb.ui

import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendParams
import usb.ventoy.LayoutPartition

enum class IsoAction {
    LinkXml,
    /** Stage USB file into app filesDir, then queue raw write (Partitions confirm). */
    SendToRawWriter,
}

enum class WindowsImportKind {
    Image,
    XmlTemplate,
}

data class IsoImportProposal(
    val uri: String,
    val name: String,
    val targetName: String,
    val sizeBytes: Long?,
    val exists: Boolean,
    val identity: String?,
    val attachGeneration: Long,
    val requestGeneration: Long,
    val kind: WindowsImportKind = WindowsImportKind.Image,
    val directory: String = "/ISO",
    /** Destructive sections found in a phone XML at inspection time; empty for images. */
    val templateMarkers: List<String> = emptyList(),
    /** Ventoy data-partition geometry pinned by the picker; null outside a pick session. */
    val partitionStartLba: ULong? = null,
    val partitionSectorCount: ULong? = null,
    /** Pick session that requested this inspection; null for a non-picker import. */
    val pickSessionId: String? = null,
)

/** One existing `auto_install` binding shown in the XML section. */
data class WindowsImageLink(val image: String, val templates: List<String>)


data class AutounattendImageCandidates(
    val images: List<AutounattendBinding>,
    val searchScope: List<String>,
    val scopeLimitedBy: String?,
    val truncated: Boolean = false,
    val error: String? = null,
)


enum class UnattendField {
    Language,
    Locale,
    ComputerName,
    LocalAccountName,
    LocalAccountPassword,
    LocalAccountAdministrator,
    DiskLayout,
    DiskId,
    PartitionSize,
    PartitionRole,
    PartitionLabel,
    TpmBypass,
    SecureBootBypass,
    KeyboardLayout,
    DriveLetter,
    PartitionGuid,
    OemProductKey,
    EditionSelection,
}


/** Separate from DestructiveConfirmation: no USB identity or USB capacity is shown. */
data class DiskLayoutConfirmation(
    val diskId: Long?,
    val partitionCount: Int,
    val writeLayout: Boolean,
    /** SHA-256 of the exact params/image plan the user confirmed. */
    val planSha256: String? = null,
    /** Exact layout snapshot shown in the dialog, never re-read from the live form. */
    val partitions: List<LayoutPartition> = emptyList(),
    val declaredSizeMb: Long? = null,
    val totalMb: Long? = null,
    val remainingMb: Long? = null,
    /** First Primary receives ImageInstall; null means validation should have refused it. */
    val installTargetPartitionId: Int? = null,
    /** Mirrors WillWipeDisk exactly; false still rewrites the selected disk's table. */
    val wipe: Boolean = false,
)


/** Generation request waiting only for the separate target-disk acknowledgement. */
data class PendingAutounattendGeneration(
    val xmlName: String,
    val params: AutounattendParams,
    val imagePaths: List<String>,
    val overwriteXml: Boolean,
    val allowDestructiveTemplate: Boolean,
    val identity: String? = null,
    val attachGeneration: Long = 0L,
    val expectedTemplateSha256: String? = null,
    val expectedPlanSha256: String? = null,
    val renameFromXmlName: String? = null,
)

/** Raw XML text apply waiting only for overwrite acknowledgement. */
data class PendingAutounattendRawWrite(
    val xmlName: String,
    val rawBytes: ByteArray,
    val overwriteXml: Boolean,
    val allowDestructiveTemplate: Boolean,
    val identity: String? = null,
    val attachGeneration: Long = 0L,
    val expectedTemplateSha256: String? = null,
    val renameFromXmlName: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAutounattendRawWrite) return false
        return xmlName == other.xmlName &&
            rawBytes.contentEquals(other.rawBytes) &&
            overwriteXml == other.overwriteXml &&
            allowDestructiveTemplate == other.allowDestructiveTemplate &&
            identity == other.identity &&
            attachGeneration == other.attachGeneration &&
            expectedTemplateSha256 == other.expectedTemplateSha256 &&
            renameFromXmlName == other.renameFromXmlName
    }

    override fun hashCode(): Int {
        var result = xmlName.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        result = 31 * result + overwriteXml.hashCode()
        result = 31 * result + allowDestructiveTemplate.hashCode()
        result = 31 * result + (identity?.hashCode() ?: 0)
        result = 31 * result + attachGeneration.hashCode()
        result = 31 * result + (expectedTemplateSha256?.hashCode() ?: 0)
        result = 31 * result + (renameFromXmlName?.hashCode() ?: 0)
        return result
    }
}
