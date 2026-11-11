package usb.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import usb.diagnostics.CheckpointPublisher
import usb.diagnostics.CheckpointStore
import usb.diagnostics.RescueArchiveTarget
import usb.diagnostics.probeWriteSupport
import usb.diagnostics.RescueMap
import usb.diagnostics.RescueSink
import usb.diagnostics.asRescueSink

/** One document from a SAF tree; no platform type escapes into the UI/reducer. */
data class SafDocument(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
)

/**
 * Thin platform adapter for a user-selected SAF tree. It owns document placement and
 * byte sync only; rescue naming, validation and the durable engine boundary live in
 * [usb.diagnostics.BackupArchive] and [usb.diagnostics.RescueArchiveOperations].
 *
 * New documents are opened append-only (`w`) and never rewound: checkpoint maps are
 * published as fresh files, so a failed write cannot truncate the previous valid map.
 */
class SafBackupFolder(
    private val resolver: ContentResolver,
    val treeUri: Uri,
) {
    private val rootDocumentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    fun displayName(): String? = queryDocument(rootDocumentUri)?.name

    /**
     * Provider-reported free space for this tree, or null when it cannot be trusted.
     *
     * Free space belongs to the provider *root* row, and a tree's root is identified by
     * [DocumentsContract.Root.COLUMN_DOCUMENT_ID], not by the tree document ID used as a
     * root ID. All roots are enumerated and the single root whose document ID equals this
     * tree's document ID is used. A nested tree (no matching root) or an ambiguous mapping
     * (several roots) is unknown, never a guess.
     */
    fun availableBytes(): Long? = runCatching {
        val authority = treeUri.authority ?: return@runCatching null
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val cursor = resolver.query(
            DocumentsContract.buildRootsUri(authority),
            arrayOf(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            ),
            null,
            null,
            null,
        ) ?: return@runCatching null
        cursor.use { availableBytesForTree(it, treeDocumentId) }
    }.getOrNull()

    fun listRootFiles(): List<SafDocument> = listChildren(rootDocumentUri)

    fun listChildren(parent: Uri): List<SafDocument> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("cannot list documents in $parent")
        return cursor.use {
            val result = ArrayList<SafDocument>()
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                val size = if (it.isNull(3)) null else it.getLong(3)
                result += SafDocument(
                    uri = documentUri(id),
                    name = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = size,
                )
            }
            result
        }
    }

    fun createSubfolder(name: String): Uri =
        requireNotNull(
            DocumentsContract.createDocument(
                resolver,
                rootDocumentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ),
        ) { "the provider refused to create folder '$name'" }

    fun createRootFile(name: String, mimeType: String): Uri =
        createFile(rootDocumentUri, name, mimeType)

    fun createFile(parent: Uri, name: String, mimeType: String): Uri {
        // createDocument may return a non-tree URI; re-scope the parent for the next call.
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent),
        )
        return requireNotNull(
            DocumentsContract.createDocument(resolver, parentUri, mimeType, name),
        ) { "the provider refused to create file '$name'" }
    }

    fun openSyncedOutput(document: Uri): SafSyncedOutput =
        SafSyncedOutput(resolver, documentUri(DocumentsContract.getDocumentId(document)))

    /** Sequential write + fsync + close. Used for map generations and the probe. */
    fun writeTextAndSync(document: Uri, text: String) {
        val output = openSyncedOutput(document)
        var closed = false
        try {
            output.output.write(text.toByteArray(Charsets.UTF_8))
            output.sync()
            output.close()
            closed = true
        } finally {
            if (!closed) runCatching { output.close() }
        }
    }

    /** Bounded UTF-8 read with the same character cap the restore path applies. */
    fun readText(document: Uri, maxChars: Int): String {
        val scoped = documentUri(DocumentsContract.getDocumentId(document))
        val input: InputStream = resolver.openInputStream(scoped)
            ?: throw IOException("cannot open $scoped for reading")
        return input.use { stream ->
            val reader = stream.bufferedReader(Charsets.UTF_8)
            val text = StringBuilder()
            val buffer = CharArray(8192)
            var total = 0
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxChars) {
                    "document exceeds $maxChars characters: $document"
                }
                text.append(buffer, 0, read)
            }
            text.toString()
        }
    }

    /**
     * Fail closed before the image starts. A provider that cannot write, fsync and read
     * back a small document cannot host a trustworthy rescue archive. The probe name is
     * a dotfile, so a failed cleanup cannot break restore-folder selection.
     */
    fun probeWriteSupport(parent: Uri) {
        SafCheckpointStore(this, parent)
            .probeWriteSupport(".usb-write-probe-${System.nanoTime()}")
    }

    fun deleteDocument(document: Uri): Boolean =
        DocumentsContract.deleteDocument(
            resolver,
            documentUri(DocumentsContract.getDocumentId(document)),
        )

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun queryDocument(document: Uri): SafDocument? {
        val scoped = documentUri(DocumentsContract.getDocumentId(document))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = resolver.query(scoped, projection, null, null, null) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            SafDocument(
                uri = scoped,
                name = it.getString(0) ?: return null,
                isDirectory = it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = if (it.isNull(2)) null else it.getLong(2),
            )
        }
    }
}

