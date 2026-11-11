package usb.diagnostics

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The archive may never truncate the last whole map. These tests inject the failures the
 * SAF provider cannot promise away: ENOSPC, a partial write, a bad read-back, and a crash
 * before the generation is accepted.
 */
class CheckpointPublisherTest {
    private class FakeStore : CheckpointStore {
        val documents = LinkedHashMap<String, String>()
        var failWrite = false
        var syncFailure = false
        var truncateReadback = false

        override fun create(name: String): String {
            if (documents.containsKey(name)) throw IOException("name exists: $name")
            documents[name] = ""
            return name
        }

        override fun writeAndSync(document: String, text: String) {
            if (failWrite) {
                // Leave a truncated document, exactly as an interrupted provider write would.
                documents[document] = text.take(text.length / 2)
                throw IOException("ENOSPC")
            }
            if (syncFailure) {
                documents[document] = text
                throw IOException("fsync unsupported")
            }
            documents[document] = text
        }

        override fun readText(document: String, maxChars: Int): String {
            val text = documents[document] ?: throw IOException("missing $document")
            return if (truncateReadback) text.take(text.length / 2) else text
        }

        override fun delete(document: String) {
            documents.remove(document)
        }
    }

    private fun map(): RescueMap =
        RescueMap.forCopy(sectorCount = 8UL, sectorSize = 512, identity = "test")

    @Test
    fun publishedGenerationsKeepThePreviousWholeMap() {
        val store = FakeStore()
        val publisher = CheckpointPublisher(store)
        val map = map()
        publisher.publish(map)
        map.markCopied(0UL, 1UL)
        publisher.publish(map)
        map.markCopied(1UL, 1UL)
        publisher.publish(map)

        assertTrue(store.documents.containsKey("disk.map.2"))
        assertTrue(store.documents.containsKey("disk.map.3"))
        assertFalse(store.documents.containsKey("disk.map.1"))
    }

    @Test
    fun enospcDoesNotModifyThePreviousCheckpoint() {
        val store = FakeStore()
        val publisher = CheckpointPublisher(store)
        val map = map()
        publisher.publish(map)
        val previous = store.documents.getValue("disk.map.1")
        store.failWrite = true
        map.markCopied(2UL, 1UL)

        try {
            publisher.publish(map)
            fail("expected ENOSPC")
        } catch (_: IOException) {
            // expected
        }

        assertEquals(previous, store.documents.getValue("disk.map.1"))
        assertFalse(store.documents.containsKey("disk.map"))
    }

    @Test
    fun truncatedReadbackLeavesThePreviousCheckpointIntact() {
        val store = FakeStore()
        val publisher = CheckpointPublisher(store)
        val map = map()
        publisher.publish(map)
        val previous = store.documents.getValue("disk.map.1")
        store.truncateReadback = true
        map.markCopied(3UL, 1UL)

        try {
            publisher.publish(map)
            fail("expected readback failure")
        } catch (_: IOException) {
            // expected
        }

        assertEquals(previous, store.documents.getValue("disk.map.1"))
    }

    @Test
    fun syncFailureDoesNotDeleteThePreviousCheckpoint() {
        val store = FakeStore()
        val publisher = CheckpointPublisher(store)
        val map = map()
        publisher.publish(map)
        val previous = store.documents.getValue("disk.map.1")
        store.syncFailure = true
        map.markCopied(5UL, 1UL)

        runCatching { publisher.publish(map) }

        assertEquals(previous, store.documents.getValue("disk.map.1"))
    }

    @Test
    fun crashBeforeAcceptanceDoesNotDeleteThePreviousCheckpoint() {
        val store = FakeStore()
        val publisher = CheckpointPublisher(store)
        val map = map()
        publisher.publish(map)
        val previous = store.documents.getValue("disk.map.1")
        store.failWrite = true
        map.markCopied(4UL, 1UL)

        runCatching { publisher.publish(map) }

        assertEquals(previous, store.documents.getValue("disk.map.1"))
    }

    @Test
    fun finalPublishesCanonicalAndKeepsGenerations() {
        val store = FakeStore()
        val publisher = CheckpointPublisher(store)
        val map = map()
        publisher.publish(map)
        map.markCopied(0UL, 1UL)
        publisher.publish(map)

        val canonical = publisher.publishFinal()

        assertEquals("disk.map", canonical)
        assertEquals(store.documents.getValue("disk.map.2"), store.documents["disk.map"])
        assertTrue(store.documents.containsKey("disk.map.2"))
        assertTrue(store.documents.containsKey("disk.map.1"))
    }

    @Test
    fun failedFinalWriteKeepsGenerationsForFallback() {
        val store = FakeStore()
        val publisher = CheckpointPublisher(store)
        val map = map()
        publisher.publish(map)
        val previous = store.documents.getValue("disk.map.1")
        store.failWrite = true

        try {
            publisher.publishFinal()
            fail("expected ENOSPC")
        } catch (_: IOException) {
            // expected
        }

        assertEquals(previous, store.documents.getValue("disk.map.1"))
        assertFalse(store.documents.containsKey("disk.map.2"))
    }

    @Test
    fun probeSucceedsOnlyAfterWriteAndReadback() {
        val store = FakeStore()
        store.probeWriteSupport("probe")
        assertFalse(store.documents.containsKey("probe"))
    }

    @Test
    fun probeRejectsUnsupportedWriteOrSync() {
        val store = FakeStore()
        store.failWrite = true
        var failure: Throwable? = null
        try {
            store.probeWriteSupport("probe")
        } catch (t: Throwable) {
            failure = t
        }
        assertTrue(failure is IOException)
    }

    @Test
    fun probeRejectsUnsupportedSync() {
        val store = FakeStore()
        store.syncFailure = true
        var failure: Throwable? = null
        try {
            store.probeWriteSupport("probe")
        } catch (t: Throwable) {
            failure = t
        }
        assertTrue(failure is IOException)
    }

    @Test
    fun probeRejectsReadbackMismatch() {
        val store = FakeStore()
        store.truncateReadback = true
        var failure: Throwable? = null
        try {
            store.probeWriteSupport("probe")
        } catch (t: Throwable) {
            failure = t
        }
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun identicalCheckpointIsNotRepublished() {
        val store = FakeStore()
        val publisher = CheckpointPublisher(store)
        val map = map()
        publisher.publish(map)
        publisher.publish(map)

        assertEquals(listOf("disk.map.1"), store.documents.keys.toList())
    }
}
