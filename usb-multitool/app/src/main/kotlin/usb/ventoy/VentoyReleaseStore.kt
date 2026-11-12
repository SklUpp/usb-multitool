package usb.ventoy

import java.io.File
import java.util.Properties

/**
 * App-private home of the last verified Ventoy release.
 *
 * A release is accepted only when its members recompute to this app's independent pin
 * ([VentoyDownloader.requirePinned]). `sha256.txt` inside the directory and an API-supplied
 * archive digest are not trust anchors. Saving writes and verifies a staging copy first;
 * publication keeps a recoverable copy until the new one is in place, and [load] also
 * accepts a fully verified staging directory left by an interrupted publication.
 */
object VentoyReleaseStore {
    internal const val DIR = "ventoy-release"
    internal const val STAGING = "$DIR.staging"
    internal const val BACKUP = "$DIR.old"
    private const val MANIFEST = "release.properties"

    data class StoredRelease(
        val assets: VentoyAssets,
        val archiveSha256: String,
        val sourceUrl: String,
        val directory: File,
    )

    /**
     * A save failed after it had already changed the readable store. [stored] is the
     * release [load] returns now; it may differ from [requestedRelease]. Callers must
     * replace their provenance with [stored] and surface the failure as a warning.
     */
    open class StoreChangedException(
        val stored: StoredRelease,
        val requestedRelease: String,
        cause: Throwable,
    ) : Exception(
        "requested Ventoy $requestedRelease was not saved; stored ${stored.assets.release} remains",
        cause,
    )

    /**
     * The verified release was renamed into place, but the directory fsync failed. The
     * bytes are readable now but may not survive a crash; callers must reload this
     * [stored] provenance and must not report a clean success.
     */
    class PublishedButUnconfirmedException(
        published: StoredRelease,
        requestedRelease: String,
        cause: Throwable,
    ) : StoreChangedException(published, requestedRelease, cause)

    /**
     * Promotion of an interrupted staging copy became the readable store, but its
     * durability or a later step failed. [stored] is the recovered release, not
     * necessarily [requestedRelease].
     */
    class PromotionButUnconfirmedException(
        recovered: StoredRelease,
        requestedRelease: String,
        cause: Throwable,
    ) : StoreChangedException(recovered, requestedRelease, cause)

    /**
     * The store was mutated, then could not be read back. The caller must clear its
     * cached provenance instead of showing a release that may no longer exist.
     */
    class StoreUnknownException(
        val requestedRelease: String,
        cause: Throwable,
    ) : Exception(
        "store state after requested Ventoy $requestedRelease is unknown",
        cause,
    )

    /** Distinguishes a confirmed empty store from one whose bytes failed verification. */
    sealed interface Inspection {
        data object Absent : Inspection
        data object Unknown : Inspection
        data object Corrupt : Inspection
        data class Verified(val stored: StoredRelease) : Inspection
    }

    /** Newest fully verified copy; staging/backup recover a crash without trusting them. */
    fun load(filesDir: File): StoredRelease? =
        (inspect(filesDir) as? Inspection.Verified)?.stored

    /**
     * Fail closed: [Inspection.Absent] requires either a legitimately missing app-private
     * root (before first use) or a directory we listed and found no store entry in.
     * A non-directory or an unreadable/erroring listing is never treated as empty.
     */
    fun inspect(
        filesDir: File,
        listDirectory: (File) -> Array<File>? = { it.listFiles() },
    ): Inspection {
        if (!filesDir.exists()) {
            return when (proveAbsent(filesDir, listDirectory)) {
                true -> Inspection.Absent
                false, null -> Inspection.Unknown
            }
        }
        if (!filesDir.isDirectory) return Inspection.Corrupt
        val entries = try {
            listDirectory(filesDir)
        } catch (failure: Throwable) {
            fatalCauseOrNull(failure)?.let { throw it }
            return Inspection.Unknown
        } ?: return Inspection.Unknown
        val names = entries.mapNotNull { it.name }.toSet()
        val candidates = listOf(DIR, STAGING, BACKUP)
            .filter { it in names }
            .map { File(filesDir, it) }
        if (candidates.isEmpty()) return Inspection.Absent
        for (candidate in candidates) {
            verify(candidate)?.let { return Inspection.Verified(it) }
        }
        return Inspection.Corrupt
    }

    /**
     * `exists() == false` is not proof: an inaccessible directory also reports false.
     * Walk to the nearest ancestor that exists, then require a readable listing that
     * either proves the child name absent (`true`) or shows it is present/inaccessible
     * (`false`). `null` means no ancestor could be checked.
     */
    private fun proveAbsent(
        file: File,
        listDirectory: (File) -> Array<File>?,
    ): Boolean? {
        var child = file
        var parent = child.parentFile
        while (parent != null) {
            if (parent.exists()) {
                val entries = try {
                    listDirectory(parent)
                } catch (failure: Throwable) {
                    fatalCauseOrNull(failure)?.let { throw it }
                    return null
                } ?: return null
                return entries.none { it.name == child.name }
            }
            child = parent
            parent = parent.parentFile
        }
        return null
    }

