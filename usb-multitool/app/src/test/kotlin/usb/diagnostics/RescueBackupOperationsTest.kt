package usb.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import usb.device.DiskException
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.testutil.RecordingDisk

/**
 * Fault-injection for the outer backup durable boundary (final sync / checkpoint).
 * Image bytes may already exist — classification must be Partial, never silent Failed/Completed.
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
    fun cleanBackupSetsCommitReachedOnlyAfterFinalCheckpoint() {
        val disk = RecordingDisk(sectorCount = 4UL, sectorSize = 512)
        val image = File(dir, "rescue-ok.img")
        val mapFile = File(dir, "rescue-ok.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")
        var checkpointCalls = 0
        var sawCommitBeforeCheckpoint = false

        RescueBackupOperations.copy(
            disk = disk,
            image = image,
            mapFile = mapFile,
            map = map,
            operation = op,
            onFinalCheckpoint = { snapshot, file ->
                if (op.commitReached) sawCommitBeforeCheckpoint = true
                checkpointCalls++
                RescueCheckpoint.save(snapshot, file)
            },
        )

        assertEquals(OperationState.Completed, op.state)
        assertTrue(op.commitReached)
        assertTrue(op.mutationAttempted)
        assertTrue(checkpointCalls >= 1)
        assertFalse(
            "commitReached must not flip before outer final checkpoint returns",
            sawCommitBeforeCheckpoint,
        )
        assertTrue(mapFile.exists())
        assertTrue(image.length() > 0L)
    }

    @Test
    fun preImageDisconnectDoesNotSetCommitReached() {
        val disk =
            object : RecordingDisk(sectorCount = 4UL, sectorSize = 512) {
                override fun read(lba: ULong, destination: java.nio.ByteBuffer) {
                    throw DiskException.Disconnected()
                }
            }
        val image = File(dir, "rescue-pre.img")
        val mapFile = File(dir, "rescue-pre.map")
        val map = tinyMap(disk)
        val op = DiskOperation(name = "rescue-backup")

        try {
            RescueBackupOperations.copy(
                disk = disk,
                image = image,
                mapFile = mapFile,
                map = map,
                operation = op,
            )
            fail("expected disconnect")
        } catch (_: DiskException.Disconnected) {
        }

        assertFalse(op.commitReached)
    }
}
