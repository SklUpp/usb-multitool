package usb.ventoy

import java.security.MessageDigest

/**
 * Unattend domain models: generator parameters, parsed document state, image bindings, and
 * the generation plan hash.
 *
 * Pure Kotlin (stdlib only): [Autounattend] and UI both read these, so this file never
 * imports usb.ui. Screens own their own state and strings.
 */

enum class DiskPartitionRole { Efi, Msr, Primary, Recovery }

data class LayoutPartition(
    val sizeMb: Long,
    val role: DiskPartitionRole,
    val label: String? = null,
)

/**
 * Describes the future Windows target machine, never this USB stick.
 * [diskId] is user-entered and cannot be verified by this app.
 * [declaredSizeMb] is user-entered too: it is never measured, never written to the XML, and
 * only lets the editor check the partition sum against the capacity the user believes the
 * target disk has.
 */
data class DiskLayout(
    val writeLayout: Boolean = false,
    val diskId: Long? = null,
    val wipe: Boolean = false,
    val partitions: List<LayoutPartition> = emptyList(),
    val declaredSizeMb: Long? = null,
)

/** Sum of partition sizes in MiB; null when the sum would overflow [Long]. */
fun diskLayoutTotalMb(partitions: List<LayoutPartition>): Long? {
    var total = 0L
    for (partition in partitions) {
        if (partition.sizeMb > Long.MAX_VALUE - total) return null
        total += partition.sizeMb
    }
    return total
}

/**
 * 1-based CreatePartition order of the first Primary, the partition that
 * `ImageInstall/OSImage/InstallTo` will target. Null when no Primary exists.
 */
fun diskLayoutInstallTargetPartitionId(partitions: List<LayoutPartition>): Int? {
    val index = partitions.indexOfFirst { it.role == DiskPartitionRole.Primary }
    return if (index >= 0) index + 1 else null
}

/** Declared capacity minus the partition sum; null without a declared size or on overflow. */
fun diskLayoutRemainingMb(layout: DiskLayout): Long? {
    val total = diskLayoutTotalMb(layout.partitions) ?: return null
    val declared = layout.declaredSizeMb?.takeIf { it > 0 } ?: return null
    return declared - total
}

data class LocalAccount(
    val name: String? = null,
    val password: String? = null,
    val inAdministrators: Boolean? = null,
) {
    /** Never let a data-class dump carry the plaintext password into logs or traces. */
    override fun toString(): String =
        "LocalAccount(name=$name, password=${if (password == null) "null" else "<redacted>"}, " +
            "inAdministrators=$inAdministrators)"
}

/** null = omit that block from the generated file; never write an empty block. */
data class AutounattendParams(
    val languageTag: String? = null,
    val localeId: String? = null,
    val computerName: String? = null,
    val localAccount: LocalAccount? = null,
    val diskLayout: DiskLayout? = null,
)

data class AutounattendDefaults(
    val languageTag: String? = null,
    val localeId: String? = null,
    val computerName: String? = null,
    val localAccount: LocalAccount? = null,
    val note: String = "",
)

data class UnknownElement(val name: String, val path: String?)

data class AutounattendDocument(
    val existing: AutounattendParams?,
    val unknownElements: List<UnknownElement>,
    val boundImages: List<String>,
    val sourcePath: String,
    val destructiveMarkers: List<String> = emptyList(),
    /** SHA-256 of the exact inspected XML bytes; acknowledgement must match this. */
    val templateSha256: String? = null,
    /** Existing layout content that blocks rewrite, surfaced before generation. */
    val layoutProblems: List<AutounattendProblem> = emptyList(),
    val templateInSearchScope: Boolean? = null,
)

sealed class AutounattendState {
    data object Missing : AutounattendState()
    data class Parsed(val document: AutounattendDocument) : AutounattendState()
    data class Unreadable(
        val problem: UnreadableProblem,
        val reason: String? = null,
        val sourcePath: String? = null,
    ) : AutounattendState()
}

enum class UnreadableProblem {
    NotUnattendRoot,
    MalformedXml,
    TooLarge,
    IoFailure,
    Empty,
}

enum class AutounattendProblem {
    BadLanguageTag,
    BadComputerName,
    BadAccountName,
    BadPartitionLabel,
    LayoutOverlaps,
    LayoutTooLarge,
    LayoutSizeOverflow,
    LayoutExceedsDiskSize,
    LayoutDiskSizeMissing,
    LayoutIncomplete,
    LayoutImageInstallNotSupported,
}

/** [schemaChecked] false means "validity not checked"; never render a green state. */
data class AutounattendValidation(
    val schemaChecked: Boolean,
    val problems: List<AutounattendProblem>,
    val unavailableReason: String? = null,
)

data class AutounattendBinding(
    val imagePath: String,
    val bound: Boolean,
    val problem: BindProblem? = null,
)

/** Why an image cannot be bound; UI maps this to its own localized text. */
enum class BindProblem {
    NotOnPartition,
    TemplateUnreadable,
    OutsideScope,
    DuplicateBinding,
    Unknown,
}

/**
 * SHA-256 of the exact user-visible generation plan: XML target, every parameter, and the
 * sorted image list. The UI shows the same values in confirmation; the host recomputes it.
 */
fun autounattendPlanSha256(
    xmlName: String,
    params: AutounattendParams,
    imagePaths: List<String>,
): String {
    val layout = params.diskLayout
    val account = params.localAccount
    val canonical =
        buildString {
            append("xml=").append(xmlName.trim()).append('\n')
            append("lang=").append(params.languageTag).append('\n')
            append("locale=").append(params.localeId).append('\n')
            append("computer=").append(params.computerName).append('\n')
            append("account.name=").append(account?.name).append('\n')
            // Bind the password into the plan without carrying plaintext through the
            // digest input (it would otherwise sit in any string dump of the plan).
            append("account.passwordSha256=")
                .append(account?.password?.let { sha256Hex(it) })
                .append('\n')
            append("account.admin=").append(account?.inAdministrators).append('\n')
            append("layout.write=").append(layout?.writeLayout).append('\n')
            append("layout.diskId=").append(layout?.diskId).append('\n')
            append("layout.wipe=").append(layout?.wipe).append('\n')
            append("layout.declaredSizeMb=").append(layout?.declaredSizeMb).append('\n')
            layout?.partitions?.forEach { partition ->
                append("partition=")
                    .append(partition.role.name).append(',')
                    .append(partition.sizeMb).append(',')
                    .append(partition.label).append('\n')
            }
            imagePaths.sorted().forEach { append("image=").append(it).append('\n') }
        }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