    /**
     * Writes a verified release and returns the bytes read back from disk. [rename] and
     * [syncDirectory] are seams for host directory fsync and crash-window tests.
     *
     * If the readable store changes and a later step fails, the thrown
     * [StoreChangedException] carries the reloaded release so the caller never keeps
     * provenance that disagrees with disk. Cancellation and fatal errors always propagate.
     */
    fun save(
        filesDir: File,
        download: VentoyDownloader.VentoyReleaseDownload,
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
        syncDirectory: (File) -> Unit = { _ -> },
        createDirectory: (File) -> Boolean = { it.mkdirs() },
    ): StoredRelease {
        val current = File(filesDir, DIR)
        val backup = File(filesDir, BACKUP)
        val staging = File(filesDir, STAGING)
        val before = loadOrNull(filesDir)
        var mutationAttempted = false
        var promoted = false
        try {
            if (staging.exists()) {
                // A verified staging copy from an interrupted publication is the newest
                // good payload. Promote it instead of deleting it before the replacement
                // verifies.
                val recovered = verify(staging)
                if (recovered != null && verify(current) == null) {
                    mutationAttempted = true
                    if (current.exists() && !current.deleteRecursively()) {
                        throw IllegalStateException("cannot clear invalid ${current.path}")
                    }
                    if (!rename(staging, current)) {
                        throw IllegalStateException("cannot recover verified staging ${staging.path}")
                    }
                    promoted = true
                    try {
                        syncDirectory(filesDir)
                    } catch (failure: Throwable) {
                        fatalCauseOrNull(failure)?.let { throw it }
                        throw PromotionButUnconfirmedException(
                            recovered.copy(directory = current),
                            download.assets.release,
                            failure,
                        )
                    }
                }
                if (staging.exists()) {
                    mutationAttempted = true
                    if (!staging.deleteRecursively()) {
                        throw IllegalStateException("cannot clear stale ${staging.path}")
                    }
                }
                syncDirectory(filesDir)
            }
            mutationAttempted = true
            if (!createDirectory(staging)) {
                throw IllegalStateException("cannot create ${staging.path}")
            }
            syncDirectory(filesDir)
            val assets = download.assets
            writeVerified(staging, "boot.img", assets.bootImg)
            writeVerified(staging, "core.img.xz", assets.coreImg)
            writeVerified(staging, "ventoy.disk.img.xz", assets.efiPartImg)
            writeVerified(staging, "version", assets.release.toByteArray())
            val hashes = """
                ${assets.bootImgSha256} *boot.img
                ${assets.coreImgSha256} *core.img.xz
                ${assets.diskImgSha256} *ventoy.disk.img.xz
            """.trimIndent() + "\n"
            writeVerified(staging, "sha256.txt", hashes.toByteArray())
            val manifest = Properties().apply {
                setProperty("release", assets.release)
                setProperty("commit", assets.commit)
                setProperty("archiveSha256", download.archiveSha256)
                setProperty("sourceUrl", download.sourceUrl)
            }
            val manifestText = java.io.StringWriter().also { writer ->
                manifest.store(writer, null)
            }.toString()
            writeVerified(staging, MANIFEST, manifestText.toByteArray())
            // File fsync alone does not make the new directory entries durable.
            syncDirectory(staging)

            // Read back through the strict loader plus the independent pin. A digest that
            // does not match never becomes the persistent release.
            val verified = verify(staging)
                ?: throw IllegalStateException("saved Ventoy release did not verify")
            require(verified.assets.release == assets.release) {
                "saved Ventoy version ${verified.assets.release} != ${assets.release}"
            }
            require(
                verified.archiveSha256.equals(download.archiveSha256, ignoreCase = true) &&
                    verified.sourceUrl == download.sourceUrl,
            ) {
                "saved Ventoy provenance did not read back"
            }

            if (current.exists()) {
                if (backup.exists()) {
                    // Remove the older backup only while `current` is itself valid. If
                    // current is corrupt, the backup is the only good copy and must stay.
                    mutationAttempted = true
                    if (verify(current) != null) {
                        if (!backup.deleteRecursively()) {
                            throw IllegalStateException("cannot clear ${backup.path}")
                        }
                    } else if (!current.deleteRecursively()) {
                        throw IllegalStateException("cannot clear ${current.path}")
                    }
                    syncDirectory(filesDir)
                }
                mutationAttempted = true
                if (current.exists() && !rename(current, backup)) {
                    throw IllegalStateException("cannot replace ${current.path}")
                }
                syncDirectory(filesDir)
            }
            mutationAttempted = true
            if (!rename(staging, current)) {
                if (!current.exists() && backup.exists() && rename(backup, current)) {
                    // Restored from backup: make that directory entry durable too.
                    syncDirectory(filesDir)
                }
                throw IllegalStateException("cannot publish ${current.path}")
            }
            val published = verified.copy(directory = current)
            try {
                syncDirectory(filesDir)
            } catch (failure: Throwable) {
                fatalCauseOrNull(failure)?.let { throw it }
                // The bytes are readable and verified, but a crash could lose the new
                // directory entry. Never report a clean success with stale provenance.
                throw PublishedButUnconfirmedException(
                    published,
                    download.assets.release,
                    failure,
                )
            }
            // The new current is published and durable. Removing the now-redundant old
            // copy is cleanup: a failure here must not turn a successful publish into a
            // reported download failure with stale UI provenance. The next save retries.
            if (backup.exists()) {
                mutationAttempted = true
                cleanupBestEffort { backup.deleteRecursively() }
                cleanupBestEffort { syncDirectory(filesDir) }
            }
            return verified.copy(directory = current)
        } catch (failure: Throwable) {
            // Keep a fully verified staging copy; it can still be promoted on restart.
            if (staging.exists() && verify(staging) == null) {
                cleanupBestEffort { staging.deleteRecursively() }
            }
            fatalCauseOrNull(failure)?.let { throw it }
            if (failure is StoreChangedException) throw failure
            val after = loadOrNull(filesDir)
            if (after != null && (mutationAttempted || !sameProvenance(before, after))) {
                throw StoreChangedException(after, download.assets.release, failure)
            }
            if (after == null && mutationAttempted) {
                throw StoreUnknownException(download.assets.release, failure)
            }
            throw failure
        }
    }

