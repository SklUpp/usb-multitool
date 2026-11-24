package usb.files

import android.net.Uri
import usb.saf.SafBackupFolder
import usb.saf.SafDocument

/** SAF tree surface for batch copy; production wraps [SafBackupFolder]. */
internal interface BatchSafFolder {
    fun listChildren(parent: Uri): List<SafDocument>
    fun createExclusiveFile(parent: Uri, name: String, mimeType: String): Uri
    fun createExclusiveSubfolder(parent: Uri, name: String): Uri
    fun deleteDocument(document: Uri): Boolean

    companion object {
        fun of(folder: SafBackupFolder): BatchSafFolder =
            object : BatchSafFolder {
                override fun listChildren(parent: Uri): List<SafDocument> = folder.listChildren(parent)
                override fun createExclusiveFile(parent: Uri, name: String, mimeType: String): Uri =
                    folder.createExclusiveFile(parent, name, mimeType)
                override fun createExclusiveSubfolder(parent: Uri, name: String): Uri =
                    folder.createExclusiveSubfolder(parent, name)
                override fun deleteDocument(document: Uri): Boolean = folder.deleteDocument(document)
            }
    }
}
