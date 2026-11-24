package usb.saf

import android.net.Uri

/**
 * SAF tree surface shared by backup and batch copy paths. Production wraps [SafBackupFolder].
 * Keep this free of usb.ui / usb.multitool.
 */
interface SafTree {
    fun displayName(): String?

    fun listRootFiles(): List<SafDocument>

    fun listChildren(parent: Uri): List<SafDocument>

    fun readText(document: Uri, maxChars: Int): String

    fun createExclusiveFile(parent: Uri, name: String, mimeType: String): Uri

    fun createExclusiveSubfolder(parent: Uri, name: String): Uri

    fun deleteDocument(document: Uri): Boolean
}
