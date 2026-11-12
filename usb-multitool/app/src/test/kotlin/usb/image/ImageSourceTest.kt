package usb.image

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content-URI format selection sniffs the live write stream: short reads must still
 * assemble a 6-byte magic prefix, open failures must abort, and a compressed magic
 * always wins over a mislabeled .iso so gzip/xz bytes are never written raw.
 */
class ImageSourceTest {
    @Test
    fun opaqueUriWithGzipMagicDecodesWithoutDeclaredCompressedSize() {
        val payload = ByteArray(64) { 7 }
        val gzipped = gzip(payload)
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(gzipped) },
                displayName = null,
                size = gzipped.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.GZIP, source.format)
            assertTrue(input.readBytes().contentEquals(payload))
            assertNull(input.decodedSizeHint)
        }
        assertNull(source.declaredDecodedSize)
    }

    @Test
    fun shortReadsStillAssembleGzipMagic() {
        val payload = ByteArray(48) { 9 }
        val gzipped = gzip(payload)
        val source =
            ImageSource.fromContent(
                openRaw = { OneByteAtATimeStream(gzipped) },
                displayName = "msf:1000000123",
                size = gzipped.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.GZIP, source.format)
            assertTrue(input.readBytes().contentEquals(payload))
        }
    }

    @Test
    fun isoNameWithGzipMagicDecodesAsGzip() {
        val payload = ByteArray(32) { 3 }
        val gzipped = gzip(payload)
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(gzipped) },
                displayName = "ubuntu.iso",
                size = gzipped.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.GZIP, source.format)
            assertTrue(input.readBytes().contentEquals(payload))
            assertNull(input.decodedSizeHint)
        }
        assertNull(source.declaredDecodedSize)
    }

    @Test
    fun gzipNameWithRawMagicKeepsGzipAndFailsLoudly() {
        val raw = ByteArray(64) { 1 }
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(raw) },
                displayName = "stick.img.gz",
                size = raw.size.toLong(),
            )
        assertThrows(java.util.zip.ZipException::class.java) {
            source.open().use { it.read() }
        }
        assertEquals(ImageFormat.GZIP, source.format)
    }

    @Test
    fun openFailurePropagatesInsteadOfFallingBackToImg() {
        val source =
            ImageSource.fromContent(
                openRaw = { throw java.io.IOException("resolver denied") },
                displayName = null,
                size = 100L,
            )
        val failure = assertThrows(java.io.IOException::class.java) {
            source.open()
        }
        assertTrue(failure.message!!.contains("resolver denied"))
    }

    @Test
    fun rawImgKeepsOpenableSizeAsDecodedHint() {
        val raw = ByteArray(512) { 1 }
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(raw) },
                displayName = "stick.img",
                size = raw.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.IMG, source.format)
            assertEquals(512L, input.decodedSizeHint)
            assertTrue(input.readBytes().contentEquals(raw))
        }
    }

    @Test
    fun displayNameGzipWithMatchingMagicDecodes() {
        val payload = ByteArray(32) { 3 }
        val gzipped = gzip(payload)
        val source =
            ImageSource.fromContent(
                openRaw = { ByteArrayInputStream(gzipped) },
                displayName = "ubuntu.img.gz",
                size = gzipped.size.toLong(),
            )
        source.open().use { input ->
            assertEquals(ImageFormat.GZIP, source.format)
            assertTrue(input.readBytes().contentEquals(payload))
        }
        assertNull(source.declaredDecodedSize)
    }

    @Test
    fun fromBytesNeverPublishesGzipFooterAsDecodedSize() {
        // ISIZE is mod 2^32 and repeated members each carry their own trailer, so the
        // footer is not a full decoded length: keep the size unknown and let the stream
        // decide when it overflows the selected range.
        val payload = ByteArray(96) { 4 }
        val multiMember = gzip(payload) + gzip(payload)
        val source = ImageSource.fromBytes(multiMember)
        assertEquals(ImageFormat.GZIP, source.format)
        assertNull(source.declaredDecodedSize)
        source.open().use { input ->
            assertNull(input.decodedSizeHint)
            assertTrue(input.readBytes().contentEquals(payload + payload))
        }
    }

    @Test
    fun measureDecodedBytesCountsCompressedPayloadExactly() {
        val payload = ByteArray(6000) { 7 }
        val source = ImageSource.fromBytes(gzip(payload))
        assertEquals(6000L, source.measureDecodedBytes())
    }

    @Test
    fun measureDecodedBytesCountsUnknownRawPayloadExactly() {
        val payload = ByteArray(1234) { 3 }
        val source = ImageSource.fromBytes(payload, format = ImageFormat.IMG, decodedSize = null)
        assertEquals(1234L, source.measureDecodedBytes(chunkBytes = 64))
    }

    @Test
    fun measureDecodedBytesIsCancellable() {
        val source = ImageSource.fromBytes(ByteArray(4096) { 1 })
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            source.measureDecodedBytes(
                isActive = { checks++; checks < 2 },
                chunkBytes = 64,
            )
        }
    }

    @Test
    fun stagedFileWithGzipMagicInIsoNameIsCompressed() {
        val payload = ByteArray(6000) { 5 }
        val file = java.io.File.createTempFile("staged-backup", ".iso")
        try {
            file.writeBytes(gzip(payload))
            val source = ImageSource.fromFile(file)
            source.open().use { assertEquals(ImageFormat.GZIP, source.format) }
            assertNull(source.decodedSizeForRawWrite)
            assertEquals(6000L, source.measureDecoded().bytes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun concatenatedXzStreamsAreAllDecoded() {
        val first = ByteArray(1000) { 1 }
        val second = ByteArray(700) { 2 }
        val combined = xz(first) + xz(second)
        val source = ImageSource.fromBytes(combined)
        val measured = source.measureDecoded()
        assertEquals(1700L, measured.bytes)
        source.open().use { input ->
            assertEquals(1700, input.readBytes().size)
        }
    }

    @Test
    fun measurementStopsAtLimitForDecompressionBomb() {
        val bomb = gzip(ByteArray(1 shl 20) { 3 })
        val source = ImageSource.fromBytes(bomb)
        assertThrows(ImageTooLargeException::class.java) {
            source.measureDecoded(limitBytes = 4096L, chunkBytes = 512)
        }
    }

    @Test
    fun compressedMagicReaderToleratesShortReads() {
        val xz = xz(ByteArray(16) { 1 })
        val shortRead = object : InputStream() {
            var index = 0
            override fun read(): Int = if (index < xz.size) xz[index++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= xz.size) return -1
                b[off] = xz[index++]
                return 1
            }
        }
        assertTrue(ImageFormatNames.readCompressedMagic(shortRead))
    }

    @Test
    fun measurementHandleClosesTrackedStreamOnCancel() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int = -1
            override fun close() {
                closed.countDown()
            }
        }
        val handle = MeasurementHandle()
        handle.attach(stream)
        handle.cancel()
        assertTrue(closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(!handle.isActive())
    }

    @Test
    fun measurementHandleCancelDoesNotBlockOnSlowClose() {
        awaitFreshCloseBudget()
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        try {
            val stream = object : InputStream() {
                override fun read(): Int = -1
                override fun close() {
                    release.await()
                    closed.countDown()
                }
            }
            val handle = MeasurementHandle()
            handle.attach(stream)
            val returned = java.util.concurrent.CountDownLatch(1)
            Thread {
                handle.cancel()
                returned.countDown()
            }.start()
            assertTrue(returned.await(1, java.util.concurrent.TimeUnit.SECONDS))
            // cancel() returned before the blocked close completed.
            assertFalse(
                "slow close must not finish before release",
                closed.await(0, java.util.concurrent.TimeUnit.NANOSECONDS),
            )
            release.countDown()
            assertTrue(closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            // A failed assertion must never leave the worker blocked on this latch.
            release.countDown()
        }
        awaitFreshCloseBudget()
    }

    @Test
    fun measurementHandleClosesStreamAttachedAfterCancel() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int = -1
            override fun close() {
                closed.countDown()
            }
        }
        val handle = MeasurementHandle()
        handle.cancel()
        handle.attach(stream)
        assertTrue(closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun zeroReadMagicReaderThrowsInsteadOfSpinning() {
        val zero = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertThrows(java.io.IOException::class.java) {
            ImageFormatNames.readCompressedMagic(zero)
        }
    }

    @Test
    fun zeroReadMeasurementThrowsInsteadOfSpinning() {
        val source = ImageSource.fromContent(
            openRaw = { ZeroAfterPrefix(ByteArray(6)) },
            displayName = "disk.img",
            size = 6L,
        )
        assertThrows(java.io.IOException::class.java) { source.measureDecoded() }
    }

    @Test
    fun cancelDuringOpenKeepsLeaseAndClosesLateAttachInBackground() {
        awaitFreshCloseBudget()
        val handle = MeasurementHandle()
        val closeEntered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val attachGate = java.util.concurrent.CountDownLatch(1)
        val attachReturned = java.util.concurrent.CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int = -1
            override fun close() {
                closeEntered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        try {
            Thread {
                attachGate.await()
                handle.attach(stream)
                attachReturned.countDown()
            }.start()
            assertTrue(handle.cancel())
            assertEquals(
                "cancel must retain the lease while open() may still return",
                3,
                MeasurementHandle.availableAdmissions(),
            )
            attachGate.countDown()
            assertTrue(
                "late attach must not block on close",
                attachReturned.await(1, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertTrue(closeEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(3, MeasurementHandle.availableAdmissions())
        } finally {
            attachGate.countDown()
            release.countDown()
        }
        awaitFreshCloseBudget()
    }

    @Test
    fun openFailureAndAbandonReleaseAdmissionWithoutAttach() {
        awaitFreshCloseBudget()
        val baseline = MeasurementHandle.availableAdmissions()
        val failed = MeasurementHandle()
        val source =
            ImageSource.fromContent(
                openRaw = { throw java.io.IOException("provider open failed") },
                displayName = "broken.img",
                size = 512L,
            )
        assertThrows(java.io.IOException::class.java) {
            source.measureDecoded(handle = failed)
        }
        assertEquals(baseline, MeasurementHandle.availableAdmissions())

        // Scope destroyed before the coroutine opens: cancel retains the reservation,
        // abandon (host completion, outside the cancelled scope) returns it.
        val abandoned = MeasurementHandle()
        assertEquals(baseline - 1, MeasurementHandle.availableAdmissions())
        abandoned.cancel()
        assertEquals(baseline - 1, MeasurementHandle.availableAdmissions())
        abandoned.abandon()
        assertEquals(baseline, MeasurementHandle.availableAdmissions())
    }

    @Test
    fun cancelledScopeCompletionStillAbandonsHandle() {
        awaitFreshCloseBudget()
        val baseline = MeasurementHandle.availableAdmissions()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handle = MeasurementHandle()
        val done = java.util.concurrent.CountDownLatch(1)
        val job = scope.launch { awaitCancellation() }
        job.invokeOnCompletion {
            // Mirror the host completion: release must not be launched in [scope].
            handle.cancel()
            handle.abandon()
            done.countDown()
        }
        scope.cancel()
        assertTrue(done.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(baseline, MeasurementHandle.availableAdmissions())
    }

    @Test
    fun blockedProviderClosesAreBoundedAndRetried() {
        awaitFreshCloseBudget()
        val entered = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val first = MeasurementHandle()
            first.attach(BlockingCloseStream(entered, release))
            assertTrue(first.cancel())
            val second = MeasurementHandle()
            second.attach(BlockingCloseStream(entered, release))
            assertTrue(second.cancel())
            assertTrue("both close workers should start", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Every bounded closer slot is now held by a stuck close: fail closed,
            // but keep the stream tracked instead of opening an untracked descriptor.
            val third = MeasurementHandle()
            third.attach(ByteArrayInputStream(byteArrayOf(1)))
            assertFalse(third.cancel())
            assertEquals(1, MeasurementHandle.pendingCloseCount())
            repeat(3) { assertFalse(third.retryDeferredClose()) }
            assertEquals(1, MeasurementHandle.pendingCloseCount())
            val fourth = MeasurementHandle()
            fourth.attach(ByteArrayInputStream(byteArrayOf(2)))
            assertFalse(fourth.cancel())
            assertEquals(2, MeasurementHandle.pendingCloseCount())
            assertEquals(0, MeasurementHandle.availableAdmissions())
            assertThrows(CloseCapacityException::class.java) { MeasurementHandle() }

            release.countDown()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var closed = false
            while (!closed && System.nanoTime() < deadline) {
                MeasurementHandle.retryDeferredCloses()
                closed = third.retryDeferredClose()
                if (!closed) Thread.sleep(10)
            }
            assertTrue("deferred close should run once a slot frees", closed)
            while (MeasurementHandle.pendingCloseCount() > 0 &&
                System.nanoTime() < deadline
            ) {
                MeasurementHandle.retryDeferredCloses()
                Thread.sleep(10)
            }
            assertEquals(0, MeasurementHandle.pendingCloseCount())
            awaitFreshCloseBudget()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun deferredCloseOwnershipIsAtomic() {
        awaitFreshCloseBudget()
        val entered = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val first = MeasurementHandle()
            first.attach(BlockingCloseStream(entered, release))
            assertTrue(first.cancel())
            val second = MeasurementHandle()
            second.attach(BlockingCloseStream(entered, release))
            assertTrue(second.cancel())
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))

            val closes = java.util.concurrent.atomic.AtomicInteger()
            val closed = java.util.concurrent.CountDownLatch(1)
            val deferred = MeasurementHandle()
            deferred.attach(
                object : InputStream() {
                    override fun read(): Int = -1
                    override fun close() {
                        closes.incrementAndGet()
                        Thread.sleep(50)
                        closed.countDown()
                    }
                },
            )
            assertFalse(deferred.cancel())

            release.countDown()
            val done = java.util.concurrent.CountDownLatch(8)
            repeat(8) {
                pool.execute {
                    try {
                        deferred.retryDeferredClose()
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(1, closes.get())
            awaitFreshCloseBudget()
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrentAdmissionIsBounded() {
        awaitFreshCloseBudget()
        val handles =
            java.util.Collections.synchronizedList(
                mutableListOf<MeasurementHandle>(),
            )
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(8)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                pool.execute {
                    try {
                        start.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        try {
                            handles.add(MeasurementHandle())
                        } catch (_: CloseCapacityException) {
                            failures.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(4, handles.size)
            assertEquals(4, failures.get())
        } finally {
            start.countDown()
            pool.shutdownNow()
            handles.forEach {
                it.cancel()
                it.abandon()
            }
        }
        awaitFreshCloseBudget()
    }

    /** Wait until prior tests released every admission token and close worker. */
    private fun awaitFreshCloseBudget() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while ((MeasurementHandle.availableAdmissions() < 4 ||
                MeasurementHandle.availableCloseWorkers() < 2 ||
                MeasurementHandle.pendingCloseCount() > 0) &&
            System.nanoTime() < deadline
        ) {
            MeasurementHandle.retryDeferredCloses()
            Thread.sleep(5)
        }
        assertEquals(4, MeasurementHandle.availableAdmissions())
        assertEquals(2, MeasurementHandle.availableCloseWorkers())
        assertEquals(0, MeasurementHandle.pendingCloseCount())
    }

    /** Wraps cancelled provider closes in a pair of latches. */
    private class BlockingCloseStream(
        private val entered: java.util.concurrent.CountDownLatch,
        private val release: java.util.concurrent.CountDownLatch,
    ) : InputStream() {
        override fun read(): Int = -1
        override fun close() {
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** Serves a fixed prefix, then violates the InputStream contract by returning 0. */
    private class ZeroAfterPrefix(private val prefix: ByteArray) : InputStream() {
        private var index = 0
        override fun read(): Int =
            if (index < prefix.size) prefix[index++].toInt() and 0xFF else 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (index < prefix.size && len > 0) {
                b[off] = prefix[index++]
                return 1
            }
            return 0
        }
    }

    private fun xz(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        org.apache.commons.compress.compressors.xz.XZCompressorOutputStream(out).use { stream ->
            stream.write(data)
        }
        return out.toByteArray()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    /** Forces every read to return at most one byte — covers short-read magic assembly. */
    private class OneByteAtATimeStream(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            val n = read()
            if (n < 0) return -1
            b[off] = n.toByte()
            return 1
        }
    }
}
