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
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants

import usb.diagnostics.CheckpointPublisher
import usb.diagnostics.CheckpointStore
import usb.diagnostics.RescueArchiveTarget
import usb.diagnostics.RescueMap
import usb.diagnostics.RescueSink

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
 * New documents are opened read-write only after authority, ID and metadata checks. These checks
 * validate shape, not exclusive ownership. Existing IDs may still be returned by a nonconforming
 * provider; a concurrent writer may also change a valid returned document after checks. Rejected responses are retained rather than deleted.
 */
internal fun isNewRegularFile(document: SafDocument?, scoped: Uri, authority: String): Boolean =
    document != null &&
        document.uri == scoped &&
        document.uri.authority == authority &&
        !document.isDirectory &&
        (document.sizeBytes == null || document.sizeBytes == 0L)
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

    fun createSubfolder(name: String): Uri = createSubfolder(rootDocumentUri, name)

    fun createSubfolder(parent: Uri, name: String): Uri {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent),
        )
        return requireNotNull(
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ),
        ) { "the provider refused to create folder '$name'" }
    }

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
    /**
     * Validate returned authority, ID and metadata before any write probe. The platform contract
     * promises a new ID for conforming providers, but metadata cannot prove ownership otherwise;
     * never infer ownership from display-name uniqueness.
     */
    fun createExclusiveSubfolder(name: String): Uri = createExclusiveSubfolder(rootDocumentUri, name)

    fun createExclusiveSubfolder(parent: Uri, name: String): Uri {
        val created = createSubfolder(parent, name)
        require(created.authority == treeUri.authority) {
            "SAF provider returned foreign authority for folder: $name; retained returned document $created"
        }
        val createdId = DocumentsContract.getDocumentId(created)
        val scoped = documentUri(createdId)
        val document = try {
            queryDocument(scoped)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "SAF provider query failed for folder $name; retained returned document $created",
                failure,
            )
        }
        require(
            document != null &&
                document.uri == scoped &&
                document.uri.authority == treeUri.authority &&
                DocumentsContract.getDocumentId(document.uri) == createdId &&
                document.isDirectory,
        ) { "SAF provider did not create a new folder: $name; retained returned document $created" }
        // Always return tree-scoped URI — never the raw createDocument URI.
        return scoped
    }

    fun createExclusiveFile(parent: Uri, name: String, mimeType: String): Uri {
        val created = createFile(parent, name, mimeType)
        require(created.authority == treeUri.authority) {
            "SAF provider returned foreign authority for file: $name; retained returned document $created"
        }
        val createdId = DocumentsContract.getDocumentId(created)
        val scoped = documentUri(createdId)
        val document = try {
            queryDocument(scoped)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "SAF provider query failed for file $name; retained returned document $created",
                failure,
            )
        }
        require(
            isNewRegularFile(document, scoped, treeUri.authority!!),
        ) { "SAF provider did not create a new empty file: $name; retained returned document $created" }
        return scoped
    }


    fun openSyncedOutput(document: Uri): SafSyncedOutput =
        SafSyncedOutput(resolver, documentUri(DocumentsContract.getDocumentId(document)))

    fun openPositionalImage(document: Uri): SafPositionalImage =
        SafPositionalImage(resolver, documentUri(DocumentsContract.getDocumentId(document)))

    /** Sequential write + fsync + close for checkpoint map documents. */
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

/** Random-access image document; only use on a newly created, operation-owned image. */
class SafPositionalImage(resolver: ContentResolver, document: Uri) : Closeable {
    private val descriptor = resolver.openFileDescriptor(document, "rw")
        ?: throw IOException("cannot open $document for random-access writing")
    private val fd = descriptor.fileDescriptor
    private var closed = false

    init {
        try {
            check(OsConstants.S_ISREG(Os.fstat(fd).st_mode)) { "SAF image is not a regular seekable file" }
            Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            probe()
        } catch (failure: Throwable) {
            runCatching { descriptor.close() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun probe() {
        val first = byteArrayOf(0x31, 0x32, 0x33)
        val second = byteArrayOf(0x61, 0x62)
        writeFully(first, 0)
        writeFully(second, 4096)
        check(readFully(first.size, 0).contentEquals(first)) { "SAF image failed positional-write probe" }
        check(readFully(second.size, 4096).contentEquals(second)) { "SAF image failed random-access probe" }
        Os.ftruncate(fd, 0)
        Os.fsync(fd)
    }

    private fun readFully(length: Int, offset: Long): ByteArray {
        val bytes = ByteArray(length)
        var done = 0
        while (done < length) {
            val count = try {
                Os.pread(fd, bytes, done, length - done, offset + done)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw e
            }
            if (count <= 0) throw IOException("SAF image positional read made no progress")
            done += count
        }
        return bytes
    }

    private fun writeFully(bytes: ByteArray, offset: Long) =
        writeFully(bytes, 0, bytes.size, offset)

    private fun writeFully(bytes: ByteArray, start: Int, length: Int, offset: Long) {
        var done = 0
        while (done < length) {
            val count = try {
                Os.pwrite(fd, bytes, start + done, length - done, offset + done)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw e
            }
            if (count <= 0) throw IOException("SAF image positional write made no progress")
            done += count
        }
    }

    val sink: RescueSink = object : RescueSink {
        override val positional = true

        override fun writeSector(lba: ULong, sector: ByteArray) = writeBatch(lba, sector, sector.size, sector.size)

        override fun writeBatch(lba: ULong, bytes: ByteArray, length: Int, sectorSize: Int) {
            require(sectorSize > 0 && length > 0 && length <= bytes.size && length % sectorSize == 0)
            val offset = lba * sectorSize.toULong()
            check(offset <= Long.MAX_VALUE.toULong() && length.toULong() <= Long.MAX_VALUE.toULong() - offset) {
                "rescue offset exceeds signed range"
            }
            writeFully(bytes, 0, length, offset.toLong())
        }

        override fun ensureLength(bytes: Long) {
            require(bytes >= 0)
            if (Os.fstat(fd).st_size < bytes) Os.ftruncate(fd, bytes)
        }
    }

    fun sync() = Os.fsync(fd)

    override fun close() {
        if (closed) return
        closed = true
        descriptor.close()
    }
}

/** SAF placement for [CheckpointPublisher]; all publication semantics stay in the pure class. */
class SafCheckpointStore(
    private val folder: SafBackupFolder,
    private val parent: Uri,
) : CheckpointStore {
    override fun create(name: String): String =
        folder.createExclusiveFile(parent, name, "text/plain").toString()

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
    private val image: SafPositionalImage,
    private val checkpoints: CheckpointPublisher,
) : RescueArchiveTarget {
    override val sink: RescueSink = image.sink
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
