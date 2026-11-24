package usb.multitool

import android.content.ContentResolver
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import usb.device.BlockGeometry
import usb.device.UsbDisk
import usb.device.UsbExclusiveClaim
import usb.files.AttachmentPin
import usb.files.FilesOperationRunner
import usb.files.FilesVolumeSession
import usb.files.MountedFilesVolume
import usb.files.UsbVolume
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.operation.OperationState
import usb.service.OperationHandle
import usb.service.OperationSubmitter
import usb.service.OperationToken
import usb.device.FakeBotSession
import usb.ui.AppUiState
import usb.ui.DeviceIdentity
import usb.ui.DevicePresence
import usb.ui.FilesBatchItem
import usb.ui.FilesBatchSelection
import usb.ui.FilesListingState
import usb.ui.PartitionTab
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey

@OptIn(ExperimentalCoroutinesApi::class)
class FilesCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)
    private val intents = CopyOnWriteArrayList<UiIntent>()
    private val logs = CopyOnWriteArrayList<String>()
    private val startedHandles = CopyOnWriteArrayList<Pair<OperationHandle, Boolean>>()
    private val finishedHandles = CopyOnWriteArrayList<OperationHandle>()
    private val tokenCounter = AtomicLong(100)

    private val testPin = AttachmentPin(attachGeneration = 1L, deviceKey = "SanDisk#SN-1")
    private val testIdentity = DeviceIdentity(
        label = "SanDisk",
        capacityBytes = 8_000_000_000uL,
        serialNumber = "SN-1",
        attachGeneration = 1L,
    )

    private lateinit var fakeSession: FilesVolumeSession
    private lateinit var runner: FilesOperationRunner

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeSession = FilesVolumeSession(
            currentAttachment = { testPin },
            openMounted = { partitionId, writable, _, lease ->
                val disk = UsbDisk(FakeBotSession(), BlockGeometry(512, 64UL), lease = lease)
                val vol = object : UsbFsVolume {
                    override fun list(path: String) = emptyList<UsbFsDirEntry>()
                    override fun stat(path: String) = error("unused")
                    override fun statOrNull(path: String) = null
                    override fun mkdir(path: String) = Unit
                    override fun delete(path: String) = Unit
                    override fun rename(from: String, to: String) = Unit
                    override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
                    override fun write(
                        path: String,
                        data: ByteArray,
                        offset: Long,
                        create: Boolean,
                        plannedTotalSize: Long,
                        exclusiveCreate: Boolean,
                    ) = Unit
                    override fun sync() = Unit
                    override fun close() = Unit
                }
                MountedFilesVolume(partitionId, disk, vol, writable).also {
                    it.bindLease(lease)
                }
            },
            claim = UsbExclusiveClaim(),
        )
        runner = FilesOperationRunner(fakeSession)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private class FakeUsbVolume : UsbVolume {
        var lastReadPin: AttachmentPin? = null
        var lastReadPartitionId: String? = null
        var lastWritePin: AttachmentPin? = null
        var lastWritePartitionId: String? = null

        val entriesToList = mutableListOf<UsbFsDirEntry>()

        override fun read(pin: AttachmentPin?, partitionId: String): UsbFsVolume {
            lastReadPin = pin
            lastReadPartitionId = partitionId
            return object : UsbFsVolume {
                override fun list(path: String): List<UsbFsDirEntry> = entriesToList
                override fun stat(path: String) = error("unused")
                override fun statOrNull(path: String) = null
                override fun mkdir(path: String) = Unit
                override fun delete(path: String) = Unit
                override fun rename(from: String, to: String) = Unit
                override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
                override fun write(
                    path: String,
                    data: ByteArray,
                    offset: Long,
                    create: Boolean,
                    plannedTotalSize: Long,
                    exclusiveCreate: Boolean,
                ) = Unit
                override fun sync() = Unit
                override fun close() = Unit
            }
        }

        override fun <T> write(
            pin: AttachmentPin?,
            partitionId: String,
            operation: DiskOperation,
            body: (UsbFsVolume) -> T,
        ): T {
            lastWritePin = pin
            lastWritePartitionId = partitionId
            val dummyVolume = object : UsbFsVolume {
                override fun list(path: String) = emptyList<UsbFsDirEntry>()
                override fun stat(path: String) = error("unused")
                override fun statOrNull(path: String) = null
                override fun mkdir(path: String) = Unit
                override fun delete(path: String) = Unit
                override fun rename(from: String, to: String) = Unit
                override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
                override fun write(
                    path: String,
                    data: ByteArray,
                    offset: Long,
                    create: Boolean,
                    plannedTotalSize: Long,
                    exclusiveCreate: Boolean,
                ) = Unit
                override fun sync() = Unit
                override fun close() = Unit
            }
            return body(dummyVolume)
        }
    }

    private class FakeSubmitter(
        private val coroutineScope: CoroutineScope,
    ) : OperationSubmitter {
        val submitted = mutableListOf<Pair<DiskOperation, OperationToken>>()
        var runSynchronously = true

        override fun submit(operation: DiskOperation, token: OperationToken): OperationHandle {
            submitted += operation to token
            val result = CompletableDeferred<OperationState>()
            if (runSynchronously) {
                coroutineScope.launch {
                    try {
                        operation.run()
                        result.complete(OperationState.Completed)
                    } catch (e: Throwable) {
                        result.complete(OperationState.Failed(e))
                    }
                }
            }
            return object : OperationHandle {
                override val token: OperationToken get() = token
                override val state: OperationState get() = result.getCompleted()
                override suspend fun await(): OperationState = result.await()
                override fun cancel() {
                    result.complete(OperationState.Cancelled)
                }
            }
        }
    }

    private fun coordinator(
        volume: UsbVolume,
        submitter: OperationSubmitter,
        stateProvider: () -> AppUiState,
        contentResolver: ContentResolver? = null,
    ): FilesCoordinator = FilesCoordinator(
        scope = scope,
        volume = volume,
        runner = runner,
        submitter = submitter,
        pin = { testPin },
        state = stateProvider,
        intents = { intents += it },
        log = { logs += it },
        onHandleStarted = { handle, makeCurrent -> startedHandles += handle to makeCurrent },
        onHandleFinished = { handle -> finishedHandles += handle },
        resolver = { contentResolver ?: error("resolver not needed in this test") },
        nextToken = { OperationToken(tokenCounter.incrementAndGet()) },
    )

    @Test
    fun listSuccessPostsFilesListed() = runTest {
        val volume = FakeUsbVolume()
        volume.entriesToList += UsbFsDirEntry(
            name = "file.txt",
            isDirectory = false,
            size = 1234L,
        )
        val submitter = FakeSubmitter(this)

        var currentState = AppUiState(
            presence = DevicePresence.Ready,
            device = testIdentity,
            selectedPartitionId = "part-1",
            filesListGeneration = 5L,
        )

        val coord = coordinator(
            volume = volume,
            submitter = submitter,
            stateProvider = { currentState },
        )

        coord.list("/docs")
        testScheduler.advanceUntilIdle()

        assertEquals(1, submitter.submitted.size)
        assertEquals("part-1", volume.lastReadPartitionId)
        assertEquals(testPin, volume.lastReadPin)

        val listed = intents.filterIsInstance<UiIntent.FilesListed>()
        assertEquals(1, listed.size)
        assertEquals("/docs", listed[0].path)
        assertEquals(1, listed[0].files.size)
        assertEquals("file.txt", listed[0].files[0].name)
        assertFalse(listed[0].files[0].isDirectory)
        assertEquals(1234uL, listed[0].files[0].sizeBytes)
        assertEquals(5L, listed[0].generation)
        assertEquals("part-1", listed[0].partitionId)
    }

    @Test
    fun deleteBatchMismatchSubmitsNothing() = runTest {
        val volume = FakeUsbVolume()
        val submitter = FakeSubmitter(this)

        // State has different partition or listing not loaded
        val currentState = AppUiState(
            presence = DevicePresence.Ready,
            device = testIdentity,
            selectedPartitionId = "part-1",
            filesListingState = FilesListingState.Loaded,
            files = emptyList(), // item not in files -> matches() returns false
            currentPath = "/",
        )

        val coord = coordinator(
            volume = volume,
            submitter = submitter,
            stateProvider = { currentState },
        )

        val selection = FilesBatchSelection(
            directory = "/",
            items = listOf(
                FilesBatchItem(name = "gone.txt", isDirectory = false, sizeBytes = 10uL),
            ),
            identity = testIdentity.identity,
            attachGeneration = testIdentity.attachGeneration,
            partitionId = "part-1",
            startLba = 2048uL,
            sectorCount = 1000uL,
        )

        coord.deleteBatch(selection)
        testScheduler.advanceUntilIdle()

        // Batch did not match -> must submit nothing and post FilesPickTargetInvalid
        assertTrue(submitter.submitted.isEmpty())
        val statusIntents = intents.filterIsInstance<UiIntent.Status>()
        assertEquals(1, statusIntents.size)
        val msg = statusIntents[0].message as UiMessage.Key
        assertEquals(UiMessageKey.FilesPickTargetInvalid, msg.key)
    }
}