    /** Revalidates the exact confirmed provenance against the bytes on disk. */
    fun requireMatches(
        stored: StoredRelease,
        provenance: VentoyReleaseProvenance,
    ): StoredRelease {
        VentoyDownloader.requirePinned(stored.assets)
        if (stored.assets.release != provenance.release ||
            !stored.archiveSha256.equals(provenance.archiveSha256, ignoreCase = true) ||
            stored.sourceUrl != provenance.sourceUrl
        ) {
            throw VentoyAssetsMissingException(
                "Confirmed Ventoy ${provenance.release} is no longer the stored release; " +
                    "re-open the confirmation before writing",
            )
        }
        return stored
    }

    /** Fatal errors and cancellation must bypass every normal failure/warning path. */
    fun fatalCauseOrNull(failure: Throwable): Throwable? {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>(),
        )
        var cause: Throwable? = failure
        while (cause != null) {
            if (cause is java.util.concurrent.CancellationException || cause is Error) return cause
            // A malformed cause cycle must not spin forever; unknown is still not fatal.
            if (!seen.add(cause)) return null
            cause = cause.cause
        }
        return null
    }

    /** Load without turning cancellation/fatal errors into "no release". */
    private fun loadOrNull(filesDir: File): StoredRelease? =
        try {
            load(filesDir)
        } catch (failure: Throwable) {
            fatalCauseOrNull(failure)?.let { throw it }
            null
        }

    /** Best effort for ordinary IO, but cancellation/fatal errors still propagate. */
    private inline fun cleanupBestEffort(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            fatalCauseOrNull(failure)?.let { throw it }
        }
    }

    private fun sameProvenance(left: StoredRelease?, right: StoredRelease): Boolean =
        left != null &&
            left.assets.release == right.assets.release &&
            left.archiveSha256.equals(right.archiveSha256, ignoreCase = true) &&
            left.sourceUrl == right.sourceUrl

    private fun writeVerified(dir: File, name: String, bytes: ByteArray) {
        val file = File(dir, name)
        file.outputStream().use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun verify(dir: File): StoredRelease? {
        if (!dir.isDirectory) return null
        return try {
            val manifest = File(dir, MANIFEST).inputStream().use { input ->
                Properties().apply { load(input) }
            }
            val release = manifest.getProperty("release")?.trim().orEmpty()
            val archiveSha = manifest.getProperty("archiveSha256")?.trim().orEmpty()
            val sourceUrl = manifest.getProperty("sourceUrl")?.trim().orEmpty()
            val assets = VentoyAssets.loadFromDirectory(dir, strict = true)
            val source = VentoyDownloader.requirePinned(assets)
            require(source.release == release) {
                "version file ${assets.release} != manifest $release"
            }
            require(source.archiveSha256.equals(archiveSha, ignoreCase = true)) {
                "archive digest $archiveSha is not the independently pinned ${source.archiveSha256}"
            }
            StoredRelease(assets, archiveSha, sourceUrl, dir)
        } catch (failure: Throwable) {
            fatalCauseOrNull(failure)?.let { throw it }
            null
        }
    }
}