/**
 * Scans all provider roots and returns free space of the unique root whose
 * [DocumentsContract.Root.COLUMN_DOCUMENT_ID] equals [treeDocumentId], else null.
 *
 * Unknown covers: absent document-id column, no matching root (nested tree), more than one
 * matching root (ambiguous mapping), absent/null/negative available-bytes column.
 */
internal fun availableBytesForTree(cursor: Cursor, treeDocumentId: String): Long? {
    val documentIdColumn = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
    if (documentIdColumn < 0) return null
    val bytesColumn = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
    var matches = 0
    var bytes: Long? = null
    while (cursor.moveToNext()) {
        if (cursor.isNull(documentIdColumn)) continue
        if (cursor.getString(documentIdColumn) != treeDocumentId) continue
        matches++
        if (matches > 1) return null
        if (bytesColumn >= 0 && !cursor.isNull(bytesColumn)) {
            bytes = cursor.getLong(bytesColumn)
        }
    }
    return if (matches == 1) bytes?.takeIf { it >= 0L } else null
}

/**
 * One SAF document opened write-only. Sequential writes and fd.sync are the only
 * capabilities required; no seek or truncate is used, so a provider that supports plain
 * `w` is sufficient (and is probed before the image starts).
 */
class SafSyncedOutput(
    resolver: ContentResolver,
    document: Uri,
) : Closeable {
    private val descriptor = resolver.openFileDescriptor(document, "w")
        ?: throw IOException("cannot open $document for writing")
    private val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    private var closed = false

    val output: OutputStream get() = stream

    fun sync() {
        stream.flush()
        descriptor.fileDescriptor.sync()
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            stream.flush()
        } catch (t: Throwable) {
            failure = t
        }
        try {
            stream.close()
        } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
        failure?.let { throw it }
    }
}

/** SAF placement for [CheckpointPublisher]; all publication semantics stay in the pure class. */
class SafCheckpointStore(
    private val folder: SafBackupFolder,
    private val parent: Uri,
) : CheckpointStore {
    override fun create(name: String): String =
        folder.createFile(parent, name, "text/plain").toString()

    override fun writeAndSync(document: String, text: String) =
        folder.writeTextAndSync(Uri.parse(document), text)

    override fun readText(document: String, maxChars: Int): String =
        folder.readText(Uri.parse(document), maxChars)

    override fun delete(document: String) {
        folder.deleteDocument(Uri.parse(document))
    }
}

/**
 * Archive target that writes a dense image and publishes immutable map generations into
 * an already-created SAF folder. No naming/validation here: only bytes, sync, checkpoint
 * publication and close.
 */
class SafArchiveTarget(
    private val image: SafSyncedOutput,
    private val checkpoints: CheckpointPublisher,
    sectorSize: Int,
) : RescueArchiveTarget {
    override val sink: RescueSink = image.output.asRescueSink(sectorSize)
    private var closed = false

    override fun syncImage() = image.sync()

    override fun writeCheckpoint(map: RescueMap) = checkpoints.publish(map)

    override fun closeArchive() {
        if (closed) return
        closed = true
        image.close()
    }

    override fun publishCanonical() {
        checkpoints.publishFinal()
    }
}
