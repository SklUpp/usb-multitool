package usb.diagnostics

import java.io.IOException

/**
 * Document operations used by [CheckpointPublisher]. Implementations must create a new
 * document per [create], never overwrite an existing document, and make [writeAndSync]
 * durable before returning.
 */
interface CheckpointStore {
    /** Opaque document handle; creation must fail rather than replace an existing name. */
    fun create(name: String): String

    fun writeAndSync(document: String, text: String)

    fun readText(document: String, maxChars: Int): String

    fun delete(document: String)
}

private const val PROBE_TEXT = "usb-write-probe"

/**
 * Fail-closed capability probe run before a rescue image starts: the store must create,
 * write+fsync and read back the same bytes. A provider that supports only buffered writes
 * (or readback truncates) cannot host a trustworthy archive.
 */
fun CheckpointStore.probeWriteSupport(name: String) {
    val document = create(name)
    try {
        writeAndSync(document, PROBE_TEXT)
        val readBack = readText(document, PROBE_TEXT.length)
        require(readBack == PROBE_TEXT) {
            "provider did not return the bytes written; fsync/read capability unsupported"
        }
    } finally {
        runCatching { delete(document) }
    }
}

/**
 * Immutable map generations. Publication order:
 * 1. create a fresh `<base>.<n>`;
 * 2. write + fsync it;
 * 3. read it back and compare;
 * 4. only then remove an older generation, always keeping the previous whole map.
 *
 * A failure (ENOSPC, cancel, crash, truncated write) happens before step 4, so the
 * previous valid checkpoint remains untouched. [publishFinal] creates the canonical
 * `<base>` as another fresh document; old generations survive until it is verified.
 */
class CheckpointPublisher(
    private val store: CheckpointStore,
    private val baseName: String = BackupArchive.MAP_NAME,
    private val keepGenerations: Int = 2,
) {
    private var nextGeneration = 1
    private val published = ArrayList<Pair<Int, String>>()
    private var lastText: String? = null

    fun publish(map: RescueMap) {
        val text = map.encode()
        if (text == lastText) return
        val generation = nextGeneration++
        val document = store.create("$baseName.$generation")
        store.writeAndSync(document, text)
        val readBack = store.readText(document, RescueMap.MAX_ENCODED_CHARS)
        if (readBack != text) {
            throw IOException("checkpoint $baseName.$generation failed readback")
        }
        lastText = text
        published.add(0, generation to document)
        trim()
    }

    /** Publish canonical `<base>`; keep one verified generation as fallback. */
    fun publishFinal(): String? {
        val text = lastText ?: return null
        val canonical = store.create(baseName)
        store.writeAndSync(canonical, text)
        val readBack = store.readText(canonical, RescueMap.MAX_ENCODED_CHARS)
        if (readBack != text) {
            throw IOException("final map $baseName failed readback")
        }
        // Keep the checkpoint generations (at most keepGenerations) as fallback.
        return canonical
    }

    private fun trim() {
        while (published.size > keepGenerations) {
            val (_, old) = published.removeAt(published.lastIndex)
            runCatching { store.delete(old) }
        }
    }
}
