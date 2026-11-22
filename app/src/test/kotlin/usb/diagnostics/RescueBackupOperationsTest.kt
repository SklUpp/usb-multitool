package usb.diagnostics

import java.nio.ByteBuffer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import usb.device.DiskException
import usb.device.UsbDisk
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.testutil.RecordingDisk

private typealias CopySeam =
    (UsbDisk, RescueSink, RescueMap, (RescueMap) -> Unit, DiskOperation) -> RescueMap

/**
 * Fault-injection for the outer backup durable boundary (final sync / checkpoint).
 * The engine seam writes through the real [RescueSink] and checkpoint callbacks, so these
 * tests run on the host JVM: no native sf-core, no assumptions, real file bytes.
 */
class RescueBackupOperationsTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rescue-backup-op").toFile()
    }

    @After
    fun tearDown() {
        dir.walkBottomUp().forEach { it.delete() }
    }

    private fun tinyMap(disk: RecordingDisk): RescueMap =
        RescueMap.forCopy(
            sectorCount = disk.geometry.sectorCount,
            sectorSize = disk.geometry.sectorSize,
            identity = "test-stick",
        )

    /**
     * Host replacement for [Rescue.copy]: paints the map and writes sector bytes through
     * the real sink wrapper, including the engine's final [RescueSink.ensureLength] pass.
     */
    private fun hostEngine(
        disk: RecordingDisk,
        skipWrites: Set<Int> = emptySet(),
    ): CopySeam =
        { _, sink, map, checkpoint, operation ->
            val sectorSize = disk.geometry.sectorSize
            try {
                for (i in 0 until disk.geometry.sectorCount.toInt()) {
                    if (i in skipWrites) {
                        map.markFailed(i.toULong(), 1UL)
                    } else {
                        val data = ByteArray(sectorSize) { (i + 1).toByte() }
                        sink.writeBatch(i.toULong(), data, data.size, sectorSize)
                        map.markCopied(i.toULong(), 1UL)
                    }
                    checkpoint(map.snapshot())
                }
            } finally {
                sink.ensureLength(disk.geometry.capacityBytes.toLong())
            }
            when (operation.state) {
                is OperationState.Queued, is OperationState.Running ->
                    operation.state =
                        if (map.failed.isNotEmpty() || map.partial) {
                            OperationState.Partial
                        } else {
                            OperationState.Completed
                        }
                else -> Unit
            }
            map
        }

    @Test
    fun productionDefaultSeamIsRescueCopy() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val op = DiskOperation(name = "rescue-backup")
        // Rescue.copy rejects a RESTORE map before it touches the native engine, so this
        // proves the untouched default path without requiring sf-core on the host JVM.
        val restoreMap = RescueMap.forRestore(4UL, sectorSize = 512, identity = "test-stick")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                RescueBackupOperations.copy(
                    disk = disk,
                    image = File(dir, "rescue-default.img"),
                    mapFile = File(dir, "rescue-default.map"),
                    map = restoreMap,
                    operation = op,
                )
            }

        assertTrue("default engine must be Rescue.copy: ${failure.message}", "COPY map" in failure.message.orEmpty())
        assertEquals(OperationState.Queued, op.state)
        assertFalse(op.commitReached)
    }

    @Test
    fun cleanBackupWritesDenseImageAndPublishesMapBeforeCommit() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val image = File(dir, "rescue-ok.img")
        val mapFile = File(dir, "rescue-ok.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")
        var finalSyncs = 0
        var finalCheckpoints = 0
        var sawCommitBeforeCheckpoint = false

        RescueBackupOperations.copy(
            disk = disk,
            image = image,
            mapFile = mapFile,
            map = map,
            operation = op,
            onFinalImageSync = {
                finalSyncs++
                it.fd.sync()
            },
            onFinalCheckpoint = { snapshot, file ->
                if (op.commitReached) sawCommitBeforeCheckpoint = true
                finalCheckpoints++
                RescueCheckpoint.save(snapshot, file)
            },
            engine = hostEngine(disk),
        )

        assertEquals(OperationState.Completed, op.state)
        assertTrue(op.commitReached)
        assertTrue(op.mutationAttempted)
        assertEquals(1, finalSyncs)
        assertEquals(1, finalCheckpoints)
        assertFalse(
            "commitReached must not flip before outer final checkpoint returns",
            sawCommitBeforeCheckpoint,
        )

        val imageBytes = image.readBytes()
        assertEquals(4 * 512, imageBytes.size)
        assertEquals(1.toByte(), imageBytes[0])
        assertEquals(2.toByte(), imageBytes[512])

        val saved = RescueMap.decode(mapFile.readText())
        assertEquals(MapKind.COPY, saved.kind)
        assertEquals("test-stick", saved.identity)
        assertFalse(saved.partial)
        assertEquals(4UL, saved.copied.sumOf { it.last - it.first + 1UL })
    }

    @Test
    fun midReadFailureLeavesFullMapAndZeroDataOnlyAtFailedSector() {
        val sectorSize = 512
        val failedLba = 2UL
        val disk = object : RecordingDisk(sectorCount = 5UL, sectorSize = sectorSize) {
            override fun read(lba: ULong, destination: java.nio.ByteBuffer) {
                if (lba == failedLba) {
                    throw DiskException.SenseError(0x70, 0x03, 0x11, 0x00)
                }
                destination.put(ByteArray(destination.remaining()) { (lba + 1UL).toByte() })
            }
        }
        val image = File(dir, "rescue-read-middle.img")
        val mapFile = File(dir, "rescue-read-middle.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")

        RescueBackupOperations.copy(
            disk = disk,
            image = image,
            mapFile = mapFile,
            map = map,
            operation = op,
            engine = { _, sink, current, checkpoint, _ ->
                for (lba in 0UL until current.sectorCount) {
                    val bytes = ByteBuffer.allocate(sectorSize)
                    try {
                        disk.read(lba, bytes)
                        bytes.flip()
                        sink.writeSector(lba, bytes.array())
                        current.markCopied(lba, 1UL)
                    } catch (_: DiskException.SenseError) {
                        current.markFailed(lba, 1UL)
                    }
                    checkpoint(current.snapshot())
                }
                sink.ensureLength(disk.geometry.capacityBytes.toLong())
                current
            },
        )

        assertEquals(RescueStatus.COPIED, map.statusAt(0UL))
        assertEquals(RescueStatus.COPIED, map.statusAt(1UL))
        assertEquals(RescueStatus.FAILED, map.statusAt(2UL))
        assertEquals(RescueStatus.COPIED, map.statusAt(3UL))
        assertEquals(RescueStatus.COPIED, map.statusAt(4UL))
        assertEquals(listOf(2UL until 3UL), map.failed)
        assertTrue(map.copied.contains(0UL until 2UL))
        assertTrue(map.copied.contains(3UL until 5UL))
        val bytes = image.readBytes()
        assertEquals(5 * sectorSize, bytes.size)
        assertTrue(bytes.copyOfRange(2 * sectorSize, 3 * sectorSize).all { it == 0.toByte() })
        assertEquals(1.toByte(), bytes[0])
        assertEquals(2.toByte(), bytes[sectorSize])
        assertEquals(4.toByte(), bytes[3 * sectorSize])
        assertEquals(5.toByte(), bytes[4 * sectorSize])
        assertEquals(OperationState.Partial, op.state)
    }

    @Test
    fun failedSectorsStayZeroFilledThroughEnsureLength() {
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val image = File(dir, "rescue-gap.img")
        val mapFile = File(dir, "rescue-gap.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")

        RescueBackupOperations.copy(
            disk = disk,
            image = image,
            mapFile = mapFile,
            map = map,
            operation = op,
            engine = hostEngine(disk, skipWrites = setOf(6, 7)),
        )

        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
        assertTrue(op.mutationAttempted)

        // ensureLength has to grow the image after the last written sector (5).
        val imageBytes = image.readBytes()
        assertEquals(8 * 512, imageBytes.size)
        assertTrue(imageBytes.copyOfRange(6 * 512, 8 * 512).all { it == 0.toByte() })

        val saved = RescueMap.decode(mapFile.readText())
        assertEquals(2UL, saved.failed.sumOf { it.last - it.first + 1UL })
        assertTrue(saved.partial)
    }

    @Test
    fun midCopyCheckpointFailureAfterImageBytesIsPartialNotFailed() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val image = File(dir, "rescue-mid.img")
        // A regular file where the map directory must be: RescueCheckpoint.save fails
        // inside the mid-copy callback, not at the final one.
        val notADir = File(dir, "blocked").apply { writeText("x") }
        val mapFile = File(notADir, "rescue.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")

        val failure =
            assertThrows(IOException::class.java) {
                RescueBackupOperations.copy(
                    disk = disk,
                    image = image,
                    mapFile = mapFile,
                    map = map,
                    operation = op,
                    engine = hostEngine(disk),
                )
            }

        assertSame(failure, op.failureCause)
        assertTrue("image bytes are a durable side-effect", op.mutationAttempted)
        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
        assertTrue(image.length() > 0L)
        assertFalse(mapFile.exists())
    }

    @Test
    fun finalCheckpointFailureAfterImageBytesIsPartialNotFailed() {
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val image = File(dir, "rescue.img")
        val mapFile = File(dir, "rescue.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")
        val boom = IOException("final checkpoint publish failed")

        try {
            RescueBackupOperations.copy(
                disk = disk,
                image = image,
                mapFile = mapFile,
                map = map,
                operation = op,
                onFinalCheckpoint = { _, _ -> throw boom },
                engine = hostEngine(disk),
            )
            fail("expected checkpoint failure to propagate")
        } catch (e: IOException) {
            assertSame(boom, e)
        }

        assertTrue("image bytes are a durable side-effect", op.mutationAttempted)
        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
        assertSame(boom, op.failureCause)
        assertTrue("partial image must remain for diagnosis", image.length() > 0L)
    }

    @Test
    fun completedEnginePublishesCanonicalOnlyAfterDurableBoundary() {
        val disk = RecordingDisk(sectorCount = 2UL, sectorSize = 512)
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")
        val sink = java.io.ByteArrayOutputStream()
        var published = 0

        RescueBackupOperations.copy(
            disk = disk,
            sink = sink.asRescueSink(512),
            map = map,
            operation = op,
            onCheckpoint = {},
            onFinalImageSync = {},
            onFinalCheckpoint = {},
            onFinalClose = {},
            onPublishCanonical = { published++ },
            engine = hostEngine(disk),
        )

        assertEquals(OperationState.Completed, op.state)
        assertEquals(1, published)
        assertTrue(op.commitReached)
    }

    @Test
    fun completedEngineCloseFailureDowngradesToPartial() {
        val disk = RecordingDisk(sectorCount = 2UL, sectorSize = 512)
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")
        val boom = IOException("final close failed")
        val sink = java.io.ByteArrayOutputStream()

        val failure =
            assertThrows(IOException::class.java) {
                RescueBackupOperations.copy(
                    disk = disk,
                    sink = sink.asRescueSink(512),
                    map = map,
                    operation = op,
                    onCheckpoint = {},
                    onFinalImageSync = {},
                    onFinalCheckpoint = {},
                    onFinalClose = { throw boom },
                    engine = hostEngine(disk),
                )
            }

        assertSame(boom, failure)
        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
        assertSame(boom, op.failureCause)
    }

    @Test
    fun finalImageSyncFailureAfterImageBytesIsPartialNotFailed() {
        val disk = RecordingDisk(sectorCount = 8UL, sectorSize = 512)
        val image = File(dir, "rescue-sync.img")
        val mapFile = File(dir, "rescue-sync.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")
        val boom = IOException("final image fd.sync failed")

        try {
            RescueBackupOperations.copy(
                disk = disk,
                image = image,
                mapFile = mapFile,
                map = map,
                operation = op,
                onFinalImageSync = { throw boom },
                engine = hostEngine(disk),
            )
            fail("expected sync failure to propagate")
        } catch (e: IOException) {
            assertSame(boom, e)
        }

        assertTrue(op.mutationAttempted)
        assertEquals(OperationState.Partial, op.state)
        assertFalse(op.commitReached)
        assertSame(boom, op.failureCause)
    }

    @Test
    fun extentOnlyFailureBeforeFirstWriteIsPartialWithoutDiskMutation() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val image = File(dir, "rescue-extent.img")
        val mapFile = File(dir, "rescue-extent.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")
        val disconnect = DiskException.Disconnected()

        val failure =
            assertThrows(DiskException.Disconnected::class.java) {
                RescueBackupOperations.copy(
                    disk = disk,
                    image = image,
                    mapFile = mapFile,
                    map = map,
                    operation = op,
                    engine = { _, sink, _, _, _ ->
                        sink.ensureLength(disk.geometry.capacityBytes.toLong())
                        throw disconnect
                    },
                )
            }

        assertSame(disconnect, failure)
        assertSame(disconnect, op.failureCause)
        assertEquals(OperationState.Partial, op.state)
        assertFalse("image extent growth does not write the disk", op.mutationAttempted)
        assertFalse(op.commitReached)
        assertEquals(disk.geometry.capacityBytes.toLong(), image.length())
    }

    @Test
    fun preexistingImageFailureLeavesOperationUntouched() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val image =
            File(dir, "rescue-pre.img").apply {
                writeBytes(ByteArray(disk.geometry.capacityBytes.toInt()))
            }
        val mapFile = File(dir, "rescue-pre.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")
        val disconnect = DiskException.Disconnected()

        val failure =
            assertThrows(DiskException.Disconnected::class.java) {
                RescueBackupOperations.copy(
                    disk = disk,
                    image = image,
                    mapFile = mapFile,
                    map = map,
                    operation = op,
                    engine = { _, sink, _, _, _ ->
                        sink.ensureLength(disk.geometry.capacityBytes.toLong())
                        throw disconnect
                    },
                )
            }

        assertSame(disconnect, failure)
        assertEquals(OperationState.Queued, op.state)
        assertFalse(op.mutationAttempted)
        assertFalse(op.commitReached)
        assertNull(op.failureCause)
        assertEquals(disk.geometry.capacityBytes.toLong(), image.length())
    }

    @Test
    fun midCopyFailurePreservesUserOperationStateAndCause() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val image = File(dir, "rescue-user.img")
        val mapFile = File(dir, "rescue-user.map")
        val map = tinyMap(disk)
        val prior = IllegalStateException("cancelled by user")
        val op = DiskOperation(name = "rescue-backup")
        op.state = OperationState.Partial
        op.failureCause = prior
        val disconnect = DiskException.Disconnected()

        val failure =
            assertThrows(DiskException.Disconnected::class.java) {
                RescueBackupOperations.copy(
                    disk = disk,
                    image = image,
                    mapFile = mapFile,
                    map = map,
                    operation = op,
                    engine = { _, _, _, _, _ -> throw disconnect },
                )
            }

        assertSame(disconnect, failure)
        assertEquals(OperationState.Partial, op.state)
        assertSame(prior, op.failureCause)
        assertFalse(op.commitReached)
    }
}
